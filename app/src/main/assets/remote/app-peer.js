// Runs inside the app's hidden WebView. The phone is the HOST of a remote share. All WebRTC work is done by the
// shared peer script (peer.js, copied from remote/web at build time); this file only connects it to the phone's
// file handling through the native bridge (window.TouchBridge, a Java object in the app, a stand-in in tests).
import { RemotePeer, DEFAULT_ICE } from './peer.js';

const B = window.TouchBridge;
const emit = o => B.event(JSON.stringify(o));

function fromBase64(s) {
  const bin = atob(s), u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return u;
}
function toBase64(buf) {
  const u = new Uint8Array(buf); let s = '';
  for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000));
  return btoa(s);
}

let peer = null, decide = null, options = null;

window.TS = {
  /** options: { signalUrl, mode: 'send' | 'receive', files: [{id,name,size,type}], ice: 'default' | 'none', allowTypedCode } */
  async start(optionsJson) {
    options = JSON.parse(optionsJson);
    try {
      peer = await RemotePeer.host({
        signalUrl: options.signalUrl,
        iceServers: options.ice === 'none' ? [] : DEFAULT_ICE,
        allowTypedCode: options.allowTypedCode !== false,
        connectTimeoutMs: options.timeoutMs || 15000,
        onApprove: ({ code, typed }) => new Promise(resolve => { decide = resolve; emit({ type: 'approve', code, typed }); }),
        onIncoming: meta => {
          const id = B.sinkOpen(meta.name, meta.size, meta.type || '');
          if (!id) return null;
          return {
            write: buf => { B.sinkWrite(id, toBase64(buf)); },
            finish: () => B.sinkFinish(id),
            abort: () => { B.sinkAbort(id); },
          };
        },
      });
    } catch (e) { emit({ type: 'error', message: e.message }); return; }
    const base = options.signalUrl.replace(/\/$/, '');
    emit({ type: 'room', code: peer.code, secret: peer.secret, link: `${base}/r/${peer.code}#k=${peer.secret}` });
    peer.on('state', ({ state, reason, code }) => {
      emit({ type: 'state', state, reason: reason || null, code: code || null });
      if (state === 'connected') {
        peer.connectionInfo().then(i => { if (i) emit({ type: 'connection', ...i }); });
        if (options.mode === 'send') sendAll();
      }
    });
    peer.on('progress', p => emit({ type: 'progress', ...p }));
    peer.on('file-done', f => emit({ type: 'file-done', ...f }));
    peer.on('file-error', f => emit({ type: 'file-error', ...f }));
    peer.on('declined', () => emit({ type: 'declined' }));
    peer.on('rejected', r => emit({ type: 'rejected', reason: r.reason }));
  },
  decide(ok) { if (decide) { const d = decide; decide = null; d(!!ok); } },
  async stop() { try { await peer?.close(); } catch (e) {} emit({ type: 'stopped' }); },
};

async function sendAll() {
  const sources = (options.files || []).map(f => ({
    name: f.name, size: f.size, type: f.type || '',
    read: async (offset, len) => fromBase64(B.readChunk(f.id, offset, len)).buffer,
  }));
  try { await peer.sendFiles(sources); emit({ type: 'all-sent' }); }
  catch (e) { emit({ type: 'error', message: e.message }); }
}

emit({ type: 'ready' });
