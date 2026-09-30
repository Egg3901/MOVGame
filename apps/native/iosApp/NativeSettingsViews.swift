import SwiftUI
import AVFoundation
import shared

@MainActor
final class NativePreferences: ObservableObject {
    @Published var soundOn: Bool { didSet { UserDefaults.standard.set(soundOn, forKey: "mov_sound_on") } }
    @Published var volume: Double { didSet { UserDefaults.standard.set(volume, forKey: "mov_volume") } }
    @Published var reducedMotion: Bool { didSet { UserDefaults.standard.set(reducedMotion, forKey: "mov_reduce_motion") } }
    @Published var tutorialNonce = 0
    private var players: [AVAudioPlayer] = []
    init() {
        let prefs = UserDefaults.standard
        soundOn = prefs.object(forKey: "mov_sound_on") == nil ? true : prefs.bool(forKey: "mov_sound_on")
        let saved = prefs.object(forKey: "mov_volume") as? Double ?? 0.6
        volume = saved.isFinite ? min(1, max(0, saved)) : 0.6
        reducedMotion = prefs.bool(forKey: "mov_reduce_motion")
    }
    func play(_ cue: String) {
        guard soundOn, volume > 0,
              let data = Data(base64Encoded: NativeSound.companion.wavBase64(cue: cue, volume: volume)),
              let player = try? AVAudioPlayer(data: data) else { return }
        // Ambient audio follows the device's silent switch and mixes with music.
        try? AVAudioSession.sharedInstance().setCategory(.ambient)
        players.removeAll { !$0.isPlaying }
        players.append(player)
        player.play()
    }
    func replayTutorial() {
        for country in ["US", "UK", "CA", "DE", "FR", "AU"] { UserDefaults.standard.removeObject(forKey: "mov_tour_\(country)") }
        tutorialNonce += 1
    }
}

struct NativeSettingsView: View {
    @ObservedObject var settings: NativePreferences
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Form {
            Section("Audio") {
                Toggle("Sound effects", isOn: $settings.soundOn).onChange(of: settings.soundOn) { on in if on { settings.play("pollUp") } }
                Slider(value: $settings.volume, in: 0...1).disabled(!settings.soundOn).accessibilityLabel("Sound volume")
                Text("Volume: \(Int(settings.volume * 100))%")
                Button("Preview sound") { settings.play("turnAdvance") }.disabled(!settings.soundOn)
            }
            Section("Motion") {
                Toggle("Reduce motion", isOn: $settings.reducedMotion)
                Text("Election night uses instant results when this or your device's Reduce Motion setting is on.").font(.caption)
            }
            Section("Learn the game") {
                NavigationLink("How to play") { CampaignGuideView() }
                Button("Replay tutorial") { settings.replayTutorial(); dismiss() }
            }
            Section("Margin of Victory") {
                Link("Contact support", destination: URL(string: "mailto:support@lakesidegames.net")!)
                Link("Web game", destination: URL(string: "https://lakesidegames.net/games/electioneer/")!)
                Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""))").font(.caption)
            }
        }.preferredColorScheme(.dark)
    }
}

struct NativeCampaignCoach: View {
    let country: String
    let goal: String
    let turn: Int
    let selected: String?
    let queued: Int
    @ObservedObject var settings: NativePreferences
    @State private var step: Int?
    @State private var startTurn = 0
    private var key: String { "mov_tour_\(country)" }
    private func begin(force: Bool = false) {
        #if targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains(where: { $0.hasPrefix("--mov-capture-") && $0 != "--mov-capture-tutorial" }) { return }
        #endif
        if force || (turn == 0 && !UserDefaults.standard.bool(forKey: key)) { step = 0; startTurn = turn }
    }
    private func finish() { step = nil; UserDefaults.standard.set(true, forKey: key) }
    var body: some View {
        Group {
            if let step {
                let lesson = NativeHelp.companion.steps(countryId: country, goal: goal)[step]
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("TOUR · \(step + 1)/8").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
                        Spacer()
                        Button("Skip tour", action: finish).font(.caption)
                    }
                    Text(lesson.title).font(.headline)
                    Text(lesson.body).font(.subheadline)
                    HStack {
                        if step > 0 { Button("Back") { self.step = step - 1 } }
                        Spacer()
                        Button(step == 7 ? "Done" : "Next") { if step == 7 { finish() } else { self.step = step + 1 } }.buttonStyle(.borderedProminent)
                    }
                }.padding(14).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(CampaignStyle.gold))
                    .accessibilityElement(children: .contain)
                    .padding(12)
            }
        }
        .onAppear { begin() }
        .onChange(of: settings.tutorialNonce) { _ in begin(force: true) }
        .onChange(of: selected) { value in if step == 1 && value != nil { step = 2 } }
        .onChange(of: queued) { count in if step == 3 && count > 0 { step = 4 } }
        .onChange(of: turn) { value in if step == 4 && value > startTurn { step = 5 } }
    }
}
