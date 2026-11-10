# Changelog

## 0.4.4 (branch `claude/touch-share-v1`)
**Owner-verified on device (2026-11-10): iPhone (Chrome for iOS) to Android over home Wi-Fi now uploads the big WAV smoothly and fast, with no pause.**
Owner tested 0.4.3 (form-POST chunks) on the same iPhone: still a pause of about 30 s per file, then it goes. That is the first chunk timing out (12 s), a second one timing out (20 s) and the page falling back to the plain upload. So the stall is not specific to the raw PUT body; the other phone receives each chunk but the iPhone browser never finishes receiving the reply. A raw-socket check of the real server shows the reply is complete and well formed (headers and body in one read, `Content-Length: 18`, then a clean close, 47 ms after a 1 MB chunk), so the server's reply is not malformed.
- The page no longer depends on the chunk reply. Once a chunk's body has been sent it also asks `/upload/status` (which works on the iPhone) every 0.5 s; as soon as the other phone reports it has the chunk, the page cancels the unfinished request and sends the next chunk.
- `/upload/status` keeps answering `{"done":true}` after an upload completes (so a missing final reply cannot leave the page waiting). The upload offset is now volatile for cross-thread reads.
- Server sockets use TCP_NODELAY so replies are sent immediately (low-risk hardening; effect on the stall not proven).
- Verified here: 34 core tests; in headless Chromium behind a proxy that lets every chunk reach the server but swallows all 7 chunk replies (the iPhone symptom), the 7 MB file completed in 4.3 s, no fallback, identical file. Dropped-chunk recovery and close/reopen resume still pass.
- Not verified: real iOS. If this does not clear the pause, the Details panel will show whether the status check also fails.

## 0.4.3 (unreleased, branch `claude/touch-share-v1`)
Owner tested 0.4.2 on an iPhone (Chrome for iOS, iOS 26.5, WebKit) to an Android host over home Wi-Fi. The troubleshooting log showed: `POST /upload/start` fine (0.3 s); the first `PUT /upload/chunk` body was sent in 51 ms but its reply never completed (XHR readyState 3, timed out after 20 s); a following `GET /upload/status` was fine (0.14 s). After the timeout the transfer carried on and a 41 MB WAV plus PNGs arrived intact. The plain single-request form POST upload had worked reliably, including the 41 MB file. So the stall is specific to the raw-body PUT chunk request on iOS (cause inside WebKit or our reply is not proven).
- Chunks are now sent as form POSTs (`multipart/form-data`, field `chunk`), the same kind of request as the reliable plain upload. The server accepts both POST and the old raw PUT. Resume, status and cancel are unchanged.
- First chunk times out after 12 s (later chunks 20 s) so a stall costs less before the retry or the plain-upload fallback.
- Verified here: 33 core tests (new: form-POST chunk assembly, duplicate refusal, resume); real page in headless Chromium: dropped chunks recover and resume, closed-and-reopened page resumes at 1 MB, a chunk that never gets answered falls back to the plain upload after 41 s, files identical.
- Not verified: real iOS Safari/Chrome. If the first chunk still stalls, the Details panel will show it.

## 0.4.2 (unreleased, branch `claude/touch-share-v1`)
Second round after the owner retried iPhone to Android with 0.4.1: the upload still stopped after the first chunk (about 5%), with no percentage text and no error on the iPhone page, so the 5 s timeout fix was not the (only) cause. The real cause is not yet proven; this build removes suspects, adds evidence and adds a way through.
- Upload API replies now carry `Connection: close`, so every request uses a fresh connection (rules out browser keep-alive reuse).
- Upload page has a "Details (for troubleshooting)" panel with a timestamped log of every request (start, body sent, status, error, time taken, whether upload progress events arrive, readyState while waiting, browser user agent). Photograph it if an upload stalls.
- Automatic fallback: if two chunk requests in a row get no answer at all (timeout/abort = a silent stall), the page cancels the partial upload (`POST /upload/cancel`) and sends the file in one plain request (`POST /upload`). Real dropouts (quick network errors) keep retrying and resuming as before. Request timeouts: 20 s per chunk, 10 s for start/status.
- Host screen logs "Incoming name (size)" when an upload starts, so a start that never arrives is distinguishable from a chunk that never arrives.
- A cancelled or aborted upload releases its file name (no stray "(2)" copies).
- Verified here: 32 core tests; real page in headless Chromium: dropped chunks recover and resume, closed-and-reopened page resumes at 1 MB, and a chunk that never gets answered (iPhone-like stall) falls back to the plain upload after about 41 s with an identical file.
- Not verified: real Safari on iOS. Owner also reported the old stuck file status appearing after uninstall and reinstall; unexplained, asked which screen.

