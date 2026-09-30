package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable private data class JourneyFixture(val snapshot: String, val journey: NativeResultJourney)
@Serializable private data class PercentileFixture(val score: Int, val scores: List<Int>?, val rank: Int?, val result: Int?)

class NativeResultJourneyTest {
    @Test fun sharesScoresAndNextCampaignsMatchAllSixWebResultFlows() {
        val fixtures = EngineJson.decodeFromString<List<JourneyFixture>>(REVEAL_WEB_VECTORS)
        assertEquals(6, fixtures.size)
        for (fixture in fixtures) {
            assertEquals(fixture.journey, NativeResultsJourney.create(fixture.snapshot, "2000-01-01"))
            val next = fixture.journey.next!!
            if (next.countryId != "US") assertTrue(MobileCampaign.parties(next.countryId, next.electionId).any { it.id == next.partyId })
        }
    }
    @Test fun dailyPercentilesMatchTheLiveBoardFunctionForTiesEmptyBoardsAndOffBoardRanks() {
        val fixtures = EngineJson.decodeFromString<List<PercentileFixture>>(DAILY_PERCENTILE_WEB_VECTORS)
        assertEquals(180, fixtures.size)
        for (fixture in fixtures) assertEquals(fixture.result, NativeResultsJourney.percentile(fixture.score, fixture.scores, fixture.rank), fixture.toString())
    }
    @Test fun nextElectionReplacesUnavailablePartiesAndPreservesDifficulty() {
        val campaign = MobileCampaign.start("UK", "2024", "grn", "hard", "next-campaign")
        while (!campaign.isOver()) {
            if (campaign.hasPendingEvent()) campaign.answerEvent(campaign.eventChoices().first().id) else campaign.endWeek()
        }
        val snapshot = campaign.saveSnapshot()
        val next = NativeResultsJourney.create(snapshot, "2000-01-01")!!.next!!
        assertEquals("1951", next.electionId)
        assertEquals("lab", next.partyId)
        assertEquals("hard", next.difficulty)
        assertEquals(snapshot, campaign.saveSnapshot())
        assertNull(NativeResultsJourney.create(MobileGame.startGame("dem", "normal", 1L).saveSnapshot(), "2000-01-01"))
    }
}
