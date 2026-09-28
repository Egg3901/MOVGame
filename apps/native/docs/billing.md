# Store billing adapters

The native clients can be built and shipped today, but they cannot *sell*
anything: the store channels deliberately disable the Lakeside checkout and
nothing has replaced it. This is the contract for the three adapters that close
that gap, and it is the gating item for pack sales on every native platform.

## The seam that already exists

`MOVGame/src/lib/distribution.ts`:

| Channel | externalStore | nativeStoreName |
| --- | --- | --- |
| web | true | — |
| desktop-direct | true | — |
| steam | false | Steam |
| ios | false | App Store |
| android | false | Google Play |

`externalStore: false` is why native builds render no Lakeside checkout. Each
native channel needs one adapter behind this policy — the rest of the game keeps
reading `unlocked: { scenarioIds, packIds }` and does not care where it came
from.

## How entitlements work today

- The game is a **consumer** of the Lakeside platform. `server/entitlements.ts`
  calls `GET /account/api/entitlements` for the signed-in identity, caches the
  result for 30 s and **fails soft to an empty list** — play is never blocked by
  the platform being unreachable or by a missing token.
- Activation codes are redeemed on the platform (`POST /account/api/redeem-code`)
  and are OR'd on top of platform purchases (`server/activation.ts`).
- The client holds the result in `src/store/authStore.ts` (`unlocked`), and pack
  → scenario gating lives in `src/content/scenarioRegistry.ts`.
- Two starter scenarios and the daily challenge are free. Anything else is a
  paid pack.

## Adapter surface (identical on all three stores)

```
listProducts()   -> [{ storeSku, packId, price, currency }]   mapped from one table in MOVGame
purchase(packId) -> transaction result
restore()        -> re-deliver every non-consumable the account owns
entitlements()   -> { packIds } from store receipts with a local, signed cache
```

Store specifics:

- **Google Play (android)** — non-consumable products; restore via
  `queryPurchasesAsync`; purchases must be acknowledged or Google auto-refunds
  them; real-time developer notifications (RTDN) carry refund/revocation.
- **StoreKit (ios)** — non-consumable IAPs; restore via
  `Transaction.currentEntitlements`; App Store Server Notifications carry refunds
  and revocations; must be exercised through StoreKit Testing and TestFlight.
- **Steam (desktop)** — the client is free and packs are DLC; ownership must be
  read from Steamworks, and **the Steamworks partner setup and DLC SKUs do not
  exist yet**, so Steam pack sales cannot launch until they do.

## Store policy constraint (decides the architecture)

Apple and Google require their own billing for digital content consumed in the
app. So on ios/android, packs must be sold through the store, and a pack bought
on the web must **not** unlock on mobile — that is why `externalStore: false` is
correct and must stay. The pricing doc already states the same rule: a signed-in
player may use an existing Lakeside entitlement on another platform only where
store policy permits and the equivalent content is available through that
platform's purchase system.

Consequence: the launch-safe position is
- web + desktop-direct: Lakeside checkout, as today;
- ios + android: store-only pack sales, store-scoped entitlements;
- steam: DLC-only, after Steamworks DLC exists.

## Server-side bridge (open design question for the owner)

A store purchase currently has no path into the Lakeside entitlement record, so
a mobile purchase would be device-scoped. Two options:

1. **Store-scoped (simplest, launch-safe).** The adapter keeps store receipts and
   a signed local cache; the platform knows nothing. Cross-platform purchase
   recognition does not happen.
2. **Receipt bridge.** Add a platform endpoint that accepts a validated store
   receipt and mints a Lakeside entitlement. Needed if a Play purchase should
   ever appear on the web — but note store policy still forbids the reverse
   direction on mobile.

Recommendation: ship (1) for the first store submission, then decide on (2) once
Play sales exist. Do not block the Android launch on the bridge.

## Offline and refund behaviour (release gates)

- Cache the last known entitlement set with a signed timestamp; owned packs stay
  playable offline for a grace period; the free app is never blocked.
- A refund or revocation drops the pack on the next check and must not be
  re-granted from a stale cache.
- Test: purchase, refund, reinstall, offline refresh — the sequence in
  `docs/mobile.md`, on a real device or a store sandbox.

## Pack → SKU mapping

One table, exported from MOVGame, mapping `packId` → per-store SKU, so the three
stores cannot drift. Add the SKU column when the first store product is created;
until then the adapter has nothing to sell.

## Order of work

1. Play Billing on Android — the only store the ops host can build and test end
   to end (SDK 35, NDK 27.2 installed).
2. StoreKit on iOS — after the macOS/Xcode pipeline decision.
3. Steam DLC — last: it needs Steamworks partner setup, not just code.
