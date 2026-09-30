package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.CANDIDATES
import com.lakesidegames.electioneer.content.EVENTS_BY_ID
import com.lakesidegames.electioneer.content.OPPONENT_OF
import kotlin.math.abs
import kotlinx.serialization.Serializable

// The weekly turn pipeline. Port of src/engine/turn.ts.

// Deep copy of a game state (TS: structuredClone). Mutable paths are copied;
// immutable leaves (issues, results, modifiers) are shared by reference, which
// is behaviorally identical since nothing mutates through them.
internal fun GameState.deepCopy(): GameState = copy(
    candidates = candidates.mapValues { (_, c) ->
        c.copy(traits = c.traits.copy(), issuePositions = c.issuePositions.toMutableMap())
    },
    salience = salience.toMutableMap(),
    states = states.map { st ->
        st.copy(
            blocs = st.blocs.map { it.copy() },
            groundGame = st.groundGame.toMutableMap(),
        )
    },
    resources = resources.mapValues { (_, r) -> r.copy() },
    pendingEvents = pendingEvents.map { it.copy() }.toMutableList(),
    firedEventIds = firedEventIds.toMutableList(),
    queuedActions = queuedActions.map { it.copy() },
    causes = causes.map { it.copy() }.toMutableList(),
    lastRecap = lastRecap.map { it.copy() },
    runningMates = runningMates?.toMap(),
    staff = staff?.mapValues { (_, v) -> v.toList() },
    locations = locations?.toMutableMap(),
    adSpend = adSpend?.toMutableMap(),
    fundsRaised = fundsRaised?.toMutableMap(),
    debateHistory = debateHistory?.map { it.copy() }?.toMutableList(),
    timeline = timeline?.map { it.copy(demShareByState = it.demShareByState.toMap()) }?.toMutableList(),
)

// Appends a trend sample for the current state to game.timeline. Called once
// at game start (the opening baseline) and once at the end of every week.
private fun recordTimelinePoint(game: GameState) {
    val proj = projectElection(game)
    val demShareByState = proj.contests.associate { it.stateId to it.demShare }
    val timeline = game.timeline ?: mutableListOf<TurnPoint>().also { game.timeline = it }
    timeline.add(
        TurnPoint(
            turn = game.turn,
            demPoll = nationalPoll(game),
            demEV = proj.ev.getValue("dem"),
            repEV = proj.ev.getValue("rep"),
            tossupEV = proj.tossupEv,
            demMomentum = game.resources.getValue("dem").nationalMomentum,
            repMomentum = game.resources.getValue("rep").nationalMomentum,
            demCash = game.resources.getValue("dem").cash,
            repCash = game.resources.getValue("rep").cash,
            demShareByState = demShareByState,
        ),
    )
}

// Decays the transient, turn-scoped quantities. Ground game is sticky (no
// decay): early field investment pays off.
private fun decay(game: GameState) {
    for (st in game.states) {
        st.momentum *= 0.7
        if (abs(st.momentum) < 0.3) st.momentum = 0.0
        for (bloc in st.blocs) {
            // Enthusiasm relaxes toward 1.0.
            bloc.enthusiasm = 1 + (bloc.enthusiasm - 1) * 0.6
        }
    }
    for (c in listOf(CandidateId.DEM, CandidateId.REP)) {
        val res = game.resources.getValue(c.serial)
        res.nationalMomentum *= 0.75
        res.mediaNarrative *= 0.8
        // Prep buffs relax back toward the candidate's real starting traits.
        val cand = game.candidates.getValue(c.serial)
        val base = cand.baseTraits ?: CANDIDATES.getValue(c).traits
        for (k in listOf("debatePrep", "policyKnowledge", "debatingSkill")) {
            val current = cand.traits[k]
            val restored = base[k] + (current - base[k]) * 0.65
            when (k) {
                "debatePrep" -> cand.traits.debatePrep = restored
                "policyKnowledge" -> cand.traits.policyKnowledge = restored
                "debatingSkill" -> cand.traits.debatingSkill = restored
            }
        }
        // Refill candidate-days for the new week.
        res.actions = res.maxActions
    }
}

