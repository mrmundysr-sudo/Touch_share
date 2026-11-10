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

## 0.2 additions
- `DeviceRegistry` (core): PENDING/ALLOWED/DENIED per device cookie; cap of 5 waiting; denied IPs blocked.
- `ShareServer` gate: after the token check, every request needs an allowed device. Pending devices get a polling "waiting" page and `/status`; allowed devices proceed; denied get 403.
- `ShareService` (app): foreground service owning the server, wake lock, notifications (session + approval with Allow/Deny).
- `SessionHolder` (app): in-process state (url, mode, log, pending devices) that the screen renders.

## 0.3 additions (visual theme)
- `tools/prepare_art.py`: Visual Builder export -> `app/src/main/assets/art/` (WebP + `art-config.json`) and icon resources.
- `Art` loads a page by id; `ArtLayout` fits the art to the screen (letterboxed) and positions live views over named regions.
- `MainActivity` builds Home, Sending and Receiving as `ArtLayout`s and swaps them from `SessionHolder` state; the Allow prompt is a dialog around the page_04 art.
- Core `StaticAssets` + `/assets/<name>` route feed the themed browser pages; `Pages` uses `page_05.webp` as the page background.
- Art is swapped by re-running `prepare_art.py` with a new export, no code change.

## 0.4 additions (resume)
- Downloads: `GET /f/<id>` honours `Range: bytes=...` (206 + Content-Range), sends `Accept-Ranges` and an ETag, honours `If-Range`, answers HEAD. `FileSource.openAt(id, offset)` seeks (default skips).
- Uploads: `POST /upload/start?name&size` -> `{id, offset}`; `POST /upload/chunk?id&offset` as a form (field `chunk`; a raw `PUT` body is also accepted) (409 + current offset if out of order; `done` when complete); `GET /upload/status?id` -> `{offset, size}`. Chunks are written in order through `FileSink.begin()` -> `IncomingUpload` (write/finish/abort). Android's sink writes to a pending MediaStore item and publishes it on finish. Chunks are 1 MB from the page (server accepts up to 16 MB). Socket read timeout is 60 s. Limits: 16 chunk MB max, 16 concurrent uploads, free-space check.
- The page stores the upload id in localStorage (key = token + file name/size/modified) so the same file resumes after the page is closed, as long as the session is still running.

- Chunk progress on the page: after sending a chunk it also polls `/upload/status` and carries on as soon as the server reports the chunk received, so a reply that never reaches the browser (seen on iOS) does not stall the upload. Finished uploads stay answerable (`done:true`).
- Upload API extras: `POST /upload/cancel?id` aborts a partial; all upload API replies send `Connection: close`. The page falls back to the plain `POST /upload` after two silent stalls and keeps a troubleshooting log in a collapsible "Details" panel.

## Known limits
- Resume only works within a session. Stop discards partial uploads.
- Approval is on by default with no toggle. Plain HTTP by design (D-016): use it on your own hotspot or a Wi-Fi you trust.
- Android 15 limits dataSync foreground services to about 6 hours; not handled yet.
- No idle auto-stop yet (wake lock has a 2-hour safety cap).

## Original limits in v0.1
- (Fixed in 0.2) Server used to die in the background.
- Uploads are buffered to a temp file first (needs free cache space equal to the file).
- No resume, no HTTP Range, no zip-all, no folders.
- Browser uploads send one file per request (NanoHTTPD keeps one file per field name).
