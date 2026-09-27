package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.UK_BLOCS
import com.lakesidegames.electioneer.content.UK_BLOCS_BY_ID
import com.lakesidegames.electioneer.content.UK_REGIONS_BY_ID
import com.lakesidegames.electioneer.content.UkElectionData
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Builds the UK game's region contests from an election's regional results,
// calibrated so neutral play reproduces that election by construction.
// Port of src/engine/ukSetup.ts. Pure + deterministic, no RNG.

private const val EPS = 1e-9

// Normalize a vote-share vector over the parties present so it sums to 1.
private fun normalizeShare(v: Map<String, Double>): Map<String, Double> {
    var sum = 0.0
    for (x in v.values) sum += max(0.0, x)
    val out = linkedMapOf<String, Double>()
    for (p in v.keys) out[p] = if (sum > 0) max(0.0, v.getValue(p)) / sum else 0.0
    return out
}

// Solve per-bloc appeal vectors so the region's turnout-weighted aggregate
// vote share matches `target`. Each bloc starts at ln(target) + its
// demographic tilt; IPF then nudges a shared correction until the aggregate
// lands on target.
private fun solveRegionBlocs(
    blocs: List<UkBlocIn>,
    target: Map<String, Double>,
): List<StateBloc> {
    val parties = target.keys.toList()
    val lnTarget = linkedMapOf<String, Double>()
    for (p in parties) lnTarget[p] = ln(max(EPS, target.getValue(p)))
    val corr = linkedMapOf<String, Double>()
    for (p in parties) corr[p] = 0.0

    fun appealFor(tilt: Map<String, Double>): Map<String, Double> {
        val a = linkedMapOf<String, Double>()
        for (p in parties) a[p] = lnTarget.getValue(p) + (tilt[p] ?: 0.0) + corr.getValue(p)
        return a
    }

    repeat(16) {
        val agg = linkedMapOf<String, Double>()
        for (p in parties) agg[p] = 0.0
        var totW = 0.0
        for (b in blocs) {
            val sh = softmax(appealFor(b.tilt))
            val w = b.size * b.turnout
            for (p in parties) agg[p] = agg.getValue(p) + w * sh.getValue(p)
            totW += w
        }
        for (p in parties) {
            val a = if (totW > 0) agg.getValue(p) / totW else target.getValue(p)
            corr[p] = corr.getValue(p) + ln(max(EPS, target.getValue(p)) / max(EPS, a))
        }
    }

    return blocs.map { b ->
        val appeal = appealFor(b.tilt)
        val support = softmax(appeal)
        StateBloc(
            blocId = BlocId.fromSerial(b.id),
            size = b.size,
            turnoutPropensity = b.turnout,
            baselineMargin = 0.0, // unused on the multiparty path
            support = support,
            campaignMargin = 0.0,
            enthusiasm = 1.0,
            appeal = appeal.toMutableMap(),
            campaignAppeal = parties.associateWith { 0.0 }.toMutableMap(),
        )
    }
}

private data class UkBlocIn(
    val id: String,
    val size: Double,
    val turnout: Double,
    val tilt: Map<String, Double>,
)

private fun buildRegionContest(regionId: String, res: com.lakesidegames.electioneer.content.RegionResult): StateContest {
    val meta = UK_REGIONS_BY_ID.getValue(regionId)
    val target = normalizeShare(res.v)
    // The election's own seat table defines the region pool (Canada-style), so
    // historic boundary sets flow through without a separate pool map.
    val pool = res.s.values.sum().let { if (it == 0) meta.seats else it }

    // Size each bloc from electorate x (national share x regional profile multiplier).
    val raw = UK_BLOCS.map { b -> Triple(b.id, b.share * (meta.profile?.get(b.id) ?: 1.0), b) }
    val totalShare = raw.sumOf { it.second }
    val blocsIn = raw.map { (id, share, def) ->
        UkBlocIn(
            id = id,
            size = (meta.electorate * share) / totalShare,
            turnout = def.turnoutPropensity,
            tilt = def.tilt,
        )
    }

    return StateContest(
        id = regionId,
        name = meta.name,
        abbr = meta.abbr,
        electoralVotes = 0,
        region = Region.SWING,
        prior2020DemShare = 0.5,
        mediaMarketCost = 1.0,
        battleground = false,
        blocs = solveRegionBlocs(blocsIn, target),
        groundGame = mutableMapOf("dem" to 0.0, "rep" to 0.0),
        momentum = 0.0,
        seats = pool,
        baselineSeats = res.s.toMap(),
        baselineShare = target,
    )
}

fun buildUkRegions(election: UkElectionData): List<StateContest> {
    return election.regions.map { (id, res) -> buildRegionContest(id, res) }
}
