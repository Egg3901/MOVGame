package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.EVENTS_BY_ID
import com.lakesidegames.electioneer.content.GENERIC_DEBATES
import com.lakesidegames.electioneer.content.GENERIC_EVENTS
import com.lakesidegames.electioneer.content.HISTORICAL_EVENTS
import com.lakesidegames.electioneer.content.OPPONENT_OF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

// Events: scheduled beats, stochastic draws, debates, and AI resolution.
// Port of src/engine/events.ts.

private fun favorSign(candidate: CandidateId): Double =
    if (candidate == CandidateId.DEM) 1.0 else -1.0

// The scheduled-event decks author their turn numbers against the 9-week
// default campaign. Remap each literal turn N onto the actual campaign length
// at queue time: effectiveTurn = round(N * totalTurns / 9), clamped to
// [0, totalTurns - 1]. Collisions nudge later beats to the next free turn.
private const val DEFAULT_TOTAL_TURNS = 9

fun scheduledTurnMap(deck: List<GameEvent>, totalTurns: Int): Map<String, Int> {
    val map = linkedMapOf<String, Int>()
    val scheduled = deck.filter {
        it.trigger is EventTrigger.Scheduled
    }
    if (totalTurns == DEFAULT_TOTAL_TURNS) {
        // No-op: preserve the authored turns exactly.
        for (e in scheduled) map[e.id] = (e.trigger as EventTrigger.Scheduled).turn
        return map
    }
    val maxTurn = max(0, totalTurns - 1)
    // Assign in calendar order so the remap keeps the authored sequence.
    val ordered = scheduled.sortedBy { (it.trigger as EventTrigger.Scheduled).turn }
    val used = mutableSetOf<Int>()
    var lastTurn = -1
    for (e in ordered) {
        val authored = (e.trigger as EventTrigger.Scheduled).turn
        val base = round(authored * totalTurns / DEFAULT_TOTAL_TURNS.toDouble()).toInt()
        // Clamp, and never move a later beat earlier than an earlier one.
        var t = clamp(max(base, lastTurn), 0, maxTurn)
        while (t in used && t < maxTurn) t++
        map[e.id] = t
        used.add(t)
        lastTurn = t
    }
    return map
}

// Weeks until the next scheduled debate (0 = this week, null = none left).
fun turnsUntilDebate(game: GameState): Int? {
    val historical = (game.eventMode ?: EventMode.HISTORICAL) == EventMode.HISTORICAL
    val deck = if (historical) {
        HISTORICAL_EVENTS[game.scenarioId ?: "2020"] ?: HISTORICAL_EVENTS.getValue("2020")
    } else {
        GENERIC_DEBATES
    }
    val turnFor = scheduledTurnMap(deck, game.totalTurns)
    var best: Int? = null
    for (e in deck) {
        if (e.isDebate && e.trigger is EventTrigger.Scheduled) {
            val effTurn = turnFor[e.id] ?: (e.trigger as EventTrigger.Scheduled).turn
            if (effTurn >= game.turn) {
                val dt = effTurn - game.turn
                if (best == null || dt < best) best = dt
            }
        }
    }
    return best
}

// Debate readiness, 0..100: innate talent plus the two prep tracks.
fun debateReadiness(game: GameState, candidate: CandidateId): Double {
    val t = game.candidates.getValue(candidate.serial).traits
    return 0.4 * t.debatingSkill + 0.3 * t.debatePrep + 0.3 * t.policyKnowledge
}

// Readiness multipliers centered so readiness 50 gives (1, 1).
private data class DebateMultipliers(val gain: Double, val cost: Double)

private fun debateMultipliers(readiness: Double): DebateMultipliers {
    val edge = clamp((readiness - 50) / 50, -1.0, 1.0) // -1..+1
    return DebateMultipliers(
        gain = clamp(1 + edge * 0.4, 0.6, 1.4),
        cost = clamp(1 - edge * 0.4, 0.6, 1.4),
    )
}

