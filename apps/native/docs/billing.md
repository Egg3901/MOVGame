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
- The current open beta makes the complete catalog playable for free on web,
  iOS and Android. The paid model below is planned release behavior. When paid
  gating is enabled, two starter scenarios and the daily challenge stay free.
- Both native SKU tables are empty. App Store Connect has no MOV IAP products
  as of 2026-10-01. Play Console inventory is unverified. The app cannot sell
  packs until actual products, mappings and validation are configured.

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

## Store purchases and existing account access

Keep two decisions separate: selling digital packs inside a native app and
recognizing content already bought on another platform.

- Native digital sales normally use StoreKit on iOS and Play Billing on Play,
  subject to documented storefront/program exceptions. The existing
  `externalStore: false` policy hides Lakeside checkout inside native builds.
- [Apple guideline 3.1.3(b)](https://developer.apple.com/app-store/review/guidelines/#multiplatform-services)
  allows access to previously acquired multiplatform content when the same
  items are also available as in-app purchases in the app. The old blanket
  prohibition on recognizing web-bought packs was incorrect.
- [Google's Payments FAQ](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en)
  explicitly allows consumption-only access to content bought elsewhere.
  Consumption-only apps offer no purchases inside the app. Apps offering
  native digital sales follow the separate Play Billing requirements.
- Existing-account access, receipt bridging, activation-code UI and regional
  purchase links need explicit release decisions. Store-scoped ownership is
  an architectural option, not a universal prohibition on shared ownership.

The current beta stays free. Future in-app pack sales require configured
store products and actual purchase/refund/restore verification. Desktop-direct
and web retain their Lakeside policy; Steam paid DLC needs partner setup.

## Server-side bridge (open design question for the owner)

A store purchase currently has no path into the Lakeside entitlement record, so
a mobile purchase would be device-scoped. Two options:

1. **Store-scoped (simplest, launch-safe).** The adapter keeps store receipts and
   a signed local cache; the platform knows nothing. Cross-platform purchase
   recognition does not happen.
2. **Receipt bridge.** Add a platform endpoint that accepts a validated store
   receipt and mints a Lakeside entitlement. Needed if a Play purchase should
   ever appear on the web. Recognition of existing web purchases follows the
   platform and product decisions above; it is not universally prohibited.

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
