package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable

data class NativeDailyAssignment(val date: String, val scenarioId: String, val seed: String, val role: String,
    val countryId: String, val electionId: String, val label: String, val flag: String, val roleName: String)

class NativeDaily private constructor() {
    companion object {
        private val pairs = mapOf("US" to listOf("dem", "rep"), "UK" to listOf("lab", "con"),
            "CA" to listOf("lpc", "cpc"), "DE" to listOf("cdu", "spd"),
            "FR" to listOf("ens", "rn"), "AU" to listOf("alp", "lnp"))
        private val names = mapOf("dem" to "the Democrats", "rep" to "the Republicans", "lab" to "Labour",
            "con" to "the Conservatives", "lpc" to "the Liberals", "cpc" to "the Conservatives",
            "cdu" to "CDU/CSU", "spd" to "the SPD", "ens" to "Ensemble", "rn" to "the RN",
            "alp" to "Labor", "lnp" to "the Coalition")

        fun roleName(role: String): String = names[role] ?: role.uppercase()

        fun assignment(dateUTC: String): NativeDailyAssignment {
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(dateUTC))
            // Use the exported registry's order, exactly as web and server do.
            val meta = nativeElections[(Rng.hashSeed("daily:$dateUTC") % nativeElections.size).toInt()]
            val role = pairs.getValue(meta.country)[(Rng.hashSeed("role:$dateUTC:${meta.scenarioId}") % 2).toInt()]
            return NativeDailyAssignment(dateUTC, meta.scenarioId, "daily-$dateUTC", role,
                meta.country, meta.nativeId, meta.label, meta.flag, names.getValue(role))
        }

        fun matches(dateUTC: String, scenarioId: String, seed: Long, role: String): Boolean {
            val today = assignment(dateUTC)
            return today.scenarioId == scenarioId && seed == Rng.hashSeed(today.seed) && role == today.role
        }

        fun matchesUs(dateUTC: String, game: GameState): Boolean = matches(dateUTC,
            nativeElections.firstOrNull { it.country == "US" && it.nativeId == game.scenarioId }?.scenarioId ?: "",
            game.seed, game.playerCandidate.serial)

        fun nextStreak(dateUTC: String, yesterdayUTC: String, lastPlayed: String?, streak: Int): Int =
            when (lastPlayed) { dateUTC -> streak; yesterdayUTC -> streak + 1; else -> 1 }
    }
}

@Serializable
data class NativeScoreSubmission(val scenarioId: String, val difficulty: String, val score: Int,
    val facts: ScoreFacts, val playerSide: String, val electoralVotes: Map<String, Int>? = null,
    val popularShare: Map<String, Double>? = null, val seats: Map<String, Int>? = null,
    val voteShare: Map<String, Double>? = null, val evMargin: Int, val popularVoteMargin: Double,
    val turnsPlayed: Int)
