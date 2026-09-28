package com.lakesidegames.electioneer.engine
import kotlinx.serialization.Serializable

// Shared helpers for the UK and country turn loops. Port of
// src/engine/mpTurnHelpers.ts. The US advanceTurn path is untouched.

@Serializable
data class RecapItem(
    val label: String,
    val detail: String,
    val marginDelta: Double? = null,
)

interface MpResources {
    var momentum: Double
    var actions: Int
    var maxActions: Int
}

// Group this turn's causes into a week-in-review list, headed by a projection line.
fun buildCauseRecap(
    causes: List<CauseEntry>,
    turn: Int,
    header: RecapItem,
    regionLabel: String = "region(s)",
    limit: Int = 12,
): List<RecapItem> {
    val recap = mutableListOf<RecapItem>()
    val byCause = linkedMapOf<String, Pair<Double, MutableSet<String>>>()
    for (c in causes) {
        if (c.turn != turn) continue
        val entry = byCause[c.cause] ?: (0.0 to mutableSetOf())
        val newDelta = entry.first + c.marginDelta
        if (c.stateId != null) entry.second.add(c.stateId)
        byCause[c.cause] = newDelta to entry.second
    }
    for ((cause, info) in byCause) {
        recap.add(
            RecapItem(
                label = cause,
                detail = if (info.second.isNotEmpty()) "${info.second.size} $regionLabel" else "nationwide",
                marginDelta = info.first,
            ),
        )
    }
    // sortedWith is stable, matching Array.prototype.sort stability.
    recap.sortWith(compareByDescending { it.marginDelta ?: 0.0 })
    recap.add(0, header)
    return recap.take(limit)
}

@Serializable
data class PendingGateResult<T>(val blocked: Boolean, val game: T)

// Pending player-choice gate shared by UK/country: autoResolve picks the
// first choice via resolve, otherwise the game blocks for the UI modal.
fun <T> resolvePendingChoiceGate(
    game: T,
    autoResolvePlayerEvents: Boolean,
    hasPending: (T) -> Boolean,
    resolve: (T, String) -> T,
    firstChoiceId: (T) -> String?,
): PendingGateResult<T> {
    if (!hasPending(game)) return PendingGateResult(blocked = false, game = game)
    if (autoResolvePlayerEvents) {
        val choiceId = firstChoiceId(game)
        if (choiceId != null) return PendingGateResult(blocked = false, game = resolve(game, choiceId))
        return PendingGateResult(blocked = false, game = game)
    }
    return PendingGateResult(blocked = true, game = game)
}

// Momentum decay + action refill + live support refresh (UK/country identical).
fun decayMultipartyTurn(
    resources: Map<String, MpResources>,
    regions: List<StateContest>,
    parties: List<PartyId>,
) {
    for (p in parties) {
        val res = resources[p] ?: continue
        res.momentum *= 0.7
        res.actions = res.maxActions
    }
    for (region in regions) {
        region.momentum *= 0.6
        for (bloc in region.blocs) bloc.support = blocPartyShares(bloc)
    }
}
