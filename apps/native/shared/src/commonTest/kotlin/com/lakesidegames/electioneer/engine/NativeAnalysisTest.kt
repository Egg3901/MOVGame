package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.EVENTS_BY_ID
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.test.*

@Serializable
private data class DebateVector(val scenario: String, val seed: String, val player: CandidateId,
    val readiness: Double, val eventId: String, val choiceId: String, val debate: DebateResult,
    val resources: Map<String, Resources>, val salience: Map<String, Double>, val fired: List<String>,
    val pending: List<PendingEvent>, val ev: Map<String, Int>, val share: Map<String, Double>)

class NativeAnalysisTest {
    @Test
    fun usUndoRemovesTheLastPlannedActionThenRestoresAnIsolatedWeek() {
        val game = MobileGame.startGame("rep", "hard", 42)
        val initial = game.saveSnapshot()
        assertFalse(game.canUndo())
        game.queueAction("rally", "PA")
        assertTrue(game.canUndo())
        assertTrue(game.undo())
        assertEquals(initial, game.saveSnapshot())
        game.endTurn()
        val completed = game.saveSnapshot()
        for (id in game.pendingEventIds()) game.answerEvent(id, game.eventChoices(id).first().id)
        assertTrue(game.undo())
        assertEquals(initial, game.saveSnapshot())
        assertFalse(game.canUndo())
        game.endTurn()
        assertEquals(completed, game.saveSnapshot())
        assertFalse(MobileGame.restore(completed)!!.canUndo())
    }

    @Test
    fun everyWorldEngineCanUndoAndReplayTheSameWeek() {
        for (country in listOf("UK", "CA", "DE", "FR", "AU")) {
            val election = MobileCampaign.elections(country).first().nativeId
            val party = MobileCampaign.parties(country, election).first().id
            val game = MobileCampaign.start(country, election, party, "normal", "world-undo")
            val initial = game.saveSnapshot()
            assertFalse(game.canUndo())
            assertTrue(game.endWeek())
            val completed = game.saveSnapshot()
            assertTrue(game.undo())
            assertEquals(initial, game.saveSnapshot())
            assertTrue(game.endWeek())
            assertEquals(completed, game.saveSnapshot())
            assertFalse(MobileCampaign.restore(completed)!!.canUndo())
        }
    }

    @Test
    fun interactiveDebatesMatchBothWebTicketsIncludingMomentumAndHistory() {
        val vectors = EngineJson.decodeFromString<List<DebateVector>>(DEBATE_WEB_VECTORS)
        assertTrue(vectors.size > 100)
        for (v in vectors) {
            val game = createGame(NewGameOptions(seed = v.seed, scenario = v.scenario, playerCandidate = v.player))
            game.turn = 4
            for (trait in listOf("charisma", "energy", "debatePrep", "intelligence", "policyKnowledge", "debatingSkill", "fundraisingProwess")) {
                game.candidates.getValue(v.player.serial).traits[trait] = v.readiness
            }
            game.pendingEvents.addAll(listOf(PendingEvent(v.eventId, CandidateId.DEM), PendingEvent(v.eventId, CandidateId.REP)))
            val mobile = MobileGame.restore(saveGame(game, v.seed, "normal"))!!
            val available = mobile.eventChoices(v.eventId).map { it.id }
            assertEquals(EVENTS_BY_ID.getValue(v.eventId).choices.filter { choiceAvailable(game, v.player, it) }.map { it.id }, available)
            assertTrue(v.choiceId in available)
            val reply = mobile.answerEvent(v.eventId, v.choiceId)
            assertTrue(reply.contains(v.debate.resultText.getValue(v.player.serial)))
            val next = loadGame(mobile.saveSnapshot())!!.state
            val label = "${v.scenario} ${v.eventId} ${v.player} ${v.readiness} ${v.choiceId}"
            assertEquals(v.debate, assertNotNull(next.debateHistory).single(), label)
            assertEquals(v.resources, next.resources, "$label resources")
            assertEquals(v.salience, next.salience, "$label salience")
            assertEquals(v.fired, next.firedEventIds, "$label fired")
            assertEquals(v.pending, next.pendingEvents, "$label pending")
            val result = computeResult(next)
            assertEquals(v.ev, result.electoralVotes, "$label EV")
            assertTrue(abs(v.share.getValue("dem") - result.popularShare.getValue("dem")) < 1e-10, "$label votes")
            val snapshot = mobile.saveSnapshot()
            mobile.answerEvent(v.eventId, v.choiceId)
            assertEquals(snapshot, mobile.saveSnapshot(), "$label duplicate answer mutated campaign")
        }
    }

    @Test
    fun liveAnalysisIsDeterministicAndDoesNotMutateTheCampaign() {
        val mobile = MobileGame.startGame("rep", "normal", 42)
        val game = loadGame(mobile.saveSnapshot())!!.state
        val before = saveGame(game, "analysis", "normal")
        val document = NativeAnalysis.us(game, "PA")
        assertEquals(document, NativeAnalysis.us(game, "PA"))
        assertEquals("PA", document.selectedRegion)
        assertEquals(6, document.sections.single { it.id == "polls" }.rows.size)
        assertEquals(game.states.single { it.id == "PA" }.blocs.size, document.sections.single { it.id == "blocs" }.rows.size)
        assertTrue(document.sections.any { it.id == "candidate-rep" && it.rows.any { row -> row.label == "Debating skill" } })
        assertEquals(before, saveGame(game, "analysis", "normal"))
        assertTrue(NativeAnalysis.us(game, "bad-region").selectedRegion != "bad-region")
        assertEquals(3, document.charts.size)
        val pollChart = document.charts.single { it.id == "poll-margin" }
        assertEquals(game.candidates.getValue("rep").shortName, pollChart.series.single().label)
        assertTrue(abs(pollChart.series.single().points.first().value - (1 - game.timeline!!.first().demPoll * 2) * 100) < 1e-10)
        assertEquals(270.0, document.charts.single { it.id == "ev" }.reference)
    }

    @Test
    fun allWorldElectionsExposeTheirLiveLeadersPollsAndBlocsWithoutMutation() {
        for (country in MobileCampaign.countries().filter { it.id != "US" }) {
            for (election in MobileCampaign.elections(country.id)) {
                val party = MobileCampaign.parties(country.id, election.nativeId).last().id
                val game = MobileCampaign.start(country.id, election.nativeId, party, "hard", "analysis-world")
                val before = game.saveSnapshot()
                val document = game.analysis(null)
                assertEquals(document, game.analysis(document.selectedRegion))
                assertTrue(document.sections.any { it.id == "candidate-$party" })
                assertEquals(5, document.sections.single { it.id == "polls" }.rows.size)
                assertTrue(document.sections.single { it.id == "blocs" }.rows.isNotEmpty())
                assertEquals(before, game.saveSnapshot())
            }
        }
    }
}
