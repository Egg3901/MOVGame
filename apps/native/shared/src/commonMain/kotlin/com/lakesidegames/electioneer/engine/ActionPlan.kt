package com.lakesidegames.electioneer.engine

/** One seven-day campaign plan. The UI and save format share these limits. */
fun queuePlannedAction(
    game: GameState,
    type: ActionType,
    stateId: String?,
    day: Int,
    adMode: AdMode? = null,
    spendMillions: Double? = null,
    issueId: IssueId? = null,
    newPosition: Double? = null,
): Boolean {
    val resources = game.resources[game.playerCandidate.serial] ?: return false
    if (day !in 1..7 || game.queuedActions.size >= resources.actions) return false
    if (game.queuedActions.count { (it.day ?: 1) == day } >= 3) return false
    val needsState = type in setOf(ActionType.ADVERTISE, ActionType.RALLY, ActionType.SURROGATE, ActionType.GROUND_GAME, ActionType.GOTV)
    if (needsState && game.states.none { it.id == stateId && it.blocs.isNotEmpty() }) return false
    if (stateId != null && game.states.none { it.id == stateId && it.blocs.isNotEmpty() }) return false
    if (type == ActionType.ADVERTISE && (adMode == null || spendMillions == null || spendMillions !in 1.0..30.0)) return false
    val committedCash = game.queuedActions.sumOf { if (it.type == ActionType.ADVERTISE) it.spend ?: 0.0 else 0.0 }
    val newCost = if (type == ActionType.ADVERTISE) spendMillions!! * 1_000_000 else 0.0
    if (committedCash + newCost > resources.cash) return false
    if (type == ActionType.ADVERTISE && adMode == AdMode.ISSUE && issueId == null) return false
    if (type == ActionType.ISSUE_PIVOT && (issueId == null || newPosition == null || newPosition !in -1.0..1.0)) return false
    game.queuedActions = game.queuedActions + CampaignAction(
        type = type,
        candidate = game.playerCandidate,
        stateId = stateId,
        day = day,
        adMode = if (type == ActionType.ADVERTISE) adMode else null,
        spend = if (type == ActionType.ADVERTISE) spendMillions!! * 1_000_000 else null,
        issueId = if (type == ActionType.ISSUE_PIVOT || (type == ActionType.ADVERTISE && adMode == AdMode.ISSUE)) issueId else null,
        newPosition = if (type == ActionType.ISSUE_PIVOT) newPosition else null,
    )
    return true
}

fun removePlannedAction(game: GameState, index: Int): Boolean {
    if (index !in game.queuedActions.indices) return false
    game.queuedActions = game.queuedActions.filterIndexed { i, _ -> i != index }
    return true
}

fun nextOpenPlanDay(game: GameState): Int? =
    (1..7).firstOrNull { day -> game.queuedActions.count { (it.day ?: 1) == day } < 3 }

data class PlannedActionRow(val index: Int, val day: Int, val label: String)

fun plannedActionRows(game: GameState): List<PlannedActionRow> = game.queuedActions.mapIndexed { index, action ->
    val state = game.states.firstOrNull { it.id == action.stateId }?.abbr
    val title = when (action.type) {
        ActionType.ADVERTISE -> "${action.adMode?.serial ?: "positive"} ads"
        ActionType.RALLY -> "Rally"
        ActionType.SURROGATE -> "Surrogate"
        ActionType.FUNDRAISE -> "Fundraise"
        ActionType.GROUND_GAME -> "Field offices"
        ActionType.GOTV -> "GOTV"
        ActionType.OPPO_RESEARCH -> "Oppo research"
        ActionType.DEBATE_PREP -> "Debate prep"
        ActionType.POLICY_PREP -> "Policy prep"
        ActionType.ISSUE_PIVOT -> "Pivot ${action.issueId?.serial ?: "issue"}"
    }
    PlannedActionRow(index, action.day ?: 1, if (state == null) title else "$state · $title")
}
