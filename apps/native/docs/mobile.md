# Native mobile builds

The mobile apps use Jetpack Compose on Android and SwiftUI on iOS. Both call
the Kotlin Multiplatform simulation in `shared/`. The Tauri project is for
desktop only; `src-tauri/gen/android` is a retired wrapper and is not the
mobile release source.

## What players can do

The native library exposes all 49 elections across the United States, United
Kingdom, Canada, Germany, France and Australia. Each campaign uses its own
engine, parties, leaders, geography, action rules and result system. Setup
includes difficulty, a replayable seed and the engine's campaign options.
Players can plan actions, target regions, choose event and debate responses,
inspect polls and resources, undo where allowed, watch election night, and
review results and campaign replays.

Both platforms include daily assignments and rankings, Lakeside account
sign-in, champions, help, onboarding, audio and keyboard settings. Named saves
support JSON import/export and resume after app termination. U.S. saves can
sync to the campaign service with account ownership, retry and conflict
handling; world campaigns remain local, matching the web. The scenario editor
supports all six engines and stores custom rules inside each campaign's save.
Custom campaigns do not submit competitive scores or earn achievements.

The current beta catalog is free. Paid store products are not configured;
see [billing.md](billing.md). Physical-device and store exercises remain open
release gates in [release-checklist.md](release-checklist.md).

## Android verification

Use a JDK 21 and Android SDK with platform 36. Set `ANDROID_HOME` or
`local.properties` to the SDK path, then run `npm run native:verify`. This
runs shared JVM tests and assembles the Compose debug APK at
`androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

The Actions workflow also checks the packaged Android startup manifest and
runs actual emulator screen, accessibility, keyboard and restart flows. Small
phone and tablet captures use larger text. Crash reporting is initialized by
`MovApp` only when a build supplies `MOV_SENTRY_DSN`; Sentry's automatic
provider initialization stays disabled so an unconfigured build can start.

On this VPS, queue the full check through `lakeside-check-queue` per the host
instructions. A real device pass is still required for save restore, event
decisions, screen sizes, and Play purchase behavior.

## iOS verification and TestFlight

The root `.github/workflows/native.yml` compiles the shared Kotlin framework
and SwiftUI app on macOS without signing, verifies bundled content, and captures
native flows on iOS 26 and iOS 27. It also reuses the compiled app for iPhone SE
and iPad captures with larger text. `.github/workflows/ios-testflight.yml`
archives, signs, and uploads an exact reviewed main commit using Actions
secrets. The Apple organization migration is complete. The App ID is
`com.lakesidegames.electioneer`; the App Store distribution profile is
`MOV App Store`. Upload and physical-device results must still be verified.

## Release gates

Device runs must cover campaign creation, an action and event, close and
reopen restore, a full result, accessibility and phone layouts, plus purchase,
restore, refund, reinstall, and offline entitlement behavior. Store privacy
declarations must match the actual telemetry and billing setup.
