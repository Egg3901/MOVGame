# Native port plan — Margin of Victory

Owner: MOV product lead. Status as of 2026-09-25.

Two repos: `MOVGame` (web, renamed from `ahd-sim`) and `MOVGame-native` (this
repo). One engine, one UI, four storefronts. The port is **not** a rewrite: the
simulation is pure TypeScript and the interface is the same React app, so the
work is shells, platform billing, packaging and store compliance.

## Phase 0 — Repo structure ✅

- `ahd-sim` renamed to `MOVGame` on GitHub (redirects active, default branch `main`).
- `MOVGame-native` created and seeded with the `src-tauri/` shell extracted from
  `MOVGame@c1a08c80`, the icon set, and the generated Android project.
- `web.pin` records the exact MOVGame commit this repo builds against.
- Follow-up (not done yet): remove `src-tauri/` and the `tauri:*` scripts from
  MOVGame so the web repo only publishes the web app.

## Phase 1 — Host path migration

Rename `/root/projects/ahd-sim` → `/root/projects/MOVGame` so the directory
matches the repo, per this box's AGENTS.md convention. This is a two-part job,
not a `mv`:

1. `grep -rl "/root/projects/ahd-sim" /etc/systemd/system /root/bin` and update
   every hit (`ahd-sim-campaign`, `ahd-sim-worker`, `ahd-sim-worker-extra`,
   `ahd-sim-sandbox-mongo`, the `lakeside-worldsim-*` collectors,
   `reindex-satellite-games.sh`, `gen-projects-manifest.js`, `auto-deploy.sh`),
   then `daemon-reload` and verify each service.
2. Update `projects.manifest.json`, the ops-dashboard project finder, Hub
   references and the local git remote URL.
3. Run `node /root/bin/worktree-status.js` before and after; worktree admin
   files bake in absolute paths.

Blocker: the local checkout holds another session's uncommitted work (7 modified
files, plus untracked `docs/plans/`, `docs/systems/`). Commit or discard first —
do not reset.

## Phase 2 — Android (only platform buildable on this Linux host)

- Install Rust toolchain, Android NDK, Rust Android targets; set `ANDROID_HOME`
  and `NDK_HOME`.
- Produce a first `tauri:android:build` artifact and report the APK/AAB path —
  this is the first real evidence that the shell works.
- Implement Play Billing: non-consumable packs, purchase, restore, offline
  entitlement refresh. No external checkout in the Android build.
- Internal testing track submission once gates 1–5 pass.

## Phase 3 — Desktop

- Linux bundle on this host (needs the Tauri Linux dependencies, webkit2gtk 4.1).
- Windows and macOS bundles require Windows/macOS runners: enable CI in this
  repo (GitHub Actions for Windows, Codemagic or a macOS runner for macOS).
- Direct desktop (Lakeside checkout) and Steam (free client, DLC packs) are two
  separate channels built from two Vite modes. Steam DLC ownership must be wired
  before the Steam client ships.

## Phase 4 — iOS

- Requires macOS with Xcode. The studio already runs Codemagic for AHDNative, so
  route iOS generation, signing and TestFlight through that pipeline.
- `tauri:ios:init` on the macOS host, then StoreKit non-consumable packs with
  restore, then TestFlight before any public release.

## Phase 5 — Platform entitlements

One-time packs across three billing systems (Play IAP, StoreKit, Steam DLC) on
top of the existing Lakeside entitlement records. Requirements: restore paths on
every store, refund handling, reinstall behaviour, and a signed-in player using
an existing entitlement on another platform only where store policy permits.

## Phase 6 — Parity and QA gates

Run the release gates in `docs/mobile.md` as acceptance criteria, with the
evidence attached to the Hub work item for each platform: 390 × 844 phone
layouts, crash-free sessions, privacy declarations matching telemetry, and no
external checkout in mobile builds.

## Sequencing rule

Android first (only platform this host can build end-to-end), then desktop Linux
as the second proof that the shell is real, then the CI-dependent platforms
(Windows, macOS, Steam), then iOS via macOS CI. Billing adapters are written
once per platform after that platform's shell launches.
