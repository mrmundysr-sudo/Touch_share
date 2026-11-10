// Cloudflare Worker: serves the guest page and forwards /api/* to one Durable Object that runs the matchmaker.
// One Durable Object keeps all rooms in memory (they are tiny and last 10 minutes), reusing the exact same
// SignalingCore and HTTP handler that the Node tests exercise.
import { DurableObject } from 'cloudflare:workers';
import { SignalingCore } from '../../signaling/core.mjs';
import { CORS, handleApi } from '../../signaling/http.mjs';

export class Signaling extends DurableObject {
  constructor(ctx, env) { super(ctx, env); this.core = new SignalingCore(); }

  async fetch(request) {
    const url = new URL(request.url);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    const aborted = new Promise(r => request.signal?.addEventListener?.('abort', () => r(null)));
    const out = await handleApi(this.core, {
      method: request.method, pathname: url.pathname, searchParams: url.searchParams,
      ip: request.headers.get('cf-connecting-ip') || '?', key: request.headers.get('x-key') || '',
      readBody: () => request.text(), aborted,
    });
    if (!out) return new Response(null, { status: 204, headers: CORS });      // the client went away during a long poll
    return new Response(JSON.stringify(out.body), { status: out.status, headers: { 'Content-Type': 'application/json', ...CORS } });
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/api/')) return env.SIGNALING.get(env.SIGNALING.idFromName('main')).fetch(request);
    // the QR link looks like /r/123456#k=...; every such path is the guest page
    if (/^\/r\/\d{6}\/?$/.test(url.pathname)) return env.ASSETS.fetch(new Request(new URL('/', url), request));       // '/' is the page; asking for /index.html would redirect
    return env.ASSETS.fetch(request);
  },
};
