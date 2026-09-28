package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.BLOCS
import com.lakesidegames.electioneer.content.GENERIC_DEBATES
import com.lakesidegames.electioneer.content.HISTORICAL_EVENTS
import com.lakesidegames.electioneer.content.OPPONENT_OF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

// AI planner: target ranking and weekly action plans. Port of src/engine/ai.ts.
// Issue iteration uses IssueId.entries, which matches the TS ISSUE_IDS order.

// Is a scheduled debate coming within the next `turns` weeks?
private fun debateWithin(game: GameState, turns: Int): Boolean {
    val historical = (game.eventMode ?: EventMode.HISTORICAL) == EventMode.HISTORICAL
    val deck = if (historical) {
        HISTORICAL_EVENTS[game.scenarioId ?: "2020"] ?: HISTORICAL_EVENTS.getValue("2020")
    } else {
        GENERIC_DEBATES
    }
    return deck.any { e ->
        e.isDebate && e.trigger is EventTrigger.Scheduled &&
            (e.trigger as EventTrigger.Scheduled).turn > game.turn &&
            (e.trigger as EventTrigger.Scheduled).turn <= game.turn + turns
    }
}

// The issue whose higher salience would most help the AI.
private fun bestIssueForAi(game: GameState, ai: CandidateId): IssueId? {
    var best: IssueId? = null
    var bestScore = 0.05
    for (issueId in IssueId.entries) {
        val headroom = 1 - (game.salience[issueId.serial] ?: 0.5)
        if (headroom < 0.12) continue
        var adv = 0.0
        for (blocId in BLOCS.keys) adv += issueAlignment(game, ai, blocId, issueId) - 0.5
        val score = adv * headroom
        if (score > bestScore) {
            bestScore = score
            best = issueId
        }
    }
    return best
}

@Serializable
data class AiConfig(
    // 0..1 budget-efficiency multiplier; higher = spends more, smarter.
    val efficiency: Double,
    // 0..1 chance of a misallocation per planning pass.
    val mistakeRate: Double,
    // How many target states the AI considers (foresight depth).
    val foresight: Int,
    // Scales AI persuasion while keeping its cash and slot costs intact.
    val actionPower: Double = 1.0,
)

val DIFFICULTY: Map<String, AiConfig> = mapOf(
    "easy" to AiConfig(efficiency = 0.55, mistakeRate = 0.3, foresight = 3, actionPower = 0.45),
    "normal" to AiConfig(efficiency = 0.8, mistakeRate = 0.15, foresight = 5, actionPower = 0.53),
    "hard" to AiConfig(efficiency = 1.0, mistakeRate = 0.05, foresight = 8, actionPower = 1.0),
)

private fun aiShareOf(demShare: Double, ai: CandidateId): Double =
    if (ai == CandidateId.DEM) demShare else 1 - demShare

// Competitive band (distance from 50/50) flexes with the scoreboard.
fun competitiveBand(evMargin: Int): Double {
    if (evMargin < -80) return 0.11
    if (evMargin < -40) return 0.09
    if (evMargin < -15) return 0.07
    if (evMargin > 60) return 0.03
    if (evMargin > 30) return 0.04
    return 0.055
}

@Serializable
data class Target(
    val stateId: String,
    val ev: Int,
    val aiShare: Double,
    val mediaCost: Double,
    // Expected EV flipped per $1M of media, given current closeness.
    val evPerDollar: Double,
    val priority: Double,
)

fun rankTargets(game: GameState, ai: CandidateId, cfg: AiConfig): List<Target> {
    val proj = projectElection(game)
    val evMargin = proj.ev.getValue(ai.serial) - proj.ev.getValue(OPPONENT_OF.getValue(ai).serial)
    val band = competitiveBand(evMargin)
    val targets = mutableListOf<Target>()
    for (sr in proj.contests) {
        val st = game.states.find { it.id == sr.stateId }
        if (st == null || st.blocs.isEmpty()) continue
        val aiShare = aiShareOf(sr.demShare, ai)
        val dist = abs(sr.demShare - 0.5)
        if (dist > band) continue
        val closeness = 1 - dist * 2 // 1 = coin flip
        // Soft diminishing returns past ~$8M media cost.
        val costScale = max(0.35, min(1.4, 8 / max(1.0, st.mediaMarketCost)))
        val evPerDollar = (st.electoralVotes * closeness * costScale) / max(0.5, st.mediaMarketCost)
        targets.add(
            Target(
                stateId = st.id, ev = st.electoralVotes, aiShare = aiShare,
                mediaCost = st.mediaMarketCost, evPerDollar = evPerDollar, priority = evPerDollar,
            ),
        )
    }
    // sortedWith is stable, matching Array.prototype.sort stability.
    targets.sortByDescending { it.priority }
    return targets.take(cfg.foresight)
}

// Cap how many non-ad "overhead" actions can eat the weekly pool.
private fun overheadCap(cfg: AiConfig, slots: Int): Int {
    if (cfg.efficiency >= 0.95) return min(2, max(1, floor(slots * 0.2).toInt()))
    if (cfg.efficiency >= 0.75) return min(3, max(1, floor(slots * 0.3).toInt()))
    return min(4, max(2, floor(slots * 0.4).toInt()))
}

