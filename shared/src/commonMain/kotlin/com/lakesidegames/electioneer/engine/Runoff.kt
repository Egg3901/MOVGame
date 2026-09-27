package com.lakesidegames.electioneer.engine

import kotlin.math.max
import kotlinx.serialization.Serializable

// Two-round runoff (France and future two-round systems). Port of
// src/engine/runoff.ts.

// First-round shares by party; need not sum exactly to 1.
typealias RoundShares = Map<PartyId, Double>

// For each eliminated party, the fraction of its voters going to each
// finalist (and optionally "_abstain"). Rows should sum to <= 1.
typealias TransferMatrix = Map<PartyId, Map<String, Double>>

@Serializable
data class RunoffResult(
    val finalists: Pair<PartyId, PartyId>,
    val eliminated: List<PartyId>,
    // Second-round shares among ballots cast (renormalized).
    val secondRound: RoundShares,
    // Raw transferred vote mass before renormalization.
    val transferred: RoundShares,
    val abstentionRate: Double,
)

// Pick the top two candidates by first-round share. Stable: ties keep
// content order, matching Array.prototype.sort stability.
fun topTwo(firstRound: RoundShares): Pair<PartyId, PartyId> {
    val ranked = firstRound.entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }
    if (ranked.size < 2) {
        throw IllegalArgumentException("Runoff needs at least two candidates with votes")
    }
    return ranked[0].key to ranked[1].key
}

// Advance a first-round result to a second-round runoff using transfers.
fun advanceToRunoff(
    firstRound: RoundShares,
    transfers: TransferMatrix,
    finalists: Pair<PartyId, PartyId>? = null,
): RunoffResult {
    val pair = finalists ?: topTwo(firstRound)
    val (a, b) = pair
    val eliminated = firstRound.keys.filter { it != a && it != b }

    val mass = mutableMapOf(a to (firstRound[a] ?: 0.0), b to (firstRound[b] ?: 0.0))
    var abstain = 0.0

    for (elim in eliminated) {
        val row = transfers[elim] ?: emptyMap()
        val pool = firstRound[elim] ?: 0.0
        var placed = 0.0
        for (dest in listOf(a, b)) {
            val frac = max(0.0, row[dest] ?: 0.0)
            mass[dest] = (mass[dest] ?: 0.0) + pool * frac
            placed += frac
        }
        val explicitAbstain = max(0.0, row["_abstain"] ?: 0.0)
        val remainder = max(0.0, 1 - placed - explicitAbstain)
        abstain += pool * (explicitAbstain + remainder)
    }

    val cast = (mass[a] ?: 0.0) + (mass[b] ?: 0.0)
    val secondRound: RoundShares = if (cast > 0) {
        mapOf(a to (mass[a] ?: 0.0) / cast, b to (mass[b] ?: 0.0) / cast)
    } else {
        mapOf(a to 0.5, b to 0.5)
    }

    val totalFirst = firstRound.values.sum()
    return RunoffResult(
        finalists = pair,
        eliminated = eliminated,
        secondRound = secondRound,
        transferred = mass,
        abstentionRate = abstain / (if (totalFirst == 0.0) 1.0 else totalFirst),
    )
}

// Apply a national runoff result onto per-region first-round shares.
fun regionalRunoff(
    regions: Map<String, RoundShares>,
    transfers: TransferMatrix,
    finalists: Pair<PartyId, PartyId>? = null,
): Pair<Pair<PartyId, PartyId>, Map<String, RoundShares>> {
    // National aggregate picks the finalists when not supplied.
    val national = mutableMapOf<PartyId, Double>()
    for (shares in regions.values) {
        for ((p, s) in shares) national[p] = (national[p] ?: 0.0) + s
    }
    val pair = finalists ?: topTwo(national)
    val out = mutableMapOf<String, RoundShares>()
    for ((rid, shares) in regions) {
        out[rid] = advanceToRunoff(shares, transfers, pair).secondRound
    }
    return pair to out
}
