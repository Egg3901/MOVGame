package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.OPPONENT_OF
import kotlin.math.floor
import kotlinx.serialization.Serializable

// Achievements: checked once, at game end, against the finished GameState +
// GameResult. Pure predicates over recorded facts. Port of
// src/engine/achievements.ts (browser localStorage persistence is a platform
// shell concern and is not ported).

@Serializable
data class AchievementContext(
    val result: GameResult,
    val game: GameState,
    val player: CandidateId,
    val difficulty: String,
)

@Serializable
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val check: (AchievementContext) -> Boolean,
)

private fun wonState(ctx: AchievementContext, id: String): Boolean =
    ctx.result.stateResults.find { it.stateId == id }?.winner == ctx.player

private fun playerWon(ctx: AchievementContext): Boolean = ctx.result.winner == ctx.player.serial

private fun evMargin(ctx: AchievementContext): Int =
    (ctx.result.electoralVotes[ctx.player.serial] ?: 0) -
        (ctx.result.electoralVotes[OPPONENT_OF.getValue(ctx.player).serial] ?: 0)

val ACHIEVEMENTS: List<Achievement> = listOf(
    Achievement(
        id = "landslide", name = "Landslide", icon = "\uD83C\uDF0A",
        description = "Win the Electoral College by 100 or more.",
        check = { c -> playerWon(c) && evMargin(c) >= 100 },
    ),
    Achievement(
        id = "keystone-flip", name = "Keystone Flip", icon = "\uD83D\uDD11",
        description = "Flip Pennsylvania — win it after it started in the other column.",
        check = { c ->
            val pa = c.game.states.find { it.id == "PA" }
            if (pa == null) {
                false
            } else {
                val startedAgainst = if (c.player == CandidateId.DEM) pa.prior2020DemShare < 0.5
                else pa.prior2020DemShare > 0.5
                startedAgainst && wonState(c, "PA")
            }
        },
    ),
    Achievement(
        id = "swing-sweep", name = "Swing State Sweep", icon = "\uD83E\uDDF9",
        description = "Win every battleground state.",
        check = { c ->
            c.game.states.filter { it.battleground && it.blocs.isNotEmpty() }
                .all { wonState(c, it.id) }
        },
    ),
    Achievement(
        id = "debate-dominator", name = "Debate Dominator", icon = "\uD83C\uDFA4",
        description = "Win every debate by 10+ points.",
        check = { c ->
            val debates = c.game.debateHistory ?: emptyList()
            debates.isNotEmpty() && debates.all { it.winner == c.player.serial && it.margin >= 10 }
        },
    ),
    Achievement(
        id = "grassroots", name = "Grassroots", icon = "\uD83C\uDF31",
        description = "Win the election without spending a dollar on ads.",
        check = { c -> playerWon(c) && (c.game.adSpend?.get(c.player.serial) ?: 0.0) == 0.0 },
    ),
    Achievement(
        id = "war-chest", name = "War Chest", icon = "\uD83D\uDCB0",
        description = "Finish the campaign with $50M+ unspent.",
        check = { c -> (c.game.resources[c.player.serial]?.cash ?: 0.0) >= 50_000_000 },
    ),
    Achievement(
        id = "nailbiter", name = "Nailbiter", icon = "\uD83D\uDE30",
        description = "Win with fewer than 5 electoral votes to spare.",
        check = { c -> playerWon(c) && (c.result.electoralVotes[c.player.serial] ?: 0) - 270 < 5 },
    ),
    Achievement(
        id = "comeback", name = "Comeback Kid", icon = "\uD83D\uDD04",
        description = "Trail in the projection at midpoint — then win.",
        check = { c ->
            val mid = c.game.timeline?.find { it.turn == floor(c.game.totalTurns / 2.0).toInt() }
            if (mid == null) {
                false
            } else {
                val behind = if (c.player == CandidateId.DEM) mid.demEV < mid.repEV
                else mid.repEV < mid.demEV
                behind && playerWon(c)
            }
        },
    ),
    Achievement(
        id = "beltway", name = "Inside the Beltway", icon = "\uD83C\uDFDB\uFE0F",
        description = "As a Democrat, carry DC by 90+; as a Republican, hold the loss under 60 points.",
        check = { c ->
            val dc = c.result.stateResults.find { it.stateId == "DC" }
            if (dc == null) {
                false
            } else {
                val demMargin = (dc.demShare - (1 - dc.demShare)) * 100
                if (c.player == CandidateId.DEM) demMargin >= 90 else demMargin < 60
            }
        },
    ),
    Achievement(
        id = "sun-belt", name = "Sun Belt Flip", icon = "\uD83C\uDF35",
        description = "Win Arizona, Georgia, and Nevada.",
        check = { c -> wonState(c, "AZ") && wonState(c, "GA") && wonState(c, "NV") },
    ),
    Achievement(
        id = "blue-wall", name = "Blue Wall", icon = "\uD83E\uDDF1",
        description = "Win Pennsylvania, Michigan, and Wisconsin.",
        check = { c -> wonState(c, "PA") && wonState(c, "MI") && wonState(c, "WI") },
    ),
    Achievement(
        id = "steady-hand", name = "Steady Hand", icon = "\uD83E\uDDD8",
        description = "No gaffes and no backfired stunts, the whole campaign.",
        check = { c ->
            val sign = if (c.player == CandidateId.DEM) 1.0 else -1.0
            !c.game.causes.any { x ->
                (x.cause.contains("Gaffe") || x.cause.contains("backfires")) && x.marginDelta * sign < 0
            }
        },
    ),
    Achievement(
        id = "dirt-digger", name = "Dirt Digger", icon = "\uD83D\uDD75\uFE0F",
        description = "Land a scandal on your opponent and win a state by under 1.5 points.",
        check = { c ->
            val oppName = c.game.candidates[OPPONENT_OF.getValue(c.player).serial]?.shortName
            val landed = c.game.causes.any { it.cause == "Scandal lands on $oppName" }
            val squeaker = c.result.stateResults.any { it.winner == c.player && it.margin < 1.5 }
            landed && squeaker
        },
    ),
    Achievement(
        id = "vp-mvp", name = "VP MVP", icon = "\uD83E\uDD1D",
        description = "Your running mate's coalition bonus helps carry a battleground by under 3.",
        check = { c ->
            val vpPicked = c.game.runningMates?.get(c.player.serial)
            if (vpPicked == null) {
                false
            } else {
                c.result.stateResults.any { s ->
                    val st = c.game.states.find { it.id == s.stateId }
                    (st?.battleground == true) && s.winner == c.player && s.margin < 3
                }
            }
        },
    ),
    Achievement(
        id = "coast-to-coast", name = "Coast to Coast", icon = "\uD83D\uDDFD",
        description = "Win California, New York, Florida, and Texas in one campaign.",
        check = { c -> wonState(c, "CA") && wonState(c, "NY") && wonState(c, "FL") && wonState(c, "TX") },
    ),
    Achievement(
        id = "history-defied", name = "History, Defied", icon = "⚔️",
        description = "Win on Hard — the real map, no help, a ruthless opponent.",
        check = { c -> playerWon(c) && c.difficulty == "hard" },
    ),
    Achievement(
        id = "money-machine", name = "Money Machine", icon = "\uD83D\uDDA8\uFE0F",
        description = "Raise more than $150M over the campaign.",
        check = { c -> (c.game.fundsRaised?.get(c.player.serial) ?: 0.0) >= 150_000_000 },
    ),
)

// Every achievement earned by the player for a finished game.
fun checkAchievements(ctx: AchievementContext): List<Achievement> {
    if (ctx.game.result == null) return emptyList()
    return ACHIEVEMENTS.filter { a ->
        try {
            a.check(ctx)
        } catch (e: Exception) {
            false
        }
    }
}
