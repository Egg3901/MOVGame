# KMP implementation status

The branch now contains a Kotlin simulation, exported content bundles, Compose Android UI, SwiftUI iOS UI, Play Billing adapter, StoreKit adapter, and build workflows. The initial Phase 0 scaffold is complete. See `docs/native-plan.md` for the intended architecture and `docs/release-checklist.md` for remaining release gates.

On Linux, run `npm run native:verify` for shared JVM tests and the Android debug APK. On macOS, build the `MOVGameiOS` Xcode scheme to verify SwiftUI and the KMP framework link. Store release requires device and store sandbox evidence.
