// Touch Share remote share: shared peer script (ES module). Runs in the guest's browser and, later, in the
// Android app's hidden WebView. WebRTC data channel + STUN only (no relay), signalling over plain HTTPS.
//
// Flow: host creates a room and shows code + secret. Guest joins, makes an offer (with a proof that it knows the
// secret). Host checks the proof, makes an answer, shows a short verification code, and only sends the answer
// after the host's person approves. Then the two browsers connect directly and files move over the channel.

export const DEFAULT_ICE = [
  { urls: ['stun:stun.cloudflare.com:3478'] },
  { urls: ['stun:stun.l.google.com:19302'] },
];
const CHUNK = 32 * 1024;          // bytes per data channel message
const READ = 512 * 1024;          // bytes read from the source at once
const HIGH = 1024 * 1024;         // pause sending above this much buffered data
const LOW = 256 * 1024;           // resume below this
const ACK_EVERY = 1024 * 1024;

const enc = new TextEncoder();
const hex = u8 => Array.from(u8, x => x.toString(16).padStart(2, '0')).join('');

export function fingerprintOf(sdp) {
  const m = /a=fingerprint:sha-256 ([0-9A-Fa-f:]+)/.exec(sdp || '');
  return m ? m[1].toUpperCase() : '';
}
async function sha256(text) { return new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(text))); }

/** 4 digits both people can read out to each other; differs if anyone swapped the connection details. */
export async function verificationCode(sdpA, sdpB) {
  const h = await sha256('ts-sas|' + [fingerprintOf(sdpA), fingerprintOf(sdpB)].sort().join('|'));
  return String(((h[0] << 24 | h[1] << 16 | h[2] << 8 | h[3]) >>> 0) % 10000).padStart(4, '0');
}
export async function proofFor(secret, code, offerSdp) {
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(`ts-proof|${code}|${fingerprintOf(offerSdp)}`));
  return hex(new Uint8Array(sig)).slice(0, 32);
}
export function randomSecret() { const b = new Uint8Array(16); crypto.getRandomValues(b); return hex(b); }

class Emitter {
  constructor() { this._h = {}; }
  on(e, fn) { (this._h[e] ||= []).push(fn); return this; }
  emit(e, d) { for (const fn of this._h[e] || []) { try { fn(d); } catch (err) { console.error(err); } } }
}

