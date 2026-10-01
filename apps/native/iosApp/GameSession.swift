import SwiftUI
import shared
import Combine

// Phase 4 session (#22): mirrors androidApp GameSession. The Swift side
// talks only to the MobileGame facade (string serials in, plain reads out)
// and bumps `version` after every mutation so views re-render.
func nativeUTCDay(offsetDays: Int = 0) -> String {
    let formatter = DateFormatter()
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.string(from: Date(timeIntervalSinceNow: Double(offsetDays) * 86400))
}

enum PlayScreen: Equatable {
    case home, setup, loading, game, results, library, worldGame
}

@MainActor
final class GameSession: ObservableObject {
    @Published var showReveal = false
    private(set) var electionNight: NativeElectionNight?
    private func presentReveal(force: Bool = false) {
        guard let snapshot = currentSnapshot(), let night = NativeElectionNight.companion.create(snapshot: snapshot) else { return }
        if !force && UserDefaults.standard.bool(forKey: night.data().storageKey) { return }
        electionNight = night
        showReveal = true
    }
    func finishReveal() {
        if let night = electionNight { UserDefaults.standard.set(true, forKey: night.data().storageKey) }
        showReveal = false
    }
    private let replay = NativeReplayTracker()
    func canViewReplay() -> Bool { replay.canView(finished: campaign?.isOver() ?? game?.isOver() ?? false) }
    func replayDocument(turn: String?) -> NativeAnalysisDocument? { canViewReplay() ? replay.document(selectedTurn: turn) : nil }
    private func beginReplay() {
        guard let snapshot = currentSnapshot() else { return }
        replay.start(snapshot: snapshot, mode: isDaily() ? "daily" : "casual")
    }
    @discardableResult func endWorldWeek() -> Bool {
        guard let campaign else { return false }
        let beforeUnits = campaign.standings().first { $0.partyId == campaign.playerParty() }?.units ?? 0
        let previous = campaign.saveSnapshot()
        guard campaign.endWeek() else { return false }
        replay.record(previous: previous, next: campaign.saveSnapshot())
        touch()
        settings.play("turnAdvance")
        let afterUnits = campaign.standings().first { $0.partyId == campaign.playerParty() }?.units ?? 0
        if afterUnits != beforeUnits { settings.play(afterUnits > beforeUnits ? "pollUp" : "pollDown") }
        if campaign.isOver() { settings.play(afterUnits >= campaign.majority() ? "win" : "lose"); presentReveal() }
        else if campaign.hasPendingEvent() { settings.play("eventPopup") }
        return true
    }
    private static let saveKey = "mov_campaign_v1"
    @Published var playScreen: PlayScreen = .home
    @Published var setupScenarioId: String? = nil
    @Published var version = 0
    @Published var dailySetup: NativeDailyAssignment?
    let account = CampaignAccount()
    let settings = NativePreferences()
    @Published var shortcutSequence = 0
    private(set) var shortcut = ""
    func sendShortcut(_ key: String) { shortcut = key; shortcutSequence += 1 }

    func dailyAssignment() -> NativeDailyAssignment { NativeDaily.companion.assignment(dateUTC: nativeUTCDay()) }
    func openDaily(restart: Bool = false) {
        let today = dailyAssignment()
        if !restart && (game?.isDaily(dateUTC: today.date) == true || campaign?.isDaily(dateUTC: today.date) == true) {
            resumeGame()
            return
        }
        dailySetup = today
        setupScenarioId = today.electionId
        playScreen = today.countryId == "US" ? .setup : .library
    }
    func scorePayload() -> String? { campaign?.scoreSubmission() ?? game?.scoreSubmission() }
    func isDaily() -> Bool { campaign?.isDaily(dateUTC: nativeUTCDay()) ?? game?.isDaily(dateUTC: nativeUTCDay()) ?? false }
    func dailyBest() -> Int? {
        let key = "mov_daily_best_\(nativeUTCDay())"
        return UserDefaults.standard.object(forKey: key) == nil ? nil : UserDefaults.standard.integer(forKey: key)
    }
    func dailyStreak() -> Int { UserDefaults.standard.integer(forKey: "mov_daily_streak") }
    private func recordDaily() {
        guard isDaily(), let summary = campaign?.resultSummary() ?? game?.resultSummary(), summary.score >= 0 else { return }
        let date = nativeUTCDay()
        let streak = NativeDaily.companion.nextStreak(dateUTC: date, yesterdayUTC: nativeUTCDay(offsetDays: -1),
            lastPlayed: UserDefaults.standard.string(forKey: "mov_daily_last"), streak: Int32(dailyStreak()))
        UserDefaults.standard.set(max(dailyBest() ?? 0, Int(summary.score)), forKey: "mov_daily_best_\(date)")
        UserDefaults.standard.set(date, forKey: "mov_daily_last")
        UserDefaults.standard.set(Int(streak), forKey: "mov_daily_streak")
    }

