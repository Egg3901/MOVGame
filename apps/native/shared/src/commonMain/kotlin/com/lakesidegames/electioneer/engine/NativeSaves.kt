package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.SCENARIOS
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class NativeSaveDocument(val snapshot: String, val engine: String, val label: String,
    val turn: Int, val player: String)

// One portable boundary for autosave envelopes, native country saves and the
// web's raw US GameState. Platform code handles files and storage only.
class NativeSaveTransfer private constructor() {
    companion object {
        fun inspect(json: String): NativeSaveDocument? = runCatching {
            require(json.length <= 2_000_000)
            val root = EngineJson.parseToJsonElement(json).jsonObject
            if ("uk" in root || "country" in root) {
                val campaign = MobileCampaign.restore(json) ?: return null
                return NativeSaveDocument(campaign.saveSnapshot(), "world", campaign.label(), campaign.turn(), campaign.playerParty())
            }
            val saved = if ("state" in root) loadGame(json) ?: return null else {
                val state = EngineJson.decodeFromJsonElement<GameState>(root)
                val metadata = root["_movNative"] as? JsonObject
                val seed = metadata?.get("seed")?.jsonPrimitive?.contentOrNull ?: state.seed.toString()
                val difficulty = if (metadata?.containsKey("difficulty") == true)
                    metadata["difficulty"]?.jsonPrimitive?.contentOrNull?.takeUnless { it == "unknown" }
                else when (state.playerEdge) {
                    1.55 -> "easy"; 1.15 -> "normal"; 1.0 -> "hard"; else -> null
                }
                SavedGame(seed = seed, state = state, difficulty = difficulty)
            }
            val game = saved.state
            require(game.scenarioId == null || game.scenarioId in SCENARIOS)
            require(saved.difficulty == null || saved.difficulty in DIFFICULTY_MULTIPLIER)
            require(game.seed in 0..0xffffffffL && game.rngState in 0..0xffffffffL)
            require(game.totalTurns in listOf(5, 9, 14) && game.turn in 0..game.totalTurns)
            require(game.states.isNotEmpty() && game.states.map { it.id }.distinct().size == game.states.size)
            require(game.states.sumOf { it.electoralVotes } == 538)
            require(game.phase != GamePhase.RESULT || game.result != null)
            for (candidate in listOf("dem", "rep")) {
                require(game.candidates[candidate] != null)
                val resource = game.resources.getValue(candidate)
                require(resource.cash.isFinite() && resource.cash >= 0 && resource.maxActions > 0)
                require(resource.actions in 0..resource.maxActions)
            }
            require(game.queuedActions.all { it.candidate == game.playerCandidate })
            NativeSaveDocument(saveGame(game, saved.seed, saved.difficulty), "us",
                SCENARIOS[game.scenarioId ?: "2020"]!!.label, game.turn, game.playerCandidate.serial)
        }.getOrNull()

        fun export(snapshot: String): String? {
            val document = inspect(snapshot) ?: return null
            if (document.engine == "world") return document.snapshot
            val saved = loadGame(document.snapshot) ?: return null
            val state = EngineJson.encodeToJsonElement(saved.state).jsonObject
            // Web imports still see a raw GameState. Optional metadata preserves
            // native difficulty and the human seed when the file returns here.
            return compact(JsonObject(state + ("_movNative" to buildJsonObject {
                put("seed", saved.seed)
                put("difficulty", saved.difficulty ?: "unknown")
            }))).toString()
        }

        private fun compact(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterValues { it != JsonNull }.mapValues { compact(it.value) })
            is JsonArray -> JsonArray(value.map(::compact))
            else -> value
        }
    }
}

@Serializable
data class NativeNamedSave(val id: String, val name: String, val snapshot: String, val updatedAt: Long,
    val cloudOwner: String? = null, val cloudVersion: Long? = null) {
    val document: NativeSaveDocument? get() = NativeSaveTransfer.inspect(snapshot)
}

@Serializable
private data class NativeSaveIndex(val version: Int = 1, val saves: List<NativeNamedSave> = emptyList())

class NativeSaveLibrary private constructor(private var saves: List<NativeNamedSave>) {
    companion object {
        fun empty(): NativeSaveLibrary = NativeSaveLibrary(emptyList())
        fun restore(json: String): NativeSaveLibrary? = runCatching {
            val index = EngineJson.decodeFromString<NativeSaveIndex>(json)
            require(index.version == 1 && index.saves.map { it.id }.distinct().size == index.saves.size)
            NativeSaveLibrary(index.saves.filter { validId(it.id) && it.document != null })
        }.getOrNull()
        fun validId(id: String): Boolean = Regex("[A-Za-z0-9_.-]{1,80}").matches(id) && id.any { it.isLetterOrDigit() }
    }
    fun entries(): List<NativeNamedSave> = saves.sortedByDescending { it.updatedAt }
    fun get(id: String): NativeNamedSave? = saves.firstOrNull { it.id == id }
    fun json(): String = EngineJson.encodeToString(NativeSaveIndex(saves = saves))
    fun save(id: String, name: String, snapshot: String, updatedAt: Long): Boolean {
        if (!validId(id) || name.isBlank() || updatedAt < 0) return false
        val document = NativeSaveTransfer.inspect(snapshot) ?: return false
        val previous = get(id)
        val entry = NativeNamedSave(id, name.trim().take(80), document.snapshot, updatedAt,
            previous?.cloudOwner, previous?.cloudVersion)
        saves = saves.filter { it.id != id } + entry
        return true
    }
    fun markSynced(id: String, owner: String, version: Long) {
        saves = saves.map { if (it.id == id) it.copy(cloudOwner = owner, cloudVersion = version) else it }
    }
    fun uploadJson(id: String, owner: String, now: Long): String? {
        val entry = get(id) ?: return null
        val document = entry.document ?: return null
        // The web cloud library currently stores raw US campaigns.
        if (document.engine != "us") return null
        val state = EngineJson.parseToJsonElement(NativeSaveTransfer.export(entry.snapshot) ?: return null)
        return buildJsonObject {
            put("name", entry.name); put("turn", document.turn); put("playerCandidate", document.player)
            put("state", state); put("updatedAt", maxOf(now, entry.updatedAt))
            put("expectedUpdatedAt", if (entry.cloudOwner == owner) entry.cloudVersion?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        }.toString()
    }
    fun receiveCloud(id: String, name: String, json: String, owner: String, version: Long, backupId: String): Boolean {
        val document = NativeSaveTransfer.inspect(json) ?: return false
        if (!validId(id) || !validId(backupId) || name.isBlank() || version < 0) return false
        val previous = get(id)
        if (previous != null && previous.snapshot != document.snapshot) {
            save(backupId, "${previous.name} (local backup)", previous.snapshot, previous.updatedAt)
        }
        if (!save(id, name, document.snapshot, version)) return false
        markSynced(id, owner, version)
        return true
    }
    fun remove(id: String) { saves = saves.filter { it.id != id } }
}
