import SwiftUI
import shared

private enum CampaignStyle {
    static let background = Color(red: 10/255, green: 15/255, blue: 20/255)
    static let card = Color(red: 17/255, green: 27/255, blue: 38/255)
    static let gold = Color(red: 245/255, green: 185/255, blue: 66/255)
    static let muted = Color(red: 168/255, green: 181/255, blue: 194/255)
}

struct HomeView: View {
    @ObservedObject var session: GameSession

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Spacer().frame(height: 30)
                Text("THE ROAD TO 270").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold)
                Text("Margin of\nVictory").font(.system(size: 54, weight: .black, design: .serif)).fixedSize(horizontal: false, vertical: true)
                Text("Every state has a story. Every decision moves the map.")
                    .font(.title3).foregroundStyle(CampaignStyle.muted)
                if session.hasGame {
                    Button { session.resumeGame() } label: {
                        VStack(alignment: .leading, spacing: 7) {
                            Text("CONTINUE CAMPAIGN").font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
                            Text(session.savedCampaignLabel).font(.title2.bold()).foregroundStyle(.white)
                            Text("Return to the campaign trail  →").foregroundStyle(CampaignStyle.muted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading).padding(20)
                        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 18))
                    }
                }
                Button { session.playScreen = .setup } label: {
                    Text("Start a new campaign  →").font(.headline).frame(maxWidth: .infinity).padding(18)
                }
                .buttonStyle(.plain).foregroundStyle(CampaignStyle.background)
                .background(CampaignStyle.gold, in: RoundedRectangle(cornerRadius: 14))
                Text("17 U.S. presidential campaigns · 1960–2024").font(.caption).foregroundStyle(CampaignStyle.muted)
            }
            .padding(22)
        }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }
}

struct SetupView: View {
    @ObservedObject var session: GameSession
    @State private var scenarioId = "2024"
    @State private var player = "dem"
    @State private var mateId = ""
    @State private var staffIds: Set<String> = []
    @State private var difficulty = "normal"
    @State private var eventMode = "historical"
    @State private var totalTurns = 9
    @State private var seed = String(format: "%06d", Int.random(in: 0...999999))
    @State private var whatIfState = ""
    @State private var mirrorMatch = false
    @State private var pandemic = false

    private var campaigns: [CampaignChoice] { session.campaigns() }
    private var campaign: CampaignChoice { campaigns.first(where: { $0.id == scenarioId }) ?? campaigns[0] }
    private var mates: [MateChoice] { session.mates(scenarioId: scenarioId, playerSerial: player) }
    private var selectedMate: MateChoice? { mates.first(where: { $0.id == mateId }) ?? mates.first(where: { $0.historical }) ?? mates.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Button("← Campaign menu") { session.playScreen = .home }
                    .font(.subheadline.bold()).foregroundStyle(CampaignStyle.muted)
                Text("NEW CAMPAIGN").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold)
                Text("Choose your path").font(.largeTitle.bold())
                Text("Build the ticket. Assemble the team. Rewrite the map.").foregroundStyle(CampaignStyle.muted)

