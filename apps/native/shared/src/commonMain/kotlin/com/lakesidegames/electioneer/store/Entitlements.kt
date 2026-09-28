package com.lakesidegames.electioneer.store

// Pure entitlement policy shared by the Play and StoreKit adapters
// (Phase 5, #8/#23). Platform code verifies receipts with the store;
// this decides what is playable from verified purchases plus the signed
// local cache. No clock, no I/O: timestamps arrive as parameters.

// Offline grace: owned packs stay playable this long after the last
// successful store check. The free game is never blocked.
const val ENTITLEMENT_GRACE_DAYS = 7L
const val DAY_MILLIS = 86_400_000L

// One verified non-consumable purchase. purchaseToken/skus are opaque to
// shared code; the platform adapter fills them (Play purchase token,
// StoreKit transaction id).
data class VerifiedPurchase(
    val packId: String,
    val verifiedAtMillis: Long,
)

// One cached entitlement: the platform adapter stores the store-signed
// receipt (Play purchase data + signature, StoreKit JWS) and verifies it
// before calling activePacks.
data class CachedEntitlement(
    val packId: String,
    // Millis when the store last confirmed ownership.
    val confirmedAtMillis: Long,
)

// Playable packs = currently verified purchases plus cached packs whose
// confirmation is inside the grace window. A refund drops the pack on the
// next store check because it appears in neither set; a stale cache entry
// past grace never re-grants.
fun activePacks(
    purchases: List<VerifiedPurchase>,
    cache: List<CachedEntitlement>,
    nowMillis: Long,
): Set<String> {
    val out = purchases.map { it.packId }.toMutableSet()
    for (entry in cache) {
        if (entry.packId !in out &&
            nowMillis - entry.confirmedAtMillis <= ENTITLEMENT_GRACE_DAYS * DAY_MILLIS
        ) {
            out.add(entry.packId)
        }
    }
    return out
}

// Cache entries worth keeping: drop anything past grace so a refunded pack
// cannot linger on disk and confuse a future audit.
fun pruneCache(cache: List<CachedEntitlement>, nowMillis: Long): List<CachedEntitlement> =
    cache.filter { nowMillis - it.confirmedAtMillis <= ENTITLEMENT_GRACE_DAYS * DAY_MILLIS }
