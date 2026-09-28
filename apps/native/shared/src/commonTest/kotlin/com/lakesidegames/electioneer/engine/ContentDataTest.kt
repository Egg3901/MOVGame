package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.EVENTS
import com.lakesidegames.electioneer.content.EVENTS_BY_ID
import com.lakesidegames.electioneer.content.GENERIC_DEBATES
import com.lakesidegames.electioneer.content.GENERIC_EVENTS
import com.lakesidegames.electioneer.content.HISTORICAL_EVENTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Generated event content integrity: the codegen transcription must preserve
// every event, choice, and number. TS-side aggregates: 125 events,
// 385 choices, GENERIC_EVENTS 18, margin checksum 19.101999999999958.
class ContentDataTest {

    @Test
    fun eventCountsMatchTs() {
        assertEquals(125, EVENTS_BY_ID.size)
        val all = EVENTS_BY_ID.values.toList()
        assertEquals(385, all.sumOf { it.choices.size })
        assertEquals(18, GENERIC_EVENTS.size)
        assertEquals(2, GENERIC_DEBATES.size)
        assertEquals(17, HISTORICAL_EVENTS.size)
        assertEquals(8, HISTORICAL_EVENTS.getValue("2020").size)
        assertEquals(5, HISTORICAL_EVENTS.getValue("2024").size)
    }

    @Test
    fun marginChecksumMatchesTs() {
        val events: Collection<GameEvent> = EVENTS_BY_ID.values
        var sum = 0.0
        for (e: GameEvent in events) {
            val choices: List<EventChoice> = e.choices
            for (c: EventChoice in choices) {
                val deltas: List<BlocDelta> = c.effects.blocDeltas ?: emptyList()
                for (bd: BlocDelta in deltas) {
                    val m: Double? = bd.margin
                    sum += m ?: 0.0
                }
            }
        }
        assertTrue(kotlin.math.abs(sum - 19.101999999999958) <= 1e-9, "checksum $sum")
    }

    @Test
    fun debateOneSpotCheck() {
        val d = EVENTS_BY_ID.getValue("debate_1")
        assertEquals("First Presidential Debate (Cleveland)", d.title)
        assertEquals(EventTrigger.Scheduled(turn = 4), d.trigger)
        assertTrue(d.isDebate)
        assertTrue(d.oncePerGame)
        assertEquals(3, d.choices.size)
        val wonk = d.choices.first { it.id == "policy_wonk" }
        assertEquals("policyKnowledge", wonk.requires?.trait)
        assertEquals(70.0, wonk.requires?.min)
        assertEquals(mapOf("healthcare" to 0.05, "economy" to 0.04), wonk.effects.salienceDeltas)
        // Index aliases the deck objects.
        assertTrue(EVENTS_BY_ID.getValue("debate_1") === EVENTS.first { it.id == "debate_1" })
    }
}
