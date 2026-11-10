import { RemotePeer, DEFAULT_ICE } from '/peer.js';

const $ = id => document.getElementById(id);
const t0 = Date.now();
const dbg = m => { $('dbg').textContent = (((Date.now() - t0) / 1000).toFixed(1) + 's ' + m + '\n' + $('dbg').textContent).slice(0, 4000); };
const params = new URLSearchParams(location.search);
const hashParams = new URLSearchParams(location.hash.replace(/^#/, ''));
const m = /^\/r\/(\d{6})\/?$/.exec(location.pathname);
let code = m ? m[1] : (params.get('code') || '');
const secret = hashParams.get('k') || null;
const iceServers = params.get('ice') === 'none' ? [] : DEFAULT_ICE;
const IOS = /iPhone|iPad|iPod/.test(navigator.userAgent);
const MAX_INCOMING = IOS ? 500 * 1024 * 1024 : 2 * 1024 * 1024 * 1024;   // beta: big downloads are held in memory
dbg('page ready. ' + navigator.userAgent);

const REASONS = {
  'no-direct-connection': "Couldn't connect directly. This can happen on some networks (mobile data, school, office or hotel Wi-Fi). Try the same Wi-Fi or hotspot using the Touch Share app's \"Same Wi-Fi\" option.",
  'declined': 'The other phone declined this device.',
  'bad-proof': "This link isn't valid. Ask for a new QR code.",
  'secret-required': 'The other phone only accepts the QR link, not a typed code. Scan its QR code instead.',
};
const say = (text, cls) => { $('status').textContent = text; };

function sinkFor(meta) {
  if (meta.size > MAX_INCOMING) {
    const row = document.createElement('div'); row.className = 'row';
    row.textContent = `${meta.name} is too large for this browser in the beta (limit ${Math.round(MAX_INCOMING / 1048576)} MB).`;
    $('incoming').appendChild(row); return null;
  }
  const row = document.createElement('div'); row.className = 'row'; row.dataset.id = meta.id;
  const label = document.createElement('span'); label.textContent = meta.name + ' ';
  const pr = document.createElement('progress'); pr.max = 1; pr.value = 0;
  row.append(label, pr); $('incoming').appendChild(row);
  const parts = []; let got = 0;
  return {
    write(buf) { parts.push(buf); got += buf.byteLength; pr.value = got / meta.size; label.textContent = `${meta.name} ${Math.round(100 * got / meta.size)}% `; },
    finish() {
      const a = document.createElement('a'); a.className = 'save'; a.textContent = 'Save ' + meta.name;
      a.href = URL.createObjectURL(new Blob(parts, { type: meta.type || 'application/octet-stream' })); a.download = meta.name;
      row.replaceChildren(a); window.__received = (window.__received || 0) + 1; return { saved: false };
    },
    abort() { row.textContent = meta.name + ' ✗ interrupted'; },
  };
}

async function connect() {
  $('enter').classList.add('hide');
  say('Connecting…');
  let peer;
  try {
    peer = await RemotePeer.guest({ signalUrl: params.get('signal') || '', code, secret, iceServers, onIncoming: sinkFor,
      connectTimeoutMs: Number(params.get('timeout') || 15000) });
  } catch (e) { say(e.message === 'no such code' ? 'That code was not found. Check it and try again.' : e.message); $('enter').classList.remove('hide'); return; }
  window.__peer = peer;
  const onState = ({ state, reason, code: v }) => {
    dbg('state ' + state + (reason ? ' (' + reason + ')' : ''));
    if (v) { $('vcode').textContent = v; $('verify').classList.remove('hide'); }
    if (state === 'offered') say('Waiting for the other phone to approve this device…');
    if (state === 'connecting') say('Approved. Making a direct connection…');
    if (state === 'connected') { say('Connected directly. Nothing goes through a server.'); $('xfer').classList.remove('hide'); }
    if (state === 'failed') { say(REASONS[reason] || 'Could not connect (' + reason + ').'); $('xfer').classList.add('hide'); $('verify').classList.add('hide'); }
    if (state === 'denied') { say(REASONS[reason] || 'The other phone turned this device away.'); $('verify').classList.add('hide'); }
    if (state === 'closed' && reason !== 'closed') { say('The connection was lost.'); }
  };
  peer.on('state', onState);
  onState({ state: peer.state, code: peer.verification });          // catch up on anything that happened while connecting
  peer.on('progress', p => { if (p.dir === 'send') { const row = document.querySelector(`#log [data-id="${p.id}"]`); if (row) { row.querySelector('progress').value = p.done / p.total; row.firstChild.nodeValue = `${p.name} ${Math.round(100 * p.done / p.total)}% `; } } });
  peer.on('file-done', e => { if (e.dir === 'send') { const row = document.querySelector(`#log [data-id="${e.id}"]`); if (row) { row.textContent = `${e.name} ${e.ok ? '✓ sent' : '✗ failed'}`; } } });
  peer.on('file-error', e => dbg('file error ' + e.error));
}

$('sendBtn').onclick = async () => {
  const peer = window.__peer; if (!peer) return;
  for (const f of $('pick').files) {
    const row = document.createElement('div'); row.className = 'row'; row.appendChild(document.createTextNode(f.name + ' '));
    const pr = document.createElement('progress'); pr.max = 1; pr.value = 0; row.appendChild(pr); $('log').appendChild(row);
    const src = { name: f.name, size: f.size, type: f.type, read: (o, l) => f.slice(o, o + l).arrayBuffer() };
    // the id is assigned inside sendFile; tag the row once the start message has gone out
    const next = peer._fileSeq + 1; row.dataset.id = next;
    try { await peer.sendFile(src); } catch (e) { row.textContent = `${f.name} ✗ ${e.message}`; break; }
  }
};

if (code) connect();
else { $('enter').classList.remove('hide'); say('Enter the code to connect.'); $('goBtn').onclick = () => { code = $('codeIn').value.replace(/\D/g, ''); if (code.length === 6) connect(); else say('The code has 6 digits.'); }; }
