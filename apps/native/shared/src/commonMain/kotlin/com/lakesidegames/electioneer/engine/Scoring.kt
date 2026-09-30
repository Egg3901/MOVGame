package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.OPPONENT_OF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlinx.serialization.Serializable

// Campaign score: the 0-1000 leaderboard number from the player's
// perspective. Pure and shared by client and server. Port of
// src/engine/scoring.ts.

val DIFFICULTY_MULTIPLIER: Map<String, Double> = mapOf(
    "easy" to 0.7,
    "normal" to 1.0,
    "hard" to 1.5,
)

fun clampScore(x: Double, lo: Double, hi: Double): Double = max(lo, min(hi, x))

// The facts a finished game reduces to for scoring.
@Serializable
data class ScoreFacts(
    // Player's awardable units minus the outright-win threshold (may be negative).
    val unitMargin: Double,
    // Chamber size (538, 650, 343, 630, ...) — normalizes across systems.
    val chamberSize: Int,
    // Player's national vote share minus the top rival's, in points.
    val popularMargin: Double,
    val difficulty: String,
)

fun computeScoreFromFacts(f: ScoreFacts): Int {
    val norm = if (f.chamberSize > 0) 538.0 / f.chamberSize else 1.0
    val evScore = clampScore(f.unitMargin * norm * 3, -300.0, 200.0)
    val popScore = clampScore(f.popularMargin * 10, -100.0, 100.0)
    val mult = DIFFICULTY_MULTIPLIER[f.difficulty] ?: 1.0
    // Math.round in the web engine rounds half points upward. Kotlin round
    // uses ties-to-even and disagrees on otherwise valid leaderboard scores.
    return floor(clampScore((500 + evScore + popScore) * mult, 0.0, 1000.0) + 0.5).toInt()
}

fun usScoreFacts(
    electoralVotes: Map<String, Int>,
    popularShare: Map<String, Double>,
    player: CandidateId,
    difficulty: String,
): ScoreFacts {
    val rival = OPPONENT_OF.getValue(player)
    return ScoreFacts(
        unitMargin = ((electoralVotes[player.serial] ?: 0) - 270).toDouble(),
        chamberSize = 538,
        popularMargin = ((popularShare[player.serial] ?: 0.0) - (popularShare[rival.serial] ?: 0.0)) * 100,
        difficulty = difficulty,
    )
}

fun multipartyScoreFacts(
    seats: Map<String, Int>,
    voteShare: Map<String, Double>,
    player: String,
    majorityThreshold: Int,
    chamberSize: Int,
    difficulty: String,
): ScoreFacts {
    val rivalShare = (listOf(0.0) + voteShare.keys
        .filter { it != player }
        .map { voteShare[it] ?: 0.0 }).max()
    return ScoreFacts(
        unitMargin = ((seats[player] ?: 0) - majorityThreshold).toDouble(),
        chamberSize = chamberSize,
        popularMargin = ((voteShare[player] ?: 0.0) - rivalShare) * 100,
        difficulty = difficulty,
    )
}
