package com.lakesidegames.electioneer.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.lakesidegames.electioneer.BuildConfig
import com.lakesidegames.electioneer.billing.PlayBilling
import com.lakesidegames.electioneer.billing.StoreProduct
import com.lakesidegames.electioneer.content.CANDIDATES
import com.lakesidegames.electioneer.content.EVENTS_BY_ID
import com.lakesidegames.electioneer.engine.ActionType
import com.lakesidegames.electioneer.engine.CandidateId
import com.lakesidegames.electioneer.engine.GamePhase
import com.lakesidegames.electioneer.engine.GameState
import com.lakesidegames.electioneer.engine.NewGameOptions
import com.lakesidegames.electioneer.engine.PendingEvent
import com.lakesidegames.electioneer.engine.Projection
import com.lakesidegames.electioneer.engine.advanceCampaignWeek
import com.lakesidegames.electioneer.engine.beginGame
import com.lakesidegames.electioneer.engine.choiceAvailable
import com.lakesidegames.electioneer.engine.createGame
import com.lakesidegames.electioneer.engine.projectElection
import com.lakesidegames.electioneer.engine.answerPlayerEvent
import com.lakesidegames.electioneer.engine.NativeElectionNight
import com.lakesidegames.electioneer.engine.NativeReplay
import com.lakesidegames.electioneer.engine.NativeReplayTracker
import com.lakesidegames.electioneer.engine.NativeAnalysis
import com.lakesidegames.electioneer.engine.NativeUndoHistory
import com.lakesidegames.electioneer.engine.loadGame
import com.lakesidegames.electioneer.engine.saveGame
import com.lakesidegames.electioneer.engine.MobileGame
import com.lakesidegames.electioneer.engine.MobileCampaign
import com.lakesidegames.electioneer.engine.NativeResultsJourney
import com.lakesidegames.electioneer.engine.NativeResults
import com.lakesidegames.electioneer.engine.NativeDaily
import com.lakesidegames.electioneer.engine.NativeDailyAssignment
import com.lakesidegames.electioneer.engine.NativeSaveLibrary
import com.lakesidegames.electioneer.engine.NativeSaveTransfer
import com.lakesidegames.electioneer.engine.NativeNamedSave
import java.util.UUID
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import com.lakesidegames.electioneer.engine.NativeCloudQueue
import java.time.LocalDate
import java.time.ZoneOffset

// Hand-rolled nav; the session survives rotation via the platform ViewModel. Compose collects
// the StateFlows with stock collectAsState (no lifecycle-runtime-compose).
enum class Screen { HOME, SETUP, LOADING, GAME, RESULTS, STORE, ACCOUNT, LIBRARY, WORLD_GAME, SAVES, ANALYSIS, REPLAY, REVEAL, BOARDS, SETTINGS, GUIDE }

val DIFFICULTIES = listOf("easy", "normal", "hard")
internal fun nativeUtcDay(offsetDays: Long = 0): String = LocalDate.now(ZoneOffset.UTC).plusDays(offsetDays).toString()

