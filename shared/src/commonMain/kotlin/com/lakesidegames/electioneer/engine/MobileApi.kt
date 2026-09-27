package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.CANDIDATES
import com.lakesidegames.electioneer.content.EVENTS_BY_ID

// Swift-friendly facade over the US game loop (Phase 4, #22).
//
// Swift cannot call data-class copy(), default-argument constructors, or
// Any? seeds without friction, so the iOS app talks only to this class:
// string serials in, plain lists and data-class reads out. Android keeps its
// direct engine calls; this delegates to the same functions, so both UIs
// simulate identically. Pure in / pure out: no clock, no rng, no env.
class MobileGame private constructor(
    private var game: GameState,
    private val seedStr: String,
) {
    companion object {
        fun restore(snapshot: String): MobileGame? {
            val saved = loadGame(snapshot) ?: return null
            return MobileGame(saved.state, saved.seed)
        }

        fun startGame(playerSerial: String, difficulty: String, seed: Long): MobileGame {
            val player = CandidateId.entries.first { it.serial == playerSerial }
            val state = createGame(
                NewGameOptions(
                    seed = seed,
                    playerCandidate = player,
                    difficulty = difficulty,
                ),
            )
            return MobileGame(state, seed.toString())
        }

        fun candidates(): List<Candidate> = CANDIDATES.values.toList()

        fun difficulties(): List<String> = listOf("easy", "normal", "hard")
    }

    fun playerSerial(): String = game.playerCandidate.serial

    fun saveSnapshot(): String = saveGame(game, seedStr)

    fun turn(): Int = game.turn

    fun totalTurns(): Int = game.totalTurns

    fun playerCash(): Double = game.resources.getValue(game.playerCandidate.serial).cash

    fun queuedCount(): Int = game.queuedActions.size

    fun slotsLeft(): Int {
        val res = game.resources.getValue(game.playerCandidate.serial)
        return (res.actions - game.queuedActions.size).coerceAtLeast(0)
    }

    fun isOver(): Boolean = game.phase == GamePhase.RESULT

    fun stateList(): List<StateContest> = game.states

    fun evDem(): Int = projectElection(game).ev.getValue(CandidateId.DEM.serial)

    fun evRep(): Int = projectElection(game).ev.getValue(CandidateId.REP.serial)

    fun tossupEv(): Int = projectElection(game).tossupEv

    fun contests(): List<ContestProjection> = projectElection(game).contests

    fun queueAction(typeSerial: String, stateId: String?) {
        if (slotsLeft() < 1) return
        val type = ActionType.entries.first { it.serial == typeSerial }
        game.queuedActions = game.queuedActions + CampaignAction(
            type = type,
            candidate = game.playerCandidate,
            stateId = stateId,
        )
    }

    fun clearQueue() {
        game.queuedActions = emptyList()
    }

    // Ends the week; returns the recap lines for display.
    fun endTurn(): List<TurnRecapItem> {
        game = advanceTurn(game, game.queuedActions, seedStr, AdvanceOptions())
        return game.lastRecap
    }

    fun pendingEventIds(): List<String> =
        game.pendingEvents
            .filter { it.forCandidate == game.playerCandidate }
            .map { it.eventId }

    fun eventTitle(eventId: String): String = EVENTS_BY_ID[eventId]?.title ?: eventId

    fun eventPrompt(eventId: String): String = EVENTS_BY_ID[eventId]?.prompt ?: ""

    fun eventChoices(eventId: String): List<EventChoice> =
        (EVENTS_BY_ID[eventId]?.choices ?: emptyList()).filter { choice ->
            (choice.side == null || choice.side == game.playerCandidate) &&
                choiceAvailable(game, game.playerCandidate, choice)
        }

    fun answerEvent(eventId: String, choiceId: String): String =
        resolveEvent(game, eventId, choiceId, game.playerCandidate)
            ?: "That response is no longer available."

    fun hasResult(): Boolean = game.result != null

    fun resultWinnerSerial(): String = game.result?.winner ?: "tie"

    fun resultDemEv(): Int =
        game.result?.electoralVotes?.get(CandidateId.DEM.serial) ?: 0

    fun resultRepEv(): Int =
        game.result?.electoralVotes?.get(CandidateId.REP.serial) ?: 0

    fun resultDemPopularShare(): Double =
        game.result?.popularShare?.get(CandidateId.DEM.serial) ?: 0.5

    fun resultPlayerName(): String =
        game.candidates[game.playerCandidate.serial]?.name ?: game.playerCandidate.serial

    fun resultWinnerName(): String {
        val winner = game.result?.winner ?: return "Tie"
        return game.candidates[winner]?.name ?: winner
    }

    fun resultStates(): List<StateResult> = game.result?.stateResults ?: emptyList()

    fun resultCauses(): List<String> =
        game.result?.postMortem?.take(5)?.map { it.cause } ?: emptyList()
}
