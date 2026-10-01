# Release checklist (Phase 6, #24)

Owner-side runbook for store submissions. The native UI and shared engine
build and run through the GitHub Actions platform gates. Unchecked items
require portal access, device testing, or store configuration.

## Android (Play)

- [ ] On a device: choose a scenario, ticket, running mate, staff, and rules;
  queue an issue ad and a pivot on separate days; remove one action; close the
  app, reopen it, and confirm the plan and campaign resume. Finish a turn and
  confirm the same event queue and result flow remain usable.
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
  URL and data-safety form. Declare the actual Lakeside account, cloud save,
  daily score and optional Ask data flows. Sentry crash reports are enabled
  only when a DSN is set.
- [ ] Release build with `MOV_SENTRY_DSN` set; verify a test crash arrives in
  Sentry before promoting to production.
- [ ] Attach gate evidence (gates 1-6 in README) to the Hub work item.

## iOS (App Store)

The Apple organization migration is complete. App ID, App Store Connect
record, and `MOV App Store` distribution profile have been created. GitHub
Actions is the release runner.

- [x] Apple Developer: register the explicit iOS App ID
  `com.lakesidegames.electioneer` in the existing team. In-App Purchase is
  enabled by default for an explicit App ID.
- [x] Apple Developer: create the `MOV App Store` distribution profile with
  the matching certificate, profile and App Store Connect API key stored in
  encrypted repository secrets. Existing signed TestFlight builds establish
  the upload path.
- [x] App Store Connect: create the `Margin of Victory` app record for the
  bundle ID. The record's Apple ID is `6817094138`.
- [ ] Run the manual GitHub `iOS TestFlight` workflow with
  `reviewed_sha` set to the exact main commit. Confirm a signed IPA uploads and
  reaches Apple's `VALID` processing state. The workflow does not submit
  to App Review; assign the build to internal testers after processing.
- [ ] On a device: repeat the campaign and action plan close/reopen test and
  verify the shared save restores through the SwiftUI session.
- [x] macOS verify: Codemagic `ios-verify` fully passed for the native
  campaign menu, setup, seven day action planner, dashboard, selected-state
  projection, and results at `981731cdbcfc630d79914fb15a5effd2873e9e65`
  (build `6ab974b032005f02f19b1808`). This workflow does not sign or publish.
- [ ] App Store Connect: products matching the SKU table, sandbox +
  TestFlight exercise of purchase/restore/refund through `StoreKitAdapter`.
- [ ] App Store Server Notifications endpoint on the campaign server (web
  repo) for refunds/revocations.
- [ ] sentry-cocoa via SPM + DSN (Android hook `MovApp` is the pattern to
  mirror; not added blind from Linux).
- [ ] Listing: screenshots, privacy manifest and nutrition label reflecting
  the actual account, cloud, score, Ask and telemetry behavior; TestFlight
  beta notes and crash-free gate.
- [ ] Attach gate evidence per platform to the Hub work item.

## Launch-safe posture (from #23)

The current beta is free. Both native SKU tables are empty; Apple has zero
MOV IAP products as of 2026-10-01, and Play Console inventory is unverified.
Before paid sales, configure actual products and decide how store purchases
map to account entitlements. Store-scoped ownership and a validated receipt
bridge are options in [billing.md](billing.md), rather than a completed release
decision. `EntitlementCache.receiptFor` preserves the signed receipt payload
needed by a future bridge. Track configuration and receipt exercises in
issues #26 and #41, and retain the unchecked device and store gates above.
