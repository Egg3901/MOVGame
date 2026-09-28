package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class SavedGame(
    val version: Int = 1,
    val seed: String,
    val state: GameState,
)

fun saveGame(state: GameState, seed: String): String =
    EngineJson.encodeToString(SavedGame(seed = seed, state = state))

fun loadGame(json: String): SavedGame? = runCatching {
    EngineJson.decodeFromString<SavedGame>(json).takeIf { it.version == 1 && it.seed.isNotBlank() }
}.getOrNull()
