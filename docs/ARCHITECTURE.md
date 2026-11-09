# Architecture (v0.1)

Two Gradle modules so the logic is testable without an emulator.

## core (JVM)
| File | Role |
|---|---|
| `ShareServer` | NanoHTTPD server. Modes: SEND (list + download) and RECEIVE (upload page + POST /upload). Every route requires `?t=<token>`; otherwise 403. Adds `Cache-Control: no-store`, `nosniff`, `no-referrer`. |
| `TokenAuth` | 128-bit random hex token per session; constant-time compare. |
| `FileNames` | Sanitizes uploaded/served names (strips paths, control chars, leading dots, length cap) and de-duplicates. |
| `NetworkPicker` | Picks the advertised IPv4: prefers `ap`/`swlan`/`wlan`/`eth`/`p2p`, private ranges only, skips cellular/loopback. Builds the share URL. |
| `Pages` | HTML for the list and upload pages; all dynamic text HTML-escaped. |
| `Models` | `FileSource`, `FileSink`, `ShareFile`, `ShareEvent`, `Mode` interfaces so Android storage is pluggable. |

## app (Android)
- `MainActivity` - Send/Receive/Stop, QR, event log. Keeps screen on while sharing.
- `UriSource` - files chosen via `ACTION_OPEN_DOCUMENT` (no storage permission needed).
- `DownloadsSink` - writes via MediaStore (API 29+) or app-specific Downloads.
- `Qr` - ZXing bitmap.
- Permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE only.

## Security model
- Server exists only while a session is active; stopping or leaving the app tears it down.
- Unguessable per-session token in the link; no token, no data.
- Traffic is plain HTTP on the local network (see DECISIONS D-004). Use only on a hotspot or network you trust.
- Uploaded names are sanitized; temp upload files are deleted after saving.

## Known limits in v0.1
- Server dies if Android kills the app in the background (no foreground service yet).
- Uploads are buffered to a temp file first (needs free cache space equal to the file).
- No resume, no HTTP Range, no zip-all, no folders.
- Browser uploads send one file per request (NanoHTTPD keeps one file per field name).
