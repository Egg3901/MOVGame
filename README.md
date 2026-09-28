# Margin of Victory

Margin of Victory is a single-player, turn-based election campaign simulator with 49
election scenarios across the United States, United Kingdom, Canada, Germany,
France, and Australia. Its scenarios span 1951 to 2027 and model each country's
electoral system, parties, regions, and campaign pressures.

> You are the campaign manager. Read the map, allocate finite resources each
> week, answer the moments that matter, and rewrite the result.

Play in the browser at
[lakesidegames.net/games/electioneer](https://lakesidegames.net/games/electioneer/).
The existing `/games/electioneer/` address and internal `electioneer` product
identifier remain unchanged so existing links, purchases, and saves keep working.

## Quick start

```bash
npm install
npm run dev
npm test
npm run calibrate
npm run build
```

The browser game runs as a React SPA. Local campaigns are stored in IndexedDB.
The optional Node server provides accounts, leaderboards, entitlement checks,
activation codes, and cloud-facing platform integration.

## Architecture

The simulation core is kept separate from the interface. `src/engine` is pure
TypeScript with no React, DOM, or browser dependencies, so it can run in the
browser, in tests, or headless for calibration. All clients live in this repo:
`apps/native` contains the Kotlin Multiplatform engine, Android Compose app,
iOS SwiftUI app, and Tauri desktop shell. The mobile engine is a Kotlin port
with parity tests; desktop builds use the web source from the same checkout.
See [native setup](apps/native/README.md).

```bash
npm ci --prefix apps/native
npm run native:verify       # shared JVM tests and Android APK
npm run native:content      # regenerate native content from web source
npm run desktop:build      # Tauri shell using this checkout's web game
```

GitHub Actions checks web, Android, and iOS independently. The manual
`iOS TestFlight` workflow signs and uploads a reviewed `main` commit.
The former MOVGame-native repository is retained for historical links; new
native work belongs here.

```text
src/
  engine/        Deterministic, seedable simulation and country adapters
  content/       Elections, candidates, parties, events, regions, and maps
  persistence/   Local and remote save providers
  store/         Zustand state, autosave, replay, and entitlement state
  ui/            React game, setup, guide, leaderboard, and results screens
apps/native/     KMP, Compose, SwiftUI, and Tauri clients
server/          Accounts, scores, identity linking, and entitlements
```

The United States engine models state polling, demographic blocs, electoral
votes, campaign actions, events, debates, and an AI opponent. Country adapters
extend the same campaign loop to parliamentary, proportional, preferential,
and runoff systems. Every game is seedable, which supports reproducible tests,
daily challenges, replay, and balance simulations.

## Content and calibration

The scenario registry is the source of truth for the current 49 elections.
Historical scenarios use authored candidates, starting conditions, maps, and
events. Calibration suites check that neutral play remains close to historical
baselines while still allowing campaign decisions to change the outcome.

## Product identity

The player-facing name is Margin of Victory. Compatibility-sensitive identifiers such
as the package name, desktop application identifier, entitlement game key,
database records, backup paths, and checkout query value remain `electioneer`.
Do not rename those without a coordinated data and deployment migration.

## License

[PolyForm Noncommercial 1.0.0](./LICENSE.md). The source is available to read,
learn from, modify, and run noncommercially. Commercial use, including selling
builds or hosting the game as a paid service, is not licensed.
