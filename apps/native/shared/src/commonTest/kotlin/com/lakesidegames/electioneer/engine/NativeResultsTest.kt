package com.lakesidegames.electioneer.engine

import kotlin.test.*
import com.lakesidegames.electioneer.content.SCENARIOS

class NativeResultsTest {
    private fun finish(difficulty: String, turns: Int = 9): MobileGame {
        val scenario = SCENARIOS.getValue("2020")
        val mate = scenario.dem.runningMates.first { it.historical }
        val game = MobileGame.startConfiguredGame("2020", "dem", mate.id, emptyList(), difficulty, "historical", turns, "result-test", "", false, false)
        repeat(turns) {
            game.pendingEventIds().forEach { id -> game.answerEvent(id, game.eventChoices(id).first().id) }
            game.endTurn()
        }
        return game
    }

    @Test fun scoringDifficultySurvivesRestoreAndUsesPlayerPerspective() {
        for (difficulty in listOf("easy", "normal", "hard")) {
            val game = finish(difficulty)
            val summary = assertNotNull(game.resultSummary())
            assertEquals(difficulty, summary.difficulty)
            assertTrue(summary.score in 0..1000)
            assertEquals(game.resultDemEv() - game.resultRepEv(), summary.unitMargin)
            assertEquals((game.resultDemPopularShare() * 2 - 1) * 100, summary.popularMargin, 1e-8)
            val restored = assertNotNull(MobileGame.restore(game.saveSnapshot()))
            assertEquals(summary, restored.resultSummary())
            assertEquals(game.resultAchievements(), restored.resultAchievements())
        }
    }

    @Test fun legacySavesDoNotInventTheirDifficulty() {
        val game = finish("hard")
        val saved = assertNotNull(loadGame(game.saveSnapshot()))
        val oldSnapshot = saveGame(saved.state, saved.seed)
        val restored = assertNotNull(MobileGame.restore(oldSnapshot))
        assertEquals(-1, restored.resultSummary()?.score)
        assertTrue(restored.resultAchievements().isEmpty())
    }

    @Test fun shortAndLongCampaignsAreMarkedCasual() {
        assertFalse(assertNotNull(finish("normal", 5).resultSummary()).standardLength)
        assertTrue(assertNotNull(finish("normal").resultSummary()).standardLength)
        assertFalse(assertNotNull(finish("normal", 14).resultSummary()).standardLength)
    }

    @Test fun queuedActionPreviewDoesNotMutateTheCampaign() {
        for ((country, election, party) in listOf(Triple("UK", "2024", "lab"), Triple("FR", "2022", "rn"))) {
            val game = MobileCampaign.start(country, election, party, "normal", "preview")
            assertTrue(game.queue("broadcast", null, 1, "positive", 4.0, null, null))
            val snapshot = game.saveSnapshot()
            val preview = game.previewPlayerUnits()
            assertTrue(preview in 0..game.totalUnits())
            assertEquals(preview, game.previewPlayerUnits())
            assertEquals(snapshot, game.saveSnapshot())
        }
    }
}
