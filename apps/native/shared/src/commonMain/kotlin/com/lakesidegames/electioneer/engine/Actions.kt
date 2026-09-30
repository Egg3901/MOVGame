package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.BLOCS
import com.lakesidegames.electioneer.content.OPPONENT_OF
import com.lakesidegames.electioneer.content.staffEffects
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

// All action effects route THROUGH the vote model: they write margin deltas
// into blocs' campaignMargin, plus enthusiasm / momentum / ground-game /
// salience side-effects. Each writes a CauseEntry so every poll move is
// traceable. Port of src/engine/actions.ts.

// Sign convention: a positive margin delta favors Dem. Translate a
// candidate-favoring effect into the right sign here.
private fun favorSign(candidate: CandidateId): Double =
    if (candidate == CandidateId.DEM) 1.0 else -1.0

// Diminishing returns: the more a bloc has already moved, the less each
// additional push does.
private fun saturate(bloc: StateBloc, raw: Double): Double {
    val ceiling = 0.7
    val room = max(0.0, 1 - abs(bloc.campaignMargin) / ceiling)
    val leanResist = 1 / (1 + abs(bloc.baselineMargin) * 1.8)
    return raw * room * leanResist
}

// How well an action lands on a bloc, based on issue alignment & salience.
fun issueAlignment(
    game: GameState,
    candidate: CandidateId,
    blocId: BlocId,
    issueId: IssueId? = null,
): Double {
    val arche = BLOCS[blocId] ?: return 0.5
    val cand = game.candidates.getValue(candidate.serial)
    val issues: List<String> = if (issueId != null) listOf(issueId.serial) else arche.issueWeights.keys.toList()
    var num = 0.0
    var den = 0.0
    for (id in issues) {
        val weight = (arche.issueWeights[id] ?: 0.0) * (game.salience[id] ?: 0.5)
        val ideal = arche.idealPositions[id] ?: 0.0
        val pos = cand.issuePositions[id] ?: 0.0
        val align = 1 - abs(pos - ideal) / 2 // 0..1
        num += weight * align
        den += weight
    }
    return if (den > 0) num / den else 0.5
}

private fun addCause(
    game: GameState,
    bloc: StateBloc,
    state: StateContest,
    cause: String,
    delta: Double,
) {
    bloc.campaignMargin += delta
    game.causes.add(
        CauseEntry(turn = game.turn, stateId = state.id, blocId = bloc.blocId, cause = cause, marginDelta = delta),
    )
}

private fun findState(game: GameState, id: String?): StateContest? =
    game.states.find { it.id == id && it.blocs.isNotEmpty() }

// JS Number.toFixed(1): one decimal, half up, always shows the digit.
internal fun toFixed1(x: Double): String {
    val r = round(x * 10.0) / 10.0
    return if (r == floor(r)) "${r.toLong()}.0" else r.toString()
}

private fun applyAdvertise(game: GameState, action: CampaignAction, rng: Rng, mult: Double = 1.0, actionPower: Double = 1.0) {
    val state = findState(game, action.stateId) ?: return
    val c = action.candidate
    val spend = max(0.0, action.spend ?: 0.0)
    val res = game.resources.getValue(c.serial)
    val actualSpend = min(spend, res.cash)
    res.cash -= actualSpend

    // Track lifetime ad spend (drives the Grassroots achievement).
    val adSpend = game.adSpend ?: mutableMapOf<String, Double>().also { game.adSpend = it }
    adSpend[c.serial] = (adSpend[c.serial] ?: 0.0) + actualSpend

    val mode = action.adMode ?: AdMode.POSITIVE
    val effectiveMillions = actualSpend / 1_000_000 / state.mediaMarketCost
    val adSat = 6.0
    val adReach = adSat * (1 - exp(-effectiveMillions / adSat)) * staffEffects(game, c).adMult
    val fundraisingBoost = 0.85 + game.candidates.getValue(c.serial).traits.fundraisingProwess / 400

    if (mode == AdMode.ISSUE && action.issueId != null) {
        val bump = min(0.12, effectiveMillions * 0.015) * actionPower
        val key = action.issueId.serial
        game.salience[key] = min(1.0, (game.salience[key] ?: 0.5) + bump)
        game.causes.add(
            CauseEntry(
                turn = game.turn, stateId = state.id,
                cause = "Issue ads raised salience of $key", marginDelta = 0.0,
            ),
        )
        return
    }

    val targetBlocs = if (action.blocId != null) state.blocs.filter { it.blocId == action.blocId } else state.blocs

    for (bloc in targetBlocs) {
        val align = issueAlignment(game, c, bloc.blocId)
        val responsiveness = 0.4 + align * 0.6
        val share = if (action.blocId != null) 1.0 else bloc.size / state.blocs.sumOf { it.size }
        var raw = adReach * 0.06 * responsiveness * fundraisingBoost
        if (action.blocId == null) raw *= share * targetBlocs.size // spread across the state
        raw = saturate(bloc, raw)
        // A little variance so repeated ads aren't perfectly predictable.
        raw *= 0.9 + rng.next() * 0.2
        raw *= mult // player difficulty handicap

        if (mode == AdMode.CONTRAST) {
            addCause(game, bloc, state, "Contrast ads in ${state.abbr}", favorSign(c) * raw * 0.6)
            bloc.enthusiasm = max(0.78, bloc.enthusiasm - 0.018)
        } else {
            addCause(game, bloc, state, "Positive ads in ${state.abbr}", favorSign(c) * raw)
        }
    }
    // Going negative dents your own media narrative.
    if (mode == AdMode.CONTRAST) res.mediaNarrative = clamp(res.mediaNarrative - 4, -100.0, 100.0)
}

