import { RemotePeer, DEFAULT_ICE } from '/peer.js';
import { incomingSink, wireSending, KIND_TEXT } from '/transfer.js';

const $ = id => document.getElementById(id);
const t0 = Date.now();
const dbg = m => { $('dbg').textContent = (((Date.now() - t0) / 1000).toFixed(1) + 's ' + m + '\n' + $('dbg').textContent).slice(0, 4000); };
const params = new URLSearchParams(location.search);
const iceServers = params.get('ice') === 'none' ? [] : DEFAULT_ICE;
const say = t => { $('status').textContent = t; };
dbg('page ready. ' + navigator.userAgent);

let peer = null, decide = null;

function showInvite(p) {
  const link = `${location.origin}/r/${p.code}#k=${p.secret}`;
  const qr = qrcode(0, 'M'); qr.addData(link); qr.make();
  $('qr').innerHTML = qr.createSvgTag({ cellSize: 6, margin: 0, scalable: true });
  const svg = $('qr').querySelector('svg'); if (svg) { svg.style.width = '170px'; svg.style.height = '170px'; }
  $('linkBox').textContent = link; $('codeShow').textContent = p.code; $('siteName').textContent = location.host;
  $('invite').classList.remove('hide');
}

async function start() {
  say('Starting…');
  try {
    peer = await RemotePeer.host({
      signalUrl: params.get('signal') || '', iceServers, allowTypedCode: params.get('typed') !== 'off',
      connectTimeoutMs: Number(params.get('timeout') || 15000),
      onApprove: ({ code, typed }) => new Promise(resolve => {
        $('vcode').textContent = code; $('typedNote').classList.toggle('hide', !typed); $('approve').classList.remove('hide');
        decide = ok => { $('approve').classList.add('hide'); decide = null; resolve(ok); };
      }),
      onIncoming: meta => incomingSink(meta, $('incoming')),
    });
  } catch (e) { say('Could not start: ' + e.message); return; }
  window.__peer = peer;
  const onState = ({ state, reason, code }) => {
    dbg('state ' + state + (reason ? ' (' + reason + ')' : ''));
    if (state === 'waiting') { showInvite(peer); say('Waiting for the other person. Room closes after 10 minutes.'); $('stopBtn').classList.remove('hide'); }
    if (state === 'approving') say('A device wants to connect. Check the number, then allow or deny.');
    if (state === 'connecting') { say('Approved. Making a direct connection…'); $('invite').classList.add('hide'); if (code) { $('vcode2').textContent = code; $('verify').classList.remove('hide'); } }
    if (state === 'connected') {
      say('Connected. Nothing goes through a server.'); $('xfer').classList.remove('hide');
      peer.connectionInfo().then(i => { if (i) { say('Connected. ' + KIND_TEXT[i.kind]); dbg('connection ' + JSON.stringify(i)); } });
    }
    if (state === 'failed') { $('xfer').classList.add('hide'); $('verify').classList.add('hide'); say("Couldn't connect directly (" + reason + "). This can happen on some networks. Try the same Wi-Fi or hotspot."); }
    if (state === 'closed' && reason !== 'closed') say('The connection was lost.');
  };
  peer.on('state', onState); onState({ state: peer.state });
  peer.on('declined', () => { $('approve').classList.add('hide'); say('Declined. Waiting for the other person.'); });
  peer.on('rejected', e => dbg('turned away: ' + e.reason));
}

$('allowBtn').onclick = () => decide && decide(true);
$('denyBtn').onclick = () => decide && decide(false);
$('stopBtn').onclick = async () => { await peer?.close(); say('Stopped.'); $('invite').classList.add('hide'); $('stopBtn').classList.add('hide'); };
wireSending(() => window.__peer, $('pick'), $('sendBtn'), $('log'), dbg);
start();
