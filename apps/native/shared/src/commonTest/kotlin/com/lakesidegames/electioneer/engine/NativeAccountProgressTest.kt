package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.SCENARIOS
import kotlin.test.*
import kotlinx.serialization.json.*

class NativeAccountProgressTest {
    @Test fun serverAndDeviceAwardsAreUnitedByElectionAndUploadsRetryUntilAcknowledged() {
        val progress = NativeAccountProgress.empty()
        progress.record("us-2020", listOf("grassroots", "landslide"))
        progress.record("us-2024", listOf("landslide"))
        val remote = """{"achievements":[{"scenario_id":"us-2020","achievement_id":"grassroots","earned_at":100},{"scenario_id":"us-2024","achievement_id":"blue-wall","earned_at":200}]}"""
        val uploads = assertNotNull(progress.uploads(remote))
        assertEquals(listOf("us-2020", "us-2024"), uploads.map { it.scenarioId })
        uploads.forEach {
            val request = EngineJson.parseToJsonElement(it.payload).jsonObject
            assertEquals(it.scenarioId, request.getValue("scenarioId").jsonPrimitive.content)
            assertEquals(listOf("landslide"), request.getValue("achievementIds").jsonArray.map { id -> id.jsonPrimitive.content })
        }
        assertEquals(4, progress.awards().size)
        val restored = NativeAccountProgress.restore(progress.json())
        assertEquals(progress.awards(), restored.awards())
        assertEquals(uploads, restored.uploads(remote), "An interrupted upload must still be pending after restart")
        val acknowledged = """{"achievements":[{"scenario_id":"us-2020","achievement_id":"grassroots"},{"scenario_id":"us-2020","achievement_id":"landslide"},{"scenario_id":"us-2024","achievement_id":"blue-wall"},{"scenario_id":"us-2024","achievement_id":"landslide"}]}"""
        assertTrue(assertNotNull(restored.uploads(acknowledged)).isEmpty())
        restored.record("us-2020", listOf("nailbiter"))
        assertEquals(listOf("us-2020"), assertNotNull(restored.uploads(acknowledged)).map { it.scenarioId })
    }

    @Test fun unreadableResponsesAndUnknownAwardsDoNotEraseOrFabricateProgress() {
        val progress = NativeAccountProgress.empty()
        assertFalse(progress.record("de-2025", listOf("landslide")))
        assertFalse(progress.record("us-2020", listOf("unknown")))
        assertTrue(progress.record("us-2020", listOf("blue-wall")))
        val before = progress.json()
        assertNull(progress.uploads("<html>offline</html>"))
        assertNull(progress.uploads("{}"))
        assertEquals(before, progress.json())
        assertTrue(NativeAccountProgress.restore("{\"version\":2}").awards().isEmpty())
        assertTrue(NativeAccountProgress.restore("broken").awards().isEmpty())
        progress.uploads("""{"achievements":[{"scenario_id":"missing","achievement_id":"landslide"},{"scenario_id":"us-2020","achievement_id":"future-award"}]}""")
        assertEquals(before, progress.json())
    }

    @Test fun onlyEarnedFinishedKnownDifficultyCampaignAwardsEnterTheJournal() {
        val scenario = SCENARIOS.getValue("2020")
        val game = MobileGame.startConfiguredGame("2020", "dem", scenario.dem.runningMates.first { it.historical }.id,
            emptyList(), "normal", "historical", 9, "account-awards", "", false, false)
        val progress = NativeAccountProgress.empty()
        assertFalse(progress.recordSnapshot(game.saveSnapshot()))
        repeat(9) {
            game.pendingEventIds().forEach { id -> game.answerEvent(id, game.eventChoices(id).first().id) }
            game.endTurn()
        }
        assertTrue(game.resultAchievements().isNotEmpty())
        assertTrue(progress.recordSnapshot(game.saveSnapshot()))
        assertEquals(game.resultAchievements(), progress.awards().map { it.award })
        assertEquals(setOf("us-2020"), progress.awards().map { it.scenarioId }.toSet())
        assertFalse(progress.recordSnapshot(game.saveSnapshot()))
        val saved = assertNotNull(loadGame(game.saveSnapshot()))
        assertFalse(NativeAccountProgress.empty().recordSnapshot(saveGame(saved.state, saved.seed)))
    }

    @Test fun lakesideReturnRequiresExactOriginStateMainFrameAndOneUnusedCode() {
        val nonce = "0123456789abcdef0123456789abcdef"
        val code = "abcdefghABCDEFGH01234567_-abcdef"
        assertEquals(32, code.length)
        val flow = NativeLakesideLogin(nonce)
        assertTrue(flow.startUrl().endsWith("%2Fnative-login%3Fstate%3D$nonce"))
        assertTrue(flow.allows("https", "auth.lakesidegames.net", -1))
        assertFalse(flow.allows("http", "auth.lakesidegames.net", -1))
        assertFalse(flow.allows("https", "auth.lakesidegames.net.attacker.test", -1))
        assertFalse(flow.allows("https", "auth.lakesidegames.net", 444))
        fun take(scheme: String = "https", host: String = "sim.ahousedividedgame.com", port: Int = -1,
                 path: String = "/native-login", states: List<String> = listOf(nonce), codes: List<String> = listOf(code),
                 fragment: String? = null, main: Boolean = true) = flow.takeCode(scheme, host, port, path, states, codes, fragment, main)
        assertNull(take(scheme = "http"))
        assertNull(take(host = "sim.ahousedividedgame.com.attacker.test"))
        assertNull(take(path = "/other"))
        assertNull(take(states = listOf("wrong")))
        assertNull(take(states = listOf(nonce, nonce)))
        assertNull(take(codes = listOf(code, code)))
        assertNull(take(codes = listOf("expired")))
        assertNull(take(fragment = "other"))
        assertNull(take(main = false))
        assertEquals(code, take())
        assertNull(take(), "A duplicate navigation cannot redeem the same code again")
    }
}
