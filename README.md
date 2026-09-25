# MOVGame-native

Native clients for **Margin of Victory** — one simulation, two native mobile
platforms, one desktop shell. Mobile uses Kotlin Multiplatform (shared engine)
with Jetpack Compose (Android) and SwiftUI (iOS). Desktop stays Tauri v2.

The Tauri Android/iOS shells that existed briefly in this repo served as proof
that the build pipeline works. They are deprecated — the actual mobile apps are
fully native.

Web edition and shared source: **[MOVGame](https://github.com/Egg3901/MOVGame)**
(renamed from `ahd-sim`, 2026-09-25). The `electioneer` slug is deliberately
retained everywhere it is user- or store-visible: public URLs, package
identifiers (`com.lakesidegames.electioneer`), product IDs, database keys and
existing entitlements.

## Architecture

| Platform | Technology | Status |
| --- | --- | --- |
| Android | KMP engine + Jetpack Compose UI | Planned (Phase 0) |
| iOS | KMP engine + SwiftUI UI | Planned (Phase 4) |
| Linux/Windows/macOS | Tauri v2 shell (webview) | ✅ Verified |

## Repo split

| | MOVGame (web) | MOVGame-native (this repo) |
| --- | --- | --- |
| Owns | web UI, web content, campaign server | shared KMP engine, native UIs, Tauri desktop shell, store billing, release CI |
| Ships | free web play, web pack purchases | free native client, store IAP / DLC packs |
| Publishes | Cloudflare Pages on `main` merge | store submissions and per-platform artifacts |

**Engine rule.** `MOVGame/src/engine` is pure, deterministic, seedable
TypeScript with zero DOM/React dependency, and it is the single source of truth
for simulation behaviour. Nothing in this repo may fork, vendor or re-implement
it. Native builds consume MOVGame at a pinned commit (`web.pin`), so bumping the
pin is an explicit reviewed change that must be followed by a native rebuild
plus the web calibration suite (`npm run calibrate` in MOVGame).

## Distribution channels

The web bundle is compiled per storefront with a Vite mode that selects a
distribution channel. The mode files live in MOVGame and are consumed by the
Tauri configs here:

| Channel | Vite mode | Tauri config | Behaviour |
| --- | --- | --- | --- |
| Android | `--mode android` | `tauri.android.conf.json` | external Lakeside checkout disabled; Play Billing required |
| iOS | `--mode ios` | `tauri.ios.conf.json` | external checkout disabled; StoreKit required |
| Direct desktop | `--mode desktop-direct` | `tauri.conf.json` | Lakeside checkout allowed |
| Steam | `--mode steam` | `tauri.steam.conf.json` | never shows Lakeside checkout; packs sold as Steam DLC |

## Status (verified vs unverified)

Verified in the source tree:

- **Desktop (Linux)**: a verified release build produced `.deb`, `.rpm` and
  `.AppImage` packages from this shell before the repo split. The shell works;
  see `docs/desktop.md`, and re-verify from this repo before shipping.
- `src-tauri/` Tauri v2 project with `tauri.conf.json`, `tauri.android.conf.json`,
  `tauri.ios.conf.json` and `tauri.steam.conf.json`, `Cargo.toml` / `Cargo.lock`,
  `build.rs`, `src/main.rs`, `src/lib.rs`. Each channel config builds the web
  bundle through `./scripts/fetch-web.sh <channel>`, so the pinned web commit is
  the only source of the frontend.
- Icon set present under `src-tauri/icons/` including `icon.icns` and
  `icon.ico`.
- Generated Android Studio project under `src-tauri/gen/android`, package
  `com.lakesidegames.electioneer`, Gradle wrapper included.
- Web repo carries the matching four `.env.<channel>` distribution profiles.

Unverified / not done:

- Android has never been built: no APK or AAB exists. The SDK and NDK were
  absent from the ops host until `scripts/bootstrap-android-sdk.sh` was added.
- iOS project does not exist yet: `tauri:ios:init` requires macOS with Xcode.
- Play Billing and StoreKit adapters are not implemented, so store pack sales
  must not launch.
- Windows and macOS desktop bundles are unbuilt (and `cargo-xwin` cross-build is
  unproven).
- No release CI exists in either repo yet.

Platform notes: `docs/desktop.md` and `docs/mobile.md`.

## Release gates (from MOVGame `docs/mobile.md`)

No store submission is complete until every gate passes with evidence:

1. No external web checkout appears in Android or iOS builds.
2. Every non-consumable purchase has a working Restore Purchases path.
3. Purchase, refund, reinstall and offline entitlement refresh are each tested.
4. Phone layouts pass at 390 × 844 CSS pixels with no horizontal overflow.
5. App Store and Play Store privacy declarations match actual telemetry.
6. Steam builds never present the Lakeside checkout and DLC ownership is wired.

## Building

Native builds fetch the web edition at the pinned commit rather than vendoring
it, and reuse the build scripts that already exist in MOVGame
(`tauri:android:build`, `tauri:steam:build`, `tauri:ios:build`):

```bash
./scripts/fetch-web.sh android        # clone MOVGame at web.pin, build the android bundle into ./dist
cd web && npm run tauri:android:build # shell that bundle (needs the Rust + Android toolchain)
```

Nothing has been built this way yet: the Rust toolchain and Android NDK are not
installed on the ops host, and getting the first Android artifact out is Phase 2
in `docs/port-plan.md`.

See `docs/port-plan.md` for the phase order and `docs/architecture.md` for the
build contract.
