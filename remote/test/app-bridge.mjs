// Tests the app's WebView script (app-peer.js) with a stand-in for the native bridge, against the REAL guest page
// served by the signaling server. Proves the JavaScript half of "the phone is the host"; the Kotlin half is
// verified by the CI build and real devices.
import { createRequire } from 'node:module';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from '../signaling/server.mjs';
import { SignalingCore } from '../signaling/core.mjs';
const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(process.env.PW_MODULES ? process.env.PW_MODULES.replace(/\/?$/, '/') : import.meta.url);
const { chromium } = require('playwright');

const { server: sig, port: sigPort } = await createServer(new SignalingCore({ createPerMinute: 1000, joinFailuresPerMinute: 1000 }));
const SIGNAL = process.env.E2E_SIGNAL || `http://127.0.0.1:${sigPort}`;     // where the matchmaker (and guest page) live

// serves the app's own assets the way the WebView's asset loader does, with peer.js copied in from remote/web
const appDir = path.resolve(here, '../../app/src/main/assets/remote'), peerJs = path.resolve(here, '../web/peer.js');
const appSrv = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x').pathname.replace(/^\/assets\/remote\//, '');
  const file = u === 'peer.js' ? peerJs : path.join(appDir, u);
  if (!file.startsWith(appDir) && file !== peerJs || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': file.endsWith('.html') ? 'text/html' : 'text/javascript' }); fs.createReadStream(file).pipe(res);
});
await new Promise(r => appSrv.listen(0, '127.0.0.1', r));
const appBase = `http://127.0.0.1:${appSrv.address().port}`;

const browser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns'] });
let failures = 0;
const ok = (n, c, x = '') => { console.log((c ? 'PASS ' : 'FAIL ') + n + (x ? '  ' + x : '')); if (!c) failures++; };

// the stand-in bridge: files are generated, sinks just count and checksum
const bridgeScript = files => `
  const table = new Uint32Array(256); for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; table[i] = c >>> 0; }
  const upd = (crc, u) => { for (let i = 0; i < u.length; i++) crc = table[(crc ^ u[i]) & 255] ^ (crc >>> 8); return crc; };
  window.events = []; window.sinks = {}; window.nextSink = 1; window.srcCrc = {};
  const files = ${JSON.stringify(files)};
  const byte = (off) => ((off * 13) ^ (off >> 7)) & 255;
  window.TouchBridge = {
    event(j) { window.events.push(JSON.parse(j)); },
    readChunk(id, off, len) { const u = new Uint8Array(len); for (let i = 0; i < len; i++) u[i] = byte(off + i);
      window.srcCrc[id] = upd(window.srcCrc[id] === undefined ? 0xFFFFFFFF : window.srcCrc[id], u);
      let s = ''; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s); },
    sinkOpen(name, size, type) { const id = String(window.nextSink++); window.sinks[id] = { name, size, n: 0, crc: 0xFFFFFFFF, done: false }; return id; },
    sinkWrite(id, b64) { const bin = atob(b64), u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); const s = window.sinks[id]; s.n += u.length; s.crc = upd(s.crc, u); },
    sinkFinish(id) { const s = window.sinks[id]; s.done = true; s.crc = (s.crc ^ 0xFFFFFFFF) >>> 0; return s.name; },
    sinkAbort(id) { window.sinks[id].aborted = true; },
  };`;
const waitEvent = (page, pred, ms = 20000) => page.waitForFunction(p => window.events.some(new Function('e', 'return ' + p)), pred, { timeout: ms });

async function startApp({ mode, files = [], extra = {} }) {
  const ctx = await browser.newContext(); await ctx.addInitScript(bridgeScript(files));
  const page = await ctx.newPage();
  await page.goto(`${appBase}/assets/remote/app-peer.html`);
  await waitEvent(page, "e.type === 'ready'");
  await page.evaluate(o => TS.start(JSON.stringify(o)), { signalUrl: SIGNAL, mode, files, ice: 'none', ...extra });
  await waitEvent(page, "e.type === 'room'");
  const room = await page.evaluate(() => events.find(e => e.type === 'room'));
  return { page, room };
}
const openGuest = async link => {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } }); const g = await ctx.newPage();
  await g.goto(link.replace(/^https?:\/\/[^/]+/, SIGNAL) + (link.includes('?') ? '' : '').replace('', '')); return g;
};

