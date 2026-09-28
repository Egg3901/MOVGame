# Margin of Victory native clients

Android uses Kotlin Multiplatform for the simulation and Jetpack Compose for the interface. iOS uses the same KMP simulation with a SwiftUI interface. Neither mobile app runs the web UI or a Tauri wrapper. Desktop remains a Tauri shell around the [MOVGame](https://github.com/Egg3901/MOVGame) web client.

The store-visible package identifier remains `com.lakesidegames.electioneer`. The web game lives at `/games/electioneer/`. All clients are maintained in this
repository: web at the root, native clients in `apps/native`.

## Project layout

| Path | Purpose |
| --- | --- |
| `shared/` | KMP simulation, content bundles, and entitlement policy |
| `androidApp/` | Compose screens and Play Billing adapter |
| `iosApp/` | SwiftUI screens and StoreKit adapter |
| `src-tauri/` | Desktop shell only |
| `../../src/` | Web engine and content from the same checkout |

The Kotlin engine is a port of MOVGame's TypeScript engine. Both implementations have cross-checked tests for deterministic behavior. Content bundles are exported from the web source in this checkout. Any engine or content change must be compared against the web source in this checkout and its calibration suite.

## Verify Android on Linux

```sh
npm run native:verify
```

This runs shared JVM tests and builds the Compose debug APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Use `./gradlew :androidApp:installDebug` to install on a connected device. Android builds need JDK 21 and Android SDK platform 36.

The iOS app requires Xcode on macOS. Build the `MOVGameiOS` scheme in `iosApp/MOVGameiOS.xcodeproj`. GitHub Actions compiles the simulator app without signing. The manual iOS TestFlight workflow signs and uploads an exact reviewed main commit using Actions secrets.

Desktop builds use `npm run desktop:build` or `npm run steam:build`. The desktop scripts build the web source from this same checkout. Install dependencies at both the repository root and `apps/native`.

## Release status

The Android and iOS apps have native campaign menus, setup for all 17 U.S. scenarios, gameplay, results, and billing adapters. Local Android tests and APK builds pass; Codemagic compiles the iOS simulator app. Device testing, store products, purchase and refund exercises, privacy declarations, and signed release artifacts still need evidence before a store release. The SKU table is empty, so the apps currently sell no packs. See [release checklist](docs/release-checklist.md) for each gate.

The old Tauri Android and iOS configs remain as historical build artifacts; they are not mobile release targets.
