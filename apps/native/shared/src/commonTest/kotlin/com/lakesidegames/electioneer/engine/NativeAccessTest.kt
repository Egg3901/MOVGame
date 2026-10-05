package com.lakesidegames.electioneer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeAccessTest {
    @Test fun gatingOffKeepsEveryElectionPlayable() {
        assertNull(NativeAccess.lockedPack("US", "2016", emptyList(), gating = false, dailyScenarioId = null))
        assertNull(NativeAccess.lockedPack("UK", "1997", emptyList(), gating = false, dailyScenarioId = null))
    }
    @Test fun freeElectionsAndTheDailyChallengeStayOpen() {
        assertNull(NativeAccess.lockedPack("US", "2024", emptyList(), gating = true, dailyScenarioId = null))
        assertNull(NativeAccess.lockedPack("US", "2020", emptyList(), gating = true, dailyScenarioId = null))
        assertNull(NativeAccess.lockedPack("UK", "1997", emptyList(), gating = true, dailyScenarioId = "uk-1997"))
    }
    @Test fun paidElectionsNeedTheirCountryBundle() {
        assertEquals("us-historical", NativeAccess.lockedPack("US", "2016", emptyList(), true, null))
        assertEquals("uk-elections", NativeAccess.lockedPack("UK", "1997", emptyList(), true, null))
        assertEquals("canada", NativeAccess.lockedPack("CA", "2025", emptyList(), true, null))
        assertEquals("germany", NativeAccess.lockedPack("DE", "2021", listOf("france"), true, null))
    }
    @Test fun ownedBundlesCompleteAndLegacyGlobalUnlock() {
        assertNull(NativeAccess.lockedPack("US", "2016", listOf("us-historical"), true, null))
        assertNull(NativeAccess.lockedPack("UK", "1997", listOf("complete"), true, null))
        assertNull(NativeAccess.lockedPack("AU", "2019", listOf("global"), true, null))
        assertEquals("us-historical", NativeAccess.lockedPack("US", "1980", listOf("global"), true, null))
    }
    @Test fun everyPaidRegistryElectionMapsToASellableBundle() {
        val sellable = setOf("us-historical", "uk-elections", "canada", "germany", "france", "australia")
        val paid = nativeElections.filter { !it.free }
        assertEquals(47, paid.size)
        paid.forEach { assertEquals(true, it.packId in sellable, it.scenarioId) }
        assertEquals(2, nativeElections.count { it.free })
    }
}