private fun applyRally(game: GameState, action: CampaignAction, rng: Rng, mult: Double = 1.0) {
    val state = findState(game, action.stateId) ?: return
    val c = action.candidate
    val res = game.resources.getValue(c.serial)

    // The candidate is now campaigning here: drives the map marker.
    val locations = game.locations ?: mutableMapOf<String, String>().also { game.locations = it }
    locations[c.serial] = state.id

    val energy = game.candidates.getValue(c.serial).traits.energy
    val charisma = game.candidates.getValue(c.serial).traits.charisma
    state.momentum = clamp(state.momentum + favorSign(c) * (6 + charisma / 20), -100.0, 100.0)
    res.nationalMomentum = clamp(res.nationalMomentum + 1.5, -100.0, 100.0)

    for (bloc in state.blocs) {
        val align = issueAlignment(game, c, bloc.blocId)
        var raw = 0.05 * (0.5 + align * 0.5) * (0.9 + charisma / 300)
        raw = saturate(bloc, raw) * mult
        addCause(game, bloc, state, "Rally in ${state.abbr}", favorSign(c) * raw)
        bloc.enthusiasm = min(1.25, bloc.enthusiasm + 0.01)
    }

    // Gaffe risk: more likely with low energy.
    val gaffeRisk = max(0.02, (0.18 * (100 - energy)) / 100)
    if (rng.chance(gaffeRisk)) {
        val penalty = 0.04 + rng.next() * 0.05
        for (bloc in state.blocs) {
            addCause(game, bloc, state, "Gaffe at ${state.abbr} rally", -favorSign(c) * penalty)
        }
        res.mediaNarrative = clamp(res.mediaNarrative - 8, -100.0, 100.0)
    }
}

private fun applySurrogate(game: GameState, action: CampaignAction, rng: Rng, mult: Double = 1.0) {
    val state = findState(game, action.stateId) ?: return
    val c = action.candidate
    val res = game.resources.getValue(c.serial)
    val cost = 250_000.0
    if (res.cash < cost) return
    res.cash -= cost
    state.momentum = clamp(state.momentum + favorSign(c) * 2, -100.0, 100.0)
    for (bloc in state.blocs) {
        var raw = 0.018 * (0.6 + issueAlignment(game, c, bloc.blocId) * 0.4)
        raw = saturate(bloc, raw) * (0.9 + rng.next() * 0.2) * mult
        addCause(game, bloc, state, "Surrogate visit to ${state.abbr}", favorSign(c) * raw)
    }
}

private fun applyFundraise(game: GameState, action: CampaignAction, rng: Rng) {
    val c = action.candidate
    val res = game.resources.getValue(c.serial)
    val trait = game.candidates.getValue(c.serial).traits.fundraisingProwess
    val momentumBonus = 1 + max(0.0, res.nationalMomentum) / 200
    val st = findState(game, action.stateId)
    val sizeMult = if (st != null) 0.6 + min(1.1, st.electoralVotes / 28.0) else 1.0
    val haul = (8_000_000 + trait * 120_000) * momentumBonus * sizeMult * (0.85 + rng.next() * 0.3) *
        staffEffects(game, c).fundraiseMult
    res.cash += haul
    val fundsRaised = game.fundsRaised ?: mutableMapOf<String, Double>().also { game.fundsRaised = it }
    fundsRaised[c.serial] = (fundsRaised[c.serial] ?: 0.0) + haul
    game.causes.add(
        CauseEntry(
            turn = game.turn,
            cause = if (st != null) "Fundraising haul in ${st.abbr} (+\$${toFixed1(haul / 1_000_000)}M)"
            else "Fundraising haul (+\$${toFixed1(haul / 1_000_000)}M)",
            marginDelta = 0.0,
        ),
    )
}

