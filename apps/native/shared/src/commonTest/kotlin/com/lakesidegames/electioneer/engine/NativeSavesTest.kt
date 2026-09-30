package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

@Serializable
private data class WebSaveNext(val seed: Long, val turn: Int, val rng: Long, val ev: Map<String, Int>,
    val share: Map<String, Double>, val cash: Double)

class NativeSavesTest {
    @Test
    fun anActualWebSaveContinuesOnTheSameRngAndDifficulty() {
        val document = assertNotNull(NativeSaveTransfer.inspect(RAW_WEB_SAVE))
        val saved = assertNotNull(loadGame(document.snapshot))
        assertEquals("hard", saved.difficulty)
        val expected = EngineJson.decodeFromString<WebSaveNext>(RAW_WEB_SAVE_NEXT)
        assertEquals(expected.seed, saved.state.seed)
        val next = advanceCampaignWeek(saved.state, saved.difficulty)
        assertEquals(expected.turn, next.turn)
        assertEquals(expected.rng, next.rngState)
        val result = computeResult(next)
        assertEquals(expected.ev, result.electoralVotes)
        assertTrue(abs(expected.share.getValue("dem") - result.popularShare.getValue("dem")) < 1e-10)
        assertTrue(abs(expected.cash - next.resources.getValue("rep").cash) < 1e-6)
    }

    @Test
    fun portableUsExportsRemainRawWebStatesAndPreserveNativeMetadata() {
        for (difficulty in listOf("easy", "normal", "hard")) {
            val game = createGame(NewGameOptions(seed = "typed-seed", difficulty = difficulty))
            val snapshot = saveGame(game, "typed-seed", difficulty)
            val exported = assertNotNull(NativeSaveTransfer.export(snapshot))
            val raw = EngineJson.parseToJsonElement(exported).jsonObject
            assertTrue("states" in raw && "candidates" in raw)
            assertFalse("state" in raw)
            val restored = loadGame(NativeSaveTransfer.inspect(exported)!!.snapshot)!!
            assertEquals("typed-seed", restored.seed)
            assertEquals(difficulty, restored.difficulty)
            assertEquals(snapshot, saveGame(restored.state, restored.seed, restored.difficulty))
            val withoutMetadata = JsonObject(raw - "_movNative").toString()
            assertEquals(difficulty, loadGame(NativeSaveTransfer.inspect(withoutMetadata)!!.snapshot)!!.difficulty)
        }
    }

    @Test
    fun legacyUnknownDifficultyStaysUnknownThroughExport() {
        val legacy = saveGame(createGame(NewGameOptions(seed = 1)), "1")
        val exported = NativeSaveTransfer.export(legacy)!!
        assertNull(loadGame(NativeSaveTransfer.inspect(exported)!!.snapshot)!!.difficulty)
    }

    @Test
    fun filesRoundTripAcrossAllCountryEngines() {
        for (country in listOf("UK", "CA", "DE", "FR", "AU")) {
            val election = MobileCampaign.elections(country).first().nativeId
            val party = MobileCampaign.parties(country, election).first().id
            val game = MobileCampaign.start(country, election, party, "hard", "portable")
            val exported = assertNotNull(NativeSaveTransfer.export(game.saveSnapshot()))
            val document = assertNotNull(NativeSaveTransfer.inspect(exported))
            assertEquals(game.saveSnapshot(), document.snapshot)
            assertEquals(country, MobileCampaign.restore(document.snapshot)!!.countryId())
        }
    }

    @Test
    fun cloudVersionsAreScopedToTheirOwnerAndDownloadsKeepLocalBackups() {
        val library = NativeSaveLibrary.empty()
        val game = createGame(NewGameOptions(seed = "one"))
        val original = saveGame(game, "one", "normal")
        assertTrue(library.save("save-one", "Original", original, 100))
        fun expected(owner: String) = EngineJson.parseToJsonElement(library.uploadJson("save-one", owner, 100)!!)
            .jsonObject.getValue("expectedUpdatedAt")
        assertEquals(JsonNull, expected("owner-a"))
        library.markSynced("save-one", "owner-a", 200)
        assertEquals(JsonPrimitive(200L), expected("owner-a"))
        assertEquals(JsonNull, expected("owner-b"))
        val remote = NativeSaveTransfer.export(saveGame(advanceCampaignWeek(game, "normal"), "one", "normal"))!!
        assertTrue(library.receiveCloud("save-one", "Cloud", remote, "owner-a", 300, "backup-one"))
        assertEquals(original, library.get("backup-one")!!.snapshot)
        assertEquals(2, library.entries().size)
        assertEquals(300L, NativeSaveLibrary.restore(library.json())!!.get("save-one")!!.cloudVersion)
        library.remove("save-one")
        assertNotNull(library.get("backup-one"))
    }

    @Test
    fun malformedFilesAndInvalidCloudIdsCannotChangeTheLibrary() {
        assertNull(NativeSaveTransfer.inspect("{}"))
        assertNull(NativeSaveTransfer.inspect("not json"))
        val snapshot = saveGame(createGame(NewGameOptions(seed = 42)), "42", "normal")
        val raw = EngineJson.parseToJsonElement(snapshot).jsonObject
        assertNull(NativeSaveTransfer.inspect(JsonObject(raw + ("version" to JsonPrimitive(99))).toString()))
        val library = NativeSaveLibrary.empty()
        assertFalse(library.save("../bad", "Save", snapshot, 100))
        assertFalse(library.receiveCloud("bad/path", "Cloud", "{}", "owner", 100, "backup"))
        assertTrue(library.entries().isEmpty())
    }
}
