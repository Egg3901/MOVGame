> Historical planning document. Current source and builds live in MOVGame under `apps/native`; follow README.md and mobile.md.

# MOV Native Port — Handoff Prompt

> Historical Phase 0 prompt. The scaffold and engine port are complete. For
> current mobile architecture and release status, use `docs/mobile.md` and
> `docs/release-checklist.md`. The task restrictions below applied only to the
> original Phase 0 assignment.

Use with `/goal` or as a standing agent instruction.

---

Read `MOVGame-native/docs/native-plan.md` in full before doing anything else.
That is the authoritative plan. The repos are `egg3901/MOVGame` (web edition,
private) and `egg3901/MOVGame-native` (native clients, private). The local
paths are `/root/projects/ahd-sim` (MOVGame — note the old directory name, the
rename to MOVGame is pending a dirty checkout being cleaned) and
`/root/projects/MOVGame-native`.

Your job is to execute Phase 0 of the native plan: scaffold the KMP project.
Set up a worktree under `/root/projects/MOVGame-native/worktrees/kmp-scaffold`
on a branch called `feat/kmp-scaffold`. As you work, file GitHub issues on
`egg3901/MOVGame-native` for every discrete task you discover — do NOT fix them
directly. Be incredibly thorough and meticulous. Every issue should have a clear
title, a description of what needs to happen, acceptance criteria, and which
phase of the plan it belongs to.

Key context:

- **Engine to port**: 22 source files, 5,711 LOC pure TypeScript in
  `src/engine/` of the MOVGame repo. The types contract is `src/engine/types.ts`.
  No React, no DOM, no browser globals. Deterministic seeded RNG. The
  calibration bar (Biden 306/Trump 232, all 538 EVs, six battleground states)
  is the acceptance gate for the engine port.

- **Content layer**: 38 files, 8,393 LOC in `src/content/`. Mostly static data
  (scenarios, candidates, issues, events, states, countries). Plan is to
  serialize to JSON rather than port line-by-line.

- **Desktop**: Tauri v2 stays for Linux/Windows/macOS. The Linux build is
  verified (.deb/.rpm/.AppImage). Do not touch the Tauri shell.

- **Billing spec**: `docs/billing.md` — the adapter contract for Play Billing,
  StoreKit, and Steam DLC. Read it but do not implement yet (Phase 5).

- **What's already done**: repo structure (MOVGame web + MOVGame-native),
  Tauri Android debug APK verified, Linux desktop bundles verified, billing
  adapter spec written, platform docs (desktop.md, mobile.md), build scripts
  (fetch-web.sh, bootstrap-android-sdk.sh, build-android.sh), PR #16 merged
  (native shell removed from web repo), web.pin bumped to post-split HEAD.

- **Git identity**: user.email `rainfordmason@gmail.com`, user.name `Mason
  Rainford`. Use `-c core.hooksPath=/dev/null` for commits.

- **Shared heavy checks**: use `/root/bin/lakeside-check-queue enqueue` for
  typecheck/test/build runs. The scheduler serializes heavy work.

- **Hub**: use `hub_work_report` for starts, meaningful progress, blockers,
  and completion evidence. MOV is product #1 in Lakeside Hub.

- **Do not**: merge to main without explicit approval. Do not modify the web
  repo (MOVGame). Do not touch the Tauri desktop shell. Do not start Phase 1
  (engine port) — only Phase 0 (scaffold).

Phase 0 deliverables:
1. KMP project structure in the worktree: `shared/` module (Kotlin/JVM +
   Kotlin/Native), `androidApp/` (Compose stub), `iosApp/` (SwiftUI stub).
2. Shared module builds for both targets. Android app runs with a placeholder
   screen. iOS project stub exists (build verification requires macOS CI).
3. GitHub Actions CI: Android build on ubuntu-latest, iOS build on macos-latest.
4. A `shared/src/commonMain/` directory ready to receive the engine port,
   with the types contract stubbed as Kotlin data classes matching
   `src/engine/types.ts`.
5. All work filed as GitHub issues on `egg3901/MOVGame-native` with clear
   titles, descriptions, acceptance criteria, and phase tags.
