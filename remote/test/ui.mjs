// Drives the real guest page like a person would, against a harness "host" page, and takes screenshots.
import { createRequire } from 'node:module';
import { createServer } from '../signaling/server.mjs';
import { SignalingCore } from '../signaling/core.mjs';
const require = createRequire(process.env.PW_MODULES ? process.env.PW_MODULES.replace(/\/?$/, '/') : import.meta.url);
const { chromium } = require('playwright');
const out = process.env.SHOTS || '.';
const { server, port } = await createServer(new SignalingCore({ createPerMinute: 1000 }));
const base = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns'] });
let failures = 0;
const ok = (n, c, x = '') => { console.log((c ? 'PASS ' : 'FAIL ') + n + (x ? '  ' + x : '')); if (!c) failures++; };
try {
  const hctx = await browser.newContext(), host = await hctx.newPage();
  await host.goto(base + '/harness.html'); await host.waitForFunction(() => window.ready);
  const { code, secret } = await host.evaluate(async () => {
    window.got = []; window.asked = 0;
    window.host = await P.RemotePeer.host({ signalUrl: '', iceServers: [], onApprove: async () => { asked++; await new Promise(r => setTimeout(r, 800)); return true; },
      onIncoming: async meta => { const parts = []; const s = { write: b => parts.push(b), finish: () => { got.push({ name: meta.name, bytes: parts.reduce((n, b) => n + b.byteLength, 0) }); }, abort() {} }; return s; } });
    return { code: host.code, secret: host.secret };
  });
  const gctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 }), g = await gctx.newPage();
  await g.goto(`${base}/r/${code}?ice=none#k=${secret}`);
  await g.waitForFunction(() => /Waiting for the other phone/.test(document.getElementById('status').innerText), null, { timeout: 10000 });
  await g.screenshot({ path: out + '/guest_waiting.png' });
  await g.waitForFunction(() => /Connected directly/.test(document.getElementById('status').innerText), null, { timeout: 20000 });
  const vc = await g.innerText('#vcode'), hv = await host.evaluate(() => host.verification);
  ok('guest page connects from the QR-style link and shows the same verification number as the host', vc === hv, vc);
  // guest page sends a real file chosen through the file input
  const buf = Buffer.alloc(3 * 1024 * 1024, 7);
  await g.setInputFiles('#pick', { name: 'holiday.bin', mimeType: 'application/octet-stream', buffer: buf });
  await g.click('#sendBtn');
  await g.waitForFunction(() => /✓ sent/.test(document.getElementById('log').innerText), null, { timeout: 20000 });
  ok('file chosen on the page arrives at the host', await host.evaluate(() => got.length === 1 && got[0].name === 'holiday.bin' && got[0].bytes === 3 * 1024 * 1024));
  // host sends a file; the page offers a Save button
  await host.evaluate(async () => { const data = new Uint8Array(2 * 1024 * 1024).fill(9); await host.sendFile({ name: 'from-host.bin', size: data.length, type: 'application/octet-stream', read: async (o, l) => data.slice(o, o + l).buffer }); });
  await g.waitForSelector('#incoming a.save', { timeout: 20000 });
  ok('file sent by the host shows up with a Save button', (await g.innerText('#incoming a.save')) === 'Save from-host.bin');
  await g.screenshot({ path: out + '/guest_connected.png' });
  // typed-code route shows the entry box
  const t = await (await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })).newPage();
  await t.goto(`${base}/?ice=none`);
  ok('page without a code asks for the 6-digit code', await t.isVisible('#codeIn'));
  await t.fill('#codeIn', '000001'); await t.click('#goBtn');
  await t.waitForFunction(() => /not found/.test(document.getElementById('status').innerText), null, { timeout: 8000 });
  ok('a wrong code gives a clear message', true);
  await t.screenshot({ path: out + '/guest_enter_code.png' });
  // no direct path: clear message
  const h2 = await (await browser.newContext()).newPage(); await h2.goto(base + '/harness.html'); await h2.waitForFunction(() => window.ready);
  const r2 = await h2.evaluate(async () => { window.host2 = await P.RemotePeer.host({ signalUrl: '', iceServers: [], rtcConfig: { iceTransportPolicy: 'relay' }, connectTimeoutMs: 3000, onApprove: async () => true }); return { code: host2.code, secret: host2.secret }; });
  const f = await (await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })).newPage();
  await f.goto(`${base}/r/${r2.code}?ice=none&timeout=3000#k=${r2.secret}`);
  await f.waitForFunction(() => /Couldn't connect directly|Could not connect/.test(document.getElementById('status').innerText) , null, { timeout: 15000 }).catch(() => {});
  const msg = await f.innerText('#status');
  ok('page explains a failed direct connection in plain words', /Couldn't connect directly|connection/i.test(msg), msg.slice(0, 60));
  await f.screenshot({ path: out + '/guest_failed.png' });
} catch (e) { console.log('FAIL unexpected: ' + e.message); failures++; }
await browser.close(); server.close();
console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED'); process.exit(failures ? 1 : 0);
