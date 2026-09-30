package com.lakesidegames.electioneer.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlinx.serialization.Serializable

// Shared multiparty AI for the UK and country engines. Port of
// src/engine/multipartyAi.ts.

@Serializable
data class MpAiConfig(
    // 0..1 budget-efficiency: higher = spends more, smarter target mix.
    val efficiency: Double,
    // 0..1 chance of a misallocation (shuffle targets) per planning pass.
    val mistakeRate: Double,
    // How many close regions the AI considers.
    val foresight: Int,
)

val MP_DIFFICULTY: Map<String, MpAiConfig> = mapOf(
    "easy" to MpAiConfig(efficiency = 0.55, mistakeRate = 0.35, foresight = 3),
    "normal" to MpAiConfig(efficiency = 0.85, mistakeRate = 0.12, foresight = 4),
    "hard" to MpAiConfig(efficiency = 1.0, mistakeRate = 0.04, foresight = 6),
)

// Action verbs shared by UkAction / CountryAction (and the gauntlet bots).
@Serializable
data class MpActionLike(
    val type: String,
    val party: PartyId,
    val regionId: String? = null,
    val spend: Double? = null,
    val mode: String? = null,
    val targetParty: PartyId? = null,
)

data class MpView(
    val regions: List<StateContest>,
    val turn: Int,
    val totalTurns: Int,
    val funds: Double,
    val actions: Int,
    // Optional government-formation context for the final stretch.
    val majority: MajorityRule? = null,
    val abstaining: List<PartyId>? = null,
    val compatible: ((lead: PartyId, partner: PartyId) -> Boolean)? = null,
)

@Serializable
data class MpTarget(
    val region: StateContest,
    val margin: Double,
    val rivalParty: PartyId?,
    // Extra weight from the late-game coalition pass (0 when not applicable).
    val coalitionBoost: Double,
)

// Preserve content order for mathematical ties across JS and native math runtimes.
private fun targetRank(value: Double): Double = floor(value * 1e12 + 0.5)

// Regions ranked by how close the party sits to the local top rival.
fun mpTargets(view: MpView, party: PartyId, max: Int): List<MpTarget> {
    val standing = view.regions.filter { it.baselineShare?.get(party) != null }
    val rows = standing.map { r ->
        val shareByParty = tallyRegion(r).shareByParty
        val mine = shareByParty[party] ?: 0.0
        val rival = max(
            0.0,
            shareByParty.keys.filter { it != party }.map { shareByParty[it] ?: 0.0 }.maxOrNull() ?: 0.0,
        )
        MpTarget(
            region = r,
            margin = mine - rival,
            rivalParty = topRival(shareByParty, party),
            coalitionBoost = 0.0,
        )
    }
    // Winnable = within striking distance; fall back to the closest contests.
    val winnable = rows.filter { it.margin > -0.2 }
    val pool = (if (winnable.isNotEmpty()) winnable else rows).toMutableList()
    // sortedWith is stable, matching Array.prototype.sort stability.
    pool.sortWith(compareBy { targetRank(abs(it.margin)) })
    return pool.take(max)
}

private fun topRival(shareByParty: Map<PartyId, Double>, party: PartyId): PartyId? {
    var best: PartyId? = null
    var bestShare = -1.0
    for ((p, s) in shareByParty) {
        if (p == party) continue
        if (s > bestShare) {
            bestShare = s
            best = p
        }
    }
    return best
}

// Late-campaign coalition pass: bias targets toward coalition math.
private fun applyCoalitionBias(
    view: MpView,
    party: PartyId,
    targets: List<MpTarget>,
    foresight: Int,
): List<MpTarget> {
    if (view.majority == null) return targets
    val turnsLeft = view.totalTurns - view.turn
    if (turnsLeft > 2) return targets

    val abstaining = view.abstaining ?: emptyList()
    val compatible = view.compatible ?: { _: PartyId, _: PartyId -> true }
    val projected = computeSeatsResult(view.regions, view.majority, abstaining, compatible)
    val seats = projected.seats
    val mySeats = seats[party] ?: 0
    val gov = formGovernment(seats, view.majority, abstaining, projected.largestParty, compatible)

    // Already on course for a firm majority: no need.
    if (gov is Government.Majority) return targets

    // Natural partners: compatible parties ranked by seats, largest first.
    val partners = seats.keys
        .filter { it != party && !abstaining.contains(it) && compatible(party, it) }
        .sortedWith(compareByDescending { seats[it] ?: 0 })
    val bestPartner = partners.firstOrNull() ?: return targets

    // Leading rival = largest other party.
    val rival = seats.keys
        .filter { it != party }
        .sortedWith(compareByDescending { seats[it] ?: 0 })
        .firstOrNull()

    // Coalition path is viable if we + partner clear the effective threshold.
    val abstainSeats = abstaining.sumOf { seats[it] ?: 0 }
    val threshold = view.majority.effectiveThreshold
        ?: (kotlin.math.floor((view.majority.total - abstainSeats) / 2.0).toInt() + 1)
    val coalitionSeats = mySeats + (seats[bestPartner] ?: 0)
    val soloGap = threshold - mySeats
    if (mySeats >= threshold) return targets
    if (soloGap <= 15) return targets
    if (coalitionSeats < threshold) return targets

    // Re-score every standing region with a coalition boost, then re-pick top N.
    val standing = view.regions.filter { it.baselineShare?.get(party) != null }
    val rescored = standing.map { r ->
        val shareByParty = tallyRegion(r).shareByParty
        val mine = shareByParty[party] ?: 0.0
        val rivalShare = max(
            0.0,
            shareByParty.keys.filter { it != party }.map { shareByParty[it] ?: 0.0 }.maxOrNull() ?: 0.0,
        )
        val margin = mine - rivalShare
        val partnerShare = shareByParty[bestPartner] ?: 0.0
        val leadingRivalShare = if (rival != null) shareByParty[rival] ?: 0.0 else 0.0
        var boost = 0.0
        if (partnerShare > 0.08 && partnerShare + 0.12 >= rivalShare && margin < 0.05) {
            boost += 0.04 // partner can take or hold this with a push
        }
        if (leadingRivalShare > mine && leadingRivalShare - max(mine, partnerShare) < 0.1) {
            boost += 0.05 // knock the rival off a knife-edge seat
        }
        if (margin > -0.08 && margin < 0.08) boost += 0.02 // still value our own tossups
        MpTarget(
            region = r,
            margin = margin,
            rivalParty = topRival(shareByParty, party),
            coalitionBoost = boost,
        )
    }

    // Sort by (closeness + coalition boost); prefer boosted regions.
    val sorted = rescored.toMutableList()
    sorted.sortWith(compareByDescending { targetRank(-abs(it.margin) + it.coalitionBoost) })
    return sorted.take(foresight)
}

