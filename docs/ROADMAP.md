# Roadmap

Status key: **Done** / **Next** / **Planned** / **Idea**. Update this file whenever scope changes and record the reason in DECISIONS.md.

| Ver | Item | Status | Notes |
|---|---|---|---|
| 0.1 | Hotspot/LAN web server, QR, one-time token, Send + Receive, MediaStore saves | **Done (needs device test)** | This scaffold. |
| 0.2 | Foreground service + notification so transfers survive backgrounding | **Done (needs device test)** | Service owns the server; Stop button in the notification; 2-hour wake-lock cap. Android 15's 6-hour data-sync limit not yet handled. |
| 0.2 | Per-device accept prompt on the host (tap to allow a new device) | **Done (needs device test)** | In-app dialog, or notification with Allow/Deny when the app is not on screen. Denied IPs stay blocked for the session. |
| 0.2 | Device-test checklist and fixes (hotspot IP, picker, large files, background transfer, approval prompt) | **Next** | Record results in CHANGELOG. |
| 0.3 | Visual theme from App Visual Builder export (6 pages, see D-010, D-013): importer script, art-driven Home/Sending/Receiving/Allow screens, themed web pages, launcher icon | **Done (needs device test)** | Owner's zip received 2026-11-10. |
| 0.3 | Sending/Receiving screens show only the latest activity line (the artwork's list strip is one line tall); consider a scrollable history panel | Idea | Needs art for a taller list area. |
| 0.4 | **Resume**: HTTP Range downloads (browsers resume interrupted downloads) and resumable chunked uploads (1 MB chunks, page reconnects and continues, bytes streamed straight into Downloads with no temp copy, free-space check, progress line) | **Done (needs device test)** | D-017. Works within one session; Stop ends it. |
| 0.5 | Auto-stop after idle, handle Android 15 foreground-service timeout, settings toggle for approval | Planned | Moved from 0.3. |
| 0.5 | "Download all as zip", transfer history screen, choose save folder (SAF) | Planned | |
| later | HTTPS as an opt-in "Secure mode" with a per-session self-signed cert (browser warning is the cost) | Idea | D-016: plain HTTP on trusted hotspot/Wi-Fi is the supported mode; revisit only if users ask. |
| 0.6 | **Remote share (beta, no relay)**: WebRTC data channel + STUN + minimal signaling; browser receiver kept; honest labelling, timeout error, fallback to local QR | Planned | D-009. Test on real networks first and record success rates; add privacy policy; short-lived codes + rate limits. No TURN unless re-approved. |
| 1.0 | **Nearby Connections (Google Play Services)**: app-to-app, BLE discovery, auto Wi-Fi/Bluetooth upgrade | Planned | Android only. Biggest UX win, no hotspot setup. |
| 1.1 | **Wi-Fi Direct** transport | Idea | May be redundant with Nearby; evaluate after 1.0. |
| 1.2 | **Bluetooth (RFCOMM/BLE) fallback** | Idea | Slow; only for no-Wi-Fi situations. |
| later | Folder send, clipboard/text share, nicer UI/Compose, localization, Play Store release | Idea | |
| 1.0 | Evaluate LocalSend open-protocol compatibility vs Nearby Connections (licence + feasibility) | Planned | See MARKET_RESEARCH.md, D-008. |
| later | Check Touch-Modules registry for reusable components | **Open** | See DECISIONS D-005. |

## Principles
- Add transports behind the same `FileSource`/`FileSink` interfaces so UI and storage stay unchanged.
- Never ship a feature without a unit test in `core` where logic can be isolated.
- Every scope change gets a DECISIONS entry and a CHANGELOG line.
