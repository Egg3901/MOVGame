package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.CANDIDATES
import com.lakesidegames.electioneer.content.EVENTS_BY_ID
import com.lakesidegames.electioneer.content.SCENARIOS
import com.lakesidegames.electioneer.content.SCENARIO_IDS
import com.lakesidegames.electioneer.content.STAFF_POOL
import com.lakesidegames.electioneer.content.ISSUES
import kotlin.math.roundToInt

data class CampaignChoice(val id: String, val year: Int, val label: String, val tagline: String, val demName: String, val repName: String)
data class MateChoice(val id: String, val name: String, val blurb: String, val historical: Boolean,
                      val bonus: String)
data class StaffChoice(val id: String, val name: String, val role: String, val blurb: String,
                       val salaryPerWeek: Double, val bonus: String)

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
    private val difficulty: String?,
) {
    private val undoHistory = NativeUndoHistory()
    fun canUndo(): Boolean = game.queuedActions.isNotEmpty() || undoHistory.available()
    fun undo(): Boolean {
        if (game.queuedActions.isNotEmpty()) return removePlannedAction(game, game.queuedActions.lastIndex)
        val previous = undoHistory.take()?.let(::loadGame) ?: return false
        game = previous.state
        return true
    }
    companion object {
        fun restore(snapshot: String): MobileGame? {
            val saved = loadGame(snapshot) ?: return null
            return MobileGame(saved.state, saved.seed, saved.difficulty)
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
            return MobileGame(beginGame(state), seed.toString(), difficulty)
        }

        fun candidates(): List<Candidate> = CANDIDATES.values.toList()

        fun difficulties(): List<String> = listOf("easy", "normal", "hard")

        fun planBonusRecipes(): List<PlanBonus> = PLAN_BONUSES

        fun campaigns(): List<CampaignChoice> = SCENARIO_IDS.map { id ->
            val s = SCENARIOS.getValue(id)
            CampaignChoice(s.id, s.year, s.label, s.tagline, s.dem.shortName, s.rep.shortName)
        }

        fun mates(scenarioId: String, playerSerial: String): List<MateChoice> {
            val s = SCENARIOS.getValue(scenarioId)
            val roster = if (playerSerial == CandidateId.DEM.serial) s.dem.runningMates else s.rep.runningMates
            return roster.map { mate ->
                val bonuses = buildList {
                    mate.traitBonuses.forEach { (trait, points) ->
                        add("+${points.roundToInt()} ${trait.replace(Regex("([a-z])([A-Z])"), "$1 $2")}")
                    }
                    mate.favorability.forEach { (bloc, value) ->
                        add("+${(value * 100).roundToInt()} ${bloc.replace('_', ' ')}")
                    }
                    mate.cashBonus?.let { add("+\$${(it / 1_000_000).roundToInt()}M war chest") }
                    mate.candidateDayBonus?.let { add("+${it.roundToInt()} candidate day") }
                }
                MateChoice(mate.id, mate.name, mate.blurb, mate.historical, bonuses.joinToString(" · "))
            }
        }

        fun staffChoices(): List<StaffChoice> = STAFF_POOL.map { staff ->
            val e = staff.effects
            val bonuses = buildList {
                if (e.maxActions > 0) add("+${e.maxActions} action/week")
                if (e.adMult > 1.0) add("+${((e.adMult - 1) * 100).roundToInt()}% ad impact")
                if (e.fundraiseMult > 1.0) add("+${((e.fundraiseMult - 1) * 100).roundToInt()}% fundraising")
                if (e.oppoShield > 0.0) add("-${(e.oppoShield * 100).roundToInt()}% opposition damage")
                if (e.debatePrepBonus > 0.0) add("+${e.debatePrepBonus.roundToInt()} debate prep")
                e.traitBonuses.forEach { (trait, points) -> add("+${points.roundToInt()} ${trait.replace(Regex("([a-z])([A-Z])"), "$1 $2")}") }
            }
            StaffChoice(staff.id, staff.name, staff.role, staff.blurb,
                        staff.salaryPerWeek, bonuses.joinToString(" · "))
        }

        fun issues(): List<Issue> = IssueId.entries.map { ISSUES.getValue(it.serial) }

        fun startConfiguredGame(
            scenarioId: String, playerSerial: String, mateId: String,
            staffIds: List<String>, difficulty: String, eventModeSerial: String,
            totalTurns: Int, seed: String, whatIfState: String,
            mirrorMatch: Boolean, pandemic: Boolean,
        ): MobileGame {
            require(scenarioId in SCENARIOS)
            require(difficulty in difficulties())
            require(totalTurns in listOf(5, 9, 14))
            require(staffIds.size <= 3 && staffIds.distinct().size == staffIds.size)
            require(staffIds.all { id -> STAFF_POOL.any { it.id == id } })
            require(mates(scenarioId, playerSerial).any { it.id == mateId })
            require(whatIfState in listOf("", "TX", "FL", "OH", "PA", "MI", "WI", "GA", "AZ", "NC", "NY"))
            val player = CandidateId.entries.first { it.serial == playerSerial }
            val eventMode = EventMode.entries.first { it.serial == eventModeSerial }
            val state = createGame(NewGameOptions(
                seed = seed, playerCandidate = player, scenario = scenarioId,
                runningMate = mateId, staff = staffIds, difficulty = difficulty,
                eventMode = eventMode, totalTurns = totalTurns,
                modifiers = GameModifiers(whatIfState = whatIfState.ifEmpty { null }, mirrorMatch = mirrorMatch, pandemic = pandemic),
            ))
            return MobileGame(beginGame(state), seed, difficulty)
        }
    }

    fun playerSerial(): String = game.playerCandidate.serial
    fun ticketName(side: String): String = game.candidates.getValue(side).shortName
    fun ticketColor(side: String): String = game.candidates.getValue(side).color

    fun campaignLabel(): String = customDocument(game.customScenario)?.label ?: SCENARIOS[game.scenarioId ?: "2020"]?.label ?: "Your campaign"

    fun isCustom(): Boolean = game.customScenario != null

    fun saveSnapshot(): String = saveGame(game, seedStr, difficulty)

    fun resultSummary(): NativeResultSummary? = NativeResults.us(game, difficulty)
    fun resultAchievements(): List<NativeAward> = NativeResults.achievements(game, difficulty)
    fun resultHistory(): List<NativeHistoricalRegion> = NativeResults.historicalUs(game)

    fun scoreSubmission(): String? = NativeResults.submission(game, difficulty)

    fun isDaily(dateUTC: String): Boolean = NativeDaily.matchesUs(dateUTC, game)

    fun analysis(regionId: String?): NativeAnalysisDocument = NativeAnalysis.us(game, regionId)

    fun askSnapshot(): String = askCampaignSnapshot(game, campaignLabel())

    fun turn(): Int = game.turn

    fun totalTurns(): Int = game.totalTurns

    fun playerCash(): Double = game.resources.getValue(game.playerCandidate.serial).cash

    fun playerMomentum(): Double = game.resources.getValue(game.playerCandidate.serial).nationalMomentum

    fun plannedSpend(): Double = game.queuedActions.sumOf { if (it.type == ActionType.ADVERTISE) it.spend ?: 0.0 else 0.0 }

    fun availableCash(): Double = (playerCash() - plannedSpend()).coerceAtLeast(0.0)

    fun queuedCount(): Int = game.queuedActions.size

    fun planBonuses(): List<PlanBonus> = plannedBonuses(game)

    fun previewPlayerEv(): Int = projectPlannedElection(game).ev.getValue(game.playerCandidate.serial)

    fun plannedActions(): List<PlannedActionRow> = plannedActionRows(game)

    fun playerIssuePosition(issueSerial: String): Double =
        game.candidates.getValue(game.playerCandidate.serial).issuePositions[issueSerial] ?: 0.0

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
        val type = ActionType.entries.first { it.serial == typeSerial }
        val day = nextOpenPlanDay(game) ?: return
        queuePlannedAction(game, type, stateId, day,
            adMode = if (type == ActionType.ADVERTISE) AdMode.POSITIVE else null,
            spendMillions = if (type == ActionType.ADVERTISE) 8.0 else null)
    }

    fun queueConfiguredAction(typeSerial: String, stateId: String?, day: Int,
                              adModeSerial: String?, spendMillions: Double?,
                              issueSerial: String?, newPosition: Double?): Boolean {
        val type = ActionType.entries.firstOrNull { it.serial == typeSerial } ?: return false
        val adMode = AdMode.entries.firstOrNull { it.serial == adModeSerial }
        val issueId = IssueId.entries.firstOrNull { it.serial == issueSerial }
        return queuePlannedAction(game, type, stateId, day, adMode, spendMillions, issueId, newPosition)
    }

    // A deterministic planning estimate. Apply the proposed move to a snapshot,
    // never to the live campaign; the turn's random events can change the result.
    fun previewMarginPoints(typeSerial: String, stateId: String, day: Int,
                            adModeSerial: String?, spendMillions: Double?, issueSerial: String?,
                            newPosition: Double?): Double {
        val copy = game.deepCopy()
        val type = ActionType.entries.firstOrNull { it.serial == typeSerial } ?: return Double.NaN
        val mode = AdMode.entries.firstOrNull { it.serial == adModeSerial }
        val issue = IssueId.entries.firstOrNull { it.serial == issueSerial }
        if (!queuePlannedAction(copy, type, stateId, day, mode, spendMillions, issue, newPosition)) return Double.NaN
        val contest = projectPlannedElection(copy).contests.firstOrNull { it.stateId == stateId } ?: return Double.NaN
        return (contest.demShare - 0.5) * 200
    }

    fun removeAction(index: Int): Boolean = removePlannedAction(game, index)

    fun clearQueue() {
        game.queuedActions = emptyList()
    }

    // Ends the week; returns the recap lines for display.
    fun endTurn(): List<TurnRecapItem> {
        if (game.phase == GamePhase.RESULT) return game.lastRecap
        undoHistory.record(saveSnapshot())
        game = advanceCampaignWeek(game, difficulty)
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
        answerPlayerEvent(game, eventId, choiceId)
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