class GameSession : ViewModel() {
    private val _electionNight = MutableStateFlow<NativeElectionNight?>(null)
    val electionNight: StateFlow<NativeElectionNight?> = _electionNight
    fun reducedMotion(): Boolean = settings.reducedMotion
    private fun presentReveal() {
        val night = currentSnapshot()?.let(NativeElectionNight::create) ?: return
        if (savePrefs?.getBoolean(night.data().storageKey, false) == true) return
        _electionNight.value = night
        _screen.value = Screen.REVEAL
    }
    fun finishReveal() {
        _electionNight.value?.let { savePrefs?.edit()?.putBoolean(it.data().storageKey, true)?.apply() }
        _electionNight.value = null
        resumeGame()
    }
    private val replay = NativeReplayTracker()
    fun canViewReplay(): Boolean = replay.canView(_campaign.value?.isOver() ?: (_game.value?.phase == GamePhase.RESULT))
    fun replayDocument(turn: String?) = if (canViewReplay()) replay.document(turn) else null
    private fun beginReplay() { currentSnapshot()?.let { replay.start(it, if (isDaily()) "daily" else "casual") } }
    fun endWorldWeek(): Boolean {
        val campaign = _campaign.value ?: return false
        val previous = campaign.saveSnapshot()
        val beforeUnits = campaign.standings().first { it.partyId == campaign.playerParty() }.units
        if (!campaign.endWeek()) return false
        replay.record(previous, campaign.saveSnapshot())
        campaignChanged()
        settings.play("turnAdvance")
        val afterUnits = campaign.standings().first { it.partyId == campaign.playerParty() }.units
        if (afterUnits != beforeUnits) settings.play(if (afterUnits > beforeUnits) "pollUp" else "pollDown")
        if (campaign.isOver()) { settings.play(if (afterUnits >= campaign.majority()) "win" else "lose"); presentReveal() }
        else if (campaign.hasPendingEvent()) settings.play("eventPopup")
        return true
    }
    private val undoHistory = NativeUndoHistory()
    fun canUndo(): Boolean = _campaign.value?.canUndo() ?: (_game.value?.queuedActions?.isNotEmpty() == true || undoHistory.available())
    fun undo() {
        _campaign.value?.let { if (it.undo()) { replay.rewind(it.saveSnapshot()); campaignChanged() }; return }
        val game = _game.value ?: return
        if (game.queuedActions.isNotEmpty()) { removeAction(game.queuedActions.lastIndex); return }
        val previous = undoHistory.take()?.let(::loadGame) ?: return
        _game.value = previous.state
        replay.rewind(saveGame(previous.state, previous.seed, previous.difficulty))
        _selected.value = null
        _recap.value = null
        _eventResult.value = null
        promptNextEvent(previous.state)
        emit()
    }
    var shortcut by androidx.compose.runtime.mutableStateOf(""); private set
    var shortcutSequence by androidx.compose.runtime.mutableIntStateOf(0); private set
    fun sendShortcut(key: String) { shortcut = key; shortcutSequence++ }
    lateinit var settings: NativePreferences
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCleared() {
        if (::settings.isInitialized) settings.close()
        scope.cancel()
        super.onCleared()
    }
    private var savePrefs: SharedPreferences? = null
    private val saveKey = "campaign_v1"
    lateinit var account: CampaignAccount
        private set
    var dailySetup: NativeDailyAssignment? = null

    fun dailyAssignment(): NativeDailyAssignment = NativeDaily.assignment(nativeUtcDay())
    fun openDaily(restart: Boolean = false) {
        val today = dailyAssignment()
        if (!restart && (_game.value?.let { NativeDaily.matchesUs(today.date, it) } == true ||
                _campaign.value?.isDaily(today.date) == true)) { resumeGame(); return }
        dailySetup = today
        setupScenarioId = today.electionId
        _screen.value = if (today.countryId == "US") Screen.SETUP else Screen.LIBRARY
    }
    fun scorePayload(): String? = _campaign.value?.scoreSubmission()
        ?: _game.value?.let { NativeResults.submission(it, campaignDifficulty) }
    fun isDaily(): Boolean = _campaign.value?.isDaily(nativeUtcDay())
        ?: (_game.value?.let { NativeDaily.matchesUs(nativeUtcDay(), it) } ?: false)
    fun dailyBest(): Int? = savePrefs?.getInt("daily_best_${nativeUtcDay()}", -1)?.takeIf { it >= 0 }
    fun dailyStreak(): Int = savePrefs?.getInt("daily_streak", 0) ?: 0

    private fun recordDaily() {
        if (!isDaily()) return
        val summary = _campaign.value?.resultSummary() ?: _game.value?.let { NativeResults.us(it, campaignDifficulty) } ?: return
        if (summary.score < 0) return
        val date = nativeUtcDay()
        val streak = NativeDaily.nextStreak(date, nativeUtcDay(-1), savePrefs?.getString("daily_last", null), dailyStreak())
        savePrefs?.edit()?.putInt("daily_best_$date", maxOf(dailyBest() ?: 0, summary.score))
            ?.putString("daily_last", date)?.putInt("daily_streak", streak)?.apply()
    }

