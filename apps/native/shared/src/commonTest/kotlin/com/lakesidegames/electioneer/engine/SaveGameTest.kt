package com.lakesidegames.electioneer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SaveGameTest {
    @Test
    fun restoresTheSameCampaignAndSeedForTheNextTurn() {
        val original = MobileGame.startGame("dem", "normal", 4201L)
        original.queueAction("rally", "PA")
        val restored = assertNotNull(MobileGame.restore(original.saveSnapshot()))
        assertEquals(original.turn(), restored.turn())
        assertEquals(original.queuedCount(), restored.queuedCount())
        assertEquals(original.evDem(), restored.evDem())
        original.endTurn()
        restored.endTurn()
        assertEquals(original.saveSnapshot(), restored.saveSnapshot())
    }

    @Test
    fun rejectsCorruptAndFutureSaves() {
        assertNull(MobileGame.restore("broken"))
        val saved = MobileGame.startGame("dem", "normal", 1L).saveSnapshot()
        assertNull(MobileGame.restore(saved.replace("\"version\":1", "\"version\":2")))
    }
}
