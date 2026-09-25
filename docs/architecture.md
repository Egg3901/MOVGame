# Build contract — MOVGame-native

## The two repos

`MOVGame` (web) is the source of truth for the simulation, the interface, the
content data and the campaign server. `MOVGame-native` (this repo) owns the
native shells, the generated platform projects, store billing and release
packaging. There is one engine and one UI; the native clients are that same
bundle compiled for a storefront and wrapped in a Tauri v2 shell.

```
MOVGame (web)                        MOVGame-native (this repo)
├── src/engine/*        ──pinned──▶  src-tauri/            Tauri v2 shell
├── src/ui/*            ──build───▶  src-tauri/gen/android  Android project
├── src/content/*                    src-tauri/icons/       store icon sets
├── server/*                         docs/                  port plan + gates
└── .env.<channel>                   web.pin                pinned MOVGame commit
```

## Pin, don't fork

`web.pin` holds the MOVGame commit every native build compiles against. Bumping
it is a reviewed change, and the review must confirm the web calibration suite
still passes (`npm run calibrate` in MOVGame) before a native artifact is cut.
Never copy engine or UI source into this repo: divergence between the two repos
would silently invalidate the calibration invariants the whole game rests on.

## Per-channel compilation

The bundle differs per storefront only by Vite mode, which sets
`VITE_DISTRIBUTION_CHANNEL`. The mode decides whether external Lakeside
checkout links exist at all:

| Channel | Mode | Tauri config | Checkout |
| --- | --- | --- | --- |
| Web | (default) | — | Lakeside web checkout |
| Direct desktop | `desktop-direct` | `tauri.conf.json` | Lakeside checkout |
| Steam | `steam` | `tauri.steam.conf.json` | Steam DLC only |
| Android | `android` | `tauri.android.conf.json` | Play Billing only |
| iOS | `ios` | (to add) | StoreKit only |

`beforeBuildCommand` in each Tauri config builds the fetched web copy with the
right mode, so a native build can never accidentally ship the web checkout.

## Path-prefix hazard (web deployment)

On the web, MOVGame is mounted by Caddy under `/games/electioneer/*` and served
from Cloudflare Pages, not from a domain root. Absolute asset or API URLs break
the moment a build assumes `/` — this caused a real black-screen incident (fixed
by host-rooted `apiBase` plus a Caddy `/api` guard). Native builds have no path
prefix, so any code that hardcodes the mount or the host must stay behind the
distribution-channel check.

## Identifiers that must not churn

- Package identifier: `com.lakesidegames.electioneer`
- Public web path: `/games/electioneer/`
- Product ID / entitlement keys: `electioneer`
- Public product name: **Margin of Victory**

Renaming the repository does not rename the product. Users, stores and existing
entitlements all key off `electioneer`, so it stays.
