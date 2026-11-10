# Remote share (beta, no relay): scoped plan

Status: **plan awaiting owner approval; no code written.** Product decision D-009 (approved): optional "from anywhere" beta using WebRTC data channels + STUN + a tiny signaling service, **no TURN relay**, honestly labelled "works on many networks, not all", falls back to the local QR flow. Everything below that is not D-009 is a **proposal**.

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

## Open decisions
1. Where the signaling service lives: own Cloudflare Worker (recommended), PeerJS Cloud (no account, third-party dependency, bundled TURN to be disabled), or a small Node service on a server the owner already has.
2. Android peer: hidden WebView running the shared script (recommended) or native WebRTC library (larger APK, separate code to maintain).
3. Starting point: S1 only (recommended) or S1 to S3 in one go.
