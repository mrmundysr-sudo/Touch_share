// Node dev/test server: HTTP adapter around SignalingCore plus the static guest page.
// The Cloudflare Worker adapter (slice 2) exposes the same API.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SignalingCore } from './core.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const webRoot = path.resolve(here, '../web');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.webp': 'image/webp', '.png': 'image/png', '.css': 'text/css', '.json': 'application/json' };

export function createServer(core = new SignalingCore(), { port = 0, host = '127.0.0.1' } = {}) {
  const cors = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-Key',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Cache-Control': 'no-store',
  };
  const json = (res, { status, body }) => { res.writeHead(status, { 'Content-Type': 'application/json', ...cors }); res.end(JSON.stringify(body)); };
  const readBody = req => new Promise((resolve, reject) => {
    let s = ''; req.on('data', c => { s += c; if (s.length > 65536) { reject(new Error('too big')); req.destroy(); } });
    req.on('end', () => resolve(s)); req.on('error', reject);
  });

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://x');
      const ip = req.headers['cf-connecting-ip'] || req.socket.remoteAddress || '?';
      if (req.method === 'OPTIONS') { res.writeHead(204, cors); return res.end(); }
      const key = req.headers['x-key'] || '';
      let m;
      if (req.method === 'POST' && url.pathname === '/api/rooms') return json(res, core.createRoom(ip));
      if ((m = /^\/api\/rooms\/(\d{6})\/(join|reset|send|recv)$/.exec(url.pathname))) {
        const code = m[1], op = m[2];
        if (op === 'join' && req.method === 'POST') return json(res, core.join(code, ip));
        if (op === 'reset' && req.method === 'POST') return json(res, core.reset(code, key));
        if (op === 'send' && req.method === 'POST') {
          let data; try { data = JSON.parse(await readBody(req)).data; } catch { return json(res, { status: 400, body: { error: 'bad json' } }); }
          return json(res, core.send(code, key, data));
        }
        if (op === 'recv' && req.method === 'GET') {
          const after = Number(url.searchParams.get('after') || 0), wait = Number(url.searchParams.get('wait') || 0) * 1000;
          const aborted = new Promise(r => req.on('close', () => r(null)));
          const out = await Promise.race([core.recv(code, key, after, wait), aborted]);
          if (out) json(res, out);
          return;
        }
      }
      if ((m = /^\/api\/rooms\/(\d{6})$/.exec(url.pathname)) && req.method === 'DELETE') return json(res, core.close(m[1], key));

      // static guest page
      let file = url.pathname === '/' || /^\/r\/\d{6}$/.test(url.pathname) ? '/index.html' : url.pathname;
      const full = path.resolve(webRoot, '.' + file);
      if (!full.startsWith(webRoot + path.sep) || !fs.existsSync(full) || fs.statSync(full).isDirectory()) { res.writeHead(404, cors); return res.end('not found'); }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(full)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      fs.createReadStream(full).pipe(res);
    } catch (e) { res.writeHead(500, cors); res.end('error'); }
  });
  return new Promise(resolve => server.listen(port, host, () => resolve({ server, port: server.address().port, core })));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { port } = await createServer(new SignalingCore(), { port: Number(process.env.PORT || 8787), host: process.env.HOST || '127.0.0.1' });
  console.log(`signaling + guest page on http://127.0.0.1:${port}`);
}
