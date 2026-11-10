// Drives the real guest page like a person would, against a harness "host" page, and takes screenshots.
import { createRequire } from 'node:module';
import { createServer } from '../signaling/server.mjs';
import { SignalingCore } from '../signaling/core.mjs';
const require = createRequire(process.env.PW_MODULES ? process.env.PW_MODULES.replace(/\/?$/, '/') : import.meta.url);
const { chromium } = require('playwright');
const out = process.env.SHOTS || '.';
const { server, port } = await createServer(new SignalingCore({ createPerMinute: 1000 }));
const base = `http://127.0.0.1:${port}`;
const SIGNAL = process.env.E2E_SIGNAL || '';
const guestBase = SIGNAL || base;          // with a Worker, the guest page itself is served by the Worker
const browser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns'] });
let failures = 0;
const ok = (n, c, x = '') => { console.log((c ? 'PASS ' : 'FAIL ') + n + (x ? '  ' + x : '')); if (!c) failures++; };
try {
  const hctx = await browser.newContext(); await hctx.addInitScript(`window.SIG = ${JSON.stringify(SIGNAL)}`); const host = await hctx.newPage();
  await host.goto(base + '/harness.html'); await host.waitForFunction(() => window.ready);
  const { code, secret } = await host.evaluate(async () => {
    window.got = []; window.asked = 0;
    window.host = await P.RemotePeer.host({ signalUrl: window.SIG, iceServers: [], onApprove: async () => { asked++; await new Promise(r => setTimeout(r, 800)); return true; },
      onIncoming: async meta => { const parts = []; const s = { write: b => parts.push(b), finish: () => { got.push({ name: meta.name, bytes: parts.reduce((n, b) => n + b.byteLength, 0) }); }, abort() {} }; return s; } });
    return { code: host.code, secret: host.secret };
  });
  const gctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 }), g = await gctx.newPage();
  await g.goto(`${guestBase}/r/${code}?ice=none#k=${secret}`);
  await g.waitForFunction(() => /Waiting for the other phone/.test(document.getElementById('status').innerText), null, { timeout: 10000 });
  await g.waitForFunction(() => document.getElementById('vcode').textContent.length === 4, null, { timeout: 5000 });   // the number is there while the host is still deciding
  await g.screenshot({ path: out + '/guest_waiting.png' });
  await g.waitForFunction(() => /Connected\./.test(document.getElementById('status').innerText), null, { timeout: 20000 });
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
  await t.goto(`${guestBase}/?ice=none`);
  ok('page without a code asks for the 6-digit code', await t.isVisible('#codeIn'));
  await t.fill('#codeIn', '000001'); await t.click('#goBtn');
  await t.waitForFunction(() => /not found/.test(document.getElementById('status').innerText), null, { timeout: 8000 });
  ok('a wrong code gives a clear message', true);
  await t.screenshot({ path: out + '/guest_enter_code.png' });
  // no direct path: clear message
  const h2c = await browser.newContext(); await h2c.addInitScript(`window.SIG = ${JSON.stringify(SIGNAL)}`); const h2 = await h2c.newPage(); await h2.goto(base + '/harness.html'); await h2.waitForFunction(() => window.ready);
  const r2 = await h2.evaluate(async () => { window.host2 = await P.RemotePeer.host({ signalUrl: window.SIG, iceServers: [], rtcConfig: { iceTransportPolicy: 'relay' }, connectTimeoutMs: 3000, onApprove: async () => true }); return { code: host2.code, secret: host2.secret }; });
  const f = await (await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })).newPage();
  await f.goto(`${guestBase}/r/${r2.code}?ice=none&timeout=3000#k=${r2.secret}`);
  await f.waitForFunction(() => /Couldn't connect directly|Could not connect/.test(document.getElementById('status').innerText) , null, { timeout: 15000 }).catch(() => {});
  const msg = await f.innerText('#status');
  ok('page explains a failed direct connection in plain words', /Couldn't connect directly|connection/i.test(msg), msg.slice(0, 60));
  await f.screenshot({ path: out + '/guest_failed.png' });

  // ---- the real HOST page and the real GUEST page together (what two phones will do) ----
  {
    const hp = await (await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })).newPage();
    await hp.goto(`${guestBase}/host.html?ice=none`);
    await hp.waitForSelector('#linkBox:not(:empty)', { timeout: 10000 });
    await hp.screenshot({ path: out + '/host_invite.png' });
    const link = await hp.innerText('#linkBox');
    ok('host page shows a scannable invite (QR drawn) with the link and the 6-digit code', (await hp.$('#qr svg')) !== null && /\/r\/\d{6}#k=[0-9a-f]{32}$/.test(link) && /^\d{6}$/.test(await hp.innerText('#codeShow')), link.replace(/#k=.*/, '#k=…'));
    const gp = await (await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })).newPage();
    await gp.goto(link.replace(/^https?:\/\/[^/]+/, guestBase) + '');
    await hp.waitForSelector('#approve:not(.hide)', { timeout: 15000 });
    const num = await hp.innerText('#vcode');
    await hp.screenshot({ path: out + '/host_approve.png' });
    await hp.click('#allowBtn');
    await gp.waitForFunction(() => /Connected\./.test(document.getElementById('status').innerText), null, { timeout: 20000 });
    ok('host page approval flow connects the guest page, both show the same number', num === await gp.innerText('#vcode'), num);
    const kind = await gp.innerText('#status');
    ok('connection type is reported', /Direct/.test(kind), kind);
    await gp.setInputFiles('#pick', { name: 'phone-to-phone.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(2 * 1024 * 1024, 5) });
    await gp.click('#sendBtn');
    await hp.waitForSelector('#incoming a.save', { timeout: 20000 });
    ok('file sent from the guest page shows a Save button on the host page', (await hp.innerText('#incoming a.save')) === 'Save phone-to-phone.bin');
    await hp.setInputFiles('#pick', { name: 'reply.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(1024 * 1024, 3) });
    await hp.click('#sendBtn');
    await gp.waitForSelector('#incoming a.save', { timeout: 20000 });
    ok('file sent from the host page shows a Save button on the guest page', (await gp.innerText('#incoming a.save')) === 'Save reply.bin');
    await hp.screenshot({ path: out + '/host_connected.png' });
    // a stranger typing the code: host sees a "typed" warning and can deny
    const hp2 = await (await browser.newContext()).newPage(); await hp2.goto(`${guestBase}/host.html?ice=none`);
    await hp2.waitForSelector('#codeShow:not(:empty)', { timeout: 10000 });
    const typedCode = await hp2.innerText('#codeShow');
    const tp = await (await browser.newContext()).newPage(); await tp.goto(`${guestBase}/?ice=none`);
    await tp.fill('#codeIn', typedCode); await tp.click('#goBtn');
    await hp2.waitForSelector('#approve:not(.hide)', { timeout: 15000 });
    ok('typed-code guest shows the "typed the code" warning on the host page', await hp2.isVisible('#typedNote'));
    await hp2.click('#denyBtn');
    await tp.waitForFunction(() => /declined/.test(document.getElementById('status').innerText), null, { timeout: 10000 });
    ok('denying shows "declined" on the guest page', true);
    await hp2.waitForFunction(() => /Waiting for the other person/.test(document.getElementById('status').innerText), null, { timeout: 5000 });
    ok('host page goes back to waiting after a deny', true);
  }
} catch (e) { console.log('FAIL unexpected: ' + e.message); failures++; }
await browser.close(); server.close();
console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED'); process.exit(failures ? 1 : 0);
