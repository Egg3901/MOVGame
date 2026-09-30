package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.getCountry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class WebPlanVector(
    val countryId: String, val electionId: String, val party: String,
    val target: String, val issue: String, val failedSetup: Boolean,
    val plan: JsonArray, val previewSeats: Map<String, Int>, val previewVote: Map<String, Double>,
    val seats: Map<String, Int>, val rng: Long, val funds: Double, val salience: Double,
    val bonuses: List<String>,
)

@Serializable
private data class UsWebPlanVector(
    val player: CandidateId, val kind: String, val plan: List<CampaignAction>, val preview: Projection,
    val rng: Long, val cash: Double, val salience: Double, val paMargin: List<Double>, val bonuses: List<String>,
    val recap: List<TurnRecapItem>,
)

class PlanBonusesTest {
    @Test
    fun usCommittedTurnsAndPreviewsMatchWebForBothCandidates() {
        val vectors = Json.decodeFromString<List<UsWebPlanVector>>(US_PLAN_WEB_VECTORS)
        assertEquals(6, vectors.size)
        for (v in vectors) {
            val label = "${v.player} ${v.kind}"
            val game = createGame(NewGameOptions(seed = 4242, scenario = "2024", playerCandidate = v.player))
            val resources = game.resources.getValue(v.player.serial)
            resources.actions = 12
            resources.cash = if (v.kind == "failed_setup") 0.0 else 100_000_000.0
            game.queuedActions = v.plan
            val before = saveGame(game, "web-plan-vector")
            val preview = projectPlannedElection(game)
            assertEquals(before, saveGame(game, "web-plan-vector"), "$label preview mutates live game")
            assertEquals(v.preview.ev, preview.ev, "$label preview EV")
            assertEquals(v.preview.tossupEv, preview.tossupEv, "$label tossup EV")
            for (contest in v.preview.contests) assertTrue(abs(contest.demShare -
                preview.contests.first { it.stateId == contest.stateId }.demShare) < 1e-10, "$label ${contest.stateId} preview share")
            val next = advanceTurn(game, game.queuedActions, "web-plan-vector")
            assertEquals(v.rng, next.rngState, "$label RNG")
            assertTrue(abs(v.cash - next.resources.getValue(v.player.serial).cash) < 1e-6, "$label cash")
            assertTrue(abs(v.salience - next.salience.getValue("economy")) < 1e-10, "$label salience")
            val margins = next.states.first { it.id == "PA" }.blocs.map { it.campaignMargin }
            for ((i, margin) in v.paMargin.withIndex()) assertTrue(abs(margin - margins[i]) < 1e-10, "$label PA bloc $i")
            assertEquals(v.bonuses, next.causes.filter { it.cause.startsWith("Plan bonus:") }.map { it.cause }, "$label bonuses")
            assertEquals(v.recap.map { it.label to it.detail }, next.lastRecap.map { it.label to it.detail }, "$label recap")
        }
    }

    @Test
    fun bonusesRequireExplicitEarlierDaysAndMatchingTargets() {
        val ad = PlanMove("advertise", 3, "PA", "issue")
        assertTrue(planBonusesForAction(ad, listOf(PlanMove("rally", null, "PA"))).isEmpty())
        assertTrue(planBonusesForAction(ad, listOf(PlanMove("rally", 3, "PA"))).isEmpty())
        assertTrue(planBonusesForAction(ad, listOf(PlanMove("rally", 1, "OH"))).isEmpty())
        assertEquals(listOf("earned_media", "message_discipline"), planBonusesForAction(ad,
            listOf(PlanMove("rally", 1, "PA"), PlanMove("policy_prep", 2))).map { it.id })
        assertEquals(listOf("field_machine"), planBonusesForAction(PlanMove("gotv", 3, "PA"),
            listOf(PlanMove("ground_game", 1, "PA"))).map { it.id })
    }

    @Test
    fun oppositionResearchCanBeNationalButCannotTargetAnotherRegion() {
        val ad = PlanMove("broadcast", 4, "ont", "contrast")
        assertEquals(listOf("attack_line"), planBonusesForAction(ad,
            listOf(PlanMove("oppo_research", 2))).map { it.id })
        assertTrue(planBonusesForAction(ad, listOf(PlanMove("oppo_research", 2, "bc"))).isEmpty())
    }

    @Test
    fun committedTurnsAndPreviewsMatchWebAcrossAllMultipartyElections() {
        val json = Json
        val vectors = json.decodeFromString<List<WebPlanVector>>(PLAN_WEB_VECTORS)
        assertEquals(64, vectors.size)
        for (v in vectors) {
            val label = "${v.countryId} ${v.electionId} failed setup=${v.failedSetup}"
            val funds = if (v.failedSetup) 0.0 else 100.0
            val previewSeats: Map<String, Int>
            val previewVote: Map<String, Double>
            val seats: Map<String, Int>
            val rng: Long
            val cash: Double
            val salience: Double
            val causes: List<CauseEntry>
            if (v.countryId == "UK") {
                val game = createUkGame(NewUkGameOptions(seed = 4242, election = v.electionId,
                    playerParty = v.party, difficulty = "normal"))
                game.resources.getValue(v.party).actions = 12
                game.resources.getValue(v.party).funds = funds
                game.queuedActions = json.decodeFromJsonElement(v.plan)
                val original = json.encodeToString(UkGameState.serializer(), game)
                val preview = projectUkPreview(game)
                assertEquals(original, json.encodeToString(UkGameState.serializer(), game), "$label preview mutates live game")
                previewSeats = preview.seats; previewVote = preview.voteShare
                val next = ukAdvanceTurn(game, UkAdvanceOptions(disableAi = true))
                seats = computeUkResult(next).seats; rng = next.rngState
                cash = next.resources.getValue(v.party).funds; salience = next.salience.getValue(v.issue)
                causes = next.causes
            } else {
                val country = getCountry(v.countryId)!!
                val game = createCountryGame(country, NewCountryGameOptions(seed = 4242, election = v.electionId,
                    playerParty = v.party, difficulty = "normal"))
                game.resources.getValue(v.party).actions = 12
                game.resources.getValue(v.party).funds = funds
                game.queuedActions = json.decodeFromJsonElement(v.plan)
                val original = json.encodeToString(CountryGameState.serializer(), game)
                val preview = projectCountryPreview(game, country)
                assertEquals(original, json.encodeToString(CountryGameState.serializer(), game), "$label preview mutates live game")
                previewSeats = preview.seats; previewVote = preview.voteShare
                val next = countryAdvanceTurn(game, country, CountryAdvanceOptions(disableAi = true))
                seats = computeCountryResult(next, country).seats; rng = next.rngState
                cash = next.resources.getValue(v.party).funds; salience = next.salience.getValue(v.issue)
                causes = next.causes
            }
            assertEquals(v.previewSeats, previewSeats, "$label preview seats")
            for ((party, share) in v.previewVote) assertTrue(abs(share - previewVote.getValue(party)) < 1e-10, "$label $party preview vote")
            assertEquals(v.seats, seats, "$label turn seats")
            assertEquals(v.rng, rng, "$label RNG")
            assertTrue(abs(v.funds - cash) < 1e-10, "$label funds")
            assertTrue(abs(v.salience - salience) < 1e-10, "$label salience")
            assertEquals(v.bonuses, causes.filter { it.cause.startsWith("Plan bonus:") }.map { it.cause }, "$label bonuses")
        }
    }
}
