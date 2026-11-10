// The matchmaker's HTTP API, independent of the server it runs on (Node dev server or Cloudflare Durable Object).
export const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type, X-Key',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Cache-Control': 'no-store',
};

/**
 * Handles one API request. Returns { status, body } or null when the path is not an API path.
 * req: { method, pathname, searchParams, ip, key, readBody: () => Promise<string>, aborted?: Promise }
 */
export async function handleApi(core, req) {
  const { method, pathname, searchParams, ip, key } = req;
  if (method === 'POST' && pathname === '/api/rooms') return core.createRoom(ip);
  let m;
  if ((m = /^\/api\/rooms\/(\d{6})\/(join|reset|deny|send|recv)$/.exec(pathname))) {
    const code = m[1], op = m[2];
    if (op === 'join' && method === 'POST') return core.join(code, ip);
    if (op === 'reset' && method === 'POST') return core.reset(code, key);
    if (op === 'deny' && method === 'POST') {
      let data; try { data = JSON.parse(await req.readBody()).data; } catch { return { status: 400, body: { error: 'bad json' } }; }
      return core.deny(code, key, data);
    }
    if (op === 'send' && method === 'POST') {
      let data; try { data = JSON.parse(await req.readBody()).data; } catch { return { status: 400, body: { error: 'bad json' } }; }
      return core.send(code, key, data);
    }
    if (op === 'recv' && method === 'GET') {
      const after = Number(searchParams.get('after') || 0), wait = Number(searchParams.get('wait') || 0) * 1000;
      const run = core.recv(code, key, after, wait);
      return req.aborted ? Promise.race([run, req.aborted]) : run;
    }
  }
  if ((m = /^\/api\/rooms\/(\d{6})$/.exec(pathname)) && method === 'DELETE') return core.close(m[1], key);
  if (pathname.startsWith('/api/')) return { status: 404, body: { error: 'not found' } };
  return null;
}