                section("01  THE ELECTION") {
                    Text("SWIPE THROUGH 17 ELECTIONS").font(.caption2.bold()).tracking(1).foregroundStyle(CampaignStyle.muted)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(campaigns, id: \.id) { item in
                                Button {
                                    scenarioId = item.id
                                    mateId = ""
                                } label: {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text("\(item.year)").font(.system(size: 40, weight: .black, design: .serif))
                                            .foregroundStyle(scenarioId == item.id ? CampaignStyle.gold : Color.white)
                                        Text(item.label).font(.headline).foregroundStyle(.white)
                                        Text("\(item.demName)  v.  \(item.repName)").font(.caption).foregroundStyle(CampaignStyle.muted)
                                    }
                                    .frame(width: 218, alignment: .leading).padding(16)
                                    .background(scenarioId == item.id ? CampaignStyle.gold.opacity(0.2) : CampaignStyle.background,
                                                in: RoundedRectangle(cornerRadius: 15))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    Text(campaign.tagline).foregroundStyle(CampaignStyle.muted)
                }

                section("02  YOUR TICKET") {
                    Picker("Ticket", selection: $player) {
                        Text(campaign.demName).tag("dem")
                        Text(campaign.repName).tag("rep")
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: player) { _ in mateId = "" }
                    Text("Running mate").font(.subheadline.bold())
                    ForEach(mates, id: \.id) { mate in
                        option(selected: selectedMate?.id == mate.id, title: mate.name + (mate.historical ? " · Historical" : ""), detail: mate.blurb) {
                            mateId = mate.id
                        }
                    }
                }

                section("03  WAR ROOM · \(staffIds.count)/3") {
                    Text("Hire up to three advisers").foregroundStyle(CampaignStyle.muted)
                    ForEach(session.staffChoices(), id: \.id) { staff in
                        option(selected: staffIds.contains(staff.id), title: "\(staff.name) · \(staff.role)", detail: staff.blurb) {
                            if staffIds.contains(staff.id) { staffIds.remove(staff.id) }
                            else if staffIds.count < 3 { staffIds.insert(staff.id) }
                        }
                    }
                }

                section("04  CAMPAIGN BRIEFING") {
                    Picker("Difficulty", selection: $difficulty) {
                        ForEach(session.difficulties(), id: \.self) { Text($0.capitalized).tag($0) }
                    }.pickerStyle(.segmented)
                    Picker("Events", selection: $eventMode) {
                        Text("Historical").tag("historical")
                        Text("Plausible").tag("plausible")
                    }.pickerStyle(.segmented)
                    Picker("Campaign length", selection: $totalTurns) {
                        Text("5 weeks").tag(5)
                        Text("9 weeks").tag(9)
                        Text("14 weeks").tag(14)
                    }.pickerStyle(.segmented)
                    Picker("What if: make a state a tossup", selection: $whatIfState) {
                        Text("Off").tag("")
                        ForEach(["TX", "FL", "OH", "PA", "MI", "WI", "GA", "AZ", "NC", "NY"], id: \.self) {
                            Text($0).tag($0)
                        }
                    }.tint(CampaignStyle.gold)
                    Toggle("Mirror match · underdog boost", isOn: $mirrorMatch).tint(CampaignStyle.gold)
                    Toggle("Pandemic era issues", isOn: $pandemic).tint(CampaignStyle.gold)
                    TextField("Campaign seed", text: $seed)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textFieldStyle(.roundedBorder)
                    Text("Use the same seed to replay the same campaign").font(.caption).foregroundStyle(CampaignStyle.muted)
                }
                Button {
                    guard let mate = selectedMate else { return }
                    session.newGame(scenarioId: scenarioId, playerSerial: player, mateId: mate.id,
                                    staffIds: Array(staffIds).sorted(), difficulty: difficulty,
                                    eventMode: eventMode, totalTurns: totalTurns, seed: seed,
                                    whatIfState: whatIfState, mirrorMatch: mirrorMatch, pandemic: pandemic)
                } label: {
                    Text("Launch campaign  →").font(.headline).frame(maxWidth: .infinity).padding(18)
                }
                .buttonStyle(.plain).foregroundStyle(CampaignStyle.background)
                .background(CampaignStyle.gold, in: RoundedRectangle(cornerRadius: 14))
            }
            .padding(20)
        }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 18))
    }

    private func option(selected: Bool, title: String, detail: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text((selected ? "✓  " : "") + title).font(.subheadline.bold()).foregroundStyle(.white)
                Text(detail).font(.caption).foregroundStyle(CampaignStyle.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(12)
            .background(selected ? CampaignStyle.gold.opacity(0.2) : CampaignStyle.background,
                        in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain)
    }
}