// Scales a debate choice's effect by performance: favorable components by
// gain, costs by cost. Non-performance bits (cash, salience) pass through.
private fun scaleDebateEffect(effect: EventEffect, gain: Double, cost: Double): EventEffect {
    fun m(v: Double) = if (v >= 0) v * gain else v * cost
    return effect.copy(
        blocDeltas = effect.blocDeltas?.map { bd ->
            bd.copy(
                margin = bd.margin?.let { m(it) },
                enthusiasm = bd.enthusiasm?.let { m(it) },
            )
        },
        momentum = effect.momentum?.let { m(it) },
        narrative = effect.narrative?.let { m(it) },
    )
}

// Applies one event choice's effects in the answering candidate's favor.
fun applyEventEffect(
    game: GameState,
    effect: EventEffect,
    beneficiary: CandidateId,
    sourceTitle: String,
) {
    val sign = favorSign(beneficiary)

    if (effect.blocDeltas != null) {
        for (bd in effect.blocDeltas) {
            val margin = (bd.margin ?: 0.0) * sign
            for (st in game.states) {
                if (st.blocs.isEmpty()) continue
                val bloc = st.blocs.find { it.blocId == bd.blocId } ?: continue
                if (margin != 0.0) {
                    bloc.campaignMargin += margin
                    game.causes.add(
                        CauseEntry(
                            turn = game.turn, stateId = st.id, blocId = bloc.blocId,
                            cause = sourceTitle, marginDelta = margin,
                        ),
                    )
                }
                if (bd.enthusiasm != null && bd.enthusiasm != 0.0) {
                    bloc.enthusiasm = clamp(bloc.enthusiasm + bd.enthusiasm, 0.7, 1.4)
                }
            }
        }
    }

    if (effect.favorability != null) {
        for ((blocId, value) in effect.favorability) {
            val margin = (value ?: 0.0) * sign * 0.5
            for (st in game.states) {
                val bloc = st.blocs.find { it.blocId.serial == blocId }
                if (bloc != null) bloc.campaignMargin += margin
            }
        }
    }

    if (effect.salienceDeltas != null) {
        for ((issueId, delta) in effect.salienceDeltas) {
            game.salience[issueId] = clamp((game.salience[issueId] ?: 0.5) + (delta ?: 0.0), 0.0, 1.0)
        }
    }

    if (effect.positionShifts != null) {
        val positions = game.candidates.getValue(beneficiary.serial).issuePositions
        for ((issueId, delta) in effect.positionShifts) {
            positions[issueId] = clamp((positions[issueId] ?: 0.0) + (delta ?: 0.0), -1.0, 1.0)
        }
    }

    val res = game.resources.getValue(beneficiary.serial)
    if (effect.momentum != null) res.nationalMomentum = clamp(res.nationalMomentum + effect.momentum, -100.0, 100.0)
    if (effect.cash != null) res.cash += effect.cash
    if (effect.narrative != null) res.mediaNarrative = clamp(res.mediaNarrative + effect.narrative, -100.0, 100.0)
}

// Whether a choice is available to a candidate (trait gates).
fun choiceAvailable(game: GameState, candidate: CandidateId, choice: EventChoice): Boolean {
    if (choice.side != null && choice.side != candidate) return false // wrong ticket's option
    val req = choice.requires ?: return true
    if (req.trait != null && req.min != null) {
        return game.candidates.getValue(candidate.serial).traits[req.trait] >= req.min
    }
    return true
}

// Resolves a single pending event for one candidate with a chosen option.
// Returns the narrated result, or null when unresolvable.
fun resolveEvent(
    game: GameState,
    eventId: String,
    choiceId: String,
    beneficiary: CandidateId,
): String? {
    val event = EVENTS_BY_ID[eventId] ?: return null
    val choice = event.choices.find { it.id == choiceId } ?: return null
    if (!choiceAvailable(game, beneficiary, choice)) return null

    // Debates resolve through the performance model; everything else as authored.
    var effect = choice.effects
    if (event.isDebate) {
        val (gain, cost) = debateMultipliers(debateReadiness(game, beneficiary))
        effect = scaleDebateEffect(effect, gain, cost)
    }
    applyEventEffect(game, effect, beneficiary, "${event.title}: ${choice.text}")
    if (!game.firedEventIds.contains("$eventId:${beneficiary.serial}")) {
        game.firedEventIds.add("$eventId:${beneficiary.serial}")
    }
    game.pendingEvents = game.pendingEvents
        .filter { !(it.eventId == eventId && it.forCandidate == beneficiary) }
        .toMutableList()
    return choice.resultText
}

