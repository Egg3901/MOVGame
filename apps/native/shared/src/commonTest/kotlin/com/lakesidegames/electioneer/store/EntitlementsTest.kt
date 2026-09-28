package com.lakesidegames.electioneer.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntitlementsTest {
    private val now = 1_700_000_000_000L
    private val day = DAY_MILLIS

    @Test
    fun purchasesAlwaysPlayable() {
        val active = activePacks(
            listOf(VerifiedPurchase("pack_a", now)),
            emptyList(),
            now,
        )
        assertEquals(setOf("pack_a"), active)
    }

    @Test
    fun freshCacheGrantsOffline() {
        val active = activePacks(
            emptyList(),
            listOf(CachedEntitlement("pack_a", now - 3 * day)),
            now,
        )
        assertEquals(setOf("pack_a"), active)
    }

    @Test
    fun staleCacheDoesNotRegrant() {
        // Refunded yesterday, cache entry from 30 days ago: stays locked.
        val active = activePacks(
            emptyList(),
            listOf(CachedEntitlement("pack_a", now - 30 * day)),
            now,
        )
        assertTrue(active.isEmpty())
        assertTrue(pruneCache(listOf(CachedEntitlement("pack_a", now - 30 * day)), now).isEmpty())
    }

    @Test
    fun unionAndBoundary() {
        val active = activePacks(
            listOf(VerifiedPurchase("pack_a", now)),
            listOf(
                CachedEntitlement("pack_b", now - ENTITLEMENT_GRACE_DAYS * day),
                CachedEntitlement("pack_c", now - ENTITLEMENT_GRACE_DAYS * day - 1),
            ),
            now,
        )
        assertEquals(setOf("pack_a", "pack_b"), active)
    }
}