try {
  // A. the phone SENDS: pick files in the app, the guest page receives and offers Save
  {
    const SIZE = 6 * 1024 * 1024 + 321;
    const { page, room } = await startApp({ mode: 'send', files: [{ id: 'f1', name: 'from-phone.bin', size: SIZE, type: 'application/octet-stream' }] });
    ok('app script announces a room with a code, secret and link', /^\d{6}$/.test(room.code) && /^[0-9a-f]{32}$/.test(room.secret) && room.link.includes(`/r/${room.code}#k=${room.secret}`));
    const g = await openGuest(room.link.replace(/\/r\//, '/r/').replace(/#k=/, '?ice=none#k='));
    await waitEvent(page, "e.type === 'approve'");
    const ap = await page.evaluate(() => events.find(e => e.type === 'approve'));
    await g.waitForFunction(() => document.getElementById('vcode').textContent.length === 4, null, { timeout: 15000 });
    ok('guest shows the same number the app asked the person to check, before approval', ap.code === await g.innerText('#vcode') && ap.typed === false, ap.code);
    await page.evaluate(() => TS.decide(true));
    await g.waitForSelector('#incoming a.save', { timeout: 30000 });
    await waitEvent(page, "e.type === 'all-sent'", 30000);
    const rx = await g.evaluate(async () => { const a = document.querySelector('#incoming a.save'); const b = new Uint8Array(await (await fetch(a.href)).arrayBuffer());
      const t = new Uint32Array(256); for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[i] = c >>> 0; }
      let crc = 0xFFFFFFFF; for (let i = 0; i < b.length; i++) crc = t[(crc ^ b[i]) & 255] ^ (crc >>> 8); return { n: b.length, crc: (crc ^ 0xFFFFFFFF) >>> 0, name: a.download }; });
    const srcCrc = await page.evaluate(() => (window.srcCrc.f1 ^ 0xFFFFFFFF) >>> 0);
    ok('file read through the bridge arrives intact on the guest page', rx.n === SIZE && rx.crc === srcCrc && rx.name === 'from-phone.bin', `${(SIZE / 1048576).toFixed(1)} MB`);
    ok('app reports the connection type and the file as sent', await page.evaluate(() => events.some(e => e.type === 'connection') && events.some(e => e.type === 'file-done' && e.dir === 'send' && e.ok)));
    await page.evaluate(() => TS.stop()); await waitEvent(page, "e.type === 'stopped'");
    await page.context().close(); await g.context().close();
  }

  // B. the phone RECEIVES: the guest page sends a file, the bridge's sink gets every byte in order
  {
    const { page, room } = await startApp({ mode: 'receive' });
    const g = await openGuest(room.link.replace(/#k=/, '?ice=none#k='));
    await waitEvent(page, "e.type === 'approve'");
    await page.evaluate(() => TS.decide(true));
    await g.waitForFunction(() => /Connected\./.test(document.getElementById('status').innerText), null, { timeout: 20000 });
    const SIZE = 4 * 1024 * 1024 + 77;
    await g.setInputFiles('#pick', { name: 'to-phone.bin', mimeType: 'application/octet-stream', buffer: Buffer.from(Array.from({ length: SIZE }, (_, i) => (i * 7 ^ (i >> 5)) & 255)) });
    await g.click('#sendBtn');
    await waitEvent(page, "e.type === 'file-done' && e.dir === 'recv'", 30000);
    const s = await page.evaluate(() => Object.values(sinks)[0]);
    const expect = Buffer.from(Array.from({ length: SIZE }, (_, i) => (i * 7 ^ (i >> 5)) & 255));
    let crc = 0xFFFFFFFF; const T = new Uint32Array(256); for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; T[i] = c >>> 0; } for (const b of expect) crc = T[(crc ^ b) & 255] ^ (crc >>> 8);
    ok('file received through the bridge sink is complete and in order', s.done && s.n === SIZE && s.crc === ((crc ^ 0xFFFFFFFF) >>> 0) && s.name === 'to-phone.bin');
    await page.evaluate(() => TS.stop()); await page.context().close(); await g.context().close();
  }

  // C. declining, and a stranger typing the code
  {
    const { page, room } = await startApp({ mode: 'receive' });
    const t = await (await browser.newContext()).newPage(); await t.goto(`${SIGNAL}/?ice=none`);
    await t.fill('#codeIn', room.code); await t.click('#goBtn');
    await waitEvent(page, "e.type === 'approve'");
    ok('typed-code guest is flagged to the app as typed', await page.evaluate(() => events.find(e => e.type === 'approve').typed === true));
    await page.evaluate(() => TS.decide(false));
    await t.waitForFunction(() => /declined/.test(document.getElementById('status').innerText), null, { timeout: 10000 });
    ok('declining reaches the guest and the app keeps waiting', await page.evaluate(() => events.some(e => e.type === 'declined')));
    await page.evaluate(() => TS.stop()); await page.context().close(); await t.context().close();
  }

  // D. a bad server address reports an error to the app instead of hanging
  {
    const ctx = await browser.newContext(); await ctx.addInitScript(bridgeScript([]));
    const page = await ctx.newPage(); await page.goto(`${appBase}/assets/remote/app-peer.html`); await waitEvent(page, "e.type === 'ready'");
    await page.evaluate(() => TS.start(JSON.stringify({ signalUrl: 'http://127.0.0.1:9', mode: 'receive', files: [], ice: 'none' })));
    await waitEvent(page, "e.type === 'error'", 10000);
    ok('unreachable server gives the app an error message', true);
    await ctx.close();
  }
} catch (e) { console.log('FAIL unexpected: ' + e.message); failures++; }
await browser.close(); sig.close(); appSrv.close();
console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED'); process.exit(failures ? 1 : 0);