private fun buildRecap(game: GameState, turn: Int, evBefore: Int, cashBefore: Double): List<TurnRecapItem> {
    val recap = mutableListOf<TurnRecapItem>()
    data class Group(val label: String, val actor: CandidateId?, var delta: Double = 0.0,
                     val states: MutableSet<String> = mutableSetOf())
    val byCause = linkedMapOf<String, Group>()
    for (c in game.causes) {
        if (c.turn != turn) continue
        val key = "${c.actor?.serial ?: "event"}:${c.cause}"
        val entry = byCause.getOrPut(key) { Group(c.cause, c.actor) }
        entry.delta += c.marginDelta
        if (c.stateId != null) entry.states.add(c.stateId)
    }
    val sign = if (game.playerCandidate == CandidateId.DEM) 1.0 else -1.0
    for (info in byCause.values) {
        val actor = when (info.actor) { game.playerCandidate -> "Your action"; null -> "Campaign event"; else -> "Opponent action" }
        recap.add(
            TurnRecapItem(
                label = info.label,
                detail = "$actor · ${if (info.states.isNotEmpty()) "${info.states.size} contest(s)" else "nationwide"}",
                marginDelta = if (info.label.startsWith("Plan bonus:") || info.delta == 0.0) null else info.delta * sign,
            ),
        )
    }
    recap.sortWith(compareByDescending<TurnRecapItem> { it.detail.startsWith("Your action") }
        .thenBy { it.label.startsWith("Plan bonus:") }.thenByDescending { abs(it.marginDelta ?: 0.0) })

    val evAfter = projectElection(game).ev.getValue(game.playerCandidate.serial)
    recap.add(
        0,
        TurnRecapItem(
            label = "Projected electoral votes",
            detail = "${game.candidates.getValue(game.playerCandidate.serial).shortName} $evAfter (was $evBefore)",
            marginDelta = (evAfter - evBefore).toDouble(),
        ),
    )
    val cashAfter = game.resources.getValue(game.playerCandidate.serial).cash
    val cashDelta = cashAfter - cashBefore
    recap.add(1, TurnRecapItem(label = "Campaign cash",
        detail = "\$${toFixed1(cashAfter / 1_000_000)}M after ${if (cashDelta >= 0) "+" else "-"}\$${toFixed1(abs(cashDelta) / 1_000_000)}M this week"))
    return recap.take(2) + recap.drop(2).filter { it.detail.startsWith("Your action") } +
        recap.drop(2).filter { !it.detail.startsWith("Your action") }.take(8)
}

@Serializable
data class AdvanceOptions(
    val difficulty: AiConfig? = null,
    // If true, any player events left unanswered get a sensible default pick.
    val autoResolvePlayerEvents: Boolean = true,
)

// THE pure turn function. advanceTurn(state, actions, seed) -> newState.
// Same inputs always produce the same output (undo, replay, tests rely on it).
fun advanceTurn(
    state: GameState,
    actions: List<CampaignAction>,
    seed: String,
    opts: AdvanceOptions = AdvanceOptions(),
): GameState {
    val game = state.deepCopy()
    if (game.phase == GamePhase.RESULT) return game

    val turn = game.turn
    val rng = Rng.createRng("$seed:${game.seed}:$turn:${game.rngState}")
    val player = game.playerCandidate
    val ai = OPPONENT_OF.getValue(player)
    val cfg = opts.difficulty ?: DIFFICULTY.getValue("normal")

    val evBefore = projectElection(game).ev.getValue(player.serial)
    val cashBefore = game.resources.getValue(player.serial).cash

    // 1. Resolve leftover player events with a sensible default. Debates go
    //    head-to-head so the scorecard swing applies even when skipped.
    if (opts.autoResolvePlayerEvents) {
        val pendingDebateIds = game.pendingEvents
            .filter { it.forCandidate == player && (EVENTS_BY_ID[it.eventId]?.isDebate == true) }
            .map { it.eventId }
            .toSet()
        for (id in pendingDebateIds) resolveDebate(game, requireEvent(id), emptyMap())

        val leftover = game.pendingEvents.filter { it.forCandidate == player }
        for (p in leftover) {
            val choice = aiChooseEvent(game, requireEvent(p.eventId), player)
            resolveEvent(game, p.eventId, choice.id, player)
        }
    }

    // 2. AI resolves its own events.
    resolveAiEvents(game, ai)

    // 3. Player's queued actions in day order, then the AI's plan.
    resolvePlan(actions.filter { it.candidate == player }, { it.planMove() }, { game.causes.size }) { action, mult, bonuses ->
        applyAction(game, action, rng, mult, bonuses)
    }
    val aiActions = planAiActions(game, rng, cfg)
    for (action in aiActions) applyAction(game, action, rng, cfg.actionPower)

    // 4. Decay transient quantities and refill resources.
    decay(game)

    // 5. Recap + bookkeeping.
    game.lastRecap = buildRecap(game, turn, evBefore, cashBefore)
    game.queuedActions = emptyList()
    game.pendingEvents = mutableListOf()
    game.rngState = rng.state()
    game.turn = turn + 1

    // 6. End of campaign? Otherwise queue the next week's events.
    if (game.turn >= game.totalTurns) {
        game.result = computeResult(game)
        game.phase = GamePhase.RESULT
    } else {
        queueEventsForTurn(game, rng)
        game.phase = GamePhase.INTEL
    }
    recordTimelinePoint(game) // this week's close
    return game
}

fun advanceTurn(
    state: GameState,
    actions: List<CampaignAction>,
    seed: Long,
    opts: AdvanceOptions = AdvanceOptions(),
): GameState = advanceTurn(state, actions, seed.toString(), opts)

private fun requireEvent(id: String): GameEvent =
    EVENTS_BY_ID[id] ?: throw IllegalArgumentException("Unknown event $id")

// Opens a freshly created game. The first week is event-free.
fun beginGame(game: GameState): GameState {
    val next = game.deepCopy()
    next.pendingEvents = mutableListOf()
    next.phase = GamePhase.INTEL
    next.timeline = mutableListOf()
    recordTimelinePoint(next) // opening baseline (turn 0)
    return next
}