private fun applyGroundGame(game: GameState, action: CampaignAction) {
    val state = findState(game, action.stateId) ?: return
    val c = action.candidate
    val res = game.resources.getValue(c.serial)
    val cost = 1_500_000.0
    if (res.cash < cost || res.staffCapacity < 1) return
    res.cash -= cost
    res.staffCapacity -= 1
    state.groundGame[c.serial] = min(1.0, (state.groundGame[c.serial] ?: 0.0) + 0.2)
    game.causes.add(
        CauseEntry(
            turn = game.turn, stateId = state.id,
            cause = "Built field offices in ${state.abbr}", marginDelta = 0.0,
        ),
    )
}

private fun applyGotv(game: GameState, action: CampaignAction, mult: Double = 1.0) {
    val state = findState(game, action.stateId) ?: return
    val c = action.candidate
    val res = game.resources.getValue(c.serial)
    val cost = 1_000_000.0
    if (res.cash < cost) return
    res.cash -= cost
    val ground = state.groundGame[c.serial] ?: 0.0
    if (ground <= 0.01) {
        // No infrastructure: wasted money (rewards earlier investment).
        game.causes.add(
            CauseEntry(
                turn = game.turn, stateId = state.id,
                cause = "GOTV in ${state.abbr} fell flat (no field operation)", marginDelta = 0.0,
            ),
        )
        return
    }
    for (bloc in state.blocs) {
        val propensity = 1 - bloc.turnoutPropensity // more upside for low-turnout blocs
        val raw = saturate(bloc, 0.12 * ground * (0.5 + propensity)) * mult
        addCause(game, bloc, state, "GOTV mobilization in ${state.abbr}", favorSign(c) * raw)
        bloc.enthusiasm = min(1.3, bloc.enthusiasm + 0.03 * ground)
    }
}

private fun applyOppoResearch(game: GameState, action: CampaignAction, rng: Rng, mult: Double = 1.0) {
    val c = action.candidate
    val opp = OPPONENT_OF.getValue(c)
    val res = game.resources.getValue(c.serial)
    val cost = 2_000_000.0
    if (res.cash < cost) return
    res.cash -= cost
    // A rapid-response director on the TARGET's staff blunts incoming hits.
    val success = rng.chance(max(0.15, 0.55 - staffEffects(game, opp).oppoShield))
    val sign = favorSign(c)
    if (success) {
        for (st in game.states) {
            if (st.blocs.isEmpty()) continue
            for (bloc in st.blocs) {
                if (bloc.blocId == BlocId.COLLEGE_WHITE || bloc.blocId == BlocId.SUBURBAN_WOMEN) {
                    addCause(
                        game, bloc, st,
                        "Scandal lands on ${game.candidates.getValue(opp.serial).shortName}",
                        sign * saturate(bloc, 0.05) * mult,
                    )
                }
            }
        }
        res.mediaNarrative = clamp(res.mediaNarrative + 6, -100.0, 100.0)
    } else {
        // Backfire: looks desperate.
        res.mediaNarrative = clamp(res.mediaNarrative - 10, -100.0, 100.0)
        for (st in game.states) {
            if (st.blocs.isEmpty()) continue
            for (bloc in st.blocs) {
                if (bloc.blocId == BlocId.SUBURBAN_WOMEN) {
                    addCause(
                        game, bloc, st, "Oppo dump backfires as \"desperate\"",
                        -sign * saturate(bloc, 0.03),
                    )
                }
            }
        }
    }
}

private fun applyDebatePrep(game: GameState, action: CampaignAction) {
    val c = action.candidate
    val bonus = 8 + staffEffects(game, c).debatePrepBonus
    val traits = game.candidates.getValue(c.serial).traits
    traits.debatePrep = min(100.0, traits.debatePrep + bonus)
    game.causes.add(
        CauseEntry(turn = game.turn, cause = "Debate prep (+8 debate readiness)", marginDelta = 0.0),
    )
}