    private var cloudQueue = NativeCloudQueue.empty()
    private var cloudTask: Job? = null
    private var cloudInFlight = false
    private val _cloudSyncNotice = MutableStateFlow<String?>(null)
    val cloudSyncNotice: StateFlow<String?> = _cloudSyncNotice
    private fun persistCloudQueue() { savePrefs?.edit()?.putString("cloud_outbox_v1", cloudQueue.json())?.apply() }
    private fun offerCloud(id: String) {
        val owner = account.user.value?.id ?: return
        val entry = saveLibrary.get(id) ?: return
        if (entry.document?.engine != "us") return
        cloudQueue.offer(id, owner, entry.updatedAt)
        persistCloudQueue(); requestCloudSync()
    }
    fun retryCloudSync() {
        account.user.value?.id?.let { owner ->
            saveLibrary.entries().filter { it.document?.engine == "us" && (it.cloudOwner == null || it.cloudOwner == owner) }.forEach { cloudQueue.offer(it.id, owner, it.updatedAt) }
            cloudQueue.retry(owner); persistCloudQueue(); requestCloudSync(force = true)
        }
    }
    fun requestCloudSync(force: Boolean = false) {
        if (cloudTask?.isActive == true) {
            if (!force || cloudInFlight) return
            cloudTask?.cancel()
        }
        cloudTask = scope.launch {
            delay(1000)
            while (true) {
                val owner = account.user.value?.id ?: break
                if (account.busy.value) break
                val now = System.currentTimeMillis()
                val write = cloudQueue.next(owner, now)
                if (write == null) {
                    if (cloudQueue.conflicted(owner)) _cloudSyncNotice.value = "Cloud sync needs attention. Download the changed save or upload your local campaign as a new save. Local play is safe."
                    val wait = cloudQueue.delayMillis(owner, now)
                    if (wait < 0) break
                    delay(maxOf(1000, wait)); continue
                }
                val payload = saveLibrary.uploadJson(write.id, owner, now)
                if (payload == null) { cloudQueue.remove(write.id); persistCloudQueue(); continue }
                cloudInFlight = true
                val outcome = try { account.mirrorSave(write.id, payload, owner) } finally { cloudInFlight = false }
                if (account.user.value?.id != owner) break
                if (outcome.status == "synced") {
                    saveLibrary.markSynced(write.id, owner, outcome.version)
                    persistLibrary(); cloudQueue.acknowledge(write)
                    _cloudSyncNotice.value = "Campaigns synced to the cloud."
                } else {
                    cloudQueue.fail(write, outcome, System.currentTimeMillis())
                    _cloudSyncNotice.value = outcome.message + " Your campaign is saved on this device."
                }
                persistCloudQueue()
            }
        }
    }
    private fun mirrorAutosave(snapshot: String) {
        val previous = saveLibrary.get("autosave")
        val stamp = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: -1) + 1)
        if (saveLibrary.save("autosave", "Autosave", snapshot, stamp)) {
            saveLibrary.setReplay("autosave", replay.json()); persistLibrary(); offerCloud("autosave")
        }
    }
    private var saveLibrary = NativeSaveLibrary.empty()
    private val _namedSaves = MutableStateFlow<List<NativeNamedSave>>(emptyList())
    val namedSaves: StateFlow<List<NativeNamedSave>> = _namedSaves
    private val _saveNotice = MutableStateFlow<String?>(null)
    val saveNotice: StateFlow<String?> = _saveNotice
    private fun persistLibrary() {
        savePrefs?.edit()?.putString("named_saves_v1", saveLibrary.json())?.apply()
        _namedSaves.value = saveLibrary.entries()
    }
    fun analysis(regionId: String?) = _campaign.value?.analysis(regionId) ?: _game.value?.let { NativeAnalysis.us(it, regionId) }
    fun currentSnapshot(): String? = _campaign.value?.saveSnapshot()
        ?: _game.value?.let { saveGame(it, turnSeed, campaignDifficulty) }
    fun resultJourney() = currentSnapshot()?.let { NativeResultsJourney.create(it, nativeUtcDay()) }
    fun playNextCampaign() {
        val next = resultJourney()?.next ?: return
        saveNamed("Completed ${savedCampaignLabel() ?: next.label}")
        dailySetup = null
        val seed = System.currentTimeMillis().toString()
        if (next.countryId == "US") newGame(next.electionId, CandidateId.entries.first { it.serial == next.partyId }, next.mateId,
            emptyList(), next.difficulty, EventMode.entries.first { it.serial == next.eventMode }, 9, seed, "", false, false)
        else startCampaign(next.countryId, next.electionId, next.partyId, next.difficulty, seed)
    }
    fun exportCampaign(): String? = currentSnapshot()?.let { NativeReplay.fileExport(it, replay.json()) }
    fun saveNamed(name: String, id: String = UUID.randomUUID().toString()): Boolean {
        val snapshot = currentSnapshot() ?: return false
        val saved = saveLibrary.save(id, name, snapshot, System.currentTimeMillis())
        if (saved) { saveLibrary.setReplay(id, replay.json()); persistLibrary(); offerCloud(id); _saveNotice.value = "Saved on this device." }
        return saved
    }
    fun renameSave(id: String, name: String) {
        val entry = saveLibrary.get(id) ?: return
        if (saveLibrary.save(id, name, entry.snapshot, maxOf(System.currentTimeMillis(), entry.updatedAt + 1))) { persistLibrary(); offerCloud(id) }
    }
    fun deleteLocalSave(id: String) { saveLibrary.remove(id); cloudQueue.remove(id); persistCloudQueue(); persistLibrary() }
    fun loadNamed(id: String): Boolean {
        val entry = saveLibrary.get(id) ?: return false
        return importCampaign(entry.snapshot, entry.replay)
    }
    fun importCampaign(json: String, replayJson: String? = NativeReplay.fileReplay(json)): Boolean {
        val document = NativeSaveTransfer.inspect(json)
        if (document == null) { _saveNotice.value = "This file is not a supported campaign save."; return false }
        undoHistory.clear()
        currentSnapshot()?.takeIf { it != document.snapshot }?.let {
            val backupId = UUID.randomUUID().toString()
            saveLibrary.save(backupId, "Before loading another campaign", it, System.currentTimeMillis())
            saveLibrary.setReplay(backupId, replay.json())
            persistLibrary()
        }
        replay.restore(replayJson, document.snapshot)
        _eventResult.value = null; _recap.value = null; _pendingDialog.value = null; _selected.value = null
        if (document.engine == "world") {
            _campaign.value = MobileCampaign.restore(document.snapshot)
            _game.value = null; campaignChanged(); _screen.value = Screen.WORLD_GAME
        } else {
            val saved = loadGame(document.snapshot) ?: return false
            _campaign.value = null; _game.value = saved.state
            turnSeed = saved.seed; campaignDifficulty = saved.difficulty
            refresh(); persist(); promptNextEvent(saved.state)
            _screen.value = if (saved.state.phase == GamePhase.RESULT) Screen.RESULTS else Screen.GAME
        }
        return true
    }
    fun uploadSave(id: String) {
        val owner = account.user.value?.id ?: return
        val revision = saveLibrary.get(id)?.updatedAt ?: return
        val payload = saveLibrary.uploadJson(id, owner, System.currentTimeMillis()) ?: return
        account.uploadSave(id, payload) { user, version -> saveLibrary.markSynced(id, user, version); cloudQueue.acknowledgeRevision(id, user, revision); persistCloudQueue(); persistLibrary() }
    }
    fun uploadSaveAsNew(id: String) {
        val entry = saveLibrary.get(id) ?: return
        val copyId = UUID.randomUUID().toString()
        if (saveLibrary.save(copyId, "${entry.name} (copy)", entry.snapshot, System.currentTimeMillis())) {
            saveLibrary.setReplay(copyId, entry.replay); persistLibrary(); uploadSave(copyId)
        }
    }
    fun downloadSave(id: String) = account.downloadSave(id) { key, name, json, owner, version, replayJson ->
        check(saveLibrary.receiveCloud(key, name, json, owner, version, UUID.randomUUID().toString())) { "This cloud save is not supported by this native client." }
        cloudQueue.remove(key); cloudQueue.acknowledgeRevision(key, owner, version); persistCloudQueue()
        saveLibrary.setReplay(key, replayJson)
        persistLibrary()
    }

    fun attachStorage(context: Context) {
        if (savePrefs != null) return
        val prefs = context.applicationContext.getSharedPreferences("mov_native", Context.MODE_PRIVATE)
        savePrefs = prefs
        settings = NativePreferences(context.applicationContext)
        cloudQueue = NativeCloudQueue.restore(prefs.getString("cloud_outbox_v1", null))
        saveLibrary = prefs.getString("named_saves_v1", null)?.let(NativeSaveLibrary::restore) ?: NativeSaveLibrary.empty()
        _namedSaves.value = saveLibrary.entries()
        account = CampaignAccount(context.applicationContext, scope)
        scope.launch {
            var lastOwner: String? = null
            combine(account.user, account.busy) { user, busy -> user to busy }.collect { (user, busy) ->
                if (user?.id != lastOwner) {
                    lastOwner = user?.id
                    if (user != null) saveLibrary.entries().filter { it.cloudOwner == null || it.cloudOwner == user.id }.forEach { offerCloud(it.id) }
                }
                if (!busy) requestCloudSync()
            }
        }
        if (prefs.getString("active_campaign", "us") == "world") {
            val campaign = prefs.getString("world_campaign_v1", null)?.let(MobileCampaign::restore)
            if (campaign != null) {
                _campaign.value = campaign
                replay.restore(prefs.getString("world_replay_v1", null), campaign.saveSnapshot())
                return
            }
        }
        val saved = prefs.getString(saveKey, null)?.let(::loadGame) ?: return
        turnSeed = saved.seed
        campaignDifficulty = saved.difficulty
        _game.value = saved.state
        replay.restore(prefs.getString("us_replay_v1", null), saveGame(saved.state, saved.seed, saved.difficulty))
        _screen.value = Screen.HOME
        account.recordAchievementSnapshot(saveGame(saved.state, saved.seed, saved.difficulty))
        refresh()
        promptNextEvent(saved.state)
    }

    private fun persist() {
        val game = _game.value ?: return
        savePrefs?.edit()?.putString(saveKey, saveGame(game, turnSeed, campaignDifficulty))?.putString("active_campaign", "us")?.putString("us_replay_v1", replay.json())?.apply()
        val earned = NativeResults.achievements(game, campaignDifficulty).map { it.id }
        if (earned.isNotEmpty()) {
            val previous = savePrefs?.getStringSet("achievement_ids", emptySet()).orEmpty()
            savePrefs?.edit()?.putStringSet("achievement_ids", previous + earned)?.apply()
        }
        account.recordAchievementSnapshot(saveGame(game, turnSeed, campaignDifficulty))
        recordDaily()
        mirrorAutosave(saveGame(game, turnSeed, campaignDifficulty))
    }

    private val _campaign = MutableStateFlow<MobileCampaign?>(null)
    val campaign: StateFlow<MobileCampaign?> = _campaign
    private val _campaignVersion = MutableStateFlow(0)
    val campaignVersion: StateFlow<Int> = _campaignVersion

    fun startCampaign(countryId: String, electionId: String, party: String, difficulty: String, seed: String) {
        undoHistory.clear()
        _screen.value = Screen.LOADING
        scope.launch {
            val started = withContext(Dispatchers.Default) {
                MobileCampaign.start(countryId, electionId, party, difficulty, seed)
            }
            _campaign.value = started
            _game.value = null
            beginReplay()
            campaignChanged()
            _screen.value = Screen.WORLD_GAME
        }
    }

    fun campaignChanged() {
        _campaignVersion.value += 1
        val campaign = _campaign.value ?: return
        savePrefs?.edit()?.putString("world_campaign_v1", campaign.saveSnapshot())
            ?.putString("active_campaign", "world")?.putString("world_replay_v1", replay.json())?.apply()
        recordDaily()
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
    var campaignDifficulty: String? = null
        private set

    fun candidates() = CANDIDATES

    var setupScenarioId: String = "2024"

    fun campaigns() = MobileGame.campaigns()
    fun mates(scenario: String, player: CandidateId) = MobileGame.mates(scenario, player.serial)
    fun staffChoices() = MobileGame.staffChoices()

    fun hasSave() = _game.value != null || _campaign.value != null
    fun savedCampaignLabel(): String? = _campaign.value?.label() ?: _game.value?.let { game ->
        val id = game.scenarioId ?: "2020"
        MobileGame.campaigns().firstOrNull { it.id == id }?.label
    }

    fun resumeGame() {
        if (_campaign.value != null) {
            _screen.value = Screen.WORLD_GAME
            if (_campaign.value?.isOver() == true) presentReveal()
            return
        }
        val g = _game.value ?: return
        _screen.value = if (g.phase == GamePhase.RESULT) Screen.RESULTS else Screen.GAME
        if (g.phase == GamePhase.RESULT) presentReveal()
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
        undoHistory.clear()
        require(MobileGame.campaigns().any { it.id == scenarioId })
        require(MobileGame.mates(scenarioId, player.serial).any { it.id == mateId })
        require(staffIds.size <= 3 && staffIds.distinct().size == staffIds.size)
        require(staffIds.all { id -> MobileGame.staffChoices().any { it.id == id } })
        require(difficulty in DIFFICULTIES && totalTurns in listOf(5, 9, 14))
        require(whatIfState in listOf("", "TX", "FL", "OH", "PA", "MI", "WI", "GA", "AZ", "NC", "NY"))
        _screen.value = Screen.LOADING
        _campaign.value = null
        turnSeed = seed
        campaignDifficulty = difficulty
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
        _game.value = beginGame(g)
        beginReplay()
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
        if (g.phase == GamePhase.RESULT) return
        val beforeUnits = projectElection(g).ev[g.playerCandidate.serial] ?: 0
        val previous = saveGame(g, turnSeed, campaignDifficulty)
        undoHistory.record(previous)
        val next = advanceCampaignWeek(g, campaignDifficulty)
        _game.value = next
        replay.record(previous, saveGame(next, turnSeed, campaignDifficulty))
        refresh()
        persist()
        settings.play("turnAdvance")
        val afterUnits = projectElection(next).ev[next.playerCandidate.serial] ?: 0
        if (afterUnits != beforeUnits) settings.play(if (afterUnits > beforeUnits) "pollUp" else "pollDown")
        if (next.phase == GamePhase.RESULT) {
            settings.play(if (next.result?.winner == next.playerCandidate.serial) "win" else "lose")
            _screen.value = Screen.RESULTS
            presentReveal()
            return
        }
        _recap.value = next.lastRecap.map {
            if (it.detail.isBlank()) it.label else "${it.label}: ${it.detail}"
        }
        promptNextEvent(next)
    }

    fun dismissRecap() {
        _recap.value = null
    }

    private fun promptNextEvent(g: GameState) {
        _eventResult.value = null
        val previous = _pendingDialog.value
        _pendingDialog.value = g.pendingEvents.firstOrNull { it.forCandidate == g.playerCandidate }
        if (previous == null && _pendingDialog.value != null) settings.play("eventPopup")
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
        val text = answerPlayerEvent(g, eventId, choiceId)
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
