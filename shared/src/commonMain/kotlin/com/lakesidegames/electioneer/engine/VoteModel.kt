package com.lakesidegames.electioneer.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlinx.serialization.Serializable

// Logistic sigmoid. Owned by setup.ts in TS; duplicated here as internal so
// voteModel ports without pulling the whole lifecycle module (setup.ts will
// re-export this when it lands).
internal fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

@Serializable
data class ContestTally(
    val stateId: String,
    val demVotes: Double,
    val repVotes: Double,
    val totalVotes: Double,
    // Two-party Dem share.
    val demShare: Double,
    val winner: CandidateId,
)

// Effective two-party Dem share for a single bloc = sigmoid of its baseline
// margin plus everything the campaign has done to it (campaignMargin).
fun blocDemShare(baselineMargin: Double, campaignMargin: Double): Double =
    sigmoid(baselineMargin + campaignMargin)

// Per-point coupling of signed state momentum into vote margin. Transient:
// momentum decays each turn, so this term fades.
private const val MOMENTUM_COUPLING = 0.0017

// Live two-party Dem support for a bloc inside its state.
fun liveBlocDemShare(state: StateContest, bloc: StateBloc): Double =
    blocDemShare(bloc.baselineMargin, bloc.campaignMargin + state.momentum * MOMENTUM_COUPLING)

// Tallies one vote-bearing contest (a contest with blocs).
fun tallyContest(state: StateContest): ContestTally {
    var demVotes = 0.0
    var totalVotes = 0.0
    val momentumTerm = state.momentum * MOMENTUM_COUPLING
    for (bloc in state.blocs) {
        val votes = bloc.size * bloc.turnoutPropensity * bloc.enthusiasm
        val share = blocDemShare(bloc.baselineMargin, bloc.campaignMargin + momentumTerm)
        demVotes += votes * share
        totalVotes += votes
    }
    val repVotes = totalVotes - demVotes
    val demShare = if (totalVotes > 0.0) demVotes / totalVotes else 0.5
    return ContestTally(
        stateId = state.id,
        demVotes = demVotes,
        repVotes = repVotes,
        totalVotes = totalVotes,
        demShare = demShare,
        winner = if (demShare >= 0.5) CandidateId.DEM else CandidateId.REP,
    )
}

// Runs the full electoral model: every contest, the ME/NE at-large
// aggregates, the EV tally, the national popular vote, and the winner
// (incl. the 269-269 tie).
fun computeResult(game: GameState): GameResult {
    val tallies = linkedMapOf<String, ContestTally>()
    for (st in game.states) {
        if (st.blocs.isNotEmpty()) tallies[st.id] = tallyContest(st)
    }

    val ev = mutableMapOf(CandidateId.DEM.serial to 0, CandidateId.REP.serial to 0)
    val popularVote = mutableMapOf(CandidateId.DEM.serial to 0.0, CandidateId.REP.serial to 0.0)
    val stateResults = mutableListOf<StateResult>()

    for (st in game.states) {
        val winner: CandidateId
        val demShare: Double

        if (!st.aggregateOf.isNullOrEmpty()) {
            // At-large unit: winner of the combined district vote.
            var b = 0.0
            var t = 0.0
            for (id in st.aggregateOf) {
                val dt = tallies[id]
                if (dt != null) {
                    b += dt.demVotes
                    t += dt.repVotes
                }
            }
            winner = if (b >= t) CandidateId.DEM else CandidateId.REP
            demShare = if (b + t > 0.0) b / (b + t) else 0.5
            // Aggregate units add no popular vote (already counted in districts).
        } else {
            val dt = tallies[st.id]!!
            winner = dt.winner
            demShare = dt.demShare
            popularVote[CandidateId.DEM.serial] = popularVote.getValue(CandidateId.DEM.serial) + dt.demVotes
            popularVote[CandidateId.REP.serial] = popularVote.getValue(CandidateId.REP.serial) + dt.repVotes
        }

        ev[winner.serial] = ev.getValue(winner.serial) + st.electoralVotes
        stateResults.add(
            StateResult(
                stateId = st.id,
                electoralVotes = st.electoralVotes,
                demShare = demShare,
                winner = winner,
                margin = abs(demShare - 0.5) * 2 * 100,
            ),
        )
    }

    val totalPop = popularVote.getValue(CandidateId.DEM.serial) + popularVote.getValue(CandidateId.REP.serial)
    val winner: String = when {
        ev.getValue(CandidateId.DEM.serial) >= 270 -> CandidateId.DEM.serial
        ev.getValue(CandidateId.REP.serial) >= 270 -> CandidateId.REP.serial
        else -> "tie"
    }

    // Post-mortem: the player's biggest self-caused swings, by magnitude.
    // sortedWith is stable, matching Array.prototype.sort stability.
    val postMortem = game.causes
        .sortedWith(compareByDescending { abs(it.marginDelta) })
        .take(8)

    return GameResult(
        electoralVotes = ev,
        winner = winner,
        popularVote = popularVote,
        popularShare = mapOf(
            CandidateId.DEM.serial to if (totalPop > 0.0) popularVote.getValue(CandidateId.DEM.serial) / totalPop else 0.5,
            CandidateId.REP.serial to if (totalPop > 0.0) popularVote.getValue(CandidateId.REP.serial) / totalPop else 0.5,
        ),
        stateResults = stateResults,
        postMortem = postMortem,
    )
}

@Serializable
data class ContestProjection(
    val stateId: String,
    val demShare: Double,
    // CandidateId.serial or "tossup".
    val lean: String,
    val ev: Int,
)

@Serializable
data class Projection(
    val ev: Map<String, Int>,
    val tossupEv: Int,
    val contests: List<ContestProjection>,
)

// Lightweight EV projection used in-game (for the tally bar / AI), with a
// "tossup" band for contests inside `margin` points of 50/50.
fun projectElection(game: GameState, tossupBand: Int = 3): Projection {
    val result = computeResult(game)
    val ev = mutableMapOf(CandidateId.DEM.serial to 0, CandidateId.REP.serial to 0)
    var tossupEv = 0
    val contests = result.stateResults.map { sr ->
        val pts = (sr.demShare - 0.5) * 200 // Dem margin in points
        val lean: String = if (abs(pts) <= tossupBand) {
            tossupEv += sr.electoralVotes
            "tossup"
        } else {
            val l = if (pts > 0) CandidateId.DEM else CandidateId.REP
            ev[l.serial] = ev.getValue(l.serial) + sr.electoralVotes
            l.serial
        }
        ContestProjection(stateId = sr.stateId, demShare = sr.demShare, lean = lean, ev = sr.electoralVotes)
    }
    return Projection(ev = ev, tossupEv = tossupEv, contests = contests)
}
