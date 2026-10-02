package com.lakesidegames.electioneer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeStoreWalletTest {
    private val now = 1_800_000_000_000L
    private fun snapshot(packs: String = "\"global\"", owner: String = "owner", at: Long = now) =
        "{\"owner\":\"$owner\",\"packIds\":[$packs],\"verifiedAt\":$at}"
    @Test fun cachedRightsAreBoundToTheAuthenticatedSessionAndExpireWithoutRenewal() {
        val wallet = NativeStoreWallet.restore(null)
        assertTrue(wallet.accept(snapshot(), "session", "owner", now))
        val restored = NativeStoreWallet.restore(wallet.json())
        assertEquals(listOf("global"), restored.packs("session", now + 6 * 86_400_000))
        assertEquals(emptyList(), restored.packs("other-session", now))
        assertEquals(emptyList(), restored.packs(null, now))
        assertEquals(emptyList(), restored.packs("session", now - 1))
        assertEquals(emptyList(), restored.packs("session", now + 7 * 86_400_000 + 1))
    }
    @Test fun freshAuthoritativeRefundReplacesOldOwnershipInsteadOfMergingIt() {
        val wallet = NativeStoreWallet.restore(null)
        assertTrue(wallet.accept(snapshot(), "session", "owner", now))
        assertTrue(wallet.accept(snapshot(packs = "", at = now + 1000), "session", "owner", now + 1000))
        assertEquals(emptyList(), NativeStoreWallet.restore(wallet.json()).packs("session", now + 1000))
    }
    @Test fun delayedAndTiedSnapshotsCannotRestoreAnAlreadyObservedRefund() {
        val wallet = NativeStoreWallet.restore(null)
        assertTrue(wallet.accept(snapshot(), "session", "owner", now))
        assertTrue(wallet.accept(snapshot(packs = "", at = now + 1000), "session", "owner", now + 1000))
        assertFalse(wallet.accept(snapshot(), "session", "owner", now + 1000))
        assertTrue(wallet.accept(snapshot(at = now + 1000), "session", "owner", now + 1000))
        assertEquals(emptyList(), wallet.packs("session", now + 1000))
    }
    @Test fun wrongOwnerStaleFutureAndMalformedSnapshotsDoNotReplaceKnownRights() {
        val wallet = NativeStoreWallet.restore(null)
        assertTrue(wallet.accept(snapshot(), "session", "owner", now))
        for (bad in listOf(snapshot(owner = "other"), snapshot(packs = "\"unknown\""), snapshot(at = now - 300_001), snapshot(at = now + 300_001), "{}", "[]"))
            assertFalse(wallet.accept(bad, "session", "owner", now))
        assertEquals(listOf("global"), wallet.packs("session", now))
    }
}
