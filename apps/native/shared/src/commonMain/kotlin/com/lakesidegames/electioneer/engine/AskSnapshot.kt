package com.lakesidegames.electioneer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class AskCampaignState(val name: String, val ev: Int, val playerMargin: Double)

@Serializable
data class AskCampaignPlan(val move: String, val target: String)

@Serializable
data class AskSeatRegion(val name: String, val totalSeats: Int, val playerSeats: Int)

@Serializable
data class AskSeatCampaignSnapshot(
    val version: Int = 1,
    val game: String = "electioneer",
    val scenario: String,
    val country: String,
    val turn: Int,
    val totalTurns: Int,
    val player: String,
    val fundsMillions: Double,
    val momentum: Double,
    val actionsLeft: Int,
    val seatPlayer: Int,
    val seatTotal: Int,
    val regions: List<AskSeatRegion>,
    val planned: List<AskCampaignPlan>,
)

/** Read-only, bounded facts that a player deliberately sends to Ask. */
@Serializable
data class AskCampaignSnapshot(
    val version: Int = 1,
    val game: String = "electioneer",
    val scenario: String,
    val country: String = "US",
    val turn: Int,
    val totalTurns: Int,
    val player: String,
    val cash: Double,
    val momentum: Double,
    val actionsLeft: Int,
    val evPlayer: Int,
    val evOpponent: Int,
    val tossupEv: Int,
    val states: List<AskCampaignState>,
    val planned: List<AskCampaignPlan>,
)

fun askCampaignSnapshot(state: GameState, scenario: String): String {
    val player = state.playerCandidate.serial
    val projection = projectElection(state)
    val names = state.states.associateBy { it.id }
    val sign = if (player == CandidateId.DEM.serial) 1.0 else -1.0
    val snapshot = AskCampaignSnapshot(
        scenario = scenario,
        turn = state.turn,
        totalTurns = state.totalTurns,
        player = player,
        cash = state.resources.getValue(player).cash,
        momentum = state.resources.getValue(player).nationalMomentum,
        actionsLeft = (state.resources.getValue(player).actions - state.queuedActions.size).coerceAtLeast(0),
        evPlayer = projection.ev.getValue(player),
        evOpponent = projection.ev.getValue(if (player == CandidateId.DEM.serial) CandidateId.REP.serial else CandidateId.DEM.serial),
        tossupEv = projection.tossupEv,
        states = projection.contests.mapNotNull { row ->
            val contest = names[row.stateId] ?: return@mapNotNull null
            AskCampaignState(contest.name, row.ev, (row.demShare - 0.5) * 200 * sign)
        },
        planned = state.queuedActions.map { action ->
            AskCampaignPlan(
                move = action.type.serial,
                target = action.stateId?.let { names[it]?.name ?: it } ?: "National",
            )
        },
    )
    return EngineJson.encodeToString(snapshot)
}