private fun applyPolicyPrep(game: GameState, action: CampaignAction) {
    val c = action.candidate
    val traits = game.candidates.getValue(c.serial).traits
    traits.policyKnowledge = min(100.0, traits.policyKnowledge + 8)
    game.causes.add(
        CauseEntry(turn = game.turn, cause = "Policy prep (+8 policy command)", marginDelta = 0.0),
    )
}

private fun applyIssuePivot(game: GameState, action: CampaignAction, mult: Double = 1.0) {
    val c = action.candidate
    val issueId = action.issueId ?: return
    val newPosition = action.newPosition ?: return
    val cand = game.candidates.getValue(c.serial)
    val old = cand.issuePositions[issueId.serial] ?: 0.0
    val next = clamp(newPosition, -1.0, 1.0)
    val move = next - old
    if (abs(move) < 0.01) return
    cand.issuePositions[issueId.serial] = next

    // Recompute alignment-driven shift for every bloc that cares about the issue.
    for (st in game.states) {
        if (st.blocs.isEmpty()) continue
        for (bloc in st.blocs) {
            val arche = BLOCS[bloc.blocId] ?: continue
            val weight = (arche.issueWeights[issueId.serial] ?: 0.0) * (game.salience[issueId.serial] ?: 0.5)
            if (weight < 0.05) continue
            val ideal = arche.idealPositions[issueId.serial] ?: 0.0
            val before = 1 - abs(old - ideal) / 2
            val after = 1 - abs(next - ideal) / 2
            val delta = (after - before) * weight * 0.5 * mult
            addCause(game, bloc, st, "Pivot on ${issueId.serial}", favorSign(c) * delta)
        }
    }
    // Flip-flopper tax with suburban / college voters who prize authenticity.
    val tax = abs(move) * 0.04
    for (st in game.states) {
        if (st.blocs.isEmpty()) continue
        for (bloc in st.blocs) {
            if (bloc.blocId == BlocId.COLLEGE_WHITE || bloc.blocId == BlocId.SUBURBAN_WOMEN) {
                addCause(
                    game, bloc, st, "\"Flip-flopper\" cost on ${issueId.serial}",
                    -favorSign(c) * tax * 0.3,
                )
            }
        }
    }
}

fun applyAction(game: GameState, action: CampaignAction, rng: Rng, actionPower: Double = 1.0,
                planBonuses: List<PlanBonus> = emptyList()) {
    // Invalid or unaffordable moves preserve their slot. A deliberate GOTV
    // attempt without field offices still costs a slot and reports its failure.
    val res = game.resources.getValue(action.candidate.serial)
    if (res.actions < 1) return
    val firstNewCause = game.causes.size
    // The player's campaigning is amplified by the difficulty handicap (1.0 for
    // the AI and on hard); it scales persuasion only, not cash/infra/prep.
    val mult = (if (action.candidate == game.playerCandidate) (game.playerEdge ?: 1.0) else 1.0) * actionPower
    when (action.type) {
        ActionType.ADVERTISE -> applyAdvertise(game, action, rng, mult, actionPower)
        ActionType.RALLY -> applyRally(game, action, rng, mult)
        ActionType.SURROGATE -> applySurrogate(game, action, rng, mult)
        ActionType.FUNDRAISE -> applyFundraise(game, action, rng)
        ActionType.GROUND_GAME -> applyGroundGame(game, action)
        ActionType.GOTV -> applyGotv(game, action, mult)
        ActionType.OPPO_RESEARCH -> applyOppoResearch(game, action, rng, mult)
        ActionType.DEBATE_PREP -> applyDebatePrep(game, action)
        ActionType.POLICY_PREP -> applyPolicyPrep(game, action)
        ActionType.ISSUE_PIVOT -> applyIssuePivot(game, action, mult)
    }
    if (game.causes.size == firstNewCause) return
    for (index in firstNewCause until game.causes.size) {
        game.causes[index] = game.causes[index].copy(actor = action.candidate)
    }
    res.actions -= 1
    for (bonus in planBonuses) game.causes.add(bonus.cause(game.turn, action.stateId, action.candidate))
}

fun clamp(x: Double, lo: Double, hi: Double): Double = max(lo, min(hi, x))
fun clamp(x: Int, lo: Int, hi: Int): Int = max(lo, min(hi, x))
