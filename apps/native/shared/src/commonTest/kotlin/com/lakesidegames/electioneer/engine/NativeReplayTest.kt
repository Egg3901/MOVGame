package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.getCountry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

@Serializable private data class ReplayVector(val country: String, val election: String, val player: String,
    val seed: String, val mode: String, val log: NativeReplayLog, val report: NativeCampaignReport)
@Serializable private data class ReplayTestWorld(val version: Int = 1, val uk: UkGameState? = null, val country: CountryGameState? = null)

private fun equivalent(expected: JsonElement, actual: JsonElement, location: String = "root") {
    when (expected) {
        is JsonObject -> {
            val e = expected.filterValues { it != JsonNull }
            val a = actual.jsonObject.filterValues { it != JsonNull }
            assertEquals(e.keys, a.keys, location)
            for (key in e.keys) equivalent(e.getValue(key), a.getValue(key), "$location.$key")
        }
        is JsonArray -> {
            assertEquals(expected.size, actual.jsonArray.size, location)
            expected.forEachIndexed { index, value -> equivalent(value, actual.jsonArray[index], "$location[$index]") }
        }
        is JsonPrimitive -> {
            if (!expected.isString && expected.doubleOrNull != null) {
                assertTrue(abs(expected.double - actual.jsonPrimitive.double) < 1e-8, "$location: $expected != $actual")
            } else assertEquals(expected, actual, location)
        }
    }
}

class NativeReplayTest {
    @Test
    fun recordingAndReportsMatchTheWebAcrossEveryEngine() {
        val vectors = EngineJson.decodeFromString<List<ReplayVector>>(REPLAY_WEB_VECTORS)
        assertEquals(7, vectors.size)
        for (v in vectors) {
            var us = if (v.country == "US") beginGame(createGame(NewGameOptions(seed = v.seed, scenario = v.election,
                playerCandidate = CandidateId.entries.first { it.serial == v.player }, difficulty = "hard"))) else null
            var uk = if (v.country == "UK") createUkGame(NewUkGameOptions(seed = v.seed, election = v.election, playerParty = v.player)) else null
            val bundle = getCountry(v.country)
            var country = bundle?.let { createCountryGame(it, NewCountryGameOptions(seed = v.seed, election = v.election, playerParty = v.player)) }
            fun snapshot(): String = us?.let { saveGame(it, v.seed, "hard") } ?: EngineJson.encodeToString(ReplayTestWorld(uk = uk, country = country))
            var log = NativeReplay.start(snapshot(), v.mode)
            var steps = 0
            while ((us?.phase?.serial ?: uk?.phase ?: country!!.phase) != "result") {
                assertTrue(steps++ < 20, "Campaign must finish")
                us?.let { game -> game.queuedActions = listOf(CampaignAction(ActionType.RALLY, game.playerCandidate, stateId = "PA", day = 1),
                    CampaignAction(ActionType.FUNDRAISE, game.playerCandidate, day = 2)) }
                uk?.let { it.queuedActions = listOf(UkAction(type = UkActionType.FUNDRAISE, party = v.player, day = 1)) }
                country?.let { it.queuedActions = listOf(CountryAction(type = CountryActionType.FUNDRAISE, party = v.player, day = 1)) }
                val before = snapshot()
                us = us?.let { advanceCampaignWeek(it, "hard") }
                uk = uk?.let { ukAdvanceTurn(it, UkAdvanceOptions(autoResolvePlayerEvents = true)) }
                country = country?.let { countryAdvanceTurn(it, bundle!!, CountryAdvanceOptions(autoResolvePlayerEvents = true)) }
                log = NativeReplay.record(log, before, snapshot())
            }
            equivalent(EngineJson.encodeToJsonElement(v.log), EngineJson.encodeToJsonElement(log), v.country)
            equivalent(EngineJson.encodeToJsonElement(v.report), EngineJson.encodeToJsonElement(NativeReplay.report(log)), "${v.country} report")
            assertEquals(log, NativeReplay.restore(NativeReplay.json(log), snapshot()))
            assertTrue(NativeReplay.json(log).length < 60_000, "Replay must stay compact")
        }
    }