class Signal {
  constructor(base) { this.base = (base || '').replace(/\/$/, ''); this.key = null; this.code = null; this.after = 0; this.closed = false; this.ctl = null; }
  async _json(method, path, body, key = this.key, signal) {
    const r = await fetch(this.base + path, {
      method, signal, headers: { ...(key ? { 'X-Key': key } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    let data = {}; try { data = await r.json(); } catch {}
    return { status: r.status, body: data };
  }
  async createRoom() { const r = await this._json('POST', '/api/rooms'); if (r.status !== 201) throw new Error('could not start (' + r.status + ')'); this.code = r.body.code; this.key = r.body.hostKey; return r.body; }
  async join(code) { const r = await this._json('POST', `/api/rooms/${code}/join`); if (r.status !== 200) { const e = new Error(r.status === 404 ? 'no such code' : r.status === 409 ? 'someone else already joined' : 'could not join (' + r.status + ')'); e.status = r.status; throw e; } this.code = code; this.key = r.body.guestKey; return r.body; }
  async reset() { return this._json('POST', `/api/rooms/${this.code}/reset`); }
  async send(obj) { return this._json('POST', `/api/rooms/${this.code}/send`, { data: JSON.stringify(obj) }); }
  async close() { this.closed = true; try { this.ctl?.abort(); } catch {} try { await this._json('DELETE', `/api/rooms/${this.code}`, undefined, this.key); } catch {} }
  /** Calls onMsg for every message, in order, until closed. */
  async loop(onMsg) {
    let backoff = 500;
    while (!this.closed) {
      this.ctl = new AbortController();
      try {
        const r = await this._json('GET', `/api/rooms/${this.code}/recv?after=${this.after}&wait=20`, undefined, this.key, this.ctl.signal);
        if (r.status === 404 || r.status === 403) return 'gone';
        if (r.status === 200) {
          backoff = 500;
          for (const m of r.body.messages) { this.after = m.id; let obj; try { obj = JSON.parse(m.data); } catch { continue; } await onMsg(obj); }
        } else await new Promise(res => setTimeout(res, backoff = Math.min(backoff * 2, 4000)));
      } catch (e) {
        if (this.closed) return 'closed';
        await new Promise(res => setTimeout(res, backoff = Math.min(backoff * 2, 4000)));
      }
    }
    return 'closed';
  }
}

export class RemotePeer extends Emitter {
  /** opts: { signalUrl, iceServers, secret, allowTypedCode (default true), onApprove({code, typed})->Promise<bool>, onIncoming(meta)->sink|null, connectTimeoutMs, rtcConfig } */
  constructor(role, opts = {}) {
    super();
    this.role = role; this.opts = opts;
    this.signal = new Signal(opts.signalUrl);
    this.secret = opts.secret || null;
    this.state = 'new'; this.verification = null;
    this.stats = { maxBuffered: 0, sent: 0, received: 0 };
    this._queuedIce = []; this._remoteSet = false; this._waiters = []; this._rx = null; this._rxChain = Promise.resolve(); this._fileSeq = 0;
  }
  static async host(opts) { const p = new RemotePeer('host', opts); p.secret = opts.secret || randomSecret(); await p._startHost(); return p; }
  static async guest(opts) { const p = new RemotePeer('guest', opts); await p._startGuest(); return p; }
  get code() { return this.signal.code; }
  _setState(s, extra) { if (this.state === s) return; this.state = s; this.emit('state', { state: s, ...extra }); }

  _newPc() {
    const pc = new RTCPeerConnection({ iceServers: this.opts.iceServers ?? DEFAULT_ICE, ...(this.opts.rtcConfig || {}) });
    this.pc = pc;
    pc.onicecandidate = e => { if (e.candidate) this.signal.send({ type: 'ice', candidate: e.candidate.toJSON() }); };
    pc.onconnectionstatechange = () => { if (['failed', 'closed'].includes(pc.connectionState) && this.state === 'connected') this._lost('connection ' + pc.connectionState); };
    return pc;
  }
  async _addIce(c) {
    if (!this._remoteSet) { this._queuedIce.push(c); return; }
    try { await this.pc.addIceCandidate(c); } catch {}
  }
  async _flushIce() { this._remoteSet = true; for (const c of this._queuedIce.splice(0)) { try { await this.pc.addIceCandidate(c); } catch {} } }

  // ---------- host ----------
  async _startHost() {
    const r = await this.signal.createRoom();
    this._setState('waiting', { code: r.code });
    this.signal.loop(m => this._onSignalHost(m)).then(() => {});
  }
  async _onSignalHost(m) {
    if (m.type === 'ice') return this._addIce(m.candidate);
    if (m.type !== 'offer') return;
    if (this._handling) return;                 // one guest at a time
    this._handling = true;
    try {
      // Someone who presents a secret must prove it (QR link). Someone with no secret typed the code by hand:
      // they may reach the approval prompt, marked as unverified, unless the host switched typed codes off.
      const typed = !m.proof;
      const expected = typed ? null : await proofFor(this.secret, this.code, m.sdp);
      if ((!typed && m.proof !== expected) || (typed && this.opts.allowTypedCode === false)) {
        const reason = typed ? 'secret-required' : 'bad-proof';
        this.emit('rejected', { reason });
        await this.signal.send({ type: 'denied', reason });
        await this.signal.reset(); this._handling = false; return;
      }
      const pc = this._newPc();
      pc.ondatachannel = e => this._wireChannel(e.channel);
      await pc.setRemoteDescription({ type: 'offer', sdp: m.sdp });
      const answer = await pc.createAnswer(); await pc.setLocalDescription(answer);
      this.verification = await verificationCode(m.sdp, pc.localDescription.sdp);
      this._setState('approving', { code: this.verification });
      const ok = this.opts.onApprove ? await this.opts.onApprove({ code: this.verification, typed }) : false;
      if (!ok) {
        await this.signal.send({ type: 'denied', reason: 'declined' });
        try { pc.close(); } catch {}
        await this.signal.reset(); this._remoteSet = false; this._queuedIce = []; this._handling = false; this._setState('waiting');
        this.emit('declined', {});
        return;
      }
      await this.signal.send({ type: 'answer', sdp: pc.localDescription.sdp });
      await this._flushIce();
      this._setState('connecting');
      this._armTimeout();
    } catch (e) { this._handling = false; this._fail('error: ' + e.message); }
  }

  // ---------- guest ----------
  async _startGuest() {
    await this.signal.join(this.opts.code);
    this._setState('joining');
    const pc = this._newPc();
    this._wireChannel(pc.createDataChannel('ts', { ordered: true }));
    const offer = await pc.createOffer(); await pc.setLocalDescription(offer);
    this._offerSdp = pc.localDescription.sdp;
    const proof = this.secret ? await proofFor(this.secret, this.opts.code, this._offerSdp) : null;
    this.signal.loop(m => this._onSignalGuest(m)).then(() => {});
    await this.signal.send({ type: 'offer', sdp: this._offerSdp, proof });
    this._setState('offered');
  }
  async _onSignalGuest(m) {
    if (m.type === 'ice') return this._addIce(m.candidate);
    if (m.type === 'denied') { this._setState('denied', { reason: m.reason }); this.close(); return; }
    if (m.type !== 'answer') return;
    await this.pc.setRemoteDescription({ type: 'answer', sdp: m.sdp });
    this.verification = await verificationCode(this._offerSdp, m.sdp);
    await this._flushIce();
    this._setState('connecting', { code: this.verification });
    this._armTimeout();
  }

  // ---------- channel ----------
  _armTimeout() {
    clearTimeout(this._to);
    this._to = setTimeout(() => { if (this.state !== 'connected') this._fail('no-direct-connection'); }, this.opts.connectTimeoutMs ?? 15000);
  }
  _wireChannel(dc) {
    this.dc = dc; dc.binaryType = 'arraybuffer'; dc.bufferedAmountLowThreshold = LOW;
    dc.onopen = () => { clearTimeout(this._to); this._setState('connected', { code: this.verification }); };
    dc.onclose = () => this._lost('channel closed');
    dc.onerror = () => {};
    dc.onmessage = e => this._onData(e.data);
  }
  _fail(reason) {
    clearTimeout(this._to);
    if (['closed', 'failed', 'denied'].includes(this.state)) return;
    this._setState('failed', { reason }); this._rejectWaiters(new Error(reason));
    this.close();
  }
  _lost(reason) {
    if (['closed', 'failed', 'denied'].includes(this.state)) return;     // the first final state sticks
    this._setState('closed', { reason }); this._rejectWaiters(new Error(reason));
    this._abortRx();
  }
  async close() {
    clearTimeout(this._to);
    try { await this.signal.close(); } catch {}
    try { this.dc?.close(); } catch {}
    try { this.pc?.close(); } catch {}
    if (!['failed', 'closed', 'denied'].includes(this.state)) this._setState('closed', { reason: 'closed' });
    this._rejectWaiters(new Error('closed')); this._abortRx();
  }

  _ctl(obj) { this.dc.send(JSON.stringify(obj)); }
  _waitCtl(pred) { return new Promise((resolve, reject) => this._waiters.push({ pred, resolve, reject })); }
  _rejectWaiters(err) { for (const w of this._waiters.splice(0)) w.reject(err); }
  _abortRx() { if (this._rx) { try { this._rx.sink.abort?.(); } catch {} this._rx = null; } }
  async _drain() {
    while (this.dc.bufferedAmount > HIGH) {
      if (this.dc.readyState !== 'open') throw new Error('connection lost');
      await new Promise(res => { this.dc.onbufferedamountlow = () => { this.dc.onbufferedamountlow = null; res(); }; setTimeout(res, 500); });
    }
  }

  // ---------- receiving ----------
  _onData(data) {
    if (typeof data === 'string') {
      let m; try { m = JSON.parse(data); } catch { return; }
      for (let i = 0; i < this._waiters.length; i++) {
        if (this._waiters[i].pred(m)) { const [w] = this._waiters.splice(i, 1); w.resolve(m); return; }
      }
      if (m.t === 'start') this._rxChain = this._rxChain.then(() => this._rxStart(m)).catch(() => {});
      else if (m.t === 'end') this._rxChain = this._rxChain.then(() => this._rxEnd(m)).catch(() => {});
      return;
    }
    const rx = this._rx; if (!rx) return;
    const buf = data; rx.got += buf.byteLength; this.stats.received += buf.byteLength;
    this._rxChain = this._rxChain.then(async () => {
      await rx.sink.write(buf);
      if (rx.got - rx.acked >= ACK_EVERY) { rx.acked = rx.got; this._ctl({ t: 'ack', id: rx.id, got: rx.got }); }
      this.emit('progress', { dir: 'recv', id: rx.id, name: rx.meta.name, done: rx.got, total: rx.meta.size });
    }).catch(e => { try { rx.sink.abort?.(); } catch {} this.emit('file-error', { dir: 'recv', id: rx.id, error: e.message }); });
  }
  async _rxStart(m) {
    const sink = this.opts.onIncoming ? await this.opts.onIncoming({ id: m.id, name: m.name, size: m.size, type: m.type }) : null;
    if (!sink) { this._ctl({ t: 'skip', id: m.id }); return; }
    this._rx = { id: m.id, meta: m, sink, got: 0, acked: 0 };
    this._ctl({ t: 'ready', id: m.id });
  }
  async _rxEnd(m) {
    const rx = this._rx; if (!rx || rx.id !== m.id) return;
    this._rx = null;
    let ok = rx.got === rx.meta.size, info = null;
    try { info = await rx.sink.finish?.(); } catch { ok = false; }
    this._ctl({ t: 'done', id: m.id, ok });
    this.emit('file-done', { dir: 'recv', id: m.id, name: rx.meta.name, size: rx.got, ok, info });
  }

  // ---------- sending ----------
  /** sources: [{ name, size, type, read(offset, length) -> Promise<ArrayBuffer|Uint8Array> }] */
  async sendFiles(sources) {
    const results = [];
    for (const src of sources) results.push(await this.sendFile(src));
    return results;
  }
  async sendFile(src) {
    if (this.state !== 'connected') throw new Error('not connected');
    const id = ++this._fileSeq;
    const reply = this._waitCtl(m => (m.t === 'ready' || m.t === 'skip') && m.id === id);
    this._ctl({ t: 'start', id, name: src.name, size: src.size, type: src.type || '' });
    const r = await reply;
    if (r.t === 'skip') return { id, skipped: true };
    let sent = 0, lastReport = 0;
    for (let off = 0; off < src.size; off += READ) {
      const block = new Uint8Array(await src.read(off, Math.min(READ, src.size - off)));
      for (let p = 0; p < block.length; p += CHUNK) {
        await this._drain();
        if (this.dc.readyState !== 'open') throw new Error('connection lost');
        this.dc.send(block.subarray(p, Math.min(p + CHUNK, block.length)));
        this.stats.maxBuffered = Math.max(this.stats.maxBuffered, this.dc.bufferedAmount);
        sent += Math.min(CHUNK, block.length - p); this.stats.sent += Math.min(CHUNK, block.length - p);
      }
      if (sent - lastReport >= ACK_EVERY || sent === src.size) { lastReport = sent; this.emit('progress', { dir: 'send', id, name: src.name, done: sent, total: src.size }); }
    }
    const done = this._waitCtl(m => m.t === 'done' && m.id === id);
    this._ctl({ t: 'end', id });
    const d = await done;
    this.emit('file-done', { dir: 'send', id, name: src.name, size: src.size, ok: !!d.ok });
    return { id, ok: !!d.ok };
  }
}
