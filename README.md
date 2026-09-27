# Margin of Victory native clients

Android uses Kotlin Multiplatform for the simulation and Jetpack Compose for the interface. iOS uses the same KMP simulation with a SwiftUI interface. Neither mobile app runs the web UI or a Tauri wrapper. Desktop remains a Tauri shell around the [MOVGame](https://github.com/Egg3901/MOVGame) web client.

The store-visible package identifier remains `com.lakesidegames.electioneer`. The web game lives at `/games/electioneer/`.

## Project layout

| Path | Purpose |
| --- | --- |
| `shared/` | KMP simulation, content bundles, and entitlement policy |
| `androidApp/` | Compose screens and Play Billing adapter |
| `iosApp/` | SwiftUI screens and StoreKit adapter |
| `src-tauri/` | Desktop shell only |
| `web.pin` | Pinned MOVGame revision for desktop and exported native content |

The Kotlin engine is a port of MOVGame's TypeScript engine. Both implementations have cross-checked tests for deterministic behavior. Content bundles are exported from the pinned web revision. Any engine or content change must be compared against that revision and its calibration suite.

## Verify Android on Linux

```sh
npm run native:verify
```

This runs shared JVM tests and builds the Compose debug APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Use `./gradlew :androidApp:installDebug` to install on a connected device. Android builds need JDK 21 and Android SDK platform 36.

The iOS app requires Xcode on macOS. Build the `MOVGameiOS` scheme in `iosApp/MOVGameiOS.xcodeproj`. The Codemagic workflow in `codemagic.yaml` is the TestFlight route; signing credentials and App Store Connect setup are required.

Desktop builds use `npm run desktop:build` or `npm run steam:build`. The desktop scripts fetch MOVGame at `web.pin`.

## Release status

The Android and iOS native screens and billing adapters are implemented on the KMP branch. Local Android tests and builds are repeatable. iOS compilation, device testing, store products, purchase and refund exercises, privacy declarations, and signed release artifacts still need evidence before a store release. The SKU table is empty, so the apps currently sell no packs. See [release checklist](docs/release-checklist.md) for each gate.

The old Tauri Android and iOS configs remain as historical build artifacts; they are not mobile release targets.
