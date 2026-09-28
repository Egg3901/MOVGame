package com.lakesidegames.electioneer.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.lakesidegames.electioneer.BuildConfig
import com.lakesidegames.electioneer.billing.PlayBilling
import com.lakesidegames.electioneer.billing.StoreProduct
import com.lakesidegames.electioneer.content.CANDIDATES
import com.lakesidegames.electioneer.content.EVENTS_BY_ID
import com.lakesidegames.electioneer.engine.ActionType
import com.lakesidegames.electioneer.engine.AdvanceOptions
import com.lakesidegames.electioneer.engine.CandidateId
import com.lakesidegames.electioneer.engine.GamePhase
import com.lakesidegames.electioneer.engine.GameState
import com.lakesidegames.electioneer.engine.NewGameOptions
import com.lakesidegames.electioneer.engine.PendingEvent
import com.lakesidegames.electioneer.engine.Projection
import com.lakesidegames.electioneer.engine.advanceTurn
import com.lakesidegames.electioneer.engine.choiceAvailable
import com.lakesidegames.electioneer.engine.createGame
import com.lakesidegames.electioneer.engine.projectElection
import com.lakesidegames.electioneer.engine.resolveEvent
import com.lakesidegames.electioneer.engine.loadGame
import com.lakesidegames.electioneer.engine.saveGame
import com.lakesidegames.electioneer.engine.MobileGame
import com.lakesidegames.electioneer.engine.EventMode
import com.lakesidegames.electioneer.engine.GameModifiers
import com.lakesidegames.electioneer.engine.AdMode
import com.lakesidegames.electioneer.engine.IssueId
import com.lakesidegames.electioneer.engine.nextOpenPlanDay
import com.lakesidegames.electioneer.engine.queuePlannedAction
import com.lakesidegames.electioneer.engine.removePlannedAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Hand-rolled nav; the session survives rotation via the platform ViewModel. Compose collects
// the StateFlows with stock collectAsState (no lifecycle-runtime-compose).
enum class Screen { HOME, SETUP, LOADING, GAME, RESULTS, STORE, ACCOUNT }

val DIFFICULTIES = listOf("easy", "normal", "hard")

