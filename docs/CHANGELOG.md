# Changelog

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
