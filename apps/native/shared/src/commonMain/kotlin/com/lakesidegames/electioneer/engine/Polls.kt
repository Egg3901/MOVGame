package com.lakesidegames.electioneer.engine

import kotlin.math.min
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

// Polls shown to the player are a blurred view of the true model: true share
// + sampling noise + a per-pollster house effect. Deterministic for a given
// (game seed, turn, state, pollster) so the UI doesn't reshuffle every render.
// Port of src/engine/polls.ts.
@Serializable
data class Poll(
    val stateId: String,
    val pollster: String,
    // Reported two-party Dem share.
    val demShare: Double,
    val sampleSize: Int,
    // Points.
    val marginOfError: Double,
)

private data class Pollster(val name: String, val house: Double, val n: Int)

private val POLLSTERS = listOf(
    Pollster("Quinnipiac", 0.005, 1100),
    Pollster("Marist", -0.004, 1000),
    Pollster("Siena/NYT", 0.002, 900),
    Pollster("Trafalgar", -0.012, 1050),
    Pollster("Monmouth", 0.003, 800),
)

fun pollState(game: GameState, stateId: String): List<Poll> {
    val st = game.states.find { it.id == stateId }
    if (st == null || st.blocs.isEmpty()) return emptyList()
    val truth = tallyContest(st).demShare
    return POLLSTERS.mapIndexed { i, p ->
        val rng = Rng.createRng("poll:${game.seed}:${game.turn}:$stateId:$i")
        val moe = 1.96 * sqrt(0.25 / p.n) // ~ at 50%
        val sampling = rng.normal(0.0, moe * 0.6) // probability-space noise
        val reported = min(0.99, max(0.01, truth + p.house + sampling))
        Poll(
            stateId = stateId,
            pollster = p.name,
            demShare = reported,
            sampleSize = p.n,
            // +(moe * 100).toFixed(1): round half up at one decimal.
            marginOfError = round(moe * 1000) / 10.0,
        )
    }
}

// A simple poll average across pollsters for a state.
fun pollAverage(game: GameState, stateId: String): Double? {
    val polls = pollState(game, stateId)
    if (polls.isEmpty()) return null
    return polls.sumOf { it.demShare } / polls.size
}

// National poll average = electorate-weighted state poll averages.
fun nationalPoll(game: GameState): Double {
    var num = 0.0
    var den = 0.0
    for (st in game.states) {
        if (st.blocs.isEmpty()) continue
        val avg = pollAverage(game, st.id) ?: continue
        val weight = st.blocs.sumOf { it.size * it.turnoutPropensity }
        num += avg * weight
        den += weight
    }
    return if (den > 0) num / den else 0.5
}
