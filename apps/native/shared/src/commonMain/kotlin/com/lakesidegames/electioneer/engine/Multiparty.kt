package com.lakesidegames.electioneer.engine

import kotlin.math.floor
import kotlin.math.max
import kotlinx.serialization.Serializable

// Multiparty vote model + regional seats curve (the UK engine path). Port of
// src/engine/multiparty.ts. Pure + deterministic: no RNG.

// Numerically-stable softmax over an appeal vector → shares summing to 1.
fun softmax(appeals: Map<PartyId, Double>): Map<PartyId, Double> {
    val ids = appeals.keys.toList()
    if (ids.isEmpty()) return emptyMap()
    val max = ids.maxOf { appeals.getValue(it) }
    var sum = 0.0
    val exps = linkedMapOf<PartyId, Double>()
    for (i in ids) {
        val e = kotlin.math.exp(appeals.getValue(i) - max)
        exps[i] = e
        sum += e
    }
    val out = linkedMapOf<PartyId, Double>()
    for (i in ids) out[i] = if (sum > 0) exps.getValue(i) / sum else 1.0 / ids.size
    return out
}

// Live per-party vote shares for a single bloc.
fun blocPartyShares(bloc: StateBloc): Map<PartyId, Double> {
    val appeal = bloc.appeal ?: emptyMap()
    val camp = bloc.campaignAppeal ?: emptyMap()
    val combined = linkedMapOf<PartyId, Double>()
    for (p in appeal.keys) combined[p] = appeal.getValue(p) + (camp[p] ?: 0.0)
    return softmax(combined)
}

@Serializable
data class RegionTally(
    val votesByParty: Map<PartyId, Double>,
    val totalVotes: Double,
    val shareByParty: Map<PartyId, Double>,
)

// Turnout-weighted per-party vote totals for one region.
fun tallyRegion(region: StateContest): RegionTally {
    val votesByParty = linkedMapOf<PartyId, Double>()
    var totalVotes = 0.0
    for (bloc in region.blocs) {
        val weight = bloc.size * bloc.turnoutPropensity * bloc.enthusiasm
        val shares = blocPartyShares(bloc)
        for (p in shares.keys) {
            votesByParty[p] = (votesByParty[p] ?: 0.0) + weight * shares.getValue(p)
        }
        totalVotes += weight
    }
    val shareByParty = linkedMapOf<PartyId, Double>()
    for (p in votesByParty.keys) {
        shareByParty[p] = if (totalVotes > 0) votesByParty.getValue(p) / totalVotes else 0.0
    }
    return RegionTally(votesByParty, totalVotes, shareByParty)
}

// Largest-remainder rounding of fractional seats to integers summing to total.
fun largestRemainder(weights: Map<PartyId, Double>, total: Int): Map<PartyId, Int> {
    val ids = weights.keys.toList()
    val sumW = ids.sumOf { max(0.0, weights.getValue(it)) }
    val out = linkedMapOf<PartyId, Int>()
    if (sumW <= 0 || total <= 0) {
        for (i in ids) out[i] = 0
        return out
    }
    val exact = linkedMapOf<PartyId, Double>()
    var assigned = 0
    for (i in ids) {
        exact[i] = (max(0.0, weights.getValue(i)) / sumW) * total
        out[i] = floor(exact.getValue(i)).toInt()
        assigned += out.getValue(i)
    }
    var remaining = total - assigned
    // Leftover seats to the largest fractional remainders. sortedWith is
    // stable, matching Array.prototype.sort stability.
    val byRemainder = ids
        .map { i -> i to (exact.getValue(i) - floor(exact.getValue(i))) }
        .sortedWith(compareByDescending { it.second })
    for (k in byRemainder.indices) {
        if (remaining <= 0) break
        val id = byRemainder[k].first
        out[id] = out.getValue(id) + 1
        remaining--
    }
    return out
}

// Default FPTP amplification.
const val DEFAULT_SEAT_ELASTICITY = 2.6

// THE SEATS CURVE. Neutral reproduces the region's real seats exactly;
// swings gain/shed seats amplified by elasticity, renormalized to the pool.
fun allocateRegionSeats(
    region: StateContest,
    shareByParty: Map<PartyId, Double>,
): Map<PartyId, Int> {
    val seats = region.seats ?: 0
    val baseSeats = region.baselineSeats ?: emptyMap()
    val baseShare = region.baselineShare ?: emptyMap()
    val elasticity = region.seatElasticity ?: DEFAULT_SEAT_ELASTICITY

    val ids = linkedSetOf<PartyId>()
    ids.addAll(baseSeats.keys)
    ids.addAll(shareByParty.keys)
    val raw = linkedMapOf<PartyId, Double>()
    for (p in ids) {
        val swing = (shareByParty[p] ?: 0.0) - (baseShare[p] ?: 0.0)
        // Clamp at zero, then renormalize below preserves the pool total.
        raw[p] = max(0.0, (baseSeats[p] ?: 0) + elasticity * swing * seats)
    }
    return largestRemainder(raw, seats)
}