## 0.4.1 (unreleased, branch `claude/touch-share-v1`)
Fixes after the owner's first iPhone-to-Android upload test (uploads stalled at about 6% on both files; the iPhone page also showed unreadable buttons and an empty progress bar).
- Server: socket read timeout raised from NanoHTTPD's 5 s to 60 s. Reproduced here: a sender that goes quiet for more than 5 s mid-chunk (for example while iOS reads a large file) got HTTP 500 and a broken connection. New regression test (sender pauses 6.5 s) passes; manual test with an 8 s pause returns 200.
- Server: any failure while reading an upload now ends the connection cleanly and shows a "Blocked a request (...)" line on the host screen; a chunk with no body reports its content-length and transfer-encoding.
- Page: chunks are 1 MB (was 2 MB), every request times out after 40 s and retries, the status line shows the percentage and the reason when reconnecting (network / timed out / HTTP code), and gives up with the reason after 8 tries.
- Page styling for Safari: buttons use `-webkit-appearance:none` so their text is readable, the file picker button is styled for both `::file-selector-button` and `::-webkit-file-upload-button`, and the progress bar has explicit WebKit/Firefox styles.
- Verified here: 30 core tests pass; real page in headless Chromium against the real server (dropped chunks recovered, closed-and-reopened page resumed, identical file); one-connection keep-alive test passed.
- Not verified: real Safari on iOS (no WebKit available here). The stall at 6% is not proven to be this timeout; if it still happens, the page now says why and the host screen logs the cause.

## 0.4.0 (unreleased, branch `claude/touch-share-v1`)
- Resumable downloads: server answers HTTP Range (single ranges, suffix ranges, 416, If-Range, ETag, Accept-Ranges, HEAD). The Android file source seeks straight to the offset.
- Resumable uploads: new `/upload/start`, `/upload/status`, `/upload/chunk` protocol; the web page sends files in 2 MB chunks, retries with back-off, resyncs after a drop, and remembers the upload so choosing the same file after closing the page continues from where it stopped. Bytes are written straight to Downloads/TouchShare (no temp copy); free-space check (HTTP 507); partial files are removed if the session stops. Old one-shot `/upload` still works.
- Host screen shows a live "Receiving name NN%" line.
- Verified here: 29 core tests pass; the real upload page was driven in headless Chromium against the real server with two chunks deliberately dropped (7 MB file saved byte-identical) and with the page closed and reopened mid-upload (resumed at 2,097,152 bytes, identical file).
- Not yet verified: Android compile in CI, MediaStore pending-item writes, seek on real file providers, and behaviour on a real phone over a hotspot.

## 0.3.0 (unreleased, branch `claude/touch-share-v1`)
- Visual theme from the App Visual Builder export (`touch_share_visual_assets.zip`, 6 pages): Home, Sending, Receiving and Allow-device screens now use the painted art with live widgets (QR, link, latest activity line) placed over the artist's regions; tap areas over the painted buttons.
- New `tools/prepare_art.py` converts an export into WebP art, `art-config.json` (page id -> image + regions as fractions) and the launcher icon (adaptive + legacy).
- Allow prompt is now the themed pop-up (was a plain dialog).
- Browser pages (download, upload, waiting) share the themed web mockup; core serves `/assets/<name>` (token required, images only). 15 core tests passing locally (2 new).
- New launcher icon (UFO), portrait-only.
- Notification permission: no longer requested at launch. The first time you start sharing, a themed pop-up (same art as the Allow prompt) explains why; Android's own system box (which cannot be themed) appears only if you choose Allow. Asked once; saying no still lets you share.
- Owner-verified on device: builds install and run; the system notification box was the only unthemed element (owner screenshot 2026-11-10).
- Not yet verified: tap alignment of all screens, text fit in the thin strips, icon crop.

## 0.2.0 (unreleased, branch `claude/touch-share-v1`)
- Device approval: new browsers see a "Waiting for approval" page until the host taps Allow or Deny (dialog in app, or notification actions when the app is in the background). Denied IPs stay blocked. Pending devices cannot see the file list, download, or upload. (core: `DeviceRegistry`, `ShareServer`; 6 new unit tests, 13 total passing locally.)
- Foreground service (`ShareService`) keeps the server alive in the background; notification with Stop; partial wake lock capped at 2 hours.
- Activity now renders session state from `SessionHolder`, so reopening the app shows the QR code again.
- Added permissions: FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK.
- Not yet verified: Android compile in CI and behaviour on a real phone (background transfer, notification actions, approval flow).

## Unreleased
- Added visual/TouchShare_needs.json (6 pages, 10 files, 13 regions) for the App Visual Builder template workflow (D-010). Verified it loads in the builder's real NeedsParser with no errors or warnings.
- Recorded D-009 (remote share beta without relay) and added roadmap item 0.5. Plan only; no server work started.
- Added docs/MARKET_RESEARCH.md, decision D-008, roadmap item for LocalSend protocol evaluation.
- Owner confirmed v0.1 APK works on device (via Touch Developer app).

## 0.1.0 (unreleased, branch `claude/touch-share-v1`)
- Added `core` module: ShareServer (SEND/RECEIVE), TokenAuth, FileNames, NetworkPicker, Pages; 7 unit tests passing locally.
- Added `app` module: Send/Receive/Stop UI, QR code, document picker, MediaStore saves.
- Added CI workflow building the debug APK on `claude/**` pushes.
- Not yet verified: APK build in CI and on a real device (no Android SDK in the authoring environment).
