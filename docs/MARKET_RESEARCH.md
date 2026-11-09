# Market research (2026-11-09)

Source quality: mostly secondary tech sites and comparison blogs. No independent privacy audits were found. Verify against each app's store listing and official pages before acting on specifics.

## Landscape
| Product | Transport | Receiver needs app? | Cost / ads | Notes |
|---|---|---|---|---|
| Quick Share (Google/Samsung) | Auto: BLE discovery, Wi-Fi Direct/LAN, Bluetooth fallback | Built into most Android | Free | AirDrop interoperability launched late 2025 on Pixel 10, expanded to Pixel 9 and Samsung Galaxy S26 (rollout from March 2026); Google named OPPO, OnePlus, vivo, Xiaomi, HONOR as 2026 partners. Device lists vary between sources. |
| AirDrop | Apple proprietary | Built into Apple OS | Free | Apple devices (plus Quick Share interop above). |
| LocalSend | Same local network (HTTPS, encrypted) | **Yes**, app on both | Free, open source (Apache-2.0), no ads | Windows, macOS, Linux, Android, iOS. Sends files, folders, clipboard text. Single local network only. |
| SHAREit | Wi-Fi Direct/hotspot | Yes | Free with heavy ads, extra content | Most criticized for ads. |
| Xender | Hotspot/Wi-Fi Direct, offline | Yes | Free, lighter ads | Reported more consistent speeds than SHAREit. |
| Send Anywhere | Internet or local | Yes (or link) | Free tier | Works across networks, not purely offline. |
| Zapya, Mi Drop, Dukto, Feem, others | Wi-Fi/hotspot | Yes | Varies | Similar category. |
| XShare | - | - | - | Reported abandoned; avoid unofficial APKs. |

## Where Touch Share differs
1. **Browser-only receiver**: the other phone, laptop or guest needs no app (QR + browser). LocalSend and Quick Share both need an app or supported OS.
2. **No ads, accounts or tracking**, with a small auditable security model.
3. **Works on a plain phone hotspot** with no discovery layer.

## Where it is weaker today
- Plain HTTP (HTTPS planned 0.4), no resume, host must keep the app open (foreground service planned 0.2), manual QR step, no auto-discovery.
- Quick Share plus AirDrop interop covers the mainstream "send a photo to a friend" case well; competing head-on there is not recommended.

## Implications for the roadmap
- Prioritize the **browser receiver** advantage: make it fast, polished, and safe (0.2 accept prompt, 0.4 HTTPS).
- Consider **LocalSend-style protocol compatibility** (evaluate its open protocol) instead of inventing a new app-to-app transport for 1.0, alongside Nearby Connections. Needs a licensing and feasibility check before approval.
- Candidate niches to test with the owner: offline classrooms/events, guests without apps, older users, kids, studio-suite integration.

## Sources
- LocalSend: https://www.localsend.org , https://www.techspot.com/news/113367-localsend-like-airdrop-actually-works-everywhere.html
- App comparisons: https://www.alternativeto.net/software/shareit/ , https://www.gizbot.com/apps/features/10-best-shareit-alternatives-on-android-051850.html , https://geekchamp.com/?p=250474
- Quick Share/AirDrop: https://www.macrumors.com/2026/02/11/airdrop-quick-share-interoperability-more-phones/ , https://www.mactrast.com/2026/05/google-to-expand-quick-share-file-sharing-between-android-and-iphone/ , https://www.sammobile.com/news/airdrop-compatibility-galaxy-phones-tablets-soon/
