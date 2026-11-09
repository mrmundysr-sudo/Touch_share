# Roadmap

Status key: **Done** / **Next** / **Planned** / **Idea**. Update this file whenever scope changes and record the reason in DECISIONS.md.

| Ver | Item | Status | Notes |
|---|---|---|---|
| 0.1 | Hotspot/LAN web server, QR, one-time token, Send + Receive, MediaStore saves | **Done (needs device test)** | This scaffold. |
| 0.2 | Foreground service + notification so transfers survive backgrounding | **Next** | Required for large files; needs `FOREGROUND_SERVICE_DATA_SYNC` (API 34). |
| 0.2 | Per-transfer accept prompt on the host (tap to allow a new device) | **Next** | Closes the gap where anyone with the link can transfer. |
| 0.2 | Device-test checklist and fixes (hotspot IP, picker, large files) | **Next** | Record results in CHANGELOG. |
| 0.3 | HTTP Range + resume, "download all as zip", streaming upload (no temp copy) | Planned | |
| 0.3 | Transfer history screen, choose save folder (SAF) | Planned | |
| 0.4 | HTTPS with per-session self-signed cert (cert pinning via QR fingerprint) | Planned | Browser warning is the UX cost; see D-004. |
| 1.0 | **Nearby Connections (Google Play Services)**: app-to-app, BLE discovery, auto Wi-Fi/Bluetooth upgrade | Planned | Android only. Biggest UX win, no hotspot setup. |
| 1.1 | **Wi-Fi Direct** transport | Idea | May be redundant with Nearby; evaluate after 1.0. |
| 1.2 | **Bluetooth (RFCOMM/BLE) fallback** | Idea | Slow; only for no-Wi-Fi situations. |
| later | Folder send, clipboard/text share, nicer UI/Compose, localization, Play Store release | Idea | |
| later | Check Touch-Modules registry for reusable components | **Open** | See DECISIONS D-005. |

## Principles
- Add transports behind the same `FileSource`/`FileSink` interfaces so UI and storage stay unchanged.
- Never ship a feature without a unit test in `core` where logic can be isolated.
- Every scope change gets a DECISIONS entry and a CHANGELOG line.
