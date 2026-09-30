package com.lakesidegames.electioneer.engine

import kotlin.test.*
import com.lakesidegames.electioneer.content.*
import kotlinx.serialization.encodeToString

class MobileCampaignTest {
    @Test fun everyWebElectionCanBePlayedAndRestoredNatively() {
        assertEquals(6, MobileCampaign.countries().size)
        assertEquals(17, MobileCampaign.elections("US").size)
        for (country in MobileCampaign.countries().filter { it.id != "US" }) {
            assertTrue(MobileCampaign.elections(country.id).isNotEmpty())
            for (election in MobileCampaign.elections(country.id)) {
                val parties = MobileCampaign.parties(country.id, election.nativeId)
                assertTrue(parties.isNotEmpty(), election.scenarioId)
                for (party in parties) {
                    var game = MobileCampaign.start(country.id, election.nativeId, party.id, "normal", "native-parity")
                    assertTrue(game.map().width > 0)
                    assertEquals(game.regions().map { it.id }.toSet(), game.map().shapes.map { it.id }.toSet())
                    assertTrue(game.map().shapes.all { it.polygons.isNotEmpty() && it.polygons.all { p -> p.points.size >= 3 } })
                    assertEquals(game.totalUnits(), game.regions().sumOf { it.totalUnits }, election.scenarioId)
                    assertEquals(game.totalUnits(), game.standings().sumOf { it.units }, election.scenarioId)
                    repeat(game.totalTurns()) {
                        assertTrue(game.queue("fundraise", null, 1, null, null, null, null))
                        assertTrue(game.endWeek())
                        if (game.hasPendingEvent()) assertTrue(game.answerEvent(game.eventChoices().first().id))
                        val restored = assertNotNull(MobileCampaign.restore(game.saveSnapshot()), election.scenarioId)
                        assertEquals(game.standings(), restored.standings())
                        assertEquals(game.saveSnapshot(), restored.saveSnapshot())
                        game = restored
                    }
                    assertTrue(game.isOver(), "${election.scenarioId}/${party.id}")
                    assertTrue(game.outcome().isNotBlank())
                    assertFalse(game.endWeek())
                    assertFalse(game.queue("fundraise", null, 1, null, null, null, null))
                }
            }
        }
    }

    @Test fun queueReservesCashAndRejectsInvalidTargetsAndSettings() {
        val game = MobileCampaign.start("CA", "2021", "lpc", "normal", "budget")
        val snapshot = game.saveSnapshot()
        assertFalse(game.queue("broadcast", null, 1, "positive", Double.NaN, null, null))
        assertFalse(game.queue("rally", null, 1, null, null, null, null))
        assertFalse(game.queue("rally", "missing", 1, null, null, null, null))
        assertFalse(game.queue("broadcast", null, 1, "issue", 1.0, null, null))
        assertFalse(game.queue("issue_pivot", null, 1, null, null, "missing", null))
        assertFalse(game.queue("oppo_research", null, 1, null, null, null, "lpc"))
        assertFalse(game.queue("fundraise", null, 0, null, null, null, null))
        assertEquals(snapshot, game.saveSnapshot())
        assertTrue(game.queue("broadcast", null, 1, "positive", game.funds(), null, null))
        assertEquals(0.0, game.availableFunds(), 1e-9)
        assertFalse(game.queue("surrogate", game.regions().first().id, 2, null, null, null, null))
        game.removeAction(0)
        assertEquals(game.funds(), game.availableFunds())
        repeat(3) { assertTrue(game.queue("fundraise", null, 1, null, null, null, null)) }
        assertFalse(game.queue("fundraise", null, 1, null, null, null, null))
    }

    @Test fun requiredEventBlocksProgressAndPersistsWithoutDefaultingChoice() {
        val state = createUkGame(NewUkGameOptions(seed = "event", election = "2024", playerParty = "lab"))
        val event = (UK_ELECTION_EVENTS["2024"].orEmpty() + UK_EVENTS).first { !it.choices.isNullOrEmpty() }
        state.pendingEvent = UkPendingEvent(event.id, "lab")
        val snapshot = "{\"version\":1,\"uk\":${EngineJson.encodeToString(state)}}"
        val game = assertNotNull(MobileCampaign.restore(snapshot))
        assertTrue(game.hasPendingEvent())
        assertFalse(game.endWeek())
        assertFalse(game.answerEvent("missing"))
        assertEquals(0, game.turn())
        assertTrue(game.answerEvent(game.eventChoices().last().id))
        assertFalse(game.hasPendingEvent())
        assertTrue(game.recap().isNotEmpty())
        assertTrue(game.endWeek())
    }

    @Test fun nativeFacadeUsesTheExistingEnginesWithoutChangingTheirOutcomes() {
        val native = MobileCampaign.start("FR", "2022", "rn", "normal", "same-seed")
        var reference = createCountryGame(FRANCE, NewCountryGameOptions(seed = "same-seed", election = "2022", playerParty = "rn"))
        repeat(6) {
            assertTrue(native.endWeek())
            reference = countryAdvanceTurn(reference, FRANCE, CountryAdvanceOptions(autoResolvePlayerEvents = false))
            reference.pendingEvent?.let {
                val choice = native.eventChoices().last().id
                assertTrue(native.answerEvent(choice))
                reference = resolveCountryPlayerEvent(reference, FRANCE, choice)
            }
            val projection = reference.result ?: projectCountry(reference, FRANCE)
            assertEquals(projection.seats, native.standings().associate { it.partyId to it.units })
            assertEquals(reference.rngState, EngineJson.decodeFromString<kotlinx.serialization.json.JsonObject>(native.saveSnapshot())["country"]?.let {
                EngineJson.decodeFromString<CountryGameState>(it.toString()).rngState
            })
        }
        assertContains(native.outcome(), "presidency")
    }

    @Test fun malformedAndFutureSavesAreRejected() {
        assertNull(MobileCampaign.restore("not-json"))
        assertNull(MobileCampaign.restore("{\"version\":2}"))
        assertNull(MobileCampaign.restore("{\"version\":1}"))
    }
}
