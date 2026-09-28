package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.TOTAL_EV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Phase 1, issues #15 (setup) and #6 (calibration bar): a neutral game must
// reproduce history. Mirrors src/engine/__tests__/calibration.test.ts.
class SetupTest {

    @Test
    fun totalEvIs538() {
        assertEquals(538, TOTAL_EV)
    }

    @Test
    fun neutralPlayLandsBiden306Trump232() {
        val game = createGame(NewGameOptions(seed = "calibration", playerCandidate = CandidateId.DEM))
        val result = computeResult(game)
        assertEquals(306, result.electoralVotes.getValue("dem"))
        assertEquals(232, result.electoralVotes.getValue("rep"))
        assertEquals("dem", result.winner)
    }

    @Test
    fun neutralPopularShareNear52() {
        val game = createGame(NewGameOptions(seed = "calibration"))
        val result = computeResult(game)
        assertTrue(result.popularShare.getValue("dem") > 0.515)
        assertTrue(result.popularShare.getValue("dem") < 0.53)
    }

    @Test
    fun battlegroundsAreClose() {
        val game = createGame(NewGameOptions(seed = "calibration"))
        val result = computeResult(game)
        for (id in listOf("AZ", "GA", "WI", "PA", "NV", "NC", "FL")) {
            val sr = result.stateResults.first { it.stateId == id }
            assertTrue(sr.margin < 6, "$id margin ${sr.margin}")
        }
    }

    @Test
    fun gameShapeMatchesTsDefaults() {
        val game = createGame(NewGameOptions(seed = "calibration"))
        assertEquals(0, game.turn)
        assertEquals(9, game.totalTurns)
        assertEquals(GamePhase.INTEL, game.phase)
        assertEquals("2020", game.scenarioId)
        assertEquals(1.15, game.playerEdge)
        assertEquals(56, game.states.size)
        // Staff default: no hires.
        assertEquals(emptyList(), game.staff?.get("dem"))
        // Normal handicap resources.
        assertEquals(25_000_000.0 + 220_000_000.0, game.resources.getValue("dem").cash)
    }

    @Test
    fun staffHiresFoldIn() {
        val game = createGame(
            NewGameOptions(
                seed = "calibration",
                staff = listOf("media_guru", "finance_chair", "bogus", "debate_coach"),
            ),
        )
        assertEquals(
            listOf("media_guru", "finance_chair", "debate_coach"),
            game.staff?.get("dem"),
        )
        // No maxActions bonus among these hires; debate coach trait bonus sticks.
        assertEquals(76.0, game.candidates.getValue("dem").traits.debatePrep)
    }

    @Test
    fun whatIfAndPandemicModifiers() {
        val base = createGame(NewGameOptions(seed = "x"))
        val whatIf = createGame(
            NewGameOptions(seed = "x", modifiers = GameModifiers(whatIfState = "CA")),
        )
        assertEquals(0.5, whatIf.states.first { it.id == "CA" }.prior2020DemShare)
        assertTrue(base.states.first { it.id == "CA" }.prior2020DemShare != 0.5)
        val pandemic = createGame(
            NewGameOptions(seed = "x", scenario = "2012", modifiers = GameModifiers(pandemic = true)),
        )
        assertTrue((pandemic.salience["covid_response"] ?: 0.0) >= 0.85)
    }

    @Test
    fun easyHandicapAndMirrorMatch() {
        val easy = createGame(NewGameOptions(seed = "x", difficulty = "easy"))
        assertEquals(1.55, easy.playerEdge)
        assertTrue(easy.resources.getValue("dem").cash > 300_000_000.0)
        val hard = createGame(NewGameOptions(seed = "x", difficulty = "hard"))
        val mirror = createGame(
            NewGameOptions(seed = "x", difficulty = "hard", modifiers = GameModifiers(mirrorMatch = true)),
        )
        assertEquals(
            40_000_000.0,
            mirror.resources.getValue("dem").cash - hard.resources.getValue("dem").cash,
        )
    }

    @Test
    fun hashStrMatchesTs() {
        // hashStr is FNV-1a over the string, identical to Rng.hashSeed.
        assertEquals(Rng.hashSeed("calibration"), hashStr("calibration"))
    }
}
