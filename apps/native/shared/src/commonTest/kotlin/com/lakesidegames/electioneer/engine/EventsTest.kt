package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.EVENTS_BY_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Phase 1, issue #16 (events half): event mechanics replayed against the real
// TS engine on identical fixtures (scripts in /tmp).
class EventsTest {

    private fun traits() = CandidateTraits(
        charisma = 60.0, energy = 50.0, debatePrep = 60.0, intelligence = 60.0,
        policyKnowledge = 60.0, debatingSkill = 60.0, fundraisingProwess = 70.0,
    )

    private fun cand(id: CandidateId) = Candidate(
        id = id, name = id.serial, shortName = if (id == CandidateId.DEM) "Biden" else "Trump",
        party = if (id == CandidateId.DEM) Party.DEMOCRATIC else Party.REPUBLICAN,
        runningMate = "VP", color = "#000", traits = traits(),
        issuePositions = mutableMapOf(), baseFavorability = mutableMapOf(),
    )

    private fun game(turn: Int, eventMode: EventMode? = null) = GameState(
        seed = 11, rngState = 11, turn = turn, totalTurns = 9, granularity = "week",
        phase = GamePhase.INTEL, playerCandidate = CandidateId.DEM,
        scenarioId = "2020", eventMode = eventMode,
        candidates = mapOf("dem" to cand(CandidateId.DEM), "rep" to cand(CandidateId.REP)),
        issues = emptyMap(), salience = mutableMapOf("economy" to 0.5),
        states = listOf(
            StateContest(
                id = "PA", name = "PA", abbr = "PA", electoralVotes = 19,
                region = Region.SWING, prior2020DemShare = 0.5, mediaMarketCost = 1.0,
                battleground = true,
                blocs = listOf(
                    StateBloc(
                        blocId = BlocId.NONCOLLEGE_WHITE, size = 1000.0,
                        turnoutPropensity = 0.6, baselineMargin = 0.0,
                        support = emptyMap(), campaignMargin = 0.0, enthusiasm = 1.0,
                    ),
                ),
                groundGame = mutableMapOf(), momentum = 0.0,
            ),
        ),
        resources = mapOf(
            "dem" to Resources(cash = 5_000_000.0, actions = 7, maxActions = 7, staffCapacity = 3, nationalMomentum = 0.0, mediaNarrative = 0.0),
            "rep" to Resources(cash = 5_000_000.0, actions = 7, maxActions = 7, staffCapacity = 3, nationalMomentum = 0.0, mediaNarrative = 0.0),
        ),
        pendingEvents = mutableListOf(), firedEventIds = mutableListOf(),
        queuedActions = emptyList(), causes = mutableListOf(), lastRecap = emptyList(),
        staff = emptyMap(),
    )

    @Test
    fun scheduledTurnMapIdentityAndRemap() {
        val deck = listOf(
            GameEvent(
                id = "a", title = "a", prompt = "", subject = "both",
                trigger = EventTrigger.Scheduled(turn = 2), choices = emptyList(),
            ),
            GameEvent(
                id = "b", title = "b", prompt = "", subject = "both",
                trigger = EventTrigger.Scheduled(turn = 2), choices = emptyList(),
            ),
            GameEvent(
                id = "c", title = "c", prompt = "", subject = "both",
                trigger = EventTrigger.Scheduled(turn = 8), choices = emptyList(),
            ),
        )
        assertEquals(mapOf("a" to 2, "b" to 2, "c" to 8), scheduledTurnMap(deck, 9))
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 4), scheduledTurnMap(deck, 5))
        assertEquals(mapOf("a" to 3, "b" to 4, "c" to 12), scheduledTurnMap(deck, 14))
    }

    @Test
    fun debateReadinessAndCountdown() {
        assertEquals(60.0, debateReadiness(game(4), CandidateId.DEM))
        assertEquals(0, turnsUntilDebate(game(4)))
    }

    @Test
    fun applyEventEffectMatchesTs() {
        val g = game(4)
        val calm = EVENTS_BY_ID.getValue("debate_1").choices[0]
        applyEventEffect(g, calm.effects, CandidateId.DEM, "t")
        val bloc = g.states[0].blocs[0]
        assertEquals(-0.01, bloc.campaignMargin)
        assertEquals(1.0, bloc.enthusiasm)
        assertEquals(8.0, g.resources.getValue("dem").nationalMomentum)
        assertEquals(10.0, g.resources.getValue("dem").mediaNarrative)
        assertEquals(1, g.causes.size)
        assertEquals(mapOf("economy" to 0.5), g.salience.toMap())
    }

    @Test
    fun resolveEventMatchesTs() {
        val g = game(4)
        g.pendingEvents.add(PendingEvent("debate_1", CandidateId.DEM))
        val rt = resolveEvent(g, "debate_1", "calm_presidential", CandidateId.DEM)
        assertEquals(
            "You refuse to take the bait. The split-screen does the work; pundits call it steady and reassuring.",
            rt,
        )
        assertEquals(listOf("debate_1:dem"), g.firedEventIds)
        assertTrue(g.pendingEvents.isEmpty())
        assertNull(resolveEvent(g, "nope", "x", CandidateId.DEM))
    }

    @Test
    fun resolveDebateMatchesTs() {
        val g = game(4)
        val d = resolveDebate(g, EVENTS_BY_ID.getValue("debate_1"), emptyMap())
        assertEquals(mapOf("dem" to 88.0, "rep" to 88.0), d.scores)
        assertEquals("tie", d.winner)
        assertEquals(0.0, d.margin)
        assertEquals(false, d.meltdown)
        assertEquals(0.0, d.momentumSwing)
        assertEquals(1, g.debateHistory?.size)
    }

    @Test
    fun queueEventsMatchesTs() {
        val hist = game(4)
        queueEventsForTurn(hist, Rng.createRng("q1"))
        assertEquals(
            listOf(
                PendingEvent("h20_debate1", CandidateId.DEM),
                PendingEvent("h20_debate1", CandidateId.REP),
            ),
            hist.pendingEvents,
        )
        val plaus = game(2, EventMode.PLAUSIBLE)
        queueEventsForTurn(plaus, Rng.createRng("q2"))
        assertEquals(
            listOf(
                PendingEvent("h20_vpdebate", CandidateId.DEM),
                PendingEvent("h20_vpdebate", CandidateId.REP),
            ),
            plaus.pendingEvents,
        )
    }

    @Test
    fun aiChooseEventMatchesTs() {
        val g = game(4)
        val ev = EVENTS_BY_ID.getValue("covid_spike")
        assertEquals("science_plan", aiChooseEvent(g, ev, CandidateId.DEM).id)
        assertEquals("science_plan", aiChooseEvent(g, ev, CandidateId.REP).id)
    }
}
