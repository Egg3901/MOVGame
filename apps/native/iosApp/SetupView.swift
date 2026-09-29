import SwiftUI
import SafariServices
import shared

enum CampaignStyle {
    static let background = Color(red: 10/255, green: 15/255, blue: 20/255)
    static let card = Color(red: 17/255, green: 27/255, blue: 38/255)
    static let gold = Color(red: 245/255, green: 185/255, blue: 66/255)
    static let coral = Color(red: 239/255, green: 105/255, blue: 91/255)
    static let muted = Color(red: 168/255, green: 181/255, blue: 194/255)
    static let democrat = Color(red: 55/255, green: 121/255, blue: 237/255)
    static let republican = Color(red: 225/255, green: 75/255, blue: 75/255)
}

struct HomeView: View {
    @ObservedObject var session: GameSession
    @State private var daily: TodayChallenge?
    @State private var showingDaily = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack(spacing: 12) {
                    Image("MOVMark")
                        .resizable()
                        .frame(width: 56, height: 56)
                        .accessibilityHidden(true)
                    Text("MARGIN OF VICTORY")
                        .font(.caption.bold()).tracking(2)
                        .foregroundStyle(CampaignStyle.gold)
                }
                GeometryReader { geometry in
                    Image("cover-country-us")
                        .resizable()
                        .scaledToFill()
                        .frame(width: geometry.size.width, height: 180)
                        .clipped()
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                        .accessibilityLabel("United States Capitol")
                }
                .frame(height: 180)
                HStack(spacing: 0) {
                    CampaignStyle.democrat
                    CampaignStyle.republican
                }
                .frame(height: 4)
                .clipShape(Capsule())
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
                .background(CampaignStyle.coral, in: RoundedRectangle(cornerRadius: 14))
                Button { showingDaily = true } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("DAILY CHALLENGE · \(daily?.date ?? "TODAY")")
                            .font(.caption.bold()).tracking(1.2).foregroundStyle(CampaignStyle.gold)
                        Text(daily.map { "\($0.flag) \($0.label)" } ?? "Today's shared election")
                            .font(.headline).foregroundStyle(.white)
                        Text(daily.map { "Play as \($0.role.uppercased()) · same race and seed for everyone" }
                             ?? "Open the live challenge and leaderboard")
                            .font(.subheadline).foregroundStyle(CampaignStyle.muted)
                        Text("Play today's challenge  ↗")
                            .font(.subheadline.bold()).foregroundStyle(CampaignStyle.coral)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(18)
                    .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 16))
                }
                .buttonStyle(.plain)
                Text("17 U.S. presidential campaigns · 1960–2024").font(.caption).foregroundStyle(CampaignStyle.muted)
            }
            .padding(22)
        }
        .task {
            guard let url = URL(string: "https://sim.ahousedividedgame.com/api/daily") else { return }
            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                daily = try JSONDecoder().decode(TodayChallenge.self, from: data)
            } catch { daily = nil }
        }
        .sheet(isPresented: $showingDaily) {
            DailyWebView(url: URL(string: "https://sim.ahousedividedgame.com/?daily=1")!)
        }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }
}

private struct TodayChallenge: Decodable {
    let date: String
    let label: String
    let flag: String
    let role: String
}

private struct DailyWebView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }
    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}

struct SetupView: View {
    @ObservedObject var session: GameSession
    @State private var step = 0
    @State private var scenarioId = "2024"
    @State private var player = ""
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
    private var selectedCampaignNumber: Int { (campaigns.firstIndex(where: { $0.id == scenarioId }) ?? 0) + 1 }
    private var mates: [MateChoice] { session.mates(scenarioId: scenarioId, playerSerial: player) }
    private var selectedMate: MateChoice? { mates.first(where: { $0.id == mateId }) ?? mates.first(where: { $0.historical }) ?? mates.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Button("← Campaign menu") { session.playScreen = .home }
                    .font(.subheadline.bold()).foregroundStyle(CampaignStyle.muted)
                HStack(spacing: 10) {
                    Image("MOVMark")
                        .resizable()
                        .frame(width: 36, height: 36)
                        .accessibilityHidden(true)
                    Text("NEW CAMPAIGN").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold)
                }
                Text("Choose your path").font(.largeTitle.bold())
                Text("Build the ticket. Assemble the team. Rewrite the map.").foregroundStyle(CampaignStyle.muted)
                HStack(spacing: 7) {
                    ForEach(0..<4, id: \.self) { index in
                        VStack(spacing: 4) {
                            Capsule().fill(index == step ? CampaignStyle.gold : CampaignStyle.muted.opacity(0.3)).frame(height: 4)
                            Text(["Election", "Ticket", "War room", "Briefing"][index])
                                .font(.system(size: 10, weight: index == step ? .bold : .regular))
                                .foregroundStyle(index == step ? CampaignStyle.gold : CampaignStyle.muted)
                        }
                    }
                }
                Text("STEP \(step + 1) OF 4").font(.caption.bold()).tracking(1.5).foregroundStyle(CampaignStyle.gold)

