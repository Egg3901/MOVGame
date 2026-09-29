package com.lakesidegames.electioneer.engine

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AskSnapshotTest {
    @Test
    fun snapshotTracksThePlayersSideAndCurrentPlan() {
        val game = MobileGame.startGame("rep", "normal", 42L)
        val target = game.stateList().first { it.abbr == "PA" }
        game.queueAction("rally", target.id)
        val snapshot = EngineJson.decodeFromString<AskCampaignSnapshot>(game.askSnapshot())
        assertEquals("electioneer", snapshot.game)
        assertEquals("rep", snapshot.player)
        assertEquals(game.evRep(), snapshot.evPlayer)
        assertEquals(game.evDem(), snapshot.evOpponent)
        assertEquals(game.tossupEv(), snapshot.tossupEv)
        assertEquals(game.slotsLeft(), snapshot.actionsLeft)
        assertTrue(snapshot.states.any { it.name == target.name })
        assertTrue(snapshot.planned.any { it.move.contains("rally", ignoreCase = true) })
        assertTrue(snapshot.planned.any { it.target == target.name })
        assertFalse(game.askSnapshot().contains("rngState"))
        assertTrue(game.askSnapshot().encodeToByteArray().size <= 8_000)
    }
}
