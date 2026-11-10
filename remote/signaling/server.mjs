// Node dev/test server: HTTP adapter around SignalingCore plus the static guest page.
// The Cloudflare Worker adapter (slice 2) exposes the same API.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SignalingCore } from './core.mjs';
import { CORS, handleApi } from './http.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const webRoot = path.resolve(here, '../web');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.webp': 'image/webp', '.png': 'image/png', '.css': 'text/css', '.json': 'application/json' };

export function createServer(core = new SignalingCore(), { port = 0, host = '127.0.0.1' } = {}) {
  const json = (res, { status, body }) => { res.writeHead(status, { 'Content-Type': 'application/json', ...CORS }); res.end(JSON.stringify(body)); };
  const readBody = req => new Promise((resolve, reject) => {
    let s = ''; req.on('data', c => { s += c; if (s.length > 65536) { reject(new Error('too big')); req.destroy(); } });
    req.on('end', () => resolve(s)); req.on('error', reject);
  });

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://x');
      if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }
      const out = await handleApi(core, {
        method: req.method, pathname: url.pathname, searchParams: url.searchParams,
        ip: req.headers['cf-connecting-ip'] || req.socket.remoteAddress || '?', key: req.headers['x-key'] || '',
        readBody: () => readBody(req), aborted: new Promise(r => req.on('close', () => r(null))),
      });
      if (out) return json(res, out);
      if (out === null && url.pathname.startsWith('/api/')) return;      // client went away during a long poll

      // static guest page
      const file = url.pathname === '/' || /^\/r\/\d{6}$/.test(url.pathname) ? '/index.html' : url.pathname;
      const full = path.resolve(webRoot, '.' + file);
      if (!full.startsWith(webRoot + path.sep) || !fs.existsSync(full) || fs.statSync(full).isDirectory()) { res.writeHead(404, CORS); return res.end('not found'); }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(full)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      fs.createReadStream(full).pipe(res);
    } catch (e) { res.writeHead(500, CORS); res.end('error'); }
  });
  return new Promise(resolve => server.listen(port, host, () => resolve({ server, port: server.address().port, core })));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { port } = await createServer(new SignalingCore(), { port: Number(process.env.PORT || 8787), host: process.env.HOST || '127.0.0.1' });
  console.log(`signaling + guest page on http://127.0.0.1:${port}`);
}
