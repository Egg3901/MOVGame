package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class CampaignVector(val player: CandidateId, val difficulty: String, val scenario: String,
    val mate: String, val seed: String, val staff: List<String>, val weeks: List<CampaignWeekVector>)
@Serializable
private data class CampaignWeekVector(val turn: Int, val phase: GamePhase, val rng: Long, val ev: Map<String, Int>,
    val popular: Map<String, Double>, val resources: Resources, val staff: List<String>, val recap: List<TurnRecapItem>,
    val result: CampaignResultVector? = null)
@Serializable
private data class CampaignResultVector(val electoralVotes: Map<String, Int>, val popularShare: Map<String, Double>)

class CampaignWeekTest {
    @Test
    fun bothNativeClientsMatchFullWebCampaignsAtEveryDifficulty() {
        val json = Json { ignoreUnknownKeys = true }
        val vectors = json.decodeFromString<List<CampaignVector>>(CAMPAIGN_WEB_VECTORS)
        assertEquals(6, vectors.size)
        val failures = mutableListOf<String>()
        for (v in vectors) {
            var mobile = MobileGame.startConfiguredGame(v.scenario, v.player.serial, v.mate, v.staff,
                v.difficulty, "historical", 9, v.seed, "", false, false)
            var direct = loadGame(mobile.saveSnapshot())!!.state
            for (week in v.weeks) {
                val label = "${v.player} ${v.difficulty} week ${week.turn}"
                val actions = listOf(
                    CampaignAction(type = ActionType.RALLY, candidate = v.player, stateId = "PA", day = 1),
                    CampaignAction(type = ActionType.ADVERTISE, candidate = v.player, stateId = "PA", adMode = AdMode.POSITIVE, spend = 1_000_000.0, day = 2),
                    CampaignAction(type = ActionType.FUNDRAISE, candidate = v.player, day = 3),
                )
                direct.queuedActions = actions
                mobile = MobileGame.restore(saveGame(direct, v.seed, v.difficulty))!!
                val before = saveGame(direct, v.seed, v.difficulty)
                val next = advanceCampaignWeek(direct, v.difficulty)
                assertEquals(before, saveGame(direct, v.seed, v.difficulty), "$label mutated input")
                mobile.endTurn()
                assertEquals(saveGame(next, v.seed, v.difficulty), mobile.saveSnapshot(), "$label client mismatch")
                val projection = projectElection(next)
                try {
                    assertEquals(week.turn, next.turn, label)
                    assertEquals(week.phase, next.phase, label)
                    assertEquals(week.rng, next.rngState, "$label RNG")
                    assertEquals(week.ev, projection.ev, "$label EV")
                    assertTrue(abs(week.popular.getValue("dem") - computeResult(next).popularShare.getValue("dem")) < 1e-10, "$label popular vote")
                    val resource = next.resources.getValue(v.player.serial)
                    assertTrue(abs(week.resources.cash - resource.cash) < 1e-6, "$label cash")
                    assertEquals(week.resources.maxActions, resource.maxActions, "$label max actions")
                    assertEquals(week.resources.actions, resource.actions, "$label actions")
                    assertEquals(week.staff, next.staff?.get(v.player.serial), "$label staff")
                    assertEquals(week.recap.map { it.label to it.detail }, next.lastRecap.map { it.label to it.detail }, "$label recap")
                    week.result?.let { result ->
                        assertEquals(result.electoralVotes, next.result!!.electoralVotes, "$label result EV")
                        assertTrue(abs(result.popularShare.getValue("dem") - next.result!!.popularShare.getValue("dem")) < 1e-10, "$label result share")
                    }
                } catch (failure: AssertionError) { failures += failure.message.orEmpty() }
                direct = next
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
