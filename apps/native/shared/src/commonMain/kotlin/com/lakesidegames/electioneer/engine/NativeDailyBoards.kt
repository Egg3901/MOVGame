package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable

@Serializable data class NativeDailyChampion(val rank: Int, val username: String, val wins: Int,
    val podiums: Int, val played: Int, val totalScore: Int)
@Serializable data class NativeDailyChampions(val totalDays: Int, val entries: List<NativeDailyChampion>) {
    companion object {
        fun parse(json: String): NativeDailyChampions? = runCatching { EngineJson.decodeFromString<NativeDailyChampions>(json) }.getOrNull()
    }
}
@Serializable data class NativePlayerRanking(val scenarioId: String, val rank: Int, val score: Int, val difficulty: String)
@Serializable data class NativePlayerRankings(val rankings: List<NativePlayerRanking>) {
    companion object {
        fun parse(json: String): NativePlayerRankings? = runCatching { EngineJson.decodeFromString<NativePlayerRankings>(json) }.getOrNull()
    }
}
