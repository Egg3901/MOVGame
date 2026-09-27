package com.lakesidegames.electioneer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionPlanTest {
    @Test
    fun validatesAndPersistsSevenDayPlan() {
        val game = createGame(NewGameOptions(seed = "plan", playerCandidate = CandidateId.DEM))
        val state = game.states.first { it.abbr == "PA" }
        assertFalse(queuePlannedAction(game, ActionType.ADVERTISE, state.id, 1, AdMode.POSITIVE, 0.0))
        assertFalse(queuePlannedAction(game, ActionType.RALLY, null, 1))
        assertFalse(queuePlannedAction(game, ActionType.RALLY, state.id, 8))
        assertTrue(queuePlannedAction(game, ActionType.ADVERTISE, state.id, 1, AdMode.ISSUE, 8.0, IssueId.ECONOMY))
        assertTrue(queuePlannedAction(game, ActionType.RALLY, state.id, 1))
        assertTrue(queuePlannedAction(game, ActionType.SURROGATE, state.id, 1))
        assertFalse(queuePlannedAction(game, ActionType.GOTV, state.id, 1))
        assertTrue(queuePlannedAction(game, ActionType.ISSUE_PIVOT, null, 2, issueId = IssueId.TAXES, newPosition = 0.4))
        assertEquals(2, nextOpenPlanDay(game))
        assertEquals(4, plannedActionRows(game).size)
        val loaded = loadGame(saveGame(game, "plan"))!!.state
        assertEquals(8_000_000.0, loaded.queuedActions[0].spend)
        assertEquals(IssueId.TAXES, loaded.queuedActions[3].issueId)
        assertTrue(removePlannedAction(game, 1))
        assertEquals(3, game.queuedActions.size)
    }
}
