package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class SavedGame(
    val version: Int = 1,
    val seed: String,
    val state: GameState,
    val difficulty: String? = null,
)

fun saveGame(state: GameState, seed: String, difficulty: String? = null): String =
    EngineJson.encodeToString(SavedGame(seed = seed, state = state, difficulty = difficulty))

fun loadGame(json: String): SavedGame? = runCatching {
    EngineJson.decodeFromString<SavedGame>(json).takeIf {
        it.version == 1 && it.seed.isNotBlank() && (it.difficulty == null || it.difficulty in DIFFICULTY_MULTIPLIER)
    }
}.getOrNull()
