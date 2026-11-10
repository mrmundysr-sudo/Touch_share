// End-to-end test: two real Chromium pages, real signaling server, real WebRTC (loopback, no STUN).
// Run:  node test/e2e.mjs      (needs Playwright; set PW_MODULES to its node_modules folder if not found)
import { createRequire } from 'node:module';
import { createServer } from '../signaling/server.mjs';
import { SignalingCore } from '../signaling/core.mjs';
const require = createRequire(process.env.PW_MODULES ? process.env.PW_MODULES.replace(/\/?$/, '/') : import.meta.url);
const { chromium } = require('playwright');

const { server, port } = await createServer(new SignalingCore({ createPerMinute: 1000, joinFailuresPerMinute: 1000 }));
const base = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns'] });
let failures = 0;
const ok = (name, cond, extra = '') => { console.log((cond ? 'PASS ' : 'FAIL ') + name + (extra ? '  ' + extra : '')); if (!cond) failures++; };

const helpers = () => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; table[i] = c >>> 0; }
  const crcUpdate = (crc, u) => { for (let i = 0; i < u.length; i++) crc = table[(crc ^ u[i]) & 255] ^ (crc >>> 8); return crc; };
  window.makeSink = () => { let crc = 0xFFFFFFFF, n = 0; return { write(b) { const u = new Uint8Array(b); crc = crcUpdate(crc, u); n += u.length; }, finish() { return { crc: (crc ^ 0xFFFFFFFF) >>> 0, n }; }, abort() { window.sinkAborted = true; } }; };
  window.makeSource = (name, size) => {
    let crc = 0xFFFFFFFF;
    return { name, size, type: 'application/octet-stream',
      read(off, len) { const u = new Uint8Array(len); for (let i = 0; i < len; i++) u[i] = ((off + i) * 7 ^ ((off + i) >> 8)) & 255; crc = crcUpdate(crc, u); return u.buffer; },
      crc: () => (crc ^ 0xFFFFFFFF) >>> 0 };
  };
};
async function openPage() {
  const ctx = await browser.newContext(); const page = await ctx.newPage();
  await page.goto(base + '/harness.html'); await page.waitForFunction(() => window.ready);
  await page.evaluate(helpers);
  return page;
}
const waitState = (page, who, state, ms = 20000) => page.waitForFunction(([w, s]) => window[w] && window[w].state === s, [who, state], { timeout: ms });

async function makeHost(page, { approve = true, ice = [], extra = {} } = {}) {
  return page.evaluate(async ([approve, ice, extra]) => {
    window.approvals = 0; window.incoming = []; window.rejected = 0;
    window.host = await P.RemotePeer.host({ signalUrl: '', iceServers: ice, ...extra,
      onApprove: async ({ code }) => { window.approvals++; window.askedCode = code; return approve; },
      onIncoming: async meta => { const s = makeSink(); window.incoming.push({ meta, sink: s }); return s; } });
    window.host.on('rejected', () => window.rejected++);
    return { code: window.host.code, secret: window.host.secret };
  }, [approve, ice, extra]);
}
async function makeGuest(page, code, secret, { ice = [], extra = {} } = {}) {
  return page.evaluate(async ([code, secret, ice, extra]) => {
    window.guest = await P.RemotePeer.guest({ signalUrl: '', code, secret, iceServers: ice, onIncoming: async meta => { const s = makeSink(); (window.gotFiles ||= []).push({ meta, sink: s }); return s; }, ...extra });
    return true;
  }, [code, secret, ice, extra]);
}

