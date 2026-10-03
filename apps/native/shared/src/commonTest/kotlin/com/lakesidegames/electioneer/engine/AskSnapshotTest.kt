package com.lakesidegames.electioneer.engine

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data class AskSeatVector(val country: String, val election: String, val player: String,
    val seed: String, val target: String, val planned: Boolean, val snapshot: AskSeatCampaignSnapshot)

class AskSnapshotTest {
    @Test
    fun everySeatEngineMatchesTheWebCampaignHandoff() {
        val vectors = EngineJson.decodeFromString<List<AskSeatVector>>(ASK_SEAT_WEB_VECTORS)
        assertEquals(setOf("UK", "CA", "DE", "FR", "AU"), vectors.map { it.country }.toSet())
        assertEquals(20, vectors.size)
        for (vector in vectors) {
            val campaign = MobileCampaign.start(vector.country, vector.election, vector.player, "normal", vector.seed)
            if (vector.planned) {
                assertTrue(campaign.queue("rally", vector.target, 1, null, null, null, null))
                assertTrue(campaign.queue("fundraise", null, 2, null, null, null, null))
            }
            val saved = campaign.saveSnapshot()
            val handoff = campaign.askSnapshot()
            assertEquals(vector.snapshot, EngineJson.decodeFromString<AskSeatCampaignSnapshot>(handoff),
                "${vector.country}/${vector.player}/${vector.planned}")
            assertEquals(saved, campaign.saveSnapshot())
            assertFalse(handoff.contains("rngState"))
            assertFalse(handoff.contains("seed"))
            assertTrue(handoff.encodeToByteArray().size <= 8_000)
        }
    }

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
