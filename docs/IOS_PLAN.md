# Touch Share for iPhone: plan

Status: **branch created 2026-11-10 (`claude/touch-share-ios`, from `claude/touch-share-remote` at the Android 0.5.0-anywhere3 state). No iOS code yet. Everything here is a proposal until the owner approves it.**

Goal (owner): an iPhone app that is "as close to exactly the same as the Android version" as possible: same look (the same art, regions and live text), same screens, same Same Wi-Fi and Anywhere sharing, same device approval, resume and safety rules.

## What can be identical
| Android today | iPhone equivalent | Notes |
|---|---|---|
| Painted screens from the Visual Builder (`art-config.json` + WebP) | Same files and config; a UIKit port of `Art` / `ArtLayout` (fit the picture, live views over marked regions) | WebP decodes natively on iOS 14+. Identical look. |
| Live text (code, website, countdown, status, link) and tap areas over painted buttons | Same, UILabel and invisible buttons | |
| QR on a plain white square | CoreImage QR generator | |
| Share link, Copy link, full-screen QR overlay at full brightness | UIActivityViewController, UIPasteboard, UIScreen brightness | |
| Matchmaker, guest and host web pages, `peer.js` | Unchanged (they are web) | The iPhone is already a working *guest* in a browser. |
| Anywhere host (hidden WebView running `peer.js`, native file bridge) | Hidden WKWebView + WKScriptMessageHandler bridge, same `app-peer.js` | WebRTC data channels work in WKWebView from iOS 14.3. Unverified here. |
| Local server with token, device approval, Range, resumable chunked uploads, themed web pages | Swift port of `core` (DeviceRegistry, FileNames, NetworkPicker, Pages, ShareServer logic) on a vetted small HTTP server library | Port the 34 Kotlin tests to XCTest so both apps are held to the same behaviour. |

## Where iPhone cannot match Android (honest list)
1. **No background server.** Android keeps sharing in a foreground service. iOS suspends an app about 30 seconds after it leaves the screen, which stops the local server and the Anywhere connection. The app must stay open during a transfer (screen kept awake), with a short grace period via a background task. Approval notifications while in the background mostly cannot work.
2. **Storage location.** There is no shared Downloads folder or MediaStore. Received files go to the app's Documents folder, shown in the Files app under Touch Share (and optionally saved to Photos with a permission). Not `Download/TouchShare`.
3. **Permissions.** iOS asks for Local Network access when the app hosts or reaches devices on the local network. Wording and timing differ from Android.
4. **Personal Hotspot.** The hotspot phone's address is usually 172.20.10.1; address picking is ported but differs.
5. **Installing.** There is no APK. A build can only be installed on an iPhone if it is signed (see below).

## Build and install (decision needed)
- **Compiling:** this environment is Linux, so I cannot compile or run Swift here. A GitHub Actions macOS runner can, so the loop is: I write Swift, CI compiles and runs the XCTest port, I fix errors from logs (this will take more rounds than Android did).
- **Project file:** use XcodeGen (`project.yml`) so the repo stays text-only and CI generates the Xcode project.
- **Getting it onto your iPhone, phone-only:** TestFlight, which needs a paid Apple Developer Program account (about $99 a year) and App Store Connect API credentials stored as GitHub secrets **by the owner** (I never handle signing keys or secrets).
- **Without a paid account:** a free Apple ID lets you sideload for 7 days at a time, but only with a Mac or Windows PC (Xcode, AltStore or Sideloadly). That is not phone-only.
- **No signing at all:** CI can still prove the app compiles and the tests pass (simulator build), but it cannot be installed on a real iPhone.

## Proposed slices
| Slice | Deliverable | Verifiable without a device? |
|---|---|---|
| I0 | Branch, plan, `project.yml`, macOS CI workflow that builds and tests | yes (CI) |
| I1 | Swift port of `core` + XCTest port of the tests | yes (CI) |
| I2 | Themed UI from the shared art, Same Wi-Fi sharing | compile only; needs a device |
| I3 | Anywhere via WKWebView and the shared `app-peer.js` | compile only; needs a device |
| I4 | Signing, TestFlight pipeline (owner supplies credentials) | needs owner |

## Risks
- WKWebView may restrict WebRTC or secure-context features (needed for `crypto.subtle`) in ways I cannot test here; the fallback is serving the bridge page from the in-app local server on localhost.
- The Local Network permission and iOS background limits may force UX changes the Android version does not have.
- Two copies of the web page HTML (Kotlin `Pages.kt` and its Swift port) can drift; the tests and a shared checklist are the guard.

## Open decisions
1. Distribution: paid Apple Developer account and TestFlight, or free sideloading, or CI-compile only?
2. First slice: I0 + I1 (CI-verifiable core) before any UI, as recommended?
3. Minimum iOS version and iPhone only (no iPad)?