    @Published var recapLines: [String] = []
    @Published var showRecap = false
    @Published var eventId: String? = nil
    @Published var eventResult: String? = nil

    private var game: MobileGame? = nil
    private(set) var campaign: MobileCampaign? = nil
    #if targetEnvironment(simulator)
    private var didPrepareSimulatorCapture = false
    #endif

    private var cloudQueue = NativeCloudQueue.companion.empty()
    private var cloudTask: Task<Void, Never>?
    private var cloudInFlight = false
    private var cloudGeneration = 0
    private var accountObserver: AnyCancellable?
    @Published var cloudSyncNotice: String?
    private func persistCloudQueue() { UserDefaults.standard.set(cloudQueue.json(), forKey: "mov_cloud_outbox_v1") }
    private func offerCloud(_ id: String) {
        guard let owner = account.user?.id, cloudQueue.offerSave(library: saveLibrary, id: id, owner: owner) else { return }
        persistLibrary(); persistCloudQueue(); requestCloudSync()
    }
    func retryCloudSync() {
        guard let owner = account.user?.id else { return }
        for entry in saveLibrary.entries() where entry.document?.engine == "us" && (entry.cloudOwner == nil || entry.cloudOwner == owner) { _ = cloudQueue.offerSave(library: saveLibrary, id: entry.id, owner: owner) }
        persistLibrary(); cloudQueue.retry(owner: owner); persistCloudQueue(); requestCloudSync(force: true)
    }
    func requestCloudSync(force: Bool = false) {
        if cloudTask != nil {
            guard force && !cloudInFlight else { return }
            cloudTask?.cancel(); cloudTask = nil
        }
        cloudGeneration += 1
        let generation = cloudGeneration
        cloudTask = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
            guard let self else { return }
            defer { if self.cloudGeneration == generation { self.cloudTask = nil } }
            while !Task.isCancelled {
                guard let owner = self.account.user?.id, !self.account.busy else { return }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                guard let write = self.cloudQueue.next(owner: owner, now: now) else {
                    if self.cloudQueue.conflicted(owner: owner) { self.cloudSyncNotice = "Cloud sync needs attention. Download the changed save or upload your local campaign as a new save. Local play is safe." }
                    let wait = self.cloudQueue.delayMillis(owner: owner, now: now)
                    guard wait >= 0 else { return }
                    do { try await Task.sleep(nanoseconds: UInt64(max(1000, wait)) * 1_000_000) } catch { return }
                    continue
                }
                guard let payload = self.saveLibrary.uploadJson(id: write.id, owner: owner, now: now) else { self.cloudQueue.removeOwned(id: write.id, owner: owner); self.persistCloudQueue(); continue }
                self.cloudInFlight = true
                let outcome = await self.account.mirrorSave(id: write.id, payload: payload, owner: owner)
                self.cloudInFlight = false
                guard self.account.user?.id == owner else { return }
                if outcome.status == "synced" {
                    self.saveLibrary.markSynced(id: write.id, owner: owner, version: outcome.version)
                    self.persistLibrary(); self.cloudQueue.acknowledge(write: write)
                    self.cloudSyncNotice = "Campaigns synced to the cloud."
                } else {
                    self.cloudQueue.fail(write: write, outcome: outcome, now: Int64(Date().timeIntervalSince1970 * 1000))
                    self.cloudSyncNotice = outcome.message + " Your campaign is saved on this device."
                }
                self.persistCloudQueue()
            }
        }
    }
    private var activeAutosaveID = UserDefaults.standard.string(forKey: "mov_active_autosave_id") ?? "autosave"
    private func beginAutosave(owner: String? = nil) {
        activeAutosaveID = NativeAutosave.companion.slot(owner: owner ?? account.user?.id, nonce: UUID().uuidString)
        UserDefaults.standard.set(activeAutosaveID, forKey: "mov_active_autosave_id")
    }
    private func mirrorAutosave(_ snapshot: String) {
        let previous = saveLibrary.get(id: activeAutosaveID)
        let stamp = max(Int64(Date().timeIntervalSince1970 * 1000), (previous?.updatedAt ?? -1) + 1)
        if saveLibrary.save(id: activeAutosaveID, name: "Autosave", snapshot: snapshot, updatedAt: stamp) {
            saveLibrary.setReplay(id: activeAutosaveID, json: replay.json()); persistLibrary(); offerCloud(activeAutosaveID)
        }
    }
    private var saveLibrary = NativeSaveLibrary.companion.empty()
    @Published var namedSaves: [NativeNamedSave] = []
    @Published var saveNotice: String?
    func analysis(regionId: String?) -> NativeAnalysisDocument? { campaign?.analysis(regionId: regionId) ?? game?.analysis(regionId: regionId) }
    func currentSnapshot() -> String? { campaign?.saveSnapshot() ?? game?.saveSnapshot() }
    func resultJourney() -> NativeResultJourney? {
        guard let snapshot = currentSnapshot() else { return nil }
        return NativeResultsJourney.companion.create(snapshot: snapshot, dateUTC: nativeUTCDay())
    }
    func playNextCampaign() {
        guard let next = resultJourney()?.next else { return }
        saveNamed(name: "Completed \(savedCampaignLabel)")
        dailySetup = nil
        let seed = String(Int64(Date().timeIntervalSince1970 * 1000))
        if next.countryId == "US" {
            newGame(scenarioId: next.electionId, playerSerial: next.partyId, mateId: next.mateId,
                    staffIds: [], difficulty: next.difficulty, eventMode: next.eventMode, totalTurns: 9,
                    seed: seed, whatIfState: "", mirrorMatch: false, pandemic: false)
        } else { newCampaign(countryId: next.countryId, electionId: next.electionId, partyId: next.partyId, difficulty: next.difficulty, seed: seed) }
    }
    func exportCampaign() -> String? {
        guard let snapshot = currentSnapshot() else { return nil }
        return NativeReplay.shared.fileExport(snapshot: snapshot, replay: replay.json())
    }
    private func persistLibrary() {
        UserDefaults.standard.set(saveLibrary.json(), forKey: "mov_named_saves_v1")
        namedSaves = saveLibrary.entries()
    }
    @discardableResult func saveNamed(name: String, id: String = UUID().uuidString) -> Bool {
        guard let snapshot = currentSnapshot() else { return false }
        let saved = saveLibrary.save(id: id, name: name, snapshot: snapshot, updatedAt: Int64(Date().timeIntervalSince1970 * 1000))
        if saved { saveLibrary.setReplay(id: id, json: replay.json()); persistLibrary(); offerCloud(id); saveNotice = "Saved on this device." }
        return saved
    }
    func renameSave(id: String, name: String) {
        guard let entry = saveLibrary.get(id: id) else { return }
        if saveLibrary.save(id: id, name: name, snapshot: entry.snapshot, updatedAt: max(Int64(Date().timeIntervalSince1970 * 1000), entry.updatedAt + 1)) { persistLibrary(); offerCloud(id) }
    }
    func deleteLocalSave(id: String) { saveLibrary.remove(id: id); cloudQueue.remove(id: id); persistCloudQueue(); persistLibrary() }
    @discardableResult func loadNamed(id: String) -> Bool {
        guard let entry = saveLibrary.get(id: id) else { return false }
        return importCampaign(json: entry.snapshot, replayJSON: entry.replay, owner: entry.cloudOwner)
    }
    @discardableResult func importCampaign(json: String, replayJSON: String? = nil, owner: String? = nil) -> Bool {
        guard let document = NativeSaveTransfer.companion.inspect(json: json) else {
            saveNotice = "This file is not a supported campaign save."; return false
        }
        if let previous = currentSnapshot(), previous != document.snapshot {
            let backupID = UUID().uuidString
            _ = saveLibrary.save(id: backupID, name: "Before loading another campaign", snapshot: previous,
                updatedAt: Int64(Date().timeIntervalSince1970 * 1000))
            saveLibrary.setReplay(id: backupID, json: replay.json())
            if let previousOwner = saveLibrary.get(id: activeAutosaveID)?.cloudOwner { _ = saveLibrary.claimCloud(id: backupID, owner: previousOwner) }
            persistLibrary()
        }
        beginAutosave(owner: owner)
        replay.restore(json: replayJSON ?? NativeReplay.shared.fileReplay(json: json), snapshot: document.snapshot)
        eventId = nil; eventResult = nil; showRecap = false; recapLines = []
        if document.engine == "world" {
            campaign = MobileCampaign.companion.restore(snapshot: document.snapshot)
            game = nil; playScreen = .worldGame
        } else {
            game = MobileGame.companion.restore(snapshot: document.snapshot)
            campaign = nil; eventId = game?.pendingEventIds().first
            playScreen = game?.isOver() == true ? .results : .game
        }
        touch()
        return true
    }
    func uploadSave(id: String) async {
        guard let owner = account.user?.id else { return }
        guard saveLibrary.claimCloud(id: id, owner: owner) else {
            saveNotice = "This save belongs to another account. Use Upload as new to sync a copy with this account."; return
        }
        persistLibrary()
        guard let payload = saveLibrary.uploadJson(id: id, owner: owner, now: Int64(Date().timeIntervalSince1970 * 1000)) else { return }
        let revision = saveLibrary.get(id: id)?.updatedAt ?? 0
        if let version = await account.uploadSave(id: id, payload: payload) {
            saveLibrary.markSynced(id: id, owner: owner, version: version); cloudQueue.acknowledgeRevision(id: id, owner: owner, revision: revision); persistCloudQueue(); persistLibrary()
        }
    }
    func uploadSaveAsNew(id: String) async {
        guard let entry = saveLibrary.get(id: id) else { return }
        let copyId = UUID().uuidString
        if saveLibrary.save(id: copyId, name: "\(entry.name) (copy)", snapshot: entry.snapshot,
            updatedAt: Int64(Date().timeIntervalSince1970 * 1000)) {
            saveLibrary.setReplay(id: copyId, json: entry.replay); persistLibrary(); await uploadSave(id: copyId)
        }
    }
    func downloadSave(id: String) async {
        guard let owner = account.user?.id, let remote = await account.downloadSave(id: id) else { return }
        guard saveLibrary.receiveCloud(id: remote.id, name: remote.name, json: remote.state, owner: owner,
            version: remote.updatedAt, backupId: UUID().uuidString) else {
            saveNotice = "This cloud save is not supported by this native client."; return
        }
        cloudQueue.removeOwned(id: remote.id, owner: owner); cloudQueue.acknowledgeRevision(id: remote.id, owner: owner, revision: remote.updatedAt); persistCloudQueue()
        saveLibrary.setReplay(id: remote.id, json: remote.replay)
        persistLibrary(); saveNotice = "Downloaded. Any different local copy was kept as a backup."
    }

    init() {
        if let json = UserDefaults.standard.string(forKey: "mov_named_saves_v1"),
           let restored = NativeSaveLibrary.companion.restore(json: json) { saveLibrary = restored }
        namedSaves = saveLibrary.entries()
        cloudQueue = NativeCloudQueue.companion.restore(json: UserDefaults.standard.string(forKey: "mov_cloud_outbox_v1"))
        var lastOwner: String?
        accountObserver = account.$user.combineLatest(account.$busy).sink { [weak self] user, busy in
            guard let self else { return }
            if user?.id != lastOwner {
                lastOwner = user?.id
                if let owner = user?.id { for entry in self.saveLibrary.entries() where entry.cloudOwner == nil || entry.cloudOwner == owner { self.offerCloud(entry.id) } }
            }
            if !busy { self.requestCloudSync() }
        }
        if UserDefaults.standard.string(forKey: "mov_active_campaign") == "world",
           let snapshot = UserDefaults.standard.string(forKey: "mov_world_campaign_v1"),
           let restored = MobileCampaign.companion.restore(snapshot: snapshot) {
            campaign = restored
            replay.restore(json: UserDefaults.standard.string(forKey: "mov_world_replay_v1"), snapshot: snapshot)
            return
        }
        if let snapshot = UserDefaults.standard.string(forKey: Self.saveKey),
           let restored = MobileGame.companion.restore(snapshot: snapshot) {
            game = restored
            replay.restore(json: UserDefaults.standard.string(forKey: "mov_us_replay_v1"), snapshot: snapshot)
            playScreen = .home
            eventId = restored.pendingEventIds().first
            account.recordAchievementSnapshot(snapshot)
        }
    }

    #if targetEnvironment(simulator)
    func prepareSimulatorCaptureIfRequested() {
        guard !didPrepareSimulatorCapture else { return }
        didPrepareSimulatorCapture = true
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--mov-capture-library") {
            playScreen = .library
        } else if arguments.contains("--mov-capture-daily") {
            openDaily(restart: true)
        } else if let countryId = ["UK", "CA", "DE", "FR", "AU"].first(where: {
            arguments.contains("--mov-capture-world-\($0.lowercased())") || ($0 == "DE" && arguments.contains("--mov-capture-world-results"))
        }) {
            guard let election = MobileCampaign.companion.elections(countryId: countryId).first,
                  let party = MobileCampaign.companion.parties(countryId: countryId, electionId: election.nativeId).first else { return }
            newCampaign(countryId: countryId, electionId: election.nativeId, partyId: party.id,
                        difficulty: "normal", seed: "native-ui-capture")
        } else if arguments.contains("--mov-capture-setup") || arguments.contains("--mov-capture-setup-2016") {
            playScreen = .setup
        } else if arguments.contains("--mov-capture-game") || arguments.contains("--mov-capture-plan") ||
                    arguments.contains("--mov-capture-ask") || arguments.contains("--mov-capture-ask-login") || arguments.contains("--mov-capture-results") || arguments.contains("--mov-capture-reveal") || arguments.contains("--mov-capture-saves") || arguments.contains("--mov-capture-analysis") || arguments.contains("--mov-capture-replay") {
            let mate = mates(scenarioId: "2024", playerSerial: "dem").first(where: { $0.historical })
                ?? mates(scenarioId: "2024", playerSerial: "dem").first
            guard let mate else { return }
            newGame(scenarioId: "2024", playerSerial: "dem", mateId: mate.id,
                    staffIds: [], difficulty: "normal", eventMode: "historical",
                    totalTurns: 9, seed: "240927", whatIfState: "",
                    mirrorMatch: false, pandemic: false)
        }
    }
    #endif

    var hasGame: Bool { game != nil || campaign != nil }
    var savedCampaignLabel: String { campaign?.label() ?? game?.campaignLabel() ?? "Your campaign" }

    func newCampaign(countryId: String, electionId: String, partyId: String, difficulty: String, seed: String) {
        beginAutosave()
        playScreen = .loading
        DispatchQueue.global(qos: .userInitiated).async {
            let started = MobileCampaign.companion.start(countryId: countryId, electionId: electionId,
                partyId: partyId, difficulty: difficulty, seed: seed)
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-world-results") {
                while !started.isOver() {
                    if started.hasPendingEvent(), let choice = started.eventChoices().first {
                        _ = started.answerEvent(choiceId: choice.id)
                    } else { _ = started.endWeek() }
                }
            }
            #endif
            DispatchQueue.main.async {
                self.campaign = started
                self.game = nil
                self.eventId = nil
                self.eventResult = nil
                self.showRecap = false
                self.recapLines = []
                self.playScreen = .worldGame
                self.beginReplay()
                self.touch()
            }
        }
    }

    func askURL() -> URL {
        let base = "https://ask.lakesidegames.net/from-mov"
        guard let game,
              let data = game.askSnapshot().data(using: .utf8),
              data.count <= 8_000 else {
            return URL(string: "https://ask.lakesidegames.net/?game=electioneer")!
        }
        let encoded = data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return URL(string: "\(base)#mov=\(encoded)")!
    }

    func resumeGame() {
        if campaign != nil {
            playScreen = .worldGame
            if campaign?.isOver() == true { presentReveal() }
            return
        }
        guard let game = game else { return }
        playScreen = game.isOver() ? .results : .game
        if game.isOver() { presentReveal() }
    }

    func playTab() {
        playScreen = .home
    }

    func touch() {
        version += 1
        if let campaign {
            UserDefaults.standard.set(campaign.saveSnapshot(), forKey: "mov_world_campaign_v1")
            UserDefaults.standard.set("world", forKey: "mov_active_campaign")
            UserDefaults.standard.set(replay.json(), forKey: "mov_world_replay_v1")
            recordDaily()
            return
        }
        if let game = game {
            UserDefaults.standard.set(game.saveSnapshot(), forKey: Self.saveKey)
            UserDefaults.standard.set("us", forKey: "mov_active_campaign")
            UserDefaults.standard.set(replay.json(), forKey: "mov_us_replay_v1")
            mirrorAutosave(game.saveSnapshot())
            let previous = UserDefaults.standard.stringArray(forKey: "mov_achievement_ids") ?? []
            let earned = game.resultAchievements().map { $0.id }
            if !earned.isEmpty {
                UserDefaults.standard.set(Array(Set(previous + earned)).sorted(), forKey: "mov_achievement_ids")
            }
            account.recordAchievementSnapshot(game.saveSnapshot())
            recordDaily()
        }
    }

    func candidates() -> [Candidate] { MobileGame.companion.candidates() }

    func difficulties() -> [String] { MobileGame.companion.difficulties() }
    func campaigns() -> [CampaignChoice] { MobileGame.companion.campaigns() }
    func mates(scenarioId: String, playerSerial: String) -> [MateChoice] { MobileGame.companion.mates(scenarioId: scenarioId, playerSerial: playerSerial) }
    func staffChoices() -> [StaffChoice] { MobileGame.companion.staffChoices() }

    func newGame(scenarioId: String, playerSerial: String, mateId: String, staffIds: [String], difficulty: String, eventMode: String, totalTurns: Int, seed: String, whatIfState: String, mirrorMatch: Bool, pandemic: Bool) {
        beginAutosave()
        playScreen = .loading
        DispatchQueue.global(qos: .userInitiated).async {
            let started = MobileGame.companion.startConfiguredGame(
                scenarioId: scenarioId, playerSerial: playerSerial, mateId: mateId,
                staffIds: staffIds, difficulty: difficulty, eventModeSerial: eventMode,
                totalTurns: Int32(totalTurns), seed: seed, whatIfState: whatIfState,
                mirrorMatch: mirrorMatch, pandemic: pandemic)
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-results") || ProcessInfo.processInfo.arguments.contains("--mov-capture-reveal") {
                for _ in 0..<Int(started.totalTurns()) { _ = started.endTurn() }
            }
            #endif
            DispatchQueue.main.async {
                self.game = started
                self.campaign = nil
                self.recapLines = []
                self.showRecap = false
                self.eventId = nil
                self.eventResult = nil
                self.playScreen = started.isOver() ? .results : .game
                self.beginReplay()
                #if targetEnvironment(simulator)
                if ProcessInfo.processInfo.arguments.contains("--mov-capture-replay") {
                    for _ in 0..<3 {
                        let previous = started.saveSnapshot()
                        _ = started.endTurn()
                        self.replay.record(previous: previous, next: started.saveSnapshot())
                    }
                }
                #endif
                self.touch()
                #if targetEnvironment(simulator)
                if ProcessInfo.processInfo.arguments.contains("--mov-capture-reveal") { self.presentReveal(force: true) }
                if ProcessInfo.processInfo.arguments.contains("--mov-capture-saves") { self.saveNamed(name: "Campaign checkpoint") }
                #endif
            }
        }
    }

    func currentGame() -> MobileGame? { game }

    func projection() -> (dem: Int, rep: Int, tossup: Int) {
        guard let g = game else { return (0, 0, 0) }
        return (Int(g.evDem()), Int(g.evRep()), Int(g.tossupEv()))
    }

    func contestsById() -> [String: ContestProjection] {
        guard let g = game else { return [:] }
        var out: [String: ContestProjection] = [:]
        for c in g.contests() { out[c.stateId] = c }
        return out
    }

    func states() -> [StateContest] { game?.stateList() ?? [] }

    func issues() -> [Issue] { MobileGame.companion.issues() }
    func playerIssuePosition(_ issueSerial: String) -> Double { game?.playerIssuePosition(issueSerial: issueSerial) ?? 0 }
    func plannedActions() -> [PlannedActionRow] { game?.plannedActions() ?? [] }

    func queueConfiguredAction(typeSerial: String, stateId: String?, day: Int,
                               adModeSerial: String?, spendMillions: Double?,
                               issueSerial: String?, newPosition: Double?) -> Bool {
        guard let game = game else { return false }
        let added = game.queueConfiguredAction(typeSerial: typeSerial, stateId: stateId,
                                               day: Int32(day), adModeSerial: adModeSerial,
                                               spendMillions: spendMillions.map { KotlinDouble(double: $0) },
                                               issueSerial: issueSerial,
                                               newPosition: newPosition.map { KotlinDouble(double: $0) })
        if added { touch() }
        return added
    }

    func removeAction(_ index: Int) {
        guard let game = game else { return }
        if game.removeAction(index: Int32(index)) { touch() }
    }

    func queueAction(typeSerial: String, stateId: String?) {
        game?.queueAction(typeSerial: typeSerial, stateId: stateId)
        touch()
    }

    func clearQueue() {
        game?.clearQueue()
        touch()
    }

    func endTurn() {
        guard let g = game else { return }
        let beforeUnits = g.playerSerial() == "dem" ? projection().dem : projection().rep
        let previous = g.saveSnapshot()
        let recap = g.endTurn()
        guard g.saveSnapshot() != previous else { return }
        if g.saveSnapshot() != previous { replay.record(previous: previous, next: g.saveSnapshot()) }
        var lines: [String] = []
        for item in recap {
            lines.append(item.detail.isEmpty ? item.label : "\(item.label): \(item.detail)")
        }
        touch()
        settings.play("turnAdvance")
        let afterUnits = g.playerSerial() == "dem" ? projection().dem : projection().rep
        if afterUnits != beforeUnits { settings.play(afterUnits > beforeUnits ? "pollUp" : "pollDown") }
        if g.isOver() {
            settings.play(g.resultWinnerSerial() == g.playerSerial() ? "win" : "lose")
            playScreen = .results
            presentReveal()
            return
        }
        if !lines.isEmpty {
            recapLines = lines
            showRecap = true
        }
        promptNextEvent()
    }

    func canUndo() -> Bool { campaign?.canUndo() ?? game?.canUndo() ?? false }
    func undo() {
        let changed = campaign?.undo() ?? game?.undo() ?? false
        guard changed else { return }
        if let snapshot = currentSnapshot() { replay.rewind(snapshot: snapshot) }
        recapLines = []; showRecap = false; eventResult = nil; eventId = game?.pendingEventIds().first
        touch()
    }

    func dismissRecap() {
        showRecap = false
        promptNextEvent()
    }

    private func promptNextEvent() {
        eventResult = nil
        let previous = eventId
        eventId = game?.pendingEventIds().first
        if previous == nil && eventId != nil { settings.play("eventPopup") }
    }

    func answerEvent(choiceId: String) {
        guard let g = game, let eid = eventId else { return }
        eventResult = g.answerEvent(eventId: eid, choiceId: choiceId)
        touch()
    }

    func closeEvent() {
        eventResult = nil
        if let g = game {
            eventId = g.pendingEventIds().first
        } else {
            eventId = nil
        }
    }
}
