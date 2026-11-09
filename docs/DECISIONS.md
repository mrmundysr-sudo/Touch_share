# Decisions

**Approved** = confirmed by the owner. **Proposed** = Claude's suggestion awaiting approval.

| ID | Decision | Status | Date |
|---|---|---|---|
| D-001 | Build option A first (hotspot/LAN HTTP server + QR); expand later to Nearby Connections, Wi-Fi Direct, Bluetooth. Document all future updates. | Approved | 2026-11-09 |
| D-002 | Native Kotlin app (not Capacitor). | Approved | 2026-11-09 |
| D-003 | Repo `mrmundysr-sudo/Touch_share`; work on `claude/touch-share-v1`; no merge to `main` or release without owner approval. | Approved | 2026-11-09 |
| D-004 | v0.1 uses plain HTTP with an unguessable per-session token on a trusted hotspot/LAN; HTTPS to follow in 0.4. | Proposed | 2026-11-09 |
| D-005 | Touch-Modules registry was not accessible from this session, so no studio component was reused. Owner to grant access or confirm none applies before 0.2. | Open | 2026-11-09 |
| D-006 | Libraries: NanoHTTPD 2.3.1 (BSD-3), ZXing core 3.5.3 (Apache-2.0). Both permissive. | Proposed | 2026-11-09 |
| D-007 | Received files saved to `Download/TouchShare` via MediaStore; no storage permission requested. | Proposed | 2026-11-09 |
| D-008 | Market research done (docs/MARKET_RESEARCH.md). Differentiator to build on: browser-only receiver, no ads/accounts. Evaluate LocalSend protocol compatibility before choosing the 1.0 transport. | Proposed | 2026-11-09 |
| D-009 | Remote ("from anywhere") sharing, if built, will be an optional **beta** using WebRTC data channels with STUN and a tiny signaling service, and **no TURN relay**, to keep running costs near zero. Local same-network sharing stays the primary feature. The app must label it honestly ("works on many networks, not all"), fail clearly after a timeout, and fall back to the local QR flow. Relay can be reconsidered later if demand and budget justify it. Needs a privacy policy and a measured success rate on real networks before release. | Approved | 2026-11-09 |
| D-010 | Visual workflow: Claude writes `visual/TouchShare_needs.json` (builder needs@1, branch `claude/needs-template` of app-visual-builder); owner fills it in the builder and returns the exported zip; Claude wires art by page and file key. Kept deliberately simple: 6 pages (Home, Sending, Receiving, Allow device, Web page, App icon), mostly one finished picture per page plus clickable/live regions; no button states or PSD layers. QR, text and progress stay live. Receiving phone's browser page is themed too. | Approved | 2026-11-09 |
| D-011 | Device approval is keyed on a random browser cookie (`ts_dev`), not the IP, so two phones behind one address are approved separately. A denied device's IP stays blocked for the session. At most 5 devices can wait at once (extra get HTTP 429). Approval is always on in 0.2 (no off switch yet). | Proposed | 2026-11-09 |
| D-012 | The server runs inside a foreground service (type dataSync) so sharing continues when the app is backgrounded; session state lives in an in-process holder the screen reads. Session ends when the user taps Stop or the service is killed. | Proposed | 2026-11-09 |