                if step == 0 { section("01  THE ELECTION") {
                    HStack {
                        Button { changeElection(by: -1) } label: {
                            Image(systemName: "chevron.left").frame(width: 44, height: 44)
                        }
                        .disabled(selectedCampaignNumber == 1)
                        Spacer()
                        Menu {
                            ForEach(campaigns, id: \.id) { item in
                                Button("\(item.year) · \(item.demName) vs. \(item.repName)") {
                                    scenarioId = item.id
                                    mateId = ""
                                }
                            }
                        } label: {
                            Text("\(campaign.year)  ·  \(selectedCampaignNumber) of \(campaigns.count)  ⌄")
                                .font(.headline).foregroundStyle(CampaignStyle.gold)
                        }
                        Spacer()
                        Button { changeElection(by: 1) } label: {
                            Image(systemName: "chevron.right").frame(width: 44, height: 44)
                        }
                        .disabled(selectedCampaignNumber == campaigns.count)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(CampaignStyle.gold)

                    VStack(alignment: .leading, spacing: 14) {
                        Image("cover-country-us")
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .accessibilityLabel("United States Capitol")
                        Text(campaign.label).font(.title2.bold())
                        Text(campaign.tagline).font(.subheadline).foregroundStyle(CampaignStyle.muted)
                        Text("CHOOSE YOUR SIDE")
                            .font(.caption.bold()).tracking(1.5).foregroundStyle(CampaignStyle.muted)
                        HStack(spacing: 10) {
                            partyCard("dem", name: campaign.demName, party: "DEMOCRAT", color: CampaignStyle.democrat)
                            partyCard("rep", name: campaign.repName, party: "REPUBLICAN", color: CampaignStyle.republican)
                        }
                    }
                    .padding(16)
                    .background(CampaignStyle.background, in: RoundedRectangle(cornerRadius: 16))
                } }

                if step == 1 { section("02  YOUR TICKET") {
                    Picker("Ticket", selection: $player) {
                        Text(campaign.demName).tag("dem")
                        Text(campaign.repName).tag("rep")
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: player) { _ in mateId = "" }
                    HStack(spacing: 8) {
                        Circle()
                            .fill(player == "dem" ? CampaignStyle.democrat : CampaignStyle.republican)
                            .frame(width: 10, height: 10)
                        Text("Leading the \(player == "dem" ? "Democratic" : "Republican") ticket")
                            .font(.subheadline.bold())
                    }
                    .foregroundStyle(player == "dem" ? CampaignStyle.democrat : CampaignStyle.republican)
                    Text("Running mate").font(.subheadline.bold())
                    ForEach(mates, id: \.id) { mate in
                        option(selected: selectedMate?.id == mate.id, title: mate.name + (mate.historical ? " · Historical" : ""),
                               detail: "\(mate.bonus)\n\(mate.blurb)") {
                            mateId = mate.id
                        }
                    }
                } }

                if step == 2 { section("03  WAR ROOM · \(staffIds.count)/3") {
                    Text("Hire up to three advisers").foregroundStyle(CampaignStyle.muted)
                    Text(String(format: "Selected payroll: $%.0fk / week",
                        session.staffChoices().filter { staffIds.contains($0.id) }.reduce(0.0) { $0 + $1.salaryPerWeek } / 1_000))
                        .font(.subheadline.bold()).foregroundStyle(CampaignStyle.gold)
                    ForEach(session.staffChoices(), id: \.id) { staff in
                        option(selected: staffIds.contains(staff.id),
                               enabled: staffIds.contains(staff.id) || staffIds.count < 3,
                               title: "\(staff.name) · \(staff.role)",
                               detail: "\(staff.bonus) · $\(Int(staff.salaryPerWeek / 1_000))k/week\n\(staff.blurb)") {
                            if staffIds.contains(staff.id) { staffIds.remove(staff.id) }
                            else if staffIds.count < 3 { staffIds.insert(staff.id) }
                        }
                    }
                    if staffIds.count == 3 {
                        Text("Three advisers selected. Remove one to choose another.")
                            .font(.caption).foregroundStyle(CampaignStyle.muted)
                    }
                } }

                if step == 3 { section("04  CAMPAIGN BRIEFING") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("YOUR FIRST WEEK").font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
                        Text("1. Tap a gray, amber-ringed state on the EV map. Those are the closest races.")
                        Text("2. Choose a campaign move and tune its settings. The projected margin updates before you add it.")
                        Text("3. Schedule moves across seven days, then end the week to see what changed.")
                        Text("Win 270 electoral votes by Election Day.").font(.subheadline.bold())
                    }
                    .font(.subheadline).foregroundStyle(.white)
                    .padding(12).background(CampaignStyle.background, in: RoundedRectangle(cornerRadius: 12))
                    Picker("Difficulty", selection: $difficulty) {
                        ForEach(session.difficulties(), id: \.self) { Text($0.capitalized).tag($0) }
                    }.pickerStyle(.segmented)
                    Text(difficulty == "easy" ? "More cash and a softer opponent." : difficulty == "hard" ? "Fewer resources and a tougher opponent." : "Balanced resources and opposition.")
                        .font(.caption).foregroundStyle(CampaignStyle.muted)
                    Picker("Events", selection: $eventMode) {
                        Text("Historical").tag("historical")
                        Text("Plausible").tag("plausible")
                    }.pickerStyle(.segmented)
                    Picker("Campaign length", selection: $totalTurns) {
                        Text("5 weeks").tag(5)
                        Text("9 weeks").tag(9)
                        Text("14 weeks").tag(14)
                    }.pickerStyle(.segmented)
                    Text(totalTurns == 5 ? "A short, urgent sprint to Election Day." : totalTurns == 14 ? "More time to build a ground game." : "Nine weeks to shape the map.")
                        .font(.caption).foregroundStyle(CampaignStyle.muted)
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
                } }
            }
            .padding(20)
        }
        .id(step)
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            HStack(spacing: 10) {
                if step > 0 {
                    Button("← Back") { step -= 1 }
                        .font(.subheadline.bold()).foregroundStyle(.white)
                        .padding(17).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
                }
                Button {
                    if step < 3 { step += 1 } else { launchCampaign() }
                } label: {
                    Text(step < 3 ? "Next  →" : "Launch campaign  →")
                        .font(.headline).frame(maxWidth: .infinity).padding(18)
                }
                .buttonStyle(.plain)
                .foregroundStyle(CampaignStyle.background)
                .background(CampaignStyle.coral, in: RoundedRectangle(cornerRadius: 14))
                .disabled((step == 0 && player.isEmpty) || (step == 3 && selectedMate == nil))
            }
            .padding(.horizontal, 20).padding(.vertical, 10)
            .background(CampaignStyle.background)
        }
        .onAppear {
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-setup-2016") {
                scenarioId = "2016"
            }
            #endif
            if let requested = session.setupScenarioId,
               campaigns.contains(where: { $0.id == requested }) {
                scenarioId = requested
                session.setupScenarioId = nil
            }
        }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }

    private func changeElection(by offset: Int) {
        let index = (campaigns.firstIndex(where: { $0.id == scenarioId }) ?? 0) + offset
        guard campaigns.indices.contains(index) else { return }
        scenarioId = campaigns[index].id
        mateId = ""
    }

    private func partyCard(_ side: String, name: String, party: String, color: Color) -> some View {
        Button {
            player = side
            mateId = ""
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                Text(party).font(.caption2.bold()).tracking(0.7).foregroundStyle(color)
                Text(name).font(.subheadline.bold()).foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Text(player == side ? "✓ Selected" : "Play this side")
                    .font(.caption.bold()).foregroundStyle(player == side ? CampaignStyle.gold : CampaignStyle.muted)
            }
            .frame(maxWidth: .infinity, minHeight: 98, alignment: .topLeading)
            .padding(12)
            .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12)
                .strokeBorder(player == side ? CampaignStyle.gold : color.opacity(0.6), lineWidth: player == side ? 2 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(player == side ? .isSelected : [])
    }

    private func launchCampaign() {
        guard let mate = selectedMate else { return }
        session.newGame(scenarioId: scenarioId, playerSerial: player, mateId: mate.id,
                        staffIds: Array(staffIds).sorted(), difficulty: difficulty,
                        eventMode: eventMode, totalTurns: totalTurns, seed: seed,
                        whatIfState: whatIfState, mirrorMatch: mirrorMatch, pandemic: pandemic)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 18))
    }

    private func option(selected: Bool, enabled: Bool = true, title: String, detail: String,
                        action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text((selected ? "✓  " : "") + title).font(.subheadline.bold()).foregroundStyle(.white)
                Text(detail).font(.caption).foregroundStyle(CampaignStyle.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(12)
            .background(selected ? CampaignStyle.gold.opacity(0.2) : CampaignStyle.background,
                        in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.55)
        .accessibilityElement(children: .combine)
        .accessibilityValue(selected ? "Selected" : (enabled ? "Not selected" : "Limit reached"))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