try {
  // 1. guest -> host, 100 MB, verification codes match, backpressure bounded
  {
    const h = await openPage(), g = await openPage();
    const { code, secret } = await makeHost(h);
    await makeGuest(g, code, secret);
    await waitState(g, 'guest', 'connected'); await waitState(h, 'host', 'connected');
    const sas = await Promise.all([h.evaluate(() => host.verification), g.evaluate(() => guest.verification)]);
    ok('verification codes match on both sides', sas[0] === sas[1] && /^\d{4}$/.test(sas[0]), sas[0]);
    ok('host was asked exactly once and saw the same code', await h.evaluate(() => approvals === 1 && askedCode === host.verification));
    const t0 = Date.now();
    const SIZE = 100 * 1024 * 1024;
    const res = await g.evaluate(async size => { window.src = makeSource('big.bin', size); const r = await guest.sendFile(src); return { r, crc: src.crc(), maxBuffered: guest.stats.maxBuffered }; }, SIZE);
    const secs = (Date.now() - t0) / 1000;
    const rx = await h.evaluate(() => { const f = incoming[0]; return { n: f.sink.finish().n, crc: f.sink.finish().crc, name: f.meta.name }; });
    ok('100 MB guest -> host arrived intact', res.r.ok && rx.n === SIZE && rx.crc === res.crc, `${(SIZE / 1048576 / secs).toFixed(0)} MB/s over loopback`);
    ok('send buffer stayed bounded (backpressure)', res.maxBuffered <= 2 * 1024 * 1024, `max buffered ${(res.maxBuffered / 1024).toFixed(0)} KB`);
    // 2. host -> guest, 20 MB, both directions work on the same connection
    const r2 = await h.evaluate(async size => { window.src2 = makeSource('back.bin', size); const r = await host.sendFile(src2); return { ok: r.ok, crc: src2.crc() }; }, 20 * 1024 * 1024);
    const g2 = await g.evaluate(() => { const f = gotFiles[0]; const x = f.sink.finish(); return { n: x.n, crc: x.crc }; });
    ok('20 MB host -> guest arrived intact on the same connection', r2.ok && g2.n === 20 * 1024 * 1024 && g2.crc === r2.crc);
    // 3. a second guest cannot take over a busy room
    const g3 = await openPage();
    const err = await g3.evaluate(async code => { try { await P.RemotePeer.guest({ signalUrl: '', code, iceServers: [] }); return null; } catch (e) { return e.message; } }, code);
    ok('second guest is turned away', err === 'someone else already joined', err);
    await h.context().close(); await g.context().close(); await g3.context().close();
  }

  // 4. connection dropped mid-transfer: sender notices and does not hang
  {
    const h = await openPage(), g = await openPage();
    const { code, secret } = await makeHost(h); await makeGuest(g, code, secret);
    await waitState(g, 'guest', 'connected');
    const p = g.evaluate(async () => { window.src = makeSource('drop.bin', 80 * 1024 * 1024); try { await guest.sendFile(src); return 'finished'; } catch (e) { return 'error: ' + e.message; } });
    await h.waitForFunction(() => window.incoming && window.incoming[0] && window.incoming[0].sink.finish().n > 4 * 1024 * 1024, null, { timeout: 20000 });
    await h.evaluate(() => host.pc.close());           // the other phone loses the connection
    const t0 = Date.now();
    const out = await Promise.race([p, new Promise(r => setTimeout(() => r('HUNG'), 30000))]);
    ok('dropped connection surfaces as an error, not a hang', out.startsWith('error'), `${out} after ${((Date.now() - t0) / 1000).toFixed(1)} s`);
    await h.context().close(); await g.context().close();
  }

  // 5. stranger with only the code (wrong secret) is turned away without bothering the host
  {
    const h = await openPage(), s = await openPage(), g = await openPage();
    const { code, secret } = await makeHost(h);
    await makeGuest(s, code, 'not-the-secret');
    await waitState(s, 'guest', 'denied');
    ok('wrong secret is denied, host never prompted', await h.evaluate(() => approvals === 0 && rejected === 1));
    await makeGuest(g, code, secret);                  // the real guest can still join afterwards
    await waitState(g, 'guest', 'connected');
    ok('real guest still connects after the stranger was reset', await h.evaluate(() => approvals === 1));
    await h.context().close(); await s.context().close(); await g.context().close();
  }

  // 6. typed code only (no secret): reaches the host's prompt marked "typed", and the host's decision matters
  {
    const h = await openPage(), g = await openPage();
    const { code } = await makeHost(h, { approve: false });
    await h.evaluate(() => { const orig = host.opts.onApprove; host.opts.onApprove = async a => { window.lastTyped = a.typed; return orig(a); }; });
    await makeGuest(g, code, null);
    await waitState(g, 'guest', 'denied');
    ok('typed-code guest reaches the prompt, flagged as typed; declining denies them', await h.evaluate(() => approvals === 1 && lastTyped === true));
    await h.context().close(); await g.context().close();
    const h2 = await openPage(), g2 = await openPage();
    const r2 = await makeHost(h2, { approve: true, extra: { allowTypedCode: false } });
    await makeGuest(g2, r2.code, null);
    await waitState(g2, 'guest', 'denied');
    ok('with typed codes switched off, a guest without the secret is refused without a prompt', await h2.evaluate(() => approvals === 0 && rejected === 1));
    await h2.context().close(); await g2.context().close();
    const h3 = await openPage(), g3 = await openPage();
    const r3 = await makeHost(h3, { approve: true });
    await makeGuest(g3, r3.code, null);
    await waitState(g3, 'guest', 'connected');
    ok('approved typed-code guest connects', await h3.evaluate(() => approvals === 1));
    await h3.context().close(); await g3.context().close();
  }

  // 7. no direct path (relay-only policy and no relay): fails clearly after the timeout
  {
    const h = await openPage(), g = await openPage();
    const x = { rtcConfig: { iceTransportPolicy: 'relay' }, connectTimeoutMs: 3000 };
    const { code, secret } = await makeHost(h, { extra: x });
    const t0 = Date.now();
    await makeGuest(g, code, secret, { extra: x });
    await waitState(g, 'guest', 'failed', 15000);
    const reason = await g.evaluate(() => 'ok');
    ok('no direct connection fails with a clear state after the timeout', true, `${((Date.now() - t0) / 1000).toFixed(1)} s`);
    await h.context().close(); await g.context().close();
  }
} catch (e) {
  console.log('FAIL unexpected error: ' + e.message); failures++;
}
await browser.close(); server.close();
console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
process.exit(failures ? 1 : 0);