private fun alreadyFired(game: GameState, eventId: String, candidate: CandidateId): Boolean =
    game.firedEventIds.contains("$eventId:${candidate.serial}")

// A candidate's 0..100 showing on debate night.
private fun debatePerformanceScore(
    game: GameState,
    candidate: CandidateId,
    choice: EventChoice,
    luck: Double,
): Double {
    val readiness = debateReadiness(game, candidate) // 0..100
    val showing = (choice.effects.narrative ?: 0.0) + (choice.effects.momentum ?: 0.0) // ~ -15..+17
    return clamp(50 + (readiness - 50) * 0.6 + showing * 1.6 + luck, 0.0, 100.0)
}

// Resolves a debate head-to-head: both tickets answer, each is scored, and the
// margin drives a momentum swing on top of the choices' own effects.
// Deterministic for a given (seed, turn, event).
fun resolveDebate(
    game: GameState,
    event: GameEvent,
    choiceFor: Map<CandidateId, String>,
): DebateResult {
    val rng = Rng.createRng("debate:${game.seed}:${game.turn}:${event.id}")
    val scores = mutableMapOf<String, Double>()
    val choiceText = mutableMapOf<String, String>()
    val resultText = mutableMapOf<String, String>()

    for (c in listOf(CandidateId.DEM, CandidateId.REP)) {
        val chosenId = choiceFor[c] ?: aiChooseEvent(game, event, c).id
        val choice =
            event.choices.find { it.id == chosenId && choiceAvailable(game, c, it) }
                ?: event.choices.find { choiceAvailable(game, c, it) }
                ?: event.choices.first()
        val luck = rng.next() * 16 - 8 // ±8 unscripted
        scores[c.serial] = round(debatePerformanceScore(game, c, choice, luck)).toDouble()
        choiceText[c.serial] = choice.text
        resultText[c.serial] = choice.resultText
        resolveEvent(game, event.id, choice.id, c) // applies scaled effects + clears pending
    }

    val margin = abs(scores.getValue("dem") - scores.getValue("rep"))
    val winner: String = when {
        scores.getValue("dem") == scores.getValue("rep") -> "tie"
        scores.getValue("dem") > scores.getValue("rep") -> "dem"
        else -> "rep"
    }
    val meltdown = margin >= 20
    var momentumSwing = clamp(margin * 0.6, 0.0, 20.0)
    if (meltdown) momentumSwing = min(26.0, momentumSwing * 1.4)

    if (winner != "tie") {
        val loser = OPPONENT_OF.getValue(if (winner == "dem") CandidateId.DEM else CandidateId.REP)
        val winSide = if (winner == "dem") CandidateId.DEM else CandidateId.REP
        val w = game.resources.getValue(winSide.serial)
        val l = game.resources.getValue(loser.serial)
        w.nationalMomentum = clamp(w.nationalMomentum + momentumSwing, -100.0, 100.0)
        l.nationalMomentum = clamp(l.nationalMomentum - momentumSwing, -100.0, 100.0)
        if (meltdown) l.mediaNarrative = clamp(l.mediaNarrative - 8, -100.0, 100.0)
    }

    val debate = DebateResult(
        eventId = event.id, title = event.title, scores = scores,
        choiceText = choiceText, resultText = resultText, winner = winner,
        margin = margin, meltdown = meltdown, momentumSwing = momentumSwing,
    )
    // Record for the post-game ledger (Debate Dominator achievement, stats).
    val history = game.debateHistory ?: mutableListOf<DebateResult>().also { game.debateHistory = it }
    history.add(debate)
    return debate
}

