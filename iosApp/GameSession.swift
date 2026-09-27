import SwiftUI
import shared

// Phase 4 session (#22): mirrors androidApp GameSession. The Swift side
// talks only to the MobileGame facade (string serials in, plain reads out)
// and bumps `version` after every mutation so views re-render.
enum PlayScreen {
    case setup, game, results
}

final class GameSession: ObservableObject {
    private static let saveKey = "mov_campaign_v1"
    @Published var tab = 0 // 0 play, 1 store, 2 account
    @Published var playScreen: PlayScreen = .setup
    @Published var version = 0

    @Published var recapLines: [String] = []
    @Published var showRecap = false
    @Published var eventId: String? = nil
    @Published var eventResult: String? = nil

    private var game: MobileGame? = nil

    init() {
        if let snapshot = UserDefaults.standard.string(forKey: Self.saveKey),
           let restored = MobileGame.companion.restore(snapshot: snapshot) {
            game = restored
            playScreen = restored.isOver() ? .results : .game
            eventId = restored.pendingEventIds().first
        }
    }

    var hasGame: Bool { game != nil }

    func playTab() {
        if let g = game, g.isOver() {
            playScreen = .results
        } else if game != nil {
            playScreen = .game
        } else {
            playScreen = .setup
        }
        tab = 0
    }

    func touch() {
        version += 1
        if let game = game {
            UserDefaults.standard.set(game.saveSnapshot(), forKey: Self.saveKey)
        }
    }

    func candidates() -> [Candidate] { MobileGame.companion.candidates() }

    func difficulties() -> [String] { MobileGame.companion.difficulties() }

    func newGame(playerSerial: String, difficulty: String) {
        let seed = Int64(Date().timeIntervalSince1970 * 1000)
        game = MobileGame.companion.startGame(playerSerial: playerSerial, difficulty: difficulty, seed: seed)
        recapLines = []
        showRecap = false
        eventId = nil
        eventResult = nil
        playScreen = .game
        touch()
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
        for item in recap.prefix(6) {
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
