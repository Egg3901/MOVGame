# Native mobile plan — Kotlin Multiplatform

Decision: mobile clients are fully native, not Tauri wrappers. Desktop stays
Tauri (industry standard for desktop — Slack, Discord, VS Code all use
Electron/Tauri). Mobile uses KMP: shared Kotlin engine, native UI per platform
(Jetpack Compose on Android, SwiftUI on iOS).

## What exists today

- **Engine**: `src/engine/` — 22 source files, 5,711 LOC pure TypeScript. No
  React, no DOM, no browser globals. Deterministic, seedable RNG. The types
  contract is `src/engine/types.ts` (~400 LOC).
- **Content**: `src/content/` — 38 files, 8,393 LOC. Mostly data definitions
  (scenarios, candidates, issues, events, states, countries, packs). The UK
  multiparty system lives alongside the US two-party system.
- **Web UI**: React + Zustand + Canvas map rendering. ~15,000 LOC in `src/ui/`.
- **Store seam**: `src/lib/distribution.ts` — channel-gated entitlement policy.
- **Billing spec**: `docs/billing.md` — adapter contract for Play Billing,
  StoreKit, Steam DLC.
- **Existing Tauri shells** (Android debug APK + Linux desktop verified):
  these stay as reference and for desktop; mobile moves to KMP.

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   shared/ (KMP)                  │
│                                                  │
│  engine/         ← port from src/engine (5.7k)  │
│  content/        ← data: JSON or port (8.4k)    │
│  types/          ← data model contract          │
│  persistence/    ← save/load, local + remote    │
│  network/        ← campaign API client          │
│  entitlements/   ← store-agnostic purchase API   │
└──────────┬──────────────────┬────────────────────┘
           │                  │
  ┌────────▼────────┐ ┌──────▼──────────┐
  │  androidApp/    │ │  iosApp/        │
  │  (Compose)      │ │  (SwiftUI)      │
  │                 │ │                 │
  │  MapScreen      │ │  MapScreen      │
  │  GameScreen     │ │  GameScreen     │
  │  SetupScreen    │ │  SetupScreen    │
  │  ResultsScreen  │ │  ResultsScreen  │
  │  StoreBilling   │ │  StoreBilling   │
  └─────────────────┘ └─────────────────┘
