# Native mobile builds

The mobile apps use Jetpack Compose on Android and SwiftUI on iOS. Both call
the Kotlin Multiplatform simulation in `shared/`. The Tauri project is for
desktop only; `src-tauri/gen/android` is a retired wrapper and is not the
mobile release source.

## What players can do

The Play tab opens a native campaign menu. Players can resume a local save or
start a new campaign from 17 U.S. presidential elections (1960–2024). Setup
includes ticket, running mate, three staff slots, difficulty, historical or
plausible events, 5/9/14 week length, a replayable seed, and the three free
modifiers. The campaign desk, state map, seven day planner, event decisions,
election results, Store, and Account are native views. The planner exposes all
ten actions, state targets, ad mode and spend, issue pivots, and a limit of
three actions per day. All campaign options feed the shared engine; local
saves persist after setup and each game mutation.

The shared engine also contains UK and country systems. The current native
menu exposes the U.S. scenarios because those other game loops do not yet
have native session and screen adapters. Do not list them as playable in the
mobile app until those adapters exist.

## Android verification

Use a JDK 21 and Android SDK with platform 36. Set `ANDROID_HOME` or
`local.properties` to the SDK path, then run `npm run native:verify`. This
runs shared JVM tests and assembles the Compose debug APK at
`androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

On this VPS, queue the full check through `lakeside-check-queue` per the host
instructions. A real device pass is still required for save restore, event
decisions, screen sizes, and Play purchase behavior.

## iOS verification and TestFlight

`codemagic.yaml` has two workflows:

- `ios-verify` compiles the shared Kotlin framework and SwiftUI app for the
  iOS simulator, without signing or publishing.
- `ios-testflight` archives, signs, and uploads a reviewed commit after the
  App ID, distribution profile, and App Store Connect app record exist. It
  checks `MOV_REVIEW_COMMIT` against the exact source SHA.

Apple Developer Program benefits are temporarily disabled during the team's
membership migration (Apple case `102973233199`). The missing MOV App ID,
provisioning profile, and App Store Connect app record block TestFlight.
Codemagic has the other encrypted signing inputs. The owner will resume
portal setup when Apple completes the migration; see `release-checklist.md`
for the exact sequence. No signed MOV IPA or TestFlight build exists yet.

## Release gates

Device runs must cover campaign creation, an action and event, close and
reopen restore, a full result, accessibility and phone layouts, plus purchase,
restore, refund, reinstall, and offline entitlement behavior. Store privacy
declarations must match the actual telemetry and billing setup.