fun mpFocusedActions(
    view: MpView,
    party: PartyId,
    cfg: MpAiConfig = MP_DIFFICULTY.getValue("normal"),
    rng: Rng? = null,
): List<MpActionLike> {
    val targets = applyCoalitionBias(view, party, mpTargets(view, party, cfg.foresight), cfg.foresight)
        .toMutableList()
    if (targets.isEmpty()) return emptyList()

    // Occasional misallocation: shuffle priority (a "mistake").
    if (rng != null && targets.size > 1 && rng.chance(cfg.mistakeRate)) {
        val i = rng.int(0, targets.size - 1)
        val j = rng.int(0, targets.size - 1)
        val tmp = targets[i]
        targets[i] = targets[j]
        targets[j] = tmp
    }

    val slots = view.actions
    val turnsLeft = max(1, view.totalTurns - view.turn)
    val out = mutableListOf<MpActionLike>()
    var funds = view.funds

    if (funds < 2) {
        out.add(MpActionLike(type = "fundraise", party = party))
        funds += 1.5
    }

    // Trailing hard AIs occasionally dig dirt nationally.
    val nationalShare = averageShare(view.regions, party)
    val losing = nationalShare < 0.28
    if (losing && cfg.efficiency > 0.8 && funds >= 2 && slots > 2 &&
        (rng == null || rng.chance(0.15 + cfg.efficiency * 0.2))
    ) {
        out.add(MpActionLike(type = "oppo_research", party = party))
        funds -= 2
    }

    // One paid push per target plus a rally on the closest race.
    out.add(MpActionLike(type = "rally", party = party, regionId = targets[0].region.id))

    for (t in targets) {
        if (out.size >= slots) break
        if (turnsLeft <= 2 && funds >= 1) {
            out.add(MpActionLike(type = "gotv", party = party, regionId = t.region.id))
            funds -= 1
        } else if (funds >= 1.5 && cfg.efficiency >= 0.7) {
            // Disciplined AIs buy a small regional broadcast on their closest race.
            if (out.size < slots - 1 && funds >= 2.5 && t === targets[0]) {
                out.add(
                    MpActionLike(
                        type = "broadcast", party = party, regionId = t.region.id,
                        spend = 1.5,
                        mode = if (t.margin < 0) "contrast" else "positive",
                        targetParty = t.rivalParty,
                    ),
                )
                funds -= 1.5
            } else {
                out.add(MpActionLike(type = "ground_game", party = party, regionId = t.region.id))
                funds -= 1.5
            }
        } else if (funds >= 1.5) {
            out.add(MpActionLike(type = "ground_game", party = party, regionId = t.region.id))
            funds -= 1.5
        } else {
            out.add(MpActionLike(type = "canvass", party = party, regionId = t.region.id))
        }
    }

    // Remaining slots: free canvassing round-robin over the same targets.
    var i = 0
    while (out.size < slots) {
        out.add(MpActionLike(type = "canvass", party = party, regionId = targets[i % targets.size].region.id))
        i++
    }
    return out.take(slots)
}

fun mpScattershotActions(view: MpView, party: PartyId): List<MpActionLike> {
    val standing = view.regions.filter { it.baselineShare?.get(party) != null }
    if (standing.isEmpty()) return emptyList()
    val slots = view.actions
    val out = mutableListOf<MpActionLike>()
    if (view.funds < 1) out.add(MpActionLike(type = "fundraise", party = party))
    var i = 0
    while (out.size < slots) {
        out.add(MpActionLike(type = "canvass", party = party, regionId = standing[i % standing.size].id))
        i++
    }
    return out.take(slots)
}

private fun averageShare(regions: List<StateContest>, party: PartyId): Double {
    var num = 0.0
    var den = 0.0
    for (r in regions) {
        if (r.baselineShare?.get(party) == null) continue
        val tally = tallyRegion(r)
        num += (tally.shareByParty[party] ?: 0.0) * tally.totalVotes
        den += tally.totalVotes
    }
    return if (den > 0) num / den else 0.0
}

// In-game planner used by ukGame / countryGame.
fun planMultipartyAi(
    view: MpView,
    party: PartyId,
    difficulty: String,
    rng: Rng,
): List<MpActionLike> {
    val cfg = MP_DIFFICULTY.getValue(difficulty)
    // Easy AI occasionally goes scattershot (sloppy opponent).
    if (difficulty == "easy" && rng.chance(0.35)) return mpScattershotActions(view, party)
    return mpFocusedActions(view, party, cfg, rng)
}
