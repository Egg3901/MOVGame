package com.lakesidegames.electioneer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// End-to-end turnloop: a full 9-turn neutral campaign (no player actions)
// must follow the same trajectory as the TS engine. Vectors from running
// turn.ts on seed "turnloop" (scripts in /tmp). EV counts and bookkeeping
// assert exactly; vote shares within 1e-9 (platform transcendental rule).
class TurnloopTest {

    private fun close(expected: Double, actual: Double, label: String) {
        assertTrue(kotlin.math.abs(expected - actual) <= 1e-9, "$label expected $expected but was $actual")
    }

    @Test
    fun nineTurnNeutralCampaignMatchesTs() {
        var g = beginGame(createGame(NewGameOptions(seed = "turnloop", playerCandidate = CandidateId.DEM)))
        val trace = mutableListOf(computeResult(g).electoralVotes.getValue("dem"))
        for (t in 0 until 9) {
            g = advanceTurn(g, emptyList(), "turnloop")
            trace.add(computeResult(g).electoralVotes.getValue("dem"))
        }
        assertEquals(listOf(306, 270, 244, 233, 227, 217, 217, 217, 217, 217), trace)

        val r = g.result!!
        assertEquals(mapOf("dem" to 217, "rep" to 321), r.electoralVotes)
        assertEquals("rep", r.winner)
        assertEquals(9, g.turn)
        assertEquals(GamePhase.RESULT, g.phase)
        assertEquals(544594036L, g.rngState)
        close(0.5027195752219038, r.popularShare.getValue("dem"), "popDem")
        close(0.44813126056056324, r.stateResults.first { it.stateId == "PA" }.demShare, "paShare")

        assertEquals("Projected electoral votes", g.lastRecap[0].label)
        assertEquals("Biden 217 (was 217)", g.lastRecap[0].detail)
        assertEquals(0.0, g.lastRecap[0].marginDelta)
        assertEquals(3226, g.causes.size)

        val timeline = g.timeline!!
        assertEquals(10, timeline.size)
        assertEquals(listOf(0, 227, 217), listOf(timeline[0].turn, timeline[0].demEV, timeline[0].repEV))
        assertEquals(listOf(9, 217, 321), listOf(timeline[9].turn, timeline[9].demEV, timeline[9].repEV))
    }

    @Test
    fun advanceTurnIsPure() {
        val start = beginGame(createGame(NewGameOptions(seed = "pure", playerCandidate = CandidateId.DEM)))
        val a = advanceTurn(start, emptyList(), "pure")
        val b = advanceTurn(start, emptyList(), "pure")
        // Same inputs give the same outputs; the input is untouched.
        assertEquals(a.rngState, b.rngState)
        assertEquals(a.turn, b.turn)
        assertEquals(0, start.turn)
        assertTrue(start.causes.isEmpty())
    }

    @Test
    fun terminalGameShortCircuits() {
        var g = beginGame(createGame(NewGameOptions(seed = "term", playerCandidate = CandidateId.DEM)))
        for (t in 0 until 9) g = advanceTurn(g, emptyList(), "term")
        assertEquals(GamePhase.RESULT, g.phase)
        val again = advanceTurn(g, emptyList(), "term")
        assertEquals(9, again.turn)
        assertEquals(g.rngState, again.rngState)
    }
}
