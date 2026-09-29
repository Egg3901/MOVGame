package com.lakesidegames.electioneer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Facade contract for the iOS app (#22): string serials in, plain reads out.
class MobileApiTest {
    @Test
    fun newGameExposesSetupAndProjection() {
        val game = MobileGame.startGame("dem", "normal", 42L)
        assertEquals("dem", game.playerSerial())
        assertEquals(0, game.turn())
        assertEquals(9, game.totalTurns())
        assertTrue(game.playerCash() > 0)
        // 538 electoral votes across all leans.
        assertEquals(538, game.evDem() + game.evRep() + game.tossupEv())
        // Contest count need not equal the state list: district splits add
        // contests while aggregate units carry none.
        assertTrue(game.contests().isNotEmpty())
        assertEquals(2, MobileGame.candidates().size)
        assertEquals(listOf("easy", "normal", "hard"), MobileGame.difficulties())
    }

    @Test
    fun queueAndEndTurn() {
        val game = MobileGame.startGame("rep", "normal", 7L)
        val slots = game.slotsLeft()
        assertTrue(slots > 0)
        val target = game.stateList().first { it.abbr == "PA" }
        game.queueAction("advertise", target.id)
        game.queueAction("fundraise", null)
        assertEquals(2, game.queuedCount())
        assertEquals(slots - 2, game.slotsLeft())
        game.clearQueue()
        assertEquals(0, game.queuedCount())
        game.queueAction("rally", target.id)
        val recap = game.endTurn()
        assertEquals(1, game.turn())
        assertTrue(recap.isNotEmpty())
        assertTrue(game.pendingEventIds().size >= 0)
    }

    @Test
    fun fullGameReachesResult() {
        val game = MobileGame.startGame("dem", "easy", 1234L)
        repeat(game.totalTurns()) { game.endTurn() }
        assertTrue(game.isOver())
        assertTrue(game.hasResult())
        val total = game.resultDemEv() + game.resultRepEv()
        assertEquals(538, total)
        assertTrue(game.resultStates().isNotEmpty())
    }

    @Test
    fun unknownEventReadsAreSafe() {
        val game = MobileGame.startGame("dem", "normal", 1L)
        assertEquals("", game.eventPrompt("no-such-event"))
        assertTrue(game.eventChoices("no-such-event").isEmpty())
        assertTrue(game.answerEvent("no-such-event", "x").isNotEmpty())
    }

    @Test
    fun configuredActionFacadeBuildsARealAdAndCanRemoveIt() {
        val game = MobileGame.startGame("dem", "normal", 33L)
        val target = game.stateList().first { it.abbr == "PA" }
        assertTrue(game.queueConfiguredAction("advertise", target.id, 2, "contrast", 8.0, null, null))
        val planned = game.plannedActions()
        assertEquals(1, planned.size)
        assertEquals(2, planned.first().day)
        assertTrue(planned.first().label.contains("ads"))
        assertTrue(game.removeAction(0))
        assertEquals(0, game.queuedCount())
    }

    @Test
    fun marginPreviewRespondsToSpendWithoutChangingTheCampaign() {
        val game = MobileGame.startGame("dem", "normal", 33L)
        val target = game.stateList().first { it.abbr == "PA" }
        val snapshot = game.saveSnapshot()
        val small = game.previewMarginPoints("advertise", target.id, 1, "positive", 1.0, null, null)
        val large = game.previewMarginPoints("advertise", target.id, 1, "positive", 20.0, null, null)
        assertTrue(small.isFinite())
        assertTrue(large > small)
        assertEquals(snapshot, game.saveSnapshot())
        assertEquals(0, game.queuedCount())
    }

    @Test
    fun configuredCampaignPreservesSetupAndSave() {
        val campaigns = MobileGame.campaigns()
        assertEquals(17, campaigns.size)
        val mate = MobileGame.mates("1960", "rep").first()
        val staff = MobileGame.staffChoices().take(2).map { it.id }
        val game = MobileGame.startConfiguredGame("1960", "rep", mate.id, staff, "hard", "plausible", 5, "ui-parity-test", "PA", true, true)
        assertEquals("1960", game.campaignLabel().take(4))
        assertEquals(5, game.totalTurns())
        assertEquals("rep", game.playerSerial())
        val restored = MobileGame.restore(game.saveSnapshot())!!
        assertEquals(game.campaignLabel(), restored.campaignLabel())
        assertEquals(5, restored.totalTurns())
    }
}
