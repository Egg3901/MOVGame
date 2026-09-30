package com.lakesidegames.electioneer.engine

import kotlin.math.roundToInt

data class PlanBonus(val id: String, val name: String, val multiplier: Double,
                     val recipe: String, val blurb: String) {
    val percent: Int get() = ((multiplier - 1) * 100).roundToInt()
    internal fun cause(turn: Int, target: String?, actor: CandidateId? = null) = CauseEntry(
        turn = turn, actor = actor, stateId = target,
        cause = "Plan bonus: $name (+$percent%)", marginDelta = 0.0,
    )
}

val PLAN_BONUSES = listOf(
    PlanBonus("earned_media", "Own the news cycle", 1.2, "Rally -> ads",
        "Rally in a target, then advertise there on a later day."),
    PlanBonus("field_machine", "Turnout machine", 1.25, "Field -> GOTV",
        "Build field offices, then run GOTV there on a later day."),
    PlanBonus("message_discipline", "Make the case", 1.2, "Policy -> issue",
        "Do policy prep, then make an issue pitch or issue ad on a later day."),
    PlanBonus("attack_line", "Define the attack", 1.25, "Oppo -> contrast",
        "Research the opposition, then run contrast ads on a later day."),
)

internal data class PlanMove(val type: String, val day: Int? = null,
                             val target: String? = null, val mode: String? = null)
internal fun CampaignAction.planMove() = PlanMove(type.serial, day, stateId, adMode?.serial)
internal fun UkAction.planMove() = PlanMove(type.serial, day, regionId, mode?.serial)
internal fun CountryAction.planMove() = PlanMove(type.serial, day, regionId, mode?.serial)

internal fun planBonusesForAction(payoff: PlanMove, plan: List<PlanMove>): List<PlanBonus> {
    // Missing days preserve the balance of AI and older programmatic plans.
    fun hasSetup(test: (PlanMove) -> Boolean) = plan.any {
        it.day != null && payoff.day != null && it.day < payoff.day && test(it)
    }
    fun sameTarget(a: PlanMove) = a.target != null && a.target == payoff.target
    val ads = payoff.type == "advertise" || payoff.type == "broadcast"
    return buildList {
        if (ads && hasSetup { it.type == "rally" && sameTarget(it) }) add(PLAN_BONUSES[0])
        if (payoff.type == "gotv" && hasSetup { it.type == "ground_game" && sameTarget(it) }) add(PLAN_BONUSES[1])
        if ((payoff.type == "issue_pivot" || (ads && payoff.mode == "issue")) &&
            hasSetup { it.type == "policy_prep" }) add(PLAN_BONUSES[2])
        if (ads && payoff.mode == "contrast" && hasSetup {
                it.type == "oppo_research" && (it.target == null || payoff.target == null || it.target == payoff.target)
            }) add(PLAN_BONUSES[3])
    }
}

internal fun activePlanBonuses(plan: List<PlanMove>): List<PlanBonus> =
    plan.flatMap { planBonusesForAction(it, plan) }.distinctBy { it.id }

fun plannedBonuses(game: GameState): List<PlanBonus> = activePlanBonuses(game.queuedActions.map { it.planMove() })

fun projectPlannedElection(game: GameState): Projection {
    val clone = game.deepCopy()
    val rng = Rng.createRng("preview:${clone.seed}:${clone.turn}")
    resolvePlan(clone.queuedActions.filter { it.candidate == clone.playerCandidate },
        { it.planMove() }, { clone.causes.size }) { action, mult, bonuses ->
        applyAction(clone, action, rng, mult, bonuses)
    }
    return projectElection(clone)
}

// Only successfully executed setup moves unlock a payoff. Stable day ordering
// is shared by committed turns and throwaway previews in every engine.
internal fun <T> resolvePlan(actions: List<T>, describe: (T) -> PlanMove,
                             causeCount: () -> Int, apply: (T, Double, List<PlanBonus>) -> Unit) {
    val completed = mutableListOf<PlanMove>()
    for (action in actions.sortedBy { describe(it).day ?: 1 }) {
        val move = describe(action)
        val bonuses = planBonusesForAction(move, completed)
        val before = causeCount()
        apply(action, bonuses.fold(1.0) { mult, bonus -> mult * bonus.multiplier }, bonuses)
        if (causeCount() > before) completed.add(move)
    }
}
