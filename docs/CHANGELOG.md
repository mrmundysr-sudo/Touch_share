# Changelog

## 0.1.0 (unreleased, branch `claude/touch-share-v1`)
- Added `core` module: ShareServer (SEND/RECEIVE), TokenAuth, FileNames, NetworkPicker, Pages; 7 unit tests passing locally.
- Added `app` module: Send/Receive/Stop UI, QR code, document picker, MediaStore saves.
- Added CI workflow building the debug APK on `claude/**` pushes.
- Not yet verified: APK build in CI and on a real device (no Android SDK in the authoring environment).
