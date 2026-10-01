package com.lakesidegames.electioneer.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeCustomScenarioTest {
    private val vectors get() = EngineJson.parseToJsonElement(EDITOR_WEB_VECTORS).jsonObject
    private fun equalJson(expected: JsonElement, actual: JsonElement, path: String = "") {
        when (expected) {
            is JsonObject -> {
                assertTrue(actual is JsonObject, path)
                for ((key, value) in expected) equalJson(value, actual[key] ?: JsonNull, "$path/$key")
            }
            is JsonArray -> {
                assertTrue(actual is JsonArray, path); assertEquals(expected.size, actual.size, path)
                for (index in expected.indices) equalJson(expected[index], actual[index], "$path/$index")
            }
            is JsonPrimitive -> {
                val numeric = expected.takeUnless { it.isString }?.doubleOrNull
                if (numeric != null) {
                    assertTrue(actual is JsonPrimitive && !actual.isString, path)
                    val got = actual.doubleOrNull ?: error("Not numeric: $path")
                    assertTrue(kotlin.math.abs(numeric - got) <= 1e-8 * maxOf(1.0, kotlin.math.abs(numeric)), "$path expected $numeric got $got")
                } else assertEquals(expected, actual, path)
            }
        }
    }
    @Test fun validationMatches446WebCasesAndNormalization() {
        val fixtures = vectors.getValue("validations").jsonArray
        assertEquals(446, fixtures.size)
        for ((index, value) in fixtures.withIndex()) {
            val fixture = value.jsonObject
            val expected = fixture.getValue("result").jsonObject
            val got = NativeCustomScenario.validate(fixture.getValue("input").jsonPrimitive.content, 0)
            assertEquals(expected.getValue("ok").jsonPrimitive.boolean, got.json != null, "Validation $index: ${got.errors}")
            if (got.json != null) equalJson(expected.getValue("value"), EngineJson.parseToJsonElement(got.json), "Validation $index")
            else assertEquals(expected.getValue("errors").jsonArray.map { it.jsonPrimitive.content }, got.errors, "Validation $index")
        }
    }
    @Test fun allSixEditedCampaignsMatchWebTraitsSupportAiRngAndResults() {
        for (fixture in vectors.getValue("campaigns").jsonArray.map { it.jsonObject }) {
            val json = fixture.getValue("json").jsonPrimitive.content
            var snapshot = assertNotNull(NativeCustomScenario.start(json))
            val doc = assertNotNull(NativeCustomScenario.entry(json))
            val weeks = fixture.getValue("weeks").jsonArray
            for ((index, expected) in weeks.withIndex()) {
                val root = EngineJson.parseToJsonElement(snapshot).jsonObject
                val us = loadGame(snapshot)
                val uk = root["uk"]?.let { EngineJson.decodeFromJsonElement<UkGameState>(it) }
                val country = root["country"]?.let { EngineJson.decodeFromJsonElement<CountryGameState>(it) }
                val facts = if (us != null) {
                    val game = us.state
                    buildJsonObject {
                        put("turn", game.turn); put("rng", game.rngState)
                        put("units", EngineJson.encodeToJsonElement(projectElection(game).ev))
                        put("popular", EngineJson.encodeToJsonElement(computeResult(game).popularShare))
                        put("leaders", EngineJson.encodeToJsonElement(game.candidates))
                        put("resources", EngineJson.encodeToJsonElement(game.resources))
                    }
                } else {
                    val bundle = country?.let { assertNotNull(countryForGame(it)) }
                    val projection = uk?.let(::projectUk)
                    val result = country?.let { projectCountry(it, bundle!!) }
                    buildJsonObject {
                        put("turn", uk?.turn ?: country!!.turn); put("rng", uk?.rngState ?: country!!.rngState)
                        put("units", EngineJson.encodeToJsonElement(projection?.seats ?: result!!.seats))
                        put("popular", EngineJson.encodeToJsonElement(projection?.voteShare ?: result!!.voteShare))
                        put("leaders", if (uk != null) EngineJson.encodeToJsonElement(uk.leaders) else EngineJson.encodeToJsonElement(country!!.leaders))
                        put("resources", if (uk != null) EngineJson.encodeToJsonElement(uk.resources) else EngineJson.encodeToJsonElement(country!!.resources))
                    }
                }
                equalJson(expected, facts, "${doc.country} week $index")
                val portable = assertNotNull(NativeSaveTransfer.export(snapshot))
                snapshot = assertNotNull(NativeSaveTransfer.inspect(portable)).snapshot
                if (index < weeks.lastIndex) {
                    snapshot = if (us != null) saveGame(advanceCampaignWeek(us.state, "normal"), us.seed, "normal")
                    else if (uk != null) buildJsonObject { put("version", 1); put("uk", EngineJson.encodeToJsonElement(ukAdvanceTurn(uk, UkAdvanceOptions(autoResolvePlayerEvents = true)))) }.toString()
                    else buildJsonObject { put("version", 1); put("country", EngineJson.encodeToJsonElement(countryAdvanceTurn(country!!, countryForGame(country)!!, CountryAdvanceOptions(autoResolvePlayerEvents = true)))) }.toString()
                }
            }
            val us = MobileGame.restore(snapshot)
            val world = if (us == null) assertNotNull(MobileCampaign.restore(snapshot)) else null
            assertNull(us?.scoreSubmission() ?: world?.scoreSubmission())
            if (us != null) { assertTrue(us.isCustom()); assertTrue(us.resultAchievements().isEmpty()); assertTrue(us.resultHistory().isEmpty()) }
            else assertTrue(world!!.isCustom())
            assertFalse(us?.isDaily("2026-10-01") ?: world!!.isDaily("2026-10-01"))
            assertNotNull(us?.analysis(null) ?: world!!.analysis(null))
            assertNotNull(NativeReveal.data(snapshot))
            val journey = assertNotNull(NativeResultsJourney.create(snapshot, "2026-10-01"))
            assertNull(journey.next); assertTrue(journey.shareText.contains(doc.label))
            val replay = NativeReplay.start(snapshot, "casual")
            assertTrue(replay.parties.isNotEmpty())
        }
    }
    @Test fun sameIdDocumentsStayIndependentAndLibrarySurvivesDeletionAndRestart() {
        val library = NativeCustomLibrary.empty()
        val json = assertNotNull(NativeCustomScenario.create("CA", "custom-stable", null, 100))
        val snapshot = assertNotNull(NativeCustomScenario.start(json))
        val campaign = assertNotNull(MobileCampaign.restore(snapshot))
        val before = campaign.standings()
        val stableSnapshot = campaign.saveSnapshot()
        val altered = assertNotNull(NativeCustomScenario.edit(json, "mp/parties/0/baseSupport", "1"))
        assertNotNull(library.save(altered, 200).json)
        assertNotNull(NativeCustomScenario.start(altered))
        assertEquals(before, campaign.standings())
        assertEquals(stableSnapshot, campaign.saveSnapshot())
        val duplicate = assertNotNull(NativeCustomScenario.duplicate(altered, "custom-copy", 300))
        assertNotNull(library.save(duplicate, 300).json)
        val restored = assertNotNull(NativeCustomLibrary.restore(library.json()))
        restored.remove("custom-stable")
        assertEquals(listOf("custom-copy"), restored.entries().map { it.id })
        assertNotNull(MobileCampaign.restore(snapshot))
        val malformed = EngineJson.parseToJsonElement(snapshot).jsonObject
        val state = malformed.getValue("country").jsonObject
        assertNull(MobileCampaign.restore(JsonObject(malformed + ("country" to JsonObject(state - "customScenario"))).toString()))
        assertNull(NativeCustomScenario.validate("{", 0).json)
        assertNull(NativeCustomScenario.validate(" ".repeat(2_000_001), 0).json)
    }
}
