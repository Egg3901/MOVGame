package com.lakesidegames.electioneer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Phase 0 contract smoke tests: the Kotlin model covers every TS union member
// and the serial strings match src/engine/types.ts exactly. Run with
// ./gradlew :shared:jvmTest. The full engine-behaviour suite (ported from
// src/engine/__tests__/, calibration bar Biden 306 / Trump 232) is Phase 1.
class TypesTest {

    @Test
    fun issueIdsCoverAllTenTsMembers() {
        assertEquals(
            listOf(
                "economy", "covid_response", "healthcare", "immigration",
                "race_policing", "climate", "taxes", "law_and_order",
                "abortion", "trade",
            ),
            IssueId.entries.map { it.serial },
        )
    }

    @Test
    fun blocIdsCoverAllEightTsMembers() {
        // Phase 0 contract: the US set leads; UK/country sets extend it.
        assertEquals(
            listOf(
                "noncollege_white", "college_white", "suburban_women", "black",
                "hispanic", "asian_other", "seniors", "youth",
            ),
            BlocId.entries.take(8).map { it.serial },
        )
    }

    @Test
    fun actionTypesCoverAllTenTsMembers() {
        assertEquals(10, ActionType.entries.size)
        assertTrue(ActionType.entries.map { it.serial }.contains("issue_pivot"))
    }

    @Test
    fun gamePhasesFollowTsOrder() {
        assertEquals(
            listOf("setup", "intel", "events", "allocate", "result"),
            GamePhase.entries.map { it.serial },
        )
    }

    @Test
    fun optionalContractFieldsDefaultToNull() {
        val traits = CandidateTraits(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0)
        val candidate = Candidate(
            id = CandidateId.DEM,
            name = "Democrat",
            shortName = "Dem",
            party = Party.DEMOCRATIC,
            runningMate = "",
            color = "#0000FF",
            traits = traits,
        )
        assertNull(candidate.baseTraits)
        assertEquals(emptyMap(), candidate.issuePositions)
        assertEquals(emptyMap(), candidate.baseFavorability)
    }

    @Test
    fun gameResultDefaultsToTwoPartyShape() {
        val result = GameResult(
            electoralVotes = mapOf("dem" to 306, "rep" to 232),
            winner = "dem",
            popularVote = emptyMap(),
            popularShare = emptyMap(),
            stateResults = emptyList(),
            postMortem = emptyList(),
        )
        assertNull(result.seats)
        assertNull(result.government)
    }
}