// Builds the AI's action list for this turn.
fun planAiActions(game: GameState, rng: Rng, cfg: AiConfig): List<CampaignAction> {
    val ai = OPPONENT_OF.getValue(game.playerCandidate)
    val res = game.resources.getValue(ai.serial)
    val actions = mutableListOf<CampaignAction>()
    val targets = rankTargets(game, ai, cfg).toMutableList()
    if (targets.isEmpty()) return actions

    // Occasional misallocation: shuffle the priority order (a "mistake").
    if (rng.chance(cfg.mistakeRate)) {
        val i = rng.int(0, targets.size - 1)
        val j = rng.int(0, targets.size - 1)
        val tmp = targets[i]
        targets[i] = targets[j]
        targets[j] = tmp
    }

    val slots = res.actions
    var budget = slots
    val turnsLeft = game.totalTurns - game.turn
    val maxOverhead = overheadCap(cfg, slots)
    // Pace ad spend across remaining weeks.
    val adBudget = (res.cash / max(2, turnsLeft).toDouble()) * (0.85 + cfg.efficiency * 0.35)
    val prioritySum = targets.sumOf { it.priority }.let { if (it == 0.0) 1.0 else it }
    val proj = projectElection(game)
    val losing = proj.ev.getValue(ai.serial) < proj.ev.getValue(OPPONENT_OF.getValue(ai).serial) - 20

    // Persuasion first (ads + rallies + late GOTV).
    val persuasion = mutableListOf<CampaignAction>()
    for (t in targets) {
        persuasion.add(CampaignAction(type = ActionType.RALLY, candidate = ai, stateId = t.stateId))
        val spend = (adBudget * t.priority) / prioritySum
        if (spend >= 200_000 && res.cash >= spend) {
            val mode = if (t.aiShare < 0.49) AdMode.CONTRAST else AdMode.POSITIVE
            persuasion.add(
                CampaignAction(
                    type = ActionType.ADVERTISE, candidate = ai, stateId = t.stateId,
                    adMode = mode, spend = spend,
                ),
            )
        }
    }
    // Issue ad on the AI's best frame when there's cash and a clear issue edge.
    if (res.cash > 6_000_000 && rng.chance(0.2 + cfg.efficiency * 0.3)) {
        val issueId = bestIssueForAi(game, ai)
        if (issueId != null) {
            persuasion.add(
                0,
                CampaignAction(
                    type = ActionType.ADVERTISE, candidate = ai, stateId = targets[0].stateId,
                    adMode = AdMode.ISSUE, issueId = issueId, spend = 6_000_000.0,
                ),
            )
        }
    }
    if (turnsLeft <= 2) {
        for (t in targets.take(3)) {
            val st = game.states.find { it.id == t.stateId }
            if (st != null && (st.groundGame[ai.serial] ?: 0.0) > 0.05) {
                persuasion.add(CampaignAction(type = ActionType.GOTV, candidate = ai, stateId = t.stateId))
            }
        }
    }

    // Reserve enough slots that overhead can't starve persuasion.
    val persuasionReserve = min(persuasion.size, max(ceil(slots * 0.55).toInt(), slots - maxOverhead))
    for (a in persuasion) {
        if (actions.size >= persuasionReserve || budget <= 0) break
        actions.add(a)
        budget -= 1
    }

    // Overhead into the remaining cap.
    var overheadUsed = 0
    fun canOverhead() = overheadUsed < maxOverhead && budget > 0

    val cashFloor = 30_000_000 * min(4, max(1, turnsLeft))
    if (res.cash < cashFloor && canOverhead()) {
        val richest = targets.toList().sortedByDescending { it.ev }[0]
        actions.add(CampaignAction(type = ActionType.FUNDRAISE, candidate = ai, stateId = richest.stateId))
        budget -= 1
        overheadUsed += 1
    }
    if (losing && res.cash > 2_000_000 && canOverhead() && rng.chance(0.2 + cfg.efficiency * 0.25)) {
        actions.add(CampaignAction(type = ActionType.OPPO_RESEARCH, candidate = ai))
        budget -= 1
        overheadUsed += 1
    }
    // Ground game early only. Cap at one per turn.
    if (turnsLeft > 3 && res.staffCapacity > 0 && canOverhead()) {
        actions.add(CampaignAction(type = ActionType.GROUND_GAME, candidate = ai, stateId = targets[0].stateId))
        budget -= 1
        overheadUsed += 1
    }
    if (debateWithin(game, 2) && canOverhead()) {
        val t = game.candidates.getValue(ai.serial).traits
        if (min(t.debatePrep, t.policyKnowledge) < 86 && rng.chance(0.5 + cfg.efficiency * 0.45)) {
            actions.add(
                CampaignAction(
                    type = if (t.debatePrep <= t.policyKnowledge) ActionType.DEBATE_PREP else ActionType.POLICY_PREP,
                    candidate = ai,
                ),
            )
            budget -= 1
            overheadUsed += 1
        }
    }

    // Fill leftover slots with more persuasion (never more overhead).
    // Referential check matches TS Array.includes identity semantics.
    for (a in persuasion) {
        if (budget <= 0) break
        if (actions.any { it === a }) continue
        actions.add(a)
        budget -= 1
    }

    return actions.take(slots)
}
