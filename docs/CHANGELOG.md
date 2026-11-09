# Changelog

## Unreleased
- Added visual/TouchShare_needs.json (7 pages, 48 files, 22 regions) for the App Visual Builder template workflow (D-010).
- Recorded D-009 (remote share beta without relay) and added roadmap item 0.5. Plan only; no server work started.
- Added docs/MARKET_RESEARCH.md, decision D-008, roadmap item for LocalSend protocol evaluation.
- Owner confirmed v0.1 APK works on device (via Touch Developer app).

## 0.1.0 (unreleased, branch `claude/touch-share-v1`)
- Added `core` module: ShareServer (SEND/RECEIVE), TokenAuth, FileNames, NetworkPicker, Pages; 7 unit tests passing locally.
- Added `app` module: Send/Receive/Stop UI, QR code, document picker, MediaStore saves.
- Added CI workflow building the debug APK on `claude/**` pushes.
- Not yet verified: APK build in CI and on a real device (no Android SDK in the authoring environment).