@Serializable
data class SeatsResult(
    val seats: Map<PartyId, Int>,
    val voteShare: Map<PartyId, Double>,
    val seatResults: List<SeatResult>,
    val largestParty: PartyId,
    val hung: Boolean,
    val government: Government,
)

// Full multiparty election: vote shares → seats → government call.
fun computeSeatsResult(
    regions: List<StateContest>,
    majority: MajorityRule,
    abstainingParties: List<PartyId> = emptyList(),
    // Optional coalition-compatibility predicate. Omitted: anyone may.
    compatible: ((lead: PartyId, partner: PartyId) -> Boolean)? = null,
): SeatsResult {
    val seats = linkedMapOf<PartyId, Int>()
    val nationalVotes = linkedMapOf<PartyId, Double>()
    var nationalTotal = 0.0
    val seatResults = mutableListOf<SeatResult>()

    for (region in regions) {
        if (region.seats == null || region.blocs.isEmpty()) continue
        val tally = tallyRegion(region)
        val seatsByParty = allocateRegionSeats(region, tally.shareByParty)
        for (p in seatsByParty.keys) seats[p] = (seats[p] ?: 0) + seatsByParty.getValue(p)
        for (p in tally.votesByParty.keys) {
            nationalVotes[p] = (nationalVotes[p] ?: 0.0) + tally.votesByParty.getValue(p)
        }
        nationalTotal += tally.totalVotes

        var winner = ""
        var best = -1
        for (p in seatsByParty.keys) {
            if (seatsByParty.getValue(p) > best) {
                best = seatsByParty.getValue(p)
                winner = p
            }
        }
        seatResults.add(
            SeatResult(
                contestId = region.id,
                name = region.name,
                totalSeats = region.seats,
                seatsByParty = seatsByParty,
                voteShare = tally.shareByParty,
                winner = winner,
            ),
        )
    }

    val voteShare = linkedMapOf<PartyId, Double>()
    for (p in nationalVotes.keys) {
        voteShare[p] = if (nationalTotal > 0) nationalVotes.getValue(p) / nationalTotal else 0.0
    }

    // Largest party by seats.
    var largestParty = ""
    var mostSeats = -1
    for (p in seats.keys) {
        if (seats.getValue(p) > mostSeats) {
            mostSeats = seats.getValue(p)
            largestParty = p
        }
    }

    val government = formGovernment(seats, majority, abstainingParties, largestParty, compatible)
    val hung = government is Government.Hung || government is Government.Minority ||
        government is Government.Coalition || government is Government.ConfidenceSupply

    return SeatsResult(seats, voteShare, seatResults, largestParty, hung, government)
}

// The government-formation call. Effective threshold accounts for abstaining
// parties + the Speaker.
fun formGovernment(
    seats: Map<PartyId, Int>,
    majority: MajorityRule,
    abstainingParties: List<PartyId>,
    largestParty: PartyId,
    compatible: ((lead: PartyId, partner: PartyId) -> Boolean)? = null,
): Government {
    val abstainSeats = abstainingParties.sumOf { seats[it] ?: 0 }
    // Effective Commons size after abstentions; majority is half of that + 1.
    val effectiveTotal = majority.total - abstainSeats
    val threshold = majority.effectiveThreshold ?: (floor(effectiveTotal / 2.0).toInt() + 1)

    val lead = seats[largestParty] ?: 0
    if (lead >= threshold) return Government.Majority(party = largestParty, seats = lead)

    // Most natural coalition among willing partners, biggest first. sortedWith
    // is stable, matching Array.prototype.sort stability.
    val partners = seats.keys
        .filter { it != largestParty && !abstainingParties.contains(it) && (compatible?.invoke(largestParty, it) ?: true) }
        .sortedWith(compareByDescending { seats[it] ?: 0 })

    // A two-party coalition that clears the bar.
    for (partner in partners) {
        if (lead + (seats[partner] ?: 0) >= threshold) {
            // A small partner = confidence-and-supply; a large one = coalition.
            return if ((seats[partner] ?: 0) < lead * 0.2) {
                Government.ConfidenceSupply(
                    lead = largestParty, partner = partner,
                    seats = lead + (seats[partner] ?: 0),
                )
            } else {
                Government.Coalition(
                    parties = listOf(largestParty, partner),
                    seats = lead + (seats[partner] ?: 0),
                )
            }
        }
    }

    // Short even with a partner: minority attempt, or genuinely hung.
    if (lead >= threshold * 0.85) return Government.Minority(party = largestParty, seats = lead)
    return Government.Hung(largest = largestParty)
}
