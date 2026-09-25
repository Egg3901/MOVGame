# Mobile builds

Android and iOS are Tauri 2 targets of the same shell as desktop; the
simulation and interface stay shared with the web edition fetched at `web.pin`.

## Android

The generated Android Studio project is at `src-tauri/gen/android`, package
`com.lakesidegames.electioneer`, launcher label "Margin of Victory". It builds
on Linux. Bootstrap the SDK/NDK without root:

```text
./scripts/bootstrap-android-sdk.sh      # command-line tools, platform 35, build-tools 35, NDK
export ANDROID_HOME=/root/Android/Sdk
export NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018

npm install
npm run android:build                   # mode android
```

The Android build uses Vite mode `android`, which disables every external
Lakeside store link and checkout action. Google Play Billing and purchase
restoration are **not implemented**; the free base app may be tested, but pack
sales must not launch until that adapter is complete and tested against Play
Console products.

## iOS

The shared Rust and web code supports Tauri's iOS target, but generating,
building, signing and testing the Xcode project requires macOS with Xcode.
`tauri.ios.conf.json` already sets the `ios` mode:

```text
npm run ios:init                        # on the macOS host, generates the Xcode project
npm run ios:build                       # mode ios
```

StoreKit purchase and restoration are **not implemented**. They must be tested
through StoreKit Testing and TestFlight before release. Route this through CI
(Codemagic runs the AHDNative iOS pipeline today) rather than expecting it to
work on the Linux host.

## Release gates

No store submission is complete until every gate passes with evidence:

- No external web checkout appears in Android or iOS builds.
- Every non-consumable purchase has a working Restore Purchases path.
- A purchase, refund, reinstall and offline entitlement refresh are tested.
- Phone layouts pass at 390 × 844 CSS pixels without horizontal overflow.
- App Store and Play Store privacy declarations match actual telemetry.
