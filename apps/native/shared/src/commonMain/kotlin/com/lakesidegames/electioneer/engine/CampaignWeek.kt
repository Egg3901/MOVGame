package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.STAFF_BY_ID
import com.lakesidegames.electioneer.content.staffEffects
import kotlin.math.max
import kotlin.math.min

// Complete US week, including the staff policy used by the web campaign store.
// Always use the normalized game seed and the selected rival difficulty.
fun advanceCampaignWeek(game: GameState, difficulty: String?): GameState {
    if (game.phase == GamePhase.RESULT) return game
    var next = advanceTurn(game, game.queuedActions, game.seed,
        AdvanceOptions(difficulty = DIFFICULTY[difficulty] ?: DIFFICULTY.getValue("normal")))
    val player = next.playerCandidate
    val hires = next.staff?.get(player.serial).orEmpty()
    if (hires.isEmpty() || next.phase == GamePhase.RESULT) return next

    val resource = next.resources.getValue(player.serial)
    resource.cash = max(0.0, resource.cash - staffEffects(next, player).salaryPerWeek)
    next.lastRecap = next.lastRecap + TurnRecapItem(
        label = "Staff payroll", detail = hires.joinToString(", ") { STAFF_BY_ID[it]?.role ?: it })
    val projection = projectElection(next)
    val playerEV = projection.ev.getValue(player.serial)
    if (playerEV < 195) {
        val remaining = mutableListOf<String>()
        var maxActions = resource.maxActions
        for (id in hires) {
            val def = STAFF_BY_ID[id]
            val quits = def != null && def.loyalty < 65 &&
                Rng.createRng("staff:${next.seed}:${next.turn}:$id").next() < 0.22
            if (quits) {
                maxActions = max(1, maxActions - def.effects.maxActions)
                next.lastRecap = listOf(TurnRecapItem(
                    label = "${def.emoji} ${def.name} quits the campaign",
                    detail = "\"${def.role}s don't go down with the ship.\" The polls looked terminal.")) + next.lastRecap
            } else remaining += id
        }
        next = next.copy(
            resources = next.resources + (player.serial to resource.copy(
                maxActions = maxActions, actions = min(resource.actions, maxActions))),
            staff = next.staff.orEmpty() + (player.serial to remaining),
        )
    }
    return next
}
