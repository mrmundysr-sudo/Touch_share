# Remote share (beta, no relay): scoped plan

Status: **approved 2026-11-10 (own Cloudflare Worker, hidden WebView, slice 1 first). Slice 1 built and tested (see below); slices 2 to 4 not started.** Product decision D-009 (approved): optional "from anywhere" beta using WebRTC data channels + STUN + a tiny signaling service, **no TURN relay**, honestly labelled "works on many networks, not all", falls back to the local QR flow. Everything below that is not D-009 is a **proposal**.

## What the user sees
1. Host taps Send or Receive, then chooses **Same Wi-Fi** (today's flow, unchanged) or **Anywhere (beta)**.
2. App shows a QR code, a link and a short code. The other person opens the link in any browser (nothing to install).
3. The two phones connect directly. The host sees the existing **Allow this device?** pop-up (with a short verification code both sides can compare).
4. Files move over the direct connection. No file data ever touches a server.
5. If no direct connection after about 15 s: "Couldn't connect directly. Try the same Wi-Fi or hotspot." with a button to the local flow.

## Parts
| Part | What | Notes |
|---|---|---|
| Signaling service | Tiny service that hands each side the other's connection details (SDP/ICE) for a short-lived room | Never sees files. Sees both sides' IP addresses (they are inside the connection details). Rooms live 10 minutes, one guest, rate-limited. |
| Shared peer script (JS) | WebRTC data channel logic: chunking (about 16-64 KB), backpressure, progress, offset acknowledgements | Same script runs in the guest's browser and inside the Android app. |
| Guest web page | Themed page (reuses the web art) hosted with the signaling service | Picks files to send, or downloads what the host sends. |
| Android peer | A hidden WebView runs the same script; a small native bridge reads and writes files | Avoids shipping the native WebRTC library. |
| STUN | stun.cloudflare.com:3478 (Cloudflare documents it as free and unlimited) with stun.l.google.com:19302 as backup | Google publishes no formal terms for its public STUN. No TURN, per D-009. |

## Security and privacy
- QR link carries a long random secret in the URL fragment (never sent to the server); typed 6-digit codes are rate-limited and always need the host's Allow.
- Short verification code from the connection fingerprints, shown on both screens (detects a tampering signaling server).
- Host approval is mandatory for every guest, same as today.
- Needs a privacy note before any public release: the signaling service briefly sees IPs and connection details; no logs kept; no analytics.

## Feasibility findings (research 2026-11-10; secondary sources, verify before release)
- Cloudflare Workers free plan allows SQLite-backed Durable Objects (100,000 requests/day, 5 GB storage); WebSocket-specific free limits not confirmed. Source: Cloudflare Durable Objects limits and free-tier pages.
- PeerJS Cloud (free public broker) has no published limits or terms, no SLA, reports of dropped sessions; PeerJS's own docs say production should self-host. It also bundles a free TURN relay, which D-009 excludes unless re-approved.
- Browsers: sending large files needs chunking with backpressure. iOS Safari has no File System Access API, so a guest on iPhone **downloading** a big file must assemble it in memory: expect a practical cap (to be measured; plan assumes about 500 MB to 1 GB) and clear messaging. Uploading from an iPhone is fine (reads slices). Backgrounded mobile tabs throttle or stall data channels.
- STUN-only success is unmeasured. Earlier estimate: good between home Wi-Fi networks, poor between two mobile-data networks and on school/office/hotel Wi-Fi. We measure and publish the numbers instead of trusting estimates.

## Slices
| Slice | Deliverable | Needs owner |
|---|---|---|
| S1 | Protocol + signaling core (Node-testable) + peer script + guest page; Playwright test with two real browser pages, 100 MB file, backpressure, drop handling. **No Android changes.** | approval |
| S2 | Cloudflare Worker adapter + deploy instructions; browser to browser test across real networks (laptop, phones) | create free Cloudflare account and run one deploy command (I never handle tokens) |
| S3 | Android WebView peer, bridge, Anywhere flow, Allow pop-up with verification code; new art page for the Same Wi-Fi / Anywhere chooser (added to the Visual Builder needs file) | art from the builder |
| S4 | Real-network test matrix, results in `docs/REMOTE_SHARE_RESULTS.md`, privacy note, release decision | testing on real devices and networks |

Cost: $0 on free tiers (optional domain extra). Main ongoing duty: keep the signaling service up and the privacy note true.

## Slice 1 result (2026-11-10)
Built in `remote/`: matchmaker core (10 unit tests), Node dev server, shared peer script, themed guest page. Verified in two real Chromium pages over loopback: 100 MB transfer intact with bounded send buffer; 20 MB back the other way on the same connection; matching verification numbers; second guest refused; dropped connection reported in 0.5 s instead of hanging; stranger with a wrong secret turned away without bothering the host and the real guest still connects; typed-code guests reach the prompt flagged as typed (or are refused if typed codes are off); no-direct-path fails clearly after the timeout; the page explains each case in plain words. Four real bugs found and fixed by those tests (guest closing deleting the room, denied message lost on reset, message numbers restarting on reset, final state being overwritten). **Not yet proven:** success rates on real networks, iPhone Safari behaviour (a stall like the upload one is possible), and big-file memory limits on iOS.

## Slice 2 result (2026-11-10, built, not yet deployed)
- Cloudflare Worker (`remote/worker`): one Durable Object runs the same matchmaker code and HTTP handler as the Node tests; static assets serve the pages; the QR-style link path `/r/<code>` serves the guest page; the test-only harness page is not published.
- Verified locally with Cloudflare's own runtime (wrangler dev / workerd): the whole browser-to-browser suite and the page suite pass through the Worker.
- New **host page** (`/host.html`): QR code, link, 6-digit code, approval pop-up with the verification number (typed-code guests flagged), send/receive, stop. Lets two phones test the real connection with browsers only, before the Android app takes part. Pages now report the connection type (same network, direct across the internet, or relayed).
- Bugs found by the Worker run and fixed: a legitimate guest arriving just after a stranger was told the room was taken (turn-away is now one atomic step).
- Deployment is by phone from the Cloudflare dashboard: see `remote/worker/DEPLOY.md`. Owner action needed: create the free Cloudflare account and connect the repository.
- Not verified: the live deployment, Cloudflare dashboard wording, Workers Builds free-plan limits, and anything on real networks.

## Owner test of the deployed Worker (2026-11-10)
Owner deployed the Worker from the Cloudflare dashboard on a phone and reported it working. Tried: both phones on Wi-Fi, and Wi-Fi to mobile data; both connected after Allow, with matching verification numbers. Not yet reported: the connection-type line for each pairing, a file transfer each way, mobile data on both phones, other people's networks. Owner's feedback: the second phone only showed its number after the host tapped Allow, so the host could not compare first. Fixed (D-021, build 3afc6e9 plus the handshake change): the guest now shows the number while waiting; the host holds back its network addresses until approval. This also closes a privacy gap: before, an unapproved typed-code guest could read the host's addresses from the matchmaker. The live address could not be reached from the authoring environment (its network rules block it), so the live deployment is verified only by the owner's phones.

## Open decisions (resolved 2026-11-10)
1. Where the signaling service lives: own Cloudflare Worker (recommended), PeerJS Cloud (no account, third-party dependency, bundled TURN to be disabled), or a small Node service on a server the owner already has.
2. Android peer: hidden WebView running the shared script (recommended) or native WebRTC library (larger APK, separate code to maintain).
3. Starting point: S1 only (recommended) or S1 to S3 in one go.
