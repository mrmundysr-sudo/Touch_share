# Touch Share

Simple phone-to-phone file sharing over a hotspot or shared Wi-Fi. No accounts, no internet, no app needed on the receiving phone (a browser is enough).

## How it works (v0.1)
1. Phone A turns on its hotspot, or both phones join the same Wi-Fi. Phone B connects to it.
2. On phone A, tap **Send files** (pick files) or **Receive files**.
3. The app starts a small local web server and shows a QR code containing a one-time link.
4. Phone B scans the QR code with its camera and opens the link in a browser: it downloads the files (Send) or uploads files (Receive).
5. Tap **Stop sharing** when done. The server stops and the token is discarded.

Received files go to `Downloads/TouchShare` (Android 10+) or the app's own Downloads folder (older versions).

## Safety note
Touch Share uses plain HTTP on purpose. Use it on your own hotspot or a Wi-Fi you trust, not on public Wi-Fi. A one-time link plus your tap on **Allow** protects each session, but the traffic itself is not encrypted.

## Visuals
Screen art comes from the App Visual Builder. To update it: export from the builder, unzip, then `python3 -I tools/prepare_art.py <unzipped-folder>` (needs Pillow) and commit the generated `app/src/main/assets/art/` and icon resources. See `visual/TouchShare_needs.json` for the request that defines each page.

## Project layout
- `core/` - pure Kotlin/JVM: HTTP server, token, file-name safety, network pick, pages. Unit-tested without Android.
- `app/` - Android UI, file picker, storage, QR.
- `docs/` - ARCHITECTURE, ROADMAP, DECISIONS, CHANGELOG.

## Build and test
- `gradle :core:test` - run tests (JDK 17+).
- `gradle :app:assembleDebug` - needs the Android SDK. CI does this on every `claude/**` push and uploads the APK as a workflow artifact.

## Status
v0.3 themed build (device approval, background service, Visual Builder art). See [docs/ROADMAP.md](docs/ROADMAP.md) for what is planned and [docs/CHANGELOG.md](docs/CHANGELOG.md) for what changed.
