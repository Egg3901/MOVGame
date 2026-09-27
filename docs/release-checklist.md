# Release checklist (Phase 6, #24)

Owner-side runbook for the first store submissions. Code hooks are in the
tree; every unchecked box below needs a console action or a macOS run that
this track cannot perform headless.

## Android (Play)

- [ ] On a device: start a campaign, queue an action, close the app, reopen it,
  finish a turn, and confirm the same campaign and event queue resume.
- [ ] Play Console: create the app and first non-consumable products, then fill
  `SkuTable.entries` (`billing/SkuTable.kt`). Supply the license key as
  `MOV_PLAY_PUBLIC_KEY` through a build environment or Gradle property.
- [ ] Sandbox: purchase → refund → restore → reinstall → offline refresh,
  per docs/billing.md. Refunds must drop the pack on the next
  `queryPurchases`; a stale cache entry past grace must not re-grant
  (covered by `EntitlementsTest`, proven on device here).
- [ ] RTDN endpoint on the campaign server (web repo): revocation ->
  entitlement drop. Until it exists, refunds land on the next client check.
- [ ] Listing: title, short/full description, screenshots (tile map + EV bar
  + results), feature graphic, content rating questionnaire, privacy policy
  URL, data-safety form (no account, no ads SDK; Sentry crash reports only
  when a DSN is set).
- [ ] Release build with `MOV_SENTRY_DSN` set; verify a test crash arrives in
  Sentry before promoting to production.
- [ ] Attach gate evidence (gates 1-6 in README) to the Hub work item.

## iOS (App Store)

- [ ] Apple Developer: register the explicit iOS App ID
  `com.lakesidegames.electioneer` in the existing team. In-App Purchase is
  enabled by default for an explicit App ID.
- [ ] Apple Developer: create an **App Store Connect** distribution profile
  for that App ID using the existing Apple Distribution certificate (expires
  2027-09-10). Download its `.mobileprovision` file. Codemagic already has
  the matching certificate and App Store Connect API key in its encrypted
  `mov-signing` group; the profile is the one missing signing input.
- [ ] App Store Connect: create the iOS app record for that bundle ID with
  name `Margin of Victory`, English primary language, and an internal SKU
  such as `MOV-IOS-001`. The record must exist before Codemagic can upload.
- [ ] Run Codemagic `ios-testflight` on the reviewed commit with
  `MOV_REVIEW_COMMIT` set to its full SHA. Confirm a signed IPA uploads and
  reaches Apple's `VALID` processing state. The workflow does not submit
  to App Review; assign the build to internal testers after processing.
- [ ] On a device: repeat the campaign close/reopen test and verify the shared
  save restores through the SwiftUI session.
- [x] macOS verify: Codemagic `ios-verify` compiled the Kotlin framework and
  SwiftUI simulator app at source commit
  `2700c3f1884b6cc53cd67ae4f3f417c3411ebdc8` (build
  `6ab9539a9e9edb1d6d9a0803`). The compile step succeeded; the overall
  run was canceled after cleanup stalled for several minutes. This workflow
  does not sign or publish.
- [ ] App Store Connect: products matching the SKU table, sandbox +
  TestFlight exercise of purchase/restore/refund through `StoreKitAdapter`.
- [ ] App Store Server Notifications endpoint on the campaign server (web
  repo) for refunds/revocations.
- [ ] sentry-cocoa via SPM + DSN (Android hook `MovApp` is the pattern to
  mirror; not added blind from Linux).
- [ ] Listing: screenshots, privacy manifest + nutrition label (gameplay
  only, no tracking), TestFlight beta notes, crash-free gate.
- [ ] Attach gate evidence per platform to the Hub work item.

## Launch-safe posture (from #23)

Ship store-scoped entitlements (billing.md option 1): a Play purchase
unlocks on that device/account only and never mints a Lakeside
entitlement. The receipt bridge (option 2) is deferred until Play sales
exist; `EntitlementCache.receiptFor` preserves the signed receipt payload
the bridge endpoint will need.
