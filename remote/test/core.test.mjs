import test from 'node:test';
import assert from 'node:assert/strict';
import { SignalingCore } from '../signaling/core.mjs';

function make(opts = {}) {
  let t = 1_000_000;
  const core = new SignalingCore({ now: () => t, ...opts });
  return { core, advance: ms => { t += ms; } };
}

test('create gives a 6 digit code and keys; codes do not collide', () => {
  const { core } = make();
  const seen = new Set();
  for (let i = 0; i < 8; i++) {
    const r = core.createRoom('1.1.1.' + i);
    assert.equal(r.status, 201);
    assert.match(r.body.code, /^\d{6}$/);
    assert.equal(r.body.hostKey.length, 32);
    seen.add(r.body.code);
  }
  assert.equal(seen.size, 8);
});

test('collision is avoided even when the random source repeats', () => {
  let n = 0;
  const { core } = make({ randomInt: () => (n++ < 3 ? 123456 : 654321) });
  const a = core.createRoom('a'), b = core.createRoom('b');
  assert.equal(a.body.code, '123456'); assert.equal(b.body.code, '654321');
});

test('only one guest can join and the host can reset the slot', () => {
  const { core } = make();
  const { code, hostKey } = core.createRoom('h').body;
  const g1 = core.join(code, 'g1'); assert.equal(g1.status, 200);
  assert.equal(core.join(code, 'g2').status, 409);
  assert.equal(core.reset(code, 'wrong').status, 403);
  assert.equal(core.reset(code, g1.body.guestKey).status, 403);   // a guest cannot reset
  assert.equal(core.reset(code, hostKey).status, 200);
  assert.equal(core.join(code, 'g2').status, 200);
});

test('messages go to the other side only, in order, and need the right key', async () => {
  const { core } = make();
  const { code, hostKey } = core.createRoom('h').body;
  const { guestKey } = core.join(code, 'g').body;
  core.send(code, guestKey, 'offer-1'); core.send(code, guestKey, 'ice-1');
  const h = await core.recv(code, hostKey, 0, 0);
  assert.deepEqual(h.body.messages.map(m => m.data), ['offer-1', 'ice-1']);
  assert.equal((await core.recv(code, guestKey, 0, 0)).body.messages.length, 0);   // guest sees nothing of its own
  core.send(code, hostKey, 'answer');
  assert.deepEqual((await core.recv(code, guestKey, 0, 0)).body.messages.map(m => m.data), ['answer']);
  assert.equal((await core.recv(code, h.body.next, 'x', 0)).status, 403);
  assert.equal(core.send(code, 'nope', 'x').status, 403);
  // "after" cursor skips what was already read
  const again = await core.recv(code, hostKey, h.body.next, 0);
  assert.equal(again.body.messages.length, 0);
});

test('long poll wakes as soon as a message arrives, and times out empty', async () => {
  const { core } = make();
  const { code, hostKey } = core.createRoom('h').body;
  const { guestKey } = core.join(code, 'g').body;
  const pending = core.recv(code, hostKey, 0, 5000);
  setTimeout(() => core.send(code, guestKey, 'hello'), 20);
  const r = await pending;
  assert.deepEqual(r.body.messages.map(m => m.data), ['hello']);
  const t0 = Date.now();
  const empty = await core.recv(code, hostKey, r.body.next, 150);
  assert.equal(empty.body.messages.length, 0);
  assert.ok(Date.now() - t0 >= 100);
});

test('rooms expire after the ttl', async () => {
  const { core, advance } = make({ ttlMs: 60_000 });
  const { code, hostKey } = core.createRoom('h').body;
  advance(59_000); assert.equal(core.join(code, 'g').status, 200);
  advance(2_000);
  assert.equal((await core.recv(code, hostKey, 0, 0)).status, 404);
  assert.equal(core.join(code, 'g2').status, 404);
});

