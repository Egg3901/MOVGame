# MOVGame-native

Native clients for **Margin of Victory** — one simulation, one interface, four
storefronts. Tauri v2 shells for desktop (Windows, macOS, Linux), iOS, and
Android, plus the platform billing adapters and release packaging the web
edition does not need.

Web edition and shared source: **[MOVGame](https://github.com/Egg3901/MOVGame)**
(renamed from `ahd-sim`, 2026-09-25). The `electioneer` slug is deliberately
retained everywhere it is user- or store-visible: public URLs, package
identifiers (`com.lakesidegames.electioneer`), product IDs, database keys and
existing entitlements.

## Repo split

| | MOVGame (web) | MOVGame-native (this repo) |
| --- | --- | --- |
| Owns | engine, UI, content, campaign server | `src-tauri/` shells, `gen/` platform projects, store billing, release CI |
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

- `src-tauri/` Tauri v2 project with `tauri.conf.json`, `tauri.android.conf.json`,
  `tauri.ios.conf.json` and `tauri.steam.conf.json`, `Cargo.toml` / `Cargo.lock`,
  `build.rs`, `src/main.rs`, `src/lib.rs`. Each channel config builds the web
  bundle through `./scripts/fetch-web.sh <channel>`, so the pinned web commit is
  the only source of the frontend.
- Icon set present under `src-tauri/icons/` including `icon.icns` and
  `icon.ico`.
- Generated Android Studio project under `src-tauri/gen/android`, package
  `com.lakesidegames.electioneer`, Gradle wrapper included.
- Web repo carries the matching `tauri:*` scripts and four `.env.<channel>`
  profiles.

Unverified / not done:

- **No Tauri build has ever been produced on this host.** `docs/desktop.md` in
  MOVGame states no Rust toolchain was installed and no binary was built; that
  is still true. Every "it will build" claim is unverified until someone runs it
  and reports the artifact.
- Android has never been built here (needs Android SDK + NDK + Rust targets).
- iOS project does not exist yet: `tauri:ios:init` requires macOS with Xcode.
- Play Billing and StoreKit adapters are not implemented, so store pack sales
  must not launch.
- No release CI exists in either repo yet.

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