```

## Phases

### Phase 0 — KMP scaffold (1 week)

- Create `MOVGame-kmp/` repo (private, GitHub).
- KMP project with Gradle: `shared/` module (Kotlin/JVM + Kotlin/Native),
  `androidApp/` (Compose), `iosApp/` (SwiftUI stub).
- Shared module builds for both targets. Android app runs with a "Hello
  Margin of Victory" screen. iOS project stub builds on macOS CI.
- CI: GitHub Actions for Android (ubuntu-latest) + iOS (macos-latest).

### Phase 1 — Engine port (2–3 weeks)

Port `src/engine/` from TypeScript to Kotlin. The engine is pure computation
with a clean types contract — the port is mechanical but requires care around
determinism (seeded PRNG must produce identical output for the same seed).

Order:
1. `types.ts` → Kotlin data classes (the contract everything depends on)
2. `rng.ts` → Kotlin (seeded PRNG, must be byte-identical)
3. `system.ts`, `polls.ts`, `voteModel.ts` → core simulation
4. `turn.ts`, `actions.ts`, `events.ts` → turn processing
5. `setup.ts`, `scoring.ts`, `achievements.ts` → game lifecycle
6. `multiparty.ts`, `multipartyPolls.ts`, `multipartyAi.ts` → UK system
7. `ai.ts`, `runoff.ts` → AI planner + runoff voting
8. `countryGame.ts`, `ukGame.ts`, `ukSetup.ts` → country adapters

Validation: port the existing test suite (`src/engine/__tests__/`, 1,751 LOC)
to Kotlin. Every test must pass with the same inputs/outputs as the TS
version. The calibration tests (Biden 306/Trump 232, all 538 EVs, six
battleground states) are the acceptance bar.

### Phase 2 — Content port (1 week)

The content layer is mostly static data. Two options:

**Option A (recommended)**: serialize `src/content/` to JSON files at build
time. The shared Kotlin module loads them at runtime. No line-by-line port
needed, and web/native share the same data source.

**Option B**: port the content files to Kotlin objects. More native but more
surface area to maintain.

Option A means content updates ship as JSON bundles (potentially OTA). Option
B means content is compiled into the binary.

### Phase 3 — Android UI (3–4 weeks)

Jetpack Compose screens:
1. **SetupScreen** — scenario/country/candidate selection
2. **GameScreen** — map (Compose Canvas or Android native map), turn controls,
   issue sliders, event cards
3. **ResultsScreen** — election results, electoral map, historical comparison
4. **StoreScreen** — pack purchases via Google Play Billing
5. **AccountScreen** — Lakeside login, entitlements, cloud saves

The map is the hardest UI piece. Options:
- Compose Canvas drawing (most control, most work)
- SVG rendering via AndroidSVG library
- WebView for the map only (compromise — map is static SVG, not interactive
  game UI — but the user wants fully native, so Compose Canvas is the target)

### Phase 4 — iOS UI (3–4 weeks)

SwiftUI screens mirroring Android. Same structure, platform-native patterns.
Builds via macOS CI (Codemagic or GitHub Actions macos-latest).

The map: SwiftUI `Canvas` or `Shape` paths (equivalent to Compose Canvas).

### Phase 5 — Billing + store submission (1–2 weeks)

- Android: Google Play Billing library, wired to the entitlements module
  from `docs/billing.md`
- iOS: StoreKit 2, same entitlement contract
- Store product setup (Play Console + App Store Connect)
- Receipt validation endpoint on the campaign server
- Test: purchase, refund, restore, offline entitlement refresh

### Phase 6 — Ship (1 week)

- Android: Play Console listing, screenshots, privacy policy, content rating
- iOS: App Store Connect listing, TestFlight beta, review submission
- Monitor: crash reporting (Firebase Crashlytics or Sentry), store reviews

## Timeline

| Phase | What | Duration | Dependencies |
|---|---|---|---|
| 0 | KMP scaffold | 1 week | — |
| 1 | Engine port | 2–3 weeks | Phase 0 |
| 2 | Content port | 1 week | Phase 1 |
| 3 | Android UI | 3–4 weeks | Phase 2 |
| 4 | iOS UI | 3–4 weeks | Phase 2 (parallel with 3) |
| 5 | Billing | 1–2 weeks | Phase 3 or 4 |
| 6 | Ship | 1 week | Phase 5 |

**Total: 11–16 weeks** from start. Android could ship in ~8 weeks (phases 0–3
+ 5). iOS follows ~4 weeks later (phase 4 + 5 overlap).

## What happens to Tauri

- **Desktop**: stays. Tauri is the right tool for desktop. The Linux
  `.deb`/`.rpm`/`.AppImage` and Windows/macOS bundles continue from
  MOVGame-native.
- **Android/iOS Tauri shells**: deprecated. They served as proof that the build
  pipeline works and validated the store submission flow, but the actual mobile
  apps will be KMP + native UI.
- `MOVGame-native` repo pivots: add `shared/` (KMP engine), `androidApp/`
  (Compose), `iosApp/` (SwiftUI). The Tauri `src-tauri/` stays for desktop
  builds only.

## Open questions

1. **Map rendering**: Compose Canvas vs. a game engine (e.g., libGDX via KMP)?
   Canvas is lighter but more manual; libGDX gives you a real scene graph but
   adds a heavy dependency. Recommendation: start with Canvas, escalate only if
   the map interaction surface gets complex.
2. **Content delivery**: JSON bundles (Option A) vs. compiled Kotlin (Option B)?
   Recommendation: JSON — one source of truth, OTA-capable, easier to update.
3. **Shared state management**: Kotlin coroutines + StateFlow (KMP-native) vs.
   a ported Zustand-like store? Recommendation: StateFlow — it's the KMP
   idiom and Compose/SwiftUI both consume it naturally.
4. **macOS runner for iOS CI**: Codemagic (already used for AHDNative) vs.
   GitHub Actions macos-latest? Recommendation: Codemagic — you already have
   it configured and it has better iOS tooling.