// Signaling core for Touch Share remote share. Pure logic, no HTTP and no storage, so the same code can run
// in Node (dev server and tests) and in a Cloudflare Durable Object. It only relays small connection messages
// between a host and one guest; it never sees file data.

const DEFAULTS = {
  ttlMs: 10 * 60 * 1000,        // a room lives 10 minutes
  maxMessageChars: 20000,       // SDP is a few KB
  maxMessagesPerBox: 300,
  createPerMinute: 10,          // per IP
  joinFailuresPerMinute: 10,    // per IP: wrong code / already taken
  maxRooms: 5000,
};

function defaultRandomInt(max) {
  const a = new Uint32Array(1);
  const limit = Math.floor(0x100000000 / max) * max;
  do { globalThis.crypto.getRandomValues(a); } while (a[0] >= limit);
  return a[0] % max;
}
function defaultKey() {
  const b = new Uint8Array(16); globalThis.crypto.getRandomValues(b);
  return Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
}
function same(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

export class SignalingCore {
  constructor({ now = () => Date.now(), randomInt = defaultRandomInt, newKey = defaultKey, ...limits } = {}) {
    this.now = now; this.randomInt = randomInt; this.newKey = newKey;
    this.cfg = { ...DEFAULTS, ...limits };
    this.rooms = new Map();
    this.hits = new Map();       // "kind|ip" -> [timestamps]
  }

  // ---- rate limiting ----
  _hit(kind, ip, limit) {
    const key = kind + '|' + ip, t = this.now();
    const arr = (this.hits.get(key) || []).filter(x => t - x < 60000);
    if (arr.length >= limit) { this.hits.set(key, arr); return false; }
    arr.push(t); this.hits.set(key, arr); return true;
  }
  _count(kind, ip) {
    const t = this.now();
    return (this.hits.get(kind + '|' + ip) || []).filter(x => t - x < 60000).length;
  }

  _sweep() {
    const t = this.now();
    for (const [code, r] of this.rooms) if (t >= r.expiresAt) this._drop(code);
    for (const [k, arr] of this.hits) {
      const live = arr.filter(x => t - x < 60000);
      if (live.length) this.hits.set(k, live); else this.hits.delete(k);
    }
  }
  _drop(code) {
    const r = this.rooms.get(code);
    if (!r) return;
    this.rooms.delete(code);
    for (const w of [...r.waiters]) w.wake();
  }
  _room(code) {
    const r = this.rooms.get(String(code));
    if (!r) return null;
    if (this.now() >= r.expiresAt) { this._drop(String(code)); return null; }
    return r;
  }
  _role(r, key) {
    if (same(r.hostKey, key)) return 'host';
    if (r.guestKey && same(r.guestKey, key)) return 'guest';
    for (const [k, v] of r.retired) { if (this.now() >= v.until) r.retired.delete(k); else if (same(k, key)) return 'retired'; }
    return null;
  }

  // ---- operations; each returns { status, body } ----
  createRoom(ip = '?') {
    this._sweep();
    if (!this._hit('create', ip, this.cfg.createPerMinute)) return { status: 429, body: { error: 'slow down' } };
    if (this.rooms.size >= this.cfg.maxRooms) return { status: 503, body: { error: 'busy' } };
    let code;
    do { code = String(this.randomInt(1000000)).padStart(6, '0'); } while (this.rooms.has(code));
    const room = {
      code, hostKey: this.newKey(), guestKey: null,
      createdAt: this.now(), expiresAt: this.now() + this.cfg.ttlMs,
      boxes: { host: [], guest: [] }, nextId: { host: 1, guest: 1 }, waiters: new Set(),
      retired: new Map(),          // key of a guest that was reset -> { box, until }: may still read what was addressed to it
    };
    this.rooms.set(code, room);
    return { status: 201, body: { code, hostKey: room.hostKey, ttlSeconds: Math.round(this.cfg.ttlMs / 1000) } };
  }

  join(code, ip = '?') {
    this._sweep();
    if (this._count('joinfail', ip) >= this.cfg.joinFailuresPerMinute) return { status: 429, body: { error: 'slow down' } };
    const r = this._room(code);
    if (!r) { this._hit('joinfail', ip, 1e9); return { status: 404, body: { error: 'no such room' } }; }
    if (r.guestKey) { this._hit('joinfail', ip, 1e9); return { status: 409, body: { error: 'room already has a guest' } }; }
    r.guestKey = this.newKey();
    return { status: 200, body: { guestKey: r.guestKey } };
  }

  // host only: forget the guest and any pending messages so a different guest can join
  reset(code, key) {
    const r = this._room(code);
    if (!r) return { status: 404, body: { error: 'no such room' } };
    if (this._role(r, key) !== 'host') return { status: 403, body: { error: 'forbidden' } };
    if (r.guestKey) r.retired.set(r.guestKey, { box: r.boxes.guest, until: this.now() + 30000 });
    // message ids keep counting up: the host's cursor must never see old numbers again
    r.guestKey = null; r.boxes = { host: [], guest: [] };
    for (const w of [...r.waiters]) w.wake();
    return { status: 200, body: { ok: true } };
  }

  // host: remove the room. guest: just leave (free the guest slot) - a guest can never delete the host's room
  close(code, key) {
    const r = this._room(code);
    if (!r) return { status: 200, body: { ok: true } };
    const role = this._role(r, key);
    if (!role) return { status: 403, body: { error: 'forbidden' } };
    if (role === 'host') this._drop(String(code));
    else if (role === 'retired') { /* already released */ }
    else {
      // message ids keep counting up: the host's cursor must never see old numbers again
      r.guestKey = null; r.boxes = { host: [], guest: [] };
      for (const w of [...r.waiters]) w.wake();
    }
    return { status: 200, body: { ok: true } };
  }

  send(code, key, data) {
    const r = this._room(code);
    if (!r) return { status: 404, body: { error: 'no such room' } };
    const role = this._role(r, key);
    if (!role || role === 'retired') return { status: 403, body: { error: 'forbidden' } };
    if (typeof data !== 'string' || data.length > this.cfg.maxMessageChars) return { status: 413, body: { error: 'message too large' } };
    const to = role === 'host' ? 'guest' : 'host';
    const box = r.boxes[to];
    if (box.length >= this.cfg.maxMessagesPerBox) return { status: 429, body: { error: 'too many messages' } };
    const id = r.nextId[to]++;
    box.push({ id, data });
    for (const w of [...r.waiters]) w.wake();
    return { status: 200, body: { id } };
  }

  // Messages addressed to the caller with id > after. Waits up to waitMs for new ones.
  async recv(code, key, after = 0, waitMs = 0) {
    const first = this._recvNow(code, key, after);
    if (first.status !== 200 || first.body.messages.length || waitMs <= 0) return first;
    const r = this._room(code);
    return new Promise(resolve => {
      let done = false, timer = null;
      const waiter = {
        wake: () => {
          if (done) return;
          const res = this._recvNow(code, key, after);
          if (res.status !== 200 || res.body.messages.length) finish(res);
        },
      };
      const finish = res => { if (done) return; done = true; clearTimeout(timer); r.waiters.delete(waiter); resolve(res); };
      timer = setTimeout(() => finish(this._recvNow(code, key, after)), Math.min(waitMs, 25000));
      r.waiters.add(waiter);
    });
  }

  _recvNow(code, key, after) {
    const r = this._room(code);
    if (!r) return { status: 404, body: { error: 'no such room' } };
    const role = this._role(r, key);
    if (!role) return { status: 403, body: { error: 'forbidden' } };
    const box = role === 'retired' ? r.retired.get(key).box : r.boxes[role];
    const messages = box.filter(m => m.id > after);
    const next = messages.length ? messages[messages.length - 1].id : after;
    return { status: 200, body: { messages, next, guestJoined: !!r.guestKey } };
  }
}
