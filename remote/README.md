# Remote share (beta, no relay): slice 1

Browser-to-browser proof of the "from anywhere" feature (see `docs/REMOTE_SHARE_PLAN.md`, D-009, D-018). No Android changes yet.

- `signaling/core.mjs`: the matchmaker logic (rooms, one guest, message relay, long-poll, limits, expiry). Pure code, runs in Node and later in a Cloudflare Durable Object. Never sees files.
- `signaling/http.mjs`: the HTTP API shared by the Node server and the Worker. `signaling/server.mjs`: Node dev server (HTTP API + the guest page). `npm run serve` then open `http://127.0.0.1:8787`.
- `web/peer.js`: shared WebRTC data channel script (host + guest): proof of the QR secret, verification number, approval hook, chunking, backpressure, progress, drop detection. STUN only, no relay.
- `web/index.html` + `web/guest.js`: the themed page the other person sees.
- `web/host.html` + `web/host.js`: the host page (QR, approval pop-up, transfer) so two browsers can test the real connection before the Android app takes part.
- `web/transfer.js`: file send/receive UI shared by both pages. `web/qrcode.js`: bundled MIT QR library.
- `worker/`: the Cloudflare Worker (see `worker/DEPLOY.md`, deploy from a phone).
- `web/harness.html`: bare page used by the tests as a stand-in "host".

## Tests
- `npm test` (in this folder): matchmaker unit tests, no browser needed. Also runs in CI.
- `PW_MODULES=<folder holding playwright> node test/e2e.mjs`: two real Chromium pages, 100 MB transfer, backpressure, dropped connection, stranger with wrong secret, typed code, second guest, no direct path.
- `PW_MODULES=... SHOTS=<folder> node test/ui.mjs`: drives the real guest page and saves screenshots.
The browser tests use loopback with no STUN, so they prove the logic, not real-network success rates (that is slice 4).

## API (what the Cloudflare Worker will expose too)
`POST /api/rooms` -> `{code, hostKey}`; `POST /api/rooms/:code/join` -> `{guestKey}`; `POST .../reset` (host); `POST .../send` `{data}`; `GET .../recv?after=N&wait=20`; `DELETE /api/rooms/:code`. Keys go in the `X-Key` header. Rooms last 10 minutes, one guest, 10 room creations and 10 failed joins per minute per IP.

`E2E_SIGNAL=<worker url> node test/e2e.mjs` runs the same suite against a running Worker (`cd worker && npm install && npx wrangler dev --port 8788`).