    @Test
    fun undoAndLoadedGamesKeepHonestTimelines() {
        val initial = beginGame(createGame(NewGameOptions(seed = "rewind")))
        val baseline = saveGame(initial, "rewind", "normal")
        val one = advanceCampaignWeek(initial, "normal")
        val after = saveGame(one, "rewind", "normal")
        val log = NativeReplay.record(NativeReplay.start(baseline, "daily"), baseline, after)
        val rewound = NativeReplay.restore(NativeReplay.json(log), baseline)
        assertEquals(listOf(0), rewound.snapshots.map { it.turn })
        assertEquals("daily", rewound.mode)
        assertEquals(log, NativeReplay.record(rewound, baseline, after))
        assertEquals(listOf(0, 1), NativeReplay.restore(null, after).snapshots.map { it.turn })
        assertEquals("casual", NativeReplay.restore("bad json", after).mode)
        val world = createUkGame(NewUkGameOptions(seed = "older-save"))
        val older = ukAdvanceTurn(ukAdvanceTurn(world, UkAdvanceOptions(autoResolvePlayerEvents = true)), UkAdvanceOptions(autoResolvePlayerEvents = true))
        val legacy = EngineJson.encodeToString(ReplayTestWorld(uk = older))
        assertEquals(listOf(2), NativeReplay.restore(null, legacy).snapshots.map { it.turn })
    }
    @Test
    fun savesFilesCloudAndDailyGatesRetainTheReplay() {
        val game = MobileGame.startGame("dem", "normal", 918273L)
        val tracker = NativeReplayTracker()
        val before = game.saveSnapshot()
        tracker.start(before, "daily")
        assertFalse(tracker.canView(false))
        assertTrue(tracker.canView(true))
        tracker.record(before, before)
        assertEquals(listOf(0), EngineJson.decodeFromString<NativeReplayLog>(tracker.json()!!).snapshots.map { it.turn })
        game.queueAction("fundraise", null)
        val planned = game.saveSnapshot()
        game.endTurn()
        val after = game.saveSnapshot()
        tracker.record(planned, after)
        val replay = tracker.json()!!
        val library = NativeSaveLibrary.empty()
        assertTrue(library.save("campaign", "Campaign", after, 1))
        library.setReplay("campaign", replay)
        assertTrue(library.save("campaign", "Renamed", after, 2))
        assertEquals(replay, NativeSaveLibrary.restore(library.json())!!.get("campaign")!!.replay)
        val file = NativeReplay.fileExport(after, replay)!!
        val imported = NativeSaveTransfer.inspect(file)!!
        assertEquals(after, imported.snapshot)
        assertEquals(replay, NativeReplay.fileReplay(file))
        val payload = EngineJson.parseToJsonElement(library.uploadJson("campaign", "owner", 3)!!).jsonObject
        equivalent(EngineJson.parseToJsonElement(replay), payload.getValue("replay"))
        assertTrue(library.receiveCloud("campaign", "Cloud campaign", before, "owner", 4, "backup"))
        assertEquals(replay, library.get("backup")!!.replay)
        library.setReplay("campaign", null)
        assertNull(library.get("campaign")!!.replay)
        assertEquals("0", tracker.document("0")!!.selectedRegion)
        assertTrue(tracker.document(null)!!.sections.any { it.id == "action-tally" && it.rows.isNotEmpty() })
        tracker.rewind(before)
        assertEquals(listOf("0"), tracker.document(null)!!.regions.map { it.id })
        tracker.start(before, "casual")
        assertTrue(tracker.canView(false))
    }

    @Test
    fun worldFilesRetainEveryRecordedWeekWithoutRebuildingHistory() {
        val game = MobileCampaign.start("UK", "2024", "lab", "normal", "world-file")
        val tracker = NativeReplayTracker()
        val before = game.saveSnapshot()
        tracker.start(before, "casual")
        game.queue("fundraise", null, 1, null, null, null, null)
        val planned = game.saveSnapshot()
        assertTrue(game.endWeek())
        tracker.record(planned, game.saveSnapshot())
        val file = NativeReplay.fileExport(game.saveSnapshot(), tracker.json())!!
        val imported = NativeSaveTransfer.inspect(file)!!
        val restored = NativeReplay.restore(NativeReplay.fileReplay(file), imported.snapshot)
        assertEquals(listOf(0, 1), restored.snapshots.map { it.turn })
        assertTrue(restored.snapshots.last().actions.contains("Fundraised"))
        assertEquals(game.saveSnapshot(), imported.snapshot)
        assertTrue(NativeReplay.document(restored, "0").charts.size >= 3)
    }

}
