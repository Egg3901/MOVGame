package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.*

@Serializable private data class RevealFixture(val snapshot: String, val data: NativeRevealData, val schedule: NativeRevealSchedule)
@Serializable private data class RevealTestWorld(val uk: UkGameState? = null, val country: CountryGameState? = null)

class NativeRevealTest {
    @Test fun completeRevealMatchesTheWebAndActualSeatAllocations() {
        val fixtures = EngineJson.decodeFromString<List<RevealFixture>>(REVEAL_WEB_VECTORS)
        assertEquals(6, fixtures.size)
        for (fixture in fixtures) {
            val data = NativeReveal.data(fixture.snapshot)!!
            assertEquals(fixture.data, data)
            val actual = NativeReveal.schedule(data)
            assertEquals(fixture.schedule, actual)
            val night = NativeElectionNight.create(fixture.snapshot)!!
            assertEquals(data.units.map { it.id }.toSet(), night.map().shapes.map { it.id }.toSet())
            val final = night.board(night.count())
            assertTrue(final.finished)
            assertEquals(data.totalUnits, final.calledUnits)
            assertEquals(0, final.remaining)
            assertEquals(data.totalUnits, final.rows.sumOf { it.units })
            val allocation = mutableMapOf<String, Int>()
            for (unit in data.units) for ((party, seats) in unit.allocation ?: mapOf(unit.winnerId to unit.units))
                allocation[party] = (allocation[party] ?: 0) + seats
            for (row in final.rows.filter { it.id != "__others" }) assertEquals(allocation[row.id], row.units)
            assertEquals(allocation.entries.firstOrNull { it.value >= data.threshold }?.key, actual.projectedId)
            assertEquals(0, night.board(0).calledUnits)
            assertTrue((0 until night.count()).all { night.delay(it, 1) >= 30 && night.delay(it, 2) >= 30 })
        }
    }

    @Test fun revealDoesNotMutateResultsAndCanSkipOrResume() {
        val game = MobileCampaign.start("DE", "2025", "lnk", "normal", "seat-reveal")
        while (!game.isOver()) {
            if (game.hasPendingEvent()) game.answerEvent(game.eventChoices().first().id) else game.endWeek()
        }
        val snapshot = game.saveSnapshot()
        val night = NativeElectionNight.create(snapshot)!!
        val finished = night.board(night.count())
        assertTrue(finished.rows.sumOf { it.units } == game.totalUnits())
        assertEquals(snapshot, game.saveSnapshot())
        assertEquals(finished, NativeElectionNight.create(snapshot)!!.board(night.count()))
        assertNull(NativeElectionNight.create(MobileGame.startGame("dem", "normal", 1L).saveSnapshot()))
    }
}