class GameSession : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
    private var savePrefs: SharedPreferences? = null
    private val saveKey = "campaign_v1"

    fun attachStorage(context: Context) {
        if (savePrefs != null) return
        val prefs = context.applicationContext.getSharedPreferences("mov_native", Context.MODE_PRIVATE)
        savePrefs = prefs
        val saved = prefs.getString(saveKey, null)?.let(::loadGame) ?: return
        turnSeed = saved.seed
        _game.value = saved.state
        _screen.value = Screen.HOME
        refresh()
        promptNextEvent(saved.state)
    }

    private fun persist() {
        val game = _game.value ?: return
        savePrefs?.edit()?.putString(saveKey, saveGame(game, turnSeed))?.apply()
    }

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen

    private val _game = MutableStateFlow<GameState?>(null)
    val game: StateFlow<GameState?> = _game

    private val _projection = MutableStateFlow<Projection?>(null)
    val projection: StateFlow<Projection?> = _projection

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    // Head of the player's unanswered event queue after a turn, if any.
    private val _pendingDialog = MutableStateFlow<PendingEvent?>(null)
    val pendingDialog: StateFlow<PendingEvent?> = _pendingDialog

    // Result text of the last answered event, shown inside the dialog.
    private val _eventResult = MutableStateFlow<String?>(null)
    val eventResult: StateFlow<String?> = _eventResult

    // Turn recap lines, shown once after each End Turn.
    private val _recap = MutableStateFlow<List<String>?>(null)
    val recap: StateFlow<List<String>?> = _recap

    // Store (Phase 5, #8): products, owned packs, and the last notice.
    private var billing: PlayBilling? = null
    private val _products = MutableStateFlow<List<StoreProduct>>(emptyList())
    val products: StateFlow<List<StoreProduct>> = _products
    private val _owned = MutableStateFlow<Set<String>>(emptySet())
    val owned: StateFlow<Set<String>> = _owned
    private val _storeNotice = MutableStateFlow<String?>(null)
    val storeNotice: StateFlow<String?> = _storeNotice

    fun attachBilling(context: Context) {
        if (billing != null) return
        val b = PlayBilling(context, BuildConfig.PLAY_PUBLIC_KEY)
        billing = b
        b.setOnChangeListener {
            _owned.value = b.entitlements()
            b.lastNotice?.let { _storeNotice.value = it }
        }
        _owned.value = b.entitlements()
        b.listProducts { _products.value = it }
    }

    fun buy(activity: Activity, packId: String) {
        billing?.purchase(activity, packId)
    }

    fun restorePurchases() {
        billing?.restore { _owned.value = it }
    }

    private var turnSeed: String = "1"

    fun candidates() = CANDIDATES

    fun campaigns() = MobileGame.campaigns()
    fun mates(scenario: String, player: CandidateId) = MobileGame.mates(scenario, player.serial)
    fun staffChoices() = MobileGame.staffChoices()

    fun hasSave() = _game.value != null
    fun savedCampaignLabel(): String? = _game.value?.let { game ->
        val id = game.scenarioId ?: "2020"
        MobileGame.campaigns().firstOrNull { it.id == id }?.label
    }

    fun resumeGame() {
        val g = _game.value ?: return
        _screen.value = if (g.phase == GamePhase.RESULT) Screen.RESULTS else Screen.GAME
    }

    fun go(s: Screen) {
        _screen.value = s
    }

    // Play tab resumes the live game (or result) instead of stranding it.
    fun playTab() {
        val g = _game.value
        _screen.value = when {
            else -> Screen.HOME
        }
    }

    fun newGame(scenarioId: String, player: CandidateId, mateId: String, staffIds: List<String>, difficulty: String, eventMode: EventMode, totalTurns: Int, seed: String, whatIfState: String, mirrorMatch: Boolean, pandemic: Boolean) {
        require(MobileGame.campaigns().any { it.id == scenarioId })
        require(MobileGame.mates(scenarioId, player.serial).any { it.id == mateId })
        require(staffIds.size <= 3 && staffIds.distinct().size == staffIds.size)
        require(staffIds.all { id -> MobileGame.staffChoices().any { it.id == id } })
        require(difficulty in DIFFICULTIES && totalTurns in listOf(5, 9, 14))
        require(whatIfState in listOf("", "TX", "FL", "OH", "PA", "MI", "WI", "GA", "AZ", "NC", "NY"))
        _screen.value = Screen.LOADING
        turnSeed = seed
        scope.launch {
        val g = withContext(Dispatchers.Default) { createGame(
            NewGameOptions(
                seed = seed,
                playerCandidate = player,
                difficulty = difficulty,
                scenario = scenarioId,
                runningMate = mateId,
                staff = staffIds,
                eventMode = eventMode,
                totalTurns = totalTurns,
                modifiers = GameModifiers(whatIfState.ifEmpty { null }, mirrorMatch, pandemic),
            ),
        ) }
        _game.value = g
        _selected.value = null
        _pendingDialog.value = null
        _eventResult.value = null
        _recap.value = null
        refresh()
        _screen.value = Screen.GAME
        persist()
        }
    }

    fun playAgain() {
        _screen.value = Screen.SETUP
    }

    fun select(stateId: String?) {
        _selected.value = stateId
    }

    private fun emit() {
        // Shallow copy retires the old reference so StateFlow re-emits; the
        // engine mutates nested maps in place, exactly like the web game.
        _game.value = _game.value?.copy()
        refresh()
        persist()
    }

    private fun refresh() {
        _projection.value = _game.value?.let { projectElection(it) }
    }

    fun slotsLeft(): Int {
        val g = _game.value ?: return 0
        val res = g.resources[g.playerCandidate.serial] ?: return 0
        return (res.actions - g.queuedActions.size).coerceAtLeast(0)
    }

    fun queueAction(type: ActionType, stateId: String? = null) {
        val g = _game.value ?: return
        val day = nextOpenPlanDay(g) ?: return
        if (queuePlannedAction(g, type, stateId, day,
                adMode = if (type == ActionType.ADVERTISE) AdMode.POSITIVE else null,
                spendMillions = if (type == ActionType.ADVERTISE) 8.0 else null)) emit()
    }

    fun queueConfiguredAction(type: ActionType, stateId: String?, day: Int,
                              adMode: AdMode?, spendMillions: Double?, issueId: IssueId?, newPosition: Double?): Boolean {
        val g = _game.value ?: return false
        if (!queuePlannedAction(g, type, stateId, day, adMode, spendMillions, issueId, newPosition)) return false
        emit()
        return true
    }

    fun removeAction(index: Int) {
        val g = _game.value ?: return
        if (removePlannedAction(g, index)) emit()
    }

    fun clearQueue() {
        val g = _game.value ?: return
        g.queuedActions = emptyList()
        emit()
    }

    fun endTurn() {
        val g = _game.value ?: return
        val next = advanceTurn(g, g.queuedActions, turnSeed, AdvanceOptions())
        _game.value = next
        refresh()
        persist()
        if (next.phase == GamePhase.RESULT) {
            _screen.value = Screen.RESULTS
            return
        }
        _recap.value = next.lastRecap.take(6).map {
            if (it.detail.isBlank()) it.label else "${it.label}: ${it.detail}"
        }
        promptNextEvent(next)
    }

    fun dismissRecap() {
        _recap.value = null
    }

    private fun promptNextEvent(g: GameState) {
        _eventResult.value = null
        _pendingDialog.value = g.pendingEvents.firstOrNull { it.forCandidate == g.playerCandidate }
    }

    fun eventDef(eventId: String) = EVENTS_BY_ID[eventId]

    fun availableChoices(eventId: String) =
        (eventDef(eventId)?.choices ?: emptyList()).filter { choice ->
            val g = _game.value ?: return@filter false
            (choice.side == null || choice.side == g.playerCandidate) &&
                choiceAvailable(g, g.playerCandidate, choice)
        }

    fun answerEvent(eventId: String, choiceId: String) {
        val g = _game.value ?: return
        val text = resolveEvent(g, eventId, choiceId, g.playerCandidate)
            ?: "That response is no longer available."
        _eventResult.value = text
        emit()
    }

    fun closeEventDialog() {
        val g = _game.value
        _eventResult.value = null
        if (g == null) {
            _pendingDialog.value = null
            return
        }
        // resolveEvent already removed the answered event, so the head of the
        // remaining queue is the next unanswered player event, if any.
        _pendingDialog.value =
            g.pendingEvents.firstOrNull { it.forCandidate == g.playerCandidate }
    }
}
