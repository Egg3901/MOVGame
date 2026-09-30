import SwiftUI
import shared

// Phase 4 session (#22): mirrors androidApp GameSession. The Swift side
// talks only to the MobileGame facade (string serials in, plain reads out)
// and bumps `version` after every mutation so views re-render.
enum PlayScreen: Equatable {
    case home, setup, loading, game, results, library, worldGame
}

final class GameSession: ObservableObject {
    private static let saveKey = "mov_campaign_v1"
    @Published var playScreen: PlayScreen = .home
    @Published var setupScenarioId: String? = nil
    @Published var version = 0

    @Published var recapLines: [String] = []
    @Published var showRecap = false
    @Published var eventId: String? = nil
    @Published var eventResult: String? = nil

    private var game: MobileGame? = nil
    private(set) var campaign: MobileCampaign? = nil
    #if targetEnvironment(simulator)
    private var didPrepareSimulatorCapture = false
    #endif

    init() {
        if UserDefaults.standard.string(forKey: "mov_active_campaign") == "world",
           let snapshot = UserDefaults.standard.string(forKey: "mov_world_campaign_v1"),
           let restored = MobileCampaign.companion.restore(snapshot: snapshot) {
            campaign = restored
            return
        }
        if let snapshot = UserDefaults.standard.string(forKey: Self.saveKey),
           let restored = MobileGame.companion.restore(snapshot: snapshot) {
            game = restored
            playScreen = .home
            eventId = restored.pendingEventIds().first
        }
    }

    #if targetEnvironment(simulator)
    func prepareSimulatorCaptureIfRequested() {
        guard !didPrepareSimulatorCapture else { return }
        didPrepareSimulatorCapture = true
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--mov-capture-library") {
            playScreen = .library
        } else if let countryId = ["UK", "CA", "DE", "FR", "AU"].first(where: { arguments.contains("--mov-capture-world-\($0.lowercased())") }) {
            guard let election = MobileCampaign.companion.elections(countryId: countryId).first,
                  let party = MobileCampaign.companion.parties(countryId: countryId, electionId: election.nativeId).first else { return }
            newCampaign(countryId: countryId, electionId: election.nativeId, partyId: party.id,
                        difficulty: "normal", seed: "native-ui-capture")
        } else if arguments.contains("--mov-capture-setup") || arguments.contains("--mov-capture-setup-2016") {
            playScreen = .setup
        } else if arguments.contains("--mov-capture-game") || arguments.contains("--mov-capture-plan") ||
                    arguments.contains("--mov-capture-ask") || arguments.contains("--mov-capture-ask-login") {
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
        playScreen = .loading
        DispatchQueue.global(qos: .userInitiated).async {
            let started = MobileCampaign.companion.start(countryId: countryId, electionId: electionId,
                partyId: partyId, difficulty: difficulty, seed: seed)
            DispatchQueue.main.async {
                self.campaign = started
                self.game = nil
                self.eventId = nil
                self.eventResult = nil
                self.showRecap = false
                self.recapLines = []
                self.playScreen = .worldGame
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
            return
        }
        guard let game = game else { return }
        playScreen = game.isOver() ? .results : .game
    }

    func playTab() {
        playScreen = .home
    }

    func touch() {
        version += 1
        if let campaign {
            UserDefaults.standard.set(campaign.saveSnapshot(), forKey: "mov_world_campaign_v1")
            UserDefaults.standard.set("world", forKey: "mov_active_campaign")
            return
        }
        if let game = game {
            UserDefaults.standard.set(game.saveSnapshot(), forKey: Self.saveKey)
            UserDefaults.standard.set("us", forKey: "mov_active_campaign")
        }
    }

    func candidates() -> [Candidate] { MobileGame.companion.candidates() }

    func difficulties() -> [String] { MobileGame.companion.difficulties() }
    func campaigns() -> [CampaignChoice] { MobileGame.companion.campaigns() }
    func mates(scenarioId: String, playerSerial: String) -> [MateChoice] { MobileGame.companion.mates(scenarioId: scenarioId, playerSerial: playerSerial) }
    func staffChoices() -> [StaffChoice] { MobileGame.companion.staffChoices() }

    func newGame(scenarioId: String, playerSerial: String, mateId: String, staffIds: [String], difficulty: String, eventMode: String, totalTurns: Int, seed: String, whatIfState: String, mirrorMatch: Bool, pandemic: Bool) {
        playScreen = .loading
        DispatchQueue.global(qos: .userInitiated).async {
            let started = MobileGame.companion.startConfiguredGame(
                scenarioId: scenarioId, playerSerial: playerSerial, mateId: mateId,
                staffIds: staffIds, difficulty: difficulty, eventModeSerial: eventMode,
                totalTurns: Int32(totalTurns), seed: seed, whatIfState: whatIfState,
                mirrorMatch: mirrorMatch, pandemic: pandemic)
            DispatchQueue.main.async {
                self.game = started
                self.campaign = nil
                self.recapLines = []
                self.showRecap = false
                self.eventId = nil
                self.eventResult = nil
                self.playScreen = .game
                self.touch()
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
        let recap = g.endTurn()
        var lines: [String] = []
        for item in recap {
            lines.append(item.detail.isEmpty ? item.label : "\(item.label): \(item.detail)")
        }
        touch()
        if g.isOver() {
            playScreen = .results
            return
        }
        if !lines.isEmpty {
            recapLines = lines
            showRecap = true
        }
        promptNextEvent()
    }

    func dismissRecap() {
        showRecap = false
        promptNextEvent()
    }

    private func promptNextEvent() {
        eventResult = nil
        eventId = game?.pendingEventIds().first
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