// Queues this turn's events: every scheduled event due now, plus up to one
// stochastic draw, each as a pending decision for BOTH tickets.
fun queueEventsForTurn(game: GameState, rng: Rng) {
    fun queue(event: GameEvent) {
        for (c in listOf(CandidateId.DEM, CandidateId.REP)) {
            if (event.oncePerGame && alreadyFired(game, event.id, c)) continue
            val exists = game.pendingEvents.any { it.eventId == event.id && it.forCandidate == c }
            if (!exists) game.pendingEvents.add(PendingEvent(event.id, c))
        }
    }

    // Scheduled beats tied to the calendar.
    val historical = (game.eventMode ?: EventMode.HISTORICAL) == EventMode.HISTORICAL
    val scheduledDeck = if (historical) {
        HISTORICAL_EVENTS[game.scenarioId ?: "2020"] ?: HISTORICAL_EVENTS.getValue("2020")
    } else {
        GENERIC_DEBATES
    }
    val turnFor = scheduledTurnMap(scheduledDeck, game.totalTurns)
    for (event in scheduledDeck) {
        if (event.trigger !is EventTrigger.Scheduled) continue
        val effTurn = turnFor[event.id] ?: event.trigger.turn
        // The opening week is event-free, so the turn-0 beat surfaces with week 2.
        val due = effTurn == game.turn || (game.turn == 1 && effTurn == 0)
        if (due) queue(event)
    }

    // Stochastic draw: rare wildcard in historical mode, the main source in
    // plausible mode (where the year's real beats join the generic pool).
    if (rng.chance(if (historical) 0.35 else 0.75)) {
        val scenarioBeats = if (historical) emptyList() else HISTORICAL_EVENTS[game.scenarioId ?: "2020"] ?: emptyList()
        val pool = (GENERIC_EVENTS + scenarioBeats).filter { e ->
            if (e.oncePerGame && (alreadyFired(game, e.id, CandidateId.DEM) || alreadyFired(game, e.id, CandidateId.REP))) return@filter false
            val g = e.gate
            if (g?.minTurn != null && game.turn < g.minTurn) return@filter false
            if (g?.maxTurn != null && game.turn > g.maxTurn) return@filter false
            true
        }
        if (pool.isNotEmpty()) {
            // Generic events keep their authored weight; scenario beats get a
            // flat, slightly higher weight so the year's real moments surface.
            val weights = pool.map { e ->
                val t = e.trigger
                if (t is EventTrigger.Stochastic) t.baseWeight else 1.4
            }
            queue(rng.weightedPick(pool, weights))
        }
    }
}

// AI auto-picks the choice that maximizes its net weighted bloc support given
// current salience. Pure + legible.
fun aiChooseEvent(game: GameState, event: GameEvent, candidate: CandidateId): EventChoice {
    // Default to the first choice actually available to this ticket.
    var best = event.choices.find { choiceAvailable(game, candidate, it) } ?: event.choices.first()
    var bestScore = Double.NEGATIVE_INFINITY
    for (choice in event.choices) {
        if (!choiceAvailable(game, candidate, choice)) continue
        var score = 0.0
        for (bd in choice.effects.blocDeltas ?: emptyList()) {
            // Weight each bloc by how many votes it represents nationally.
            var weight = 0.0
            for (st in game.states) {
                val bloc = st.blocs.find { it.blocId == bd.blocId }
                if (bloc != null) weight += bloc.size * bloc.turnoutPropensity
            }
            score += (bd.margin ?: 0.0) * weight
            score += (bd.enthusiasm ?: 0.0) * weight * 0.3
        }
        score += (choice.effects.momentum ?: 0.0) * 1000
        score += (choice.effects.cash ?: 0.0) / 5000
        if (score > bestScore) {
            bestScore = score
            best = choice
        }
    }
    return best
}

// Resolves all pending events belonging to a given candidate using AI logic.
fun resolveAiEvents(game: GameState, candidate: CandidateId) {
    val mine = game.pendingEvents.filter { it.forCandidate == candidate }
    for (pending in mine) {
        val event = EVENTS_BY_ID[pending.eventId] ?: continue
        val choice = aiChooseEvent(game, event, candidate)
        resolveEvent(game, pending.eventId, choice.id, candidate)
    }
}
