// Shared file-transfer UI pieces for the host page and the guest page.
const IOS = /iPhone|iPad|iPod/.test(navigator.userAgent);
export const MAX_INCOMING = IOS ? 500 * 1024 * 1024 : 2 * 1024 * 1024 * 1024;   // beta: incoming files are held in memory

export function incomingSink(meta, container) {
  const row = document.createElement('div'); row.className = 'row';
  if (meta.size > MAX_INCOMING) {
    row.textContent = `${meta.name} is too large for this browser in the beta (limit ${Math.round(MAX_INCOMING / 1048576)} MB).`;
    container.appendChild(row); return null;
  }
  const label = document.createElement('span'); label.textContent = meta.name + ' ';
  const pr = document.createElement('progress'); pr.max = 1; pr.value = 0;
  row.append(label, pr); container.appendChild(row);
  const parts = []; let got = 0;
  return {
    write(buf) { parts.push(buf); got += buf.byteLength; pr.value = got / meta.size; label.textContent = `${meta.name} ${Math.round(100 * got / meta.size)}% `; },
    finish() {
      const a = document.createElement('a'); a.className = 'save'; a.textContent = 'Save ' + meta.name;
      a.href = URL.createObjectURL(new Blob(parts, { type: meta.type || 'application/octet-stream' })); a.download = meta.name;
      row.replaceChildren(a); return { saved: false };
    },
    abort() { row.textContent = meta.name + ' \u2717 interrupted'; },
  };
}

/** Wires a file input + Send button + log area to a connected peer. */
export function wireSending(getPeer, pickEl, btnEl, logEl, dbg = () => {}) {
  btnEl.onclick = async () => {
    const peer = getPeer(); if (!peer || peer.state !== 'connected') return;
    for (const f of pickEl.files) {
      const row = document.createElement('div'); row.className = 'row'; row.appendChild(document.createTextNode(f.name + ' '));
      const pr = document.createElement('progress'); pr.max = 1; pr.value = 0; row.appendChild(pr); logEl.appendChild(row);
      const src = { name: f.name, size: f.size, type: f.type, read: (o, l) => f.slice(o, o + l).arrayBuffer() };
      const off1 = peer.on('progress', p => { if (p.dir === 'send' && p.name === f.name) { pr.value = p.done / p.total; row.firstChild.nodeValue = `${p.name} ${Math.round(100 * p.done / p.total)}% `; } });
      try { const r = await peer.sendFile(src); row.textContent = `${f.name} ${r.skipped ? '(not accepted)' : r.ok ? '\u2713 sent' : '\u2717 failed'}`; }
      catch (e) { row.textContent = `${f.name} \u2717 ${e.message}`; dbg('send error ' + e.message); break; }
    }
  };
}

export const KIND_TEXT = {
  'same-network': 'Direct, both phones are on the same network.',
  'internet-direct': 'Direct across the internet (no relay).',
  'relayed': 'Relayed (should not happen in the beta).',
};
