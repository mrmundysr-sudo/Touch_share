# Put the remote share Worker online, from a phone

Needs: your GitHub account (this repo) and a free Cloudflare account. No computer or command line. The Worker is the small matchmaker plus the web pages (`/host.html` and the guest page). It never sees files.

## Steps (phone browser; if a page looks cramped, use "Desktop site")
1. Open **dash.cloudflare.com** and sign up with an email and password. Verify the email. (The free plan should not ask for a payment card; if it does, stop and tell me.)
2. In the dashboard open the menu and go to **Workers & Pages** (sometimes under *Compute*). Tap **Create**, then **Import a repository** (older wording: *Connect to Git*).
3. Sign in to GitHub when asked and allow Cloudflare access to **only the `Touch_share` repository**.
4. Pick `Touch_share` and set:
   - **Project / Worker name:** `touch-share-remote` (must match exactly)
   - **Production branch:** `claude/touch-share-v1`  (not `main`, which has none of this)
   - Open **Advanced settings** and set **Root directory:** `remote/worker`
   - **Build command:** leave empty. **Deploy command:** `npx wrangler deploy` (the default).
5. Tap **Save and Deploy** and wait one or two minutes. If it fails, open the build log and send me a screenshot.
6. When it succeeds, open the Worker. Its address looks like `https://touch-share-remote.<something>.workers.dev`. Send me that address.

## Try it with two phones (this tests the real connection across networks, no app needed)
- **Phone A:** open `<your address>/host.html`. It shows a QR code, a link and a 6-digit code.
- **Phone B:** scan the QR with the camera (or open the site and type the code).
- **Phone A:** the pop-up shows a 4-digit number. Check that phone B shows the same number, then tap **Allow**.
- Either phone: choose a file and tap **Send**. The other phone shows a **Save** button.
- The status line says how the phones connected: *same network*, or *direct across the internet*. If it says it couldn't connect, take a screenshot and open **Details (for troubleshooting)** and photograph that too.

Worth trying, and telling me what happened each time:
1. Both phones on the same Wi-Fi.
2. One phone on Wi-Fi, one on mobile data.
3. Both phones on mobile data (different carriers if you can).
4. One phone on someone else's Wi-Fi (friend, work, hotel).

## Notes
- When I push changes to the branch, Cloudflare redeploys by itself.
- The address is public: anyone who knows it can use the matchmaker (limited to 10 new rooms and 10 failed code guesses per minute per person). Every connection still needs your tap on **Allow**.
- Free plan limits (per Cloudflare's docs): 100,000 requests a day for the matchmaker. Each waiting phone makes about 3 requests a minute, so this is plenty for a beta.
- To switch it off: delete the Worker in the Cloudflare dashboard.
- Dashboard wording changes now and then; I could not check it live, so tell me if a step does not match what you see.
