package com.lakesidegames.electioneer.engine

import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

// Noisy multiparty polls: a blurred view of true regional vote shares.
// Port of src/engine/multipartyPolls.ts.

@Serializable
data class MpPollster(
    val name: String,
    // Additive bias toward the largest party.
    val house: Double,
    val n: Int,
)

val DEFAULT_MP_POLLSTERS: List<MpPollster> = listOf(
    MpPollster("YouGov", 0.004, 1800),
    MpPollster("Ipsos", -0.003, 1500),
    MpPollster("Survation", 0.006, 1200),
    MpPollster("Opinium", -0.005, 1400),
)

@Serializable
data class MpRegionPoll(
    val regionId: String,
    val pollster: String,
    val shareByParty: Map<PartyId, Double>,
    val sampleSize: Int,
    val marginOfError: Double,
)

@Serializable
data class MpNationalPoll(
    val pollster: String,
    val shareByParty: Map<PartyId, Double>,
    val sampleSize: Int,
)

private fun noisyShares(
    truth: Map<PartyId, Double>,
    house: Double,
    moe: Double,
    rng: Rng,
): Map<PartyId, Double> {
    val parties = truth.keys.toList()
    if (parties.isEmpty()) return emptyMap()
    // House effect nudges the current leader (first max wins ties).
    var leader = parties[0]
    for (p in parties.drop(1)) {
        if ((truth[p] ?: 0.0) > (truth[leader] ?: 0.0)) leader = p
    }
    val raw = linkedMapOf<PartyId, Double>()
    var sum = 0.0
    for (p in parties) {
        val bias = if (p == leader) house else -house / max(1.0, (parties.size - 1).toDouble())
        val v = max(0.005, (truth[p] ?: 0.0) + bias + rng.normal(0.0, moe * 0.55))
        raw[p] = v
        sum += v
    }
    val out = linkedMapOf<PartyId, Double>()
    for (p in parties) out[p] = raw.getValue(p) / sum
    return out
}

fun pollRegion(
    seed: Long,
    turn: Int,
    region: StateContest,
    pollsters: List<MpPollster> = DEFAULT_MP_POLLSTERS,
): List<MpRegionPoll> {
    val shareByParty = tallyRegion(region).shareByParty
    return pollsters.mapIndexed { i, p ->
        val rng = Rng.createRng("mppoll:$seed:$turn:${region.id}:$i")
        val moe = 1.96 * sqrt(0.25 / p.n)
        MpRegionPoll(
            regionId = region.id,
            pollster = p.name,
            shareByParty = noisyShares(shareByParty, p.house, moe, rng),
            sampleSize = p.n,
            // +(moe * 100).toFixed(1): round half up at one decimal.
            marginOfError = round(moe * 1000) / 10.0,
        )
    }
}

// Electorate-weighted national poll average across regions.
fun nationalMpPoll(
    seed: Long,
    turn: Int,
    regions: List<StateContest>,
    pollsters: List<MpPollster> = DEFAULT_MP_POLLSTERS,
): Map<PartyId, Double> {
    val agg = linkedMapOf<PartyId, Double>()
    var den = 0.0
    for (region in regions) {
        val polls = pollRegion(seed, turn, region, pollsters)
        if (polls.isEmpty()) continue
        // Average pollsters for this region.
        val avg = linkedMapOf<PartyId, Double>()
        for (poll in polls) {
            for ((p, s) in poll.shareByParty) {
                avg[p] = (avg[p] ?: 0.0) + s / polls.size
            }
        }
        val w = region.blocs.sumOf { it.size * it.turnoutPropensity }
        val weight = if (w == 0.0) 1.0 else w
        for ((p, s) in avg) {
            agg[p] = (agg[p] ?: 0.0) + s * weight
        }
        den += weight
    }
    if (den <= 0) return emptyMap()
    val out = linkedMapOf<PartyId, Double>()
    for ((p, s) in agg) out[p] = s / den
    return out
}
