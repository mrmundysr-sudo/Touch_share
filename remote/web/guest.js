import { RemotePeer, DEFAULT_ICE } from '/peer.js';
import { incomingSink, wireSending, KIND_TEXT } from '/transfer.js';

const $ = id => document.getElementById(id);
const t0 = Date.now();
const dbg = m => { $('dbg').textContent = (((Date.now() - t0) / 1000).toFixed(1) + 's ' + m + '\n' + $('dbg').textContent).slice(0, 4000); };
const params = new URLSearchParams(location.search);
const hashParams = new URLSearchParams(location.hash.replace(/^#/, ''));
const m = /^\/r\/(\d{6})\/?$/.exec(location.pathname);
let code = m ? m[1] : (params.get('code') || '');
const secret = hashParams.get('k') || null;
const iceServers = params.get('ice') === 'none' ? [] : DEFAULT_ICE;
dbg('page ready. ' + navigator.userAgent);

const REASONS = {
  'no-direct-connection': "Couldn't connect directly. This can happen on some networks (mobile data, school, office or hotel Wi-Fi). Try the same Wi-Fi or hotspot using the Touch Share app's \"Same Wi-Fi\" option.",
  'declined': 'The other phone declined this device.',
  'bad-proof': "This link isn't valid. Ask for a new QR code.",
  'secret-required': 'The other phone only accepts the QR link, not a typed code. Scan its QR code instead.',
};
const say = (text, cls) => { $('status').textContent = text; };

const sinkFor = meta => incomingSink(meta, $('incoming'));

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
    if (state === 'verify') say('Waiting for the other phone to approve. Check that it shows the same number as below.');
    if (state === 'connecting') say('Approved. Making a direct connection…');
    if (state === 'connected') {
      say('Connected. Nothing goes through a server.'); $('xfer').classList.remove('hide');
      peer.connectionInfo().then(i => { if (i) { say('Connected. ' + KIND_TEXT[i.kind]); dbg('connection ' + JSON.stringify(i)); } });
    }
    if (state === 'failed') { say(REASONS[reason] || 'Could not connect (' + reason + ').'); $('xfer').classList.add('hide'); $('verify').classList.add('hide'); }
    if (state === 'denied') { say(REASONS[reason] || 'The other phone turned this device away.'); $('verify').classList.add('hide'); }
    if (state === 'closed' && reason !== 'closed') { say('The connection was lost.'); }
  };
  peer.on('state', onState);
  onState({ state: peer.state, code: peer.verification });          // catch up on anything that happened while connecting
  peer.on('file-error', e => dbg('file error ' + e.error));
}

wireSending(() => window.__peer, $('pick'), $('sendBtn'), $('log'), dbg);

if (code) connect();
else { $('enter').classList.remove('hide'); say('Enter the code to connect.'); $('goBtn').onclick = () => { code = $('codeIn').value.replace(/\D/g, ''); if (code.length === 6) connect(); else say('The code has 6 digits.'); }; }