test('limits: message size, mailbox size, creates per minute, wrong-code guesses', () => {
  const { core, advance } = make({ maxMessageChars: 10, maxMessagesPerBox: 3, createPerMinute: 3, joinFailuresPerMinute: 4 });
  const { code, hostKey } = core.createRoom('ip').body;
  const { guestKey } = core.join(code, 'g').body;
  assert.equal(core.send(code, hostKey, 'x'.repeat(11)).status, 413);
  assert.equal(core.send(code, hostKey, 5).status, 413);
  for (let i = 0; i < 3; i++) assert.equal(core.send(code, hostKey, 'ok').status, 200);
  assert.equal(core.send(code, hostKey, 'ok').status, 429);
  assert.equal(guestKey.length, 32);
  core.createRoom('ip'); core.createRoom('ip');
  assert.equal(core.createRoom('ip').status, 429);
  assert.equal(core.createRoom('other').status, 201);                   // per IP
  advance(61_000); assert.equal(core.createRoom('ip').status, 201);
  // guessing codes: 4 failures then blocked, even for a correct code
  const r = core.createRoom('h2').body;
  for (let i = 0; i < 4; i++) assert.equal(core.join('000000', 'guesser').status, 404);
  assert.equal(core.join(r.code, 'guesser').status, 429);
  advance(61_000); assert.equal(core.join(r.code, 'guesser').status, 200);
});

test('the host closing removes the room; a guest leaving only frees the slot', async () => {
  const { core } = make();
  const { code, hostKey } = core.createRoom('h').body;
  const { guestKey } = core.join(code, 'g').body;
  assert.equal(core.close(code, 'nope').status, 403);
  assert.equal(core.close(code, guestKey).status, 200);                 // guest leaves
  assert.equal((await core.recv(code, hostKey, 0, 0)).status, 200);      // room is still there for the host
  assert.equal(core.join(code, 'g2').status, 200);                       // and someone else can join
  assert.equal(core.close(code, hostKey).status, 200);                   // host closes
  assert.equal((await core.recv(code, hostKey, 0, 0)).status, 404);
});

test('a turned-away guest can still read its last message after reset, but a new guest never sees it', async () => {
  const { core, advance } = make();
  const { code, hostKey } = core.createRoom('h').body;
  const g1 = core.join(code, 'g1').body.guestKey;
  core.send(code, hostKey, 'denied');
  assert.equal(core.reset(code, hostKey).status, 200);
  assert.deepEqual((await core.recv(code, g1, 0, 0)).body.messages.map(m => m.data), ['denied']);   // old guest still hears "denied"
  assert.equal(core.send(code, g1, 'sneaky').status, 403);                                       // but can no longer talk to the host
  assert.equal(core.close(code, g1).status, 200);                                                  // and leaving is harmless
  const g2 = core.join(code, 'g2').body.guestKey;
  assert.equal((await core.recv(code, g2, 0, 0)).body.messages.length, 0);                          // new guest starts clean
  advance(31_000);
  assert.equal((await core.recv(code, g1, 0, 0)).status, 403);                                      // the old key expires
});

test('message numbers keep counting after a reset so a host cursor never misses the next guest', async () => {
  const { core } = make();
  const { code, hostKey } = core.createRoom('h').body;
  const g1 = core.join(code, 'g1').body.guestKey;
  core.send(code, g1, 'offer-from-stranger'); core.send(code, g1, 'ice');
  const first = await core.recv(code, hostKey, 0, 0);
  assert.equal(first.body.next, 2);
  core.reset(code, hostKey);
  const g2 = core.join(code, 'g2').body.guestKey;
  core.send(code, g2, 'offer-from-real-guest');
  const second = await core.recv(code, hostKey, first.body.next, 0);      // host asks "after 2"
  assert.deepEqual(second.body.messages.map(m => m.data), ['offer-from-real-guest']);
  assert.ok(second.body.messages[0].id > 2);
});

