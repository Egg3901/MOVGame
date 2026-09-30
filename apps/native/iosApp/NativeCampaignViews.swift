import SwiftUI
import shared

private func nativePartyColor(_ hex: String) -> Color {
    let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x94a3b8
    return Color(red: Double((value >> 16) & 255) / 255,
                 green: Double((value >> 8) & 255) / 255, blue: Double(value & 255) / 255)
}

private func nativeActionName(_ serial: String) -> String { serial.replacingOccurrences(of: "_", with: " ").capitalized }

private struct NativeCountryMapView: View {
    let map: NativeMap
    let regions: [NativeRegion]
    let selectedId: String
    let onSelect: (String) -> Void

    var body: some View {
        GeometryReader { geometry in
            let factor = min(geometry.size.width / map.width, geometry.size.height / map.height)
            let offsetX = (geometry.size.width - map.width * factor) / 2
            let offsetY = (geometry.size.height - map.height * factor) / 2
            let byId = Dictionary(uniqueKeysWithValues: regions.map { ($0.id, $0) })
            ZStack {
                ForEach(map.shapes, id: \.id) { shape in
                    let path = Path { path in
                        for polygon in shape.polygons {
                            guard let first = polygon.points.first else { continue }
                            path.move(to: CGPoint(x: first.x * factor + offsetX, y: first.y * factor + offsetY))
                            for point in polygon.points.dropFirst() {
                                path.addLine(to: CGPoint(x: point.x * factor + offsetX, y: point.y * factor + offsetY))
                            }
                            path.closeSubpath()
                        }
                    }
                    path.fill(nativePartyColor(byId[shape.id]?.color ?? "#94a3b8"))
                        .overlay(path.stroke(shape.id == selectedId ? Color.white : CampaignStyle.background,
                                             lineWidth: shape.id == selectedId ? 3 : 1))
                        .contentShape(path)
                        .onTapGesture { onSelect(shape.id) }
                        .accessibilityElement()
                        .accessibilityAddTraits(.isButton)
                        .accessibilityLabel("\(byId[shape.id]?.name ?? shape.id), \(byId[shape.id]?.winner ?? "") leads")
                        .accessibilityAction { onSelect(shape.id) }
                }
            }
        }
        .frame(height: 300)
    }
}

struct NativeCampaignLibrary: View {
    @ObservedObject var session: GameSession
    @State private var countryId = "US"
    @State private var electionId = "2024"
    @State private var partyId = ""
    @State private var difficulty = "normal"
    @State private var seed = String(Int(Date().timeIntervalSince1970))
    private var elections: [NativeElection] { MobileCampaign.companion.elections(countryId: countryId) }
    private var selectedElectionId: String {
        elections.contains(where: { $0.nativeId == electionId }) ? electionId : (elections.first?.nativeId ?? "")
    }
    private var parties: [NativeParty] {
        countryId == "US" ? [] : MobileCampaign.companion.parties(countryId: countryId, electionId: selectedElectionId)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("CAMPAIGN LIBRARY").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold)
                Text("Choose your election").font(.largeTitle.bold())
                Picker("Country", selection: $countryId) {
                    ForEach(MobileCampaign.companion.countries(), id: \.id) { country in
                        Text("\(country.flag) \(country.name)").tag(country.id)
                    }
                }
                .pickerStyle(.menu)
                Picker("Election", selection: $electionId) {
                    ForEach(elections, id: \.nativeId) { election in Text(election.label).tag(election.nativeId) }
                }
                .pickerStyle(.menu)
                if let election = elections.first(where: { $0.nativeId == electionId }) {
                    Text(election.blurb).font(.subheadline).foregroundStyle(CampaignStyle.muted)
                }
                if countryId == "US" {
                    launchButton("Choose your presidential ticket") {
                        session.setupScenarioId = electionId
                        session.playScreen = .setup
                    }
                } else {
                    Picker("Your party", selection: $partyId) {
                        ForEach(parties, id: \.id) { party in Text("\(party.name) · \(party.leader)").tag(party.id) }
                    }
                    .pickerStyle(.menu)
                    if let party = parties.first(where: { $0.id == partyId }) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(party.leader).font(.title2.bold())
                            Text("Charisma \(Int(party.charisma)) · Energy \(Int(party.energy))")
                            Text("Competence \(Int(party.competence)) · Machine \(Int(party.machine))")
                        }
                        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
                    }
                    Picker("Difficulty", selection: $difficulty) {
                        ForEach(["easy", "normal", "hard"], id: \.self) { Text($0.capitalized).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    TextField("Campaign seed", text: $seed).textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    launchButton("Launch campaign") {
                        session.newCampaign(countryId: countryId, electionId: electionId, partyId: partyId,
                                            difficulty: difficulty, seed: seed)
                    }
                    .disabled(seed.trimmingCharacters(in: .whitespaces).isEmpty || partyId.isEmpty)
                }
            }
            .padding(20)
        }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
        .onChange(of: countryId) { _ in
            electionId = elections.first?.nativeId ?? "2024"
            partyId = parties.first?.id ?? ""
        }
        .onChange(of: electionId) { _ in partyId = parties.first?.id ?? "" }
    }

    private func launchButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title).font(.headline).frame(maxWidth: .infinity).padding(16) }
            .buttonStyle(.plain).foregroundStyle(CampaignStyle.background)
            .background(CampaignStyle.coral, in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct NativeStandingRows: View {
    let rows: [NativeStanding]
    let units: String
    var body: some View {
        ForEach(rows, id: \.partyId) { row in
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 3).fill(nativePartyColor(row.color)).frame(width: 14, height: 14)
                Text(row.name).font(.subheadline.bold())
                Spacer(minLength: 4)
                Text("\(row.units) \(units) · \(String(format: "%.1f", row.voteShare * 100))%")
                    .font(.caption).monospacedDigit()
            }
            .padding(.vertical, 4)
        }
    }
}

struct NativeWorldCampaign: View {
    @ObservedObject var session: GameSession
    @State private var regionId = ""
    @State private var type = "broadcast"
    @State private var mode = "positive"
    @State private var spend = 1.5
    @State private var issueId = ""
    @State private var rival = ""
    @State private var day = 1
    @State private var showRecap = false
    @State private var notice: String?
    private let columns = [GridItem(.flexible()), GridItem(.flexible())]

    var body: some View {
        let _ = session.version
        if let game = session.campaign {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(game.label()).font(.title2.bold())
                    Text(game.isOver() ? "ELECTION RESULT" : "WEEK \(game.turn() + 1) OF \(game.totalTurns())")
                        .font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
                    Text(game.goalText()).font(.subheadline).foregroundStyle(CampaignStyle.muted)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(game.isOver() ? game.outcome() : "Projected standings").font(.headline)
                        NativeStandingRows(rows: game.standings(), units: game.unitName())
                        Text("\(game.majority()) of \(game.totalUnits()) \(game.unitName()) to win outright")
                            .font(.caption).foregroundStyle(CampaignStyle.muted)
                    }.nativeCampaignCard()
                    if game.hasPendingEvent() { eventCard(game) }
                    if let notice { Text(notice).font(.subheadline).foregroundStyle(CampaignStyle.gold) }
                    Text(game.isOver() ? "REGIONAL RESULTS" : "REGIONAL PROJECTIONS")
                        .font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
                    NativeCountryMapView(map: game.map(), regions: game.regions(), selectedId: regionId) { regionId = $0 }
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(game.regions(), id: \.id) { region in
                            Button { regionId = regionId == region.id ? "" : region.id } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(region.name).font(.subheadline.bold()).foregroundStyle(.white)
                                    Text("\(region.winner) leads").font(.caption.bold()).foregroundStyle(nativePartyColor(region.color))
                                    Text("\(region.totalUnits) \(game.unitName()) in region").font(.caption)
                                    Text("You: \(region.playerUnits) · \(String(format: "%.1f", region.playerShare * 100))%")
                                        .font(.caption).monospacedDigit()
                                }
                                .frame(maxWidth: .infinity, minHeight: 96, alignment: .leading)
                                .padding(12).foregroundStyle(CampaignStyle.muted)
                                .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(regionId == region.id ? CampaignStyle.gold : .clear, lineWidth: 2))
                            }.buttonStyle(.plain)
                        }
                    }
                    if !regionId.isEmpty, let region = game.regions().first(where: { $0.id == regionId }) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(region.name).font(.headline)
                            NativeStandingRows(rows: game.regionStandings(regionId: regionId), units: game.unitName())
                        }.nativeCampaignCard()
                    }
                    if game.isOver() {
                        if let summary = game.resultSummary() {
                            VStack(alignment: .leading, spacing: 7) {
                                Text("CAMPAIGN SCORE \(summary.score) / 1000").font(.headline).foregroundStyle(CampaignStyle.gold)
                                Text("\(summary.difficulty.capitalized) difficulty · \(game.unitName().capitalized) above majority: \(summary.unitMargin)").font(.subheadline)
                                Text(String(format: "Vote margin vs. leading rival: %+.1f points", summary.popularMargin)).font(.subheadline)
                            }.nativeCampaignCard()
                        }
                        DisclosureGroup("Compare with history") {
                            ForEach(game.historicalRegions(), id: \.id) { region in
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(region.name).font(.subheadline.bold())
                                    Text("\(region.units) \(game.unitName()) now · \(region.historicalUnits) historically")
                                    Text(String(format: "Vote share swing %+.1f points", region.shareSwing))
                                }.font(.caption).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4)
                            }
                        }
                        Text("WHAT DECIDED IT").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
                        ForEach(Array(game.resultCauses().enumerated()), id: \.offset) { _, cause in Text(cause).font(.subheadline) }
                        Button("Choose another campaign") { session.playScreen = .library }.buttonStyle(.borderedProminent)
                    } else {
                        planner(game)
                        ForEach(game.plan(), id: \.index) { action in
                            HStack(alignment: .top) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Day \(action.day) · \(action.title)").font(.subheadline.bold())
                                    Text("\(action.detail) · \(game.currency())\(String(format: "%.1f", action.cost))M")
                                        .font(.caption).foregroundStyle(CampaignStyle.muted)
                                }
                                Spacer(minLength: 4)
                                Button("Remove") { game.removeAction(index: action.index); session.touch() }.font(.caption)
                            }.nativeCampaignCard()
                        }
                        Button {
                            if game.endWeek() { session.touch(); showRecap = !game.recap().isEmpty }
                        } label: { Text("End week").font(.headline).frame(maxWidth: .infinity).padding(16) }
                            .buttonStyle(.plain).foregroundStyle(CampaignStyle.background)
                            .background(CampaignStyle.coral, in: RoundedRectangle(cornerRadius: 14))
                            .disabled(game.hasPendingEvent())
                    }
                    Text("CAMPAIGN NEWS").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
                    ForEach(Array(game.news().enumerated()), id: \.offset) { _, item in
                        Text("Week \(item.turn + 1) · \(item.text)").font(.caption).foregroundStyle(CampaignStyle.muted)
                    }
                }
                .padding(20)
            }
            .background(CampaignStyle.background).preferredColorScheme(.dark)
            .onAppear { if issueId.isEmpty { issueId = game.issues().first?.id ?? "" } }
            .sheet(isPresented: $showRecap) {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            ForEach(Array(game.recap().enumerated()), id: \.offset) { _, item in
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(item.label).font(.headline)
                                    Text(item.detail).font(.subheadline).foregroundStyle(CampaignStyle.muted)
                                }.nativeCampaignCard()
                            }
                        }.padding(20)
                    }
                    .background(CampaignStyle.background).preferredColorScheme(.dark)
                    .navigationTitle("Week in review")
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Continue") { showRecap = false } } }
                }
            }
        }
    }

    private func eventCard(_ game: MobileCampaign) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(game.eventTitle()).font(.headline)
            Text(game.eventPrompt()).font(.subheadline)
            ForEach(game.eventChoices(), id: \.id) { choice in
                Button {
                    if game.answerEvent(choiceId: choice.id) { notice = choice.resultText; session.touch() }
                } label: { Text(choice.text).frame(maxWidth: .infinity, alignment: .leading) }
                    .buttonStyle(.borderedProminent)
            }
        }.nativeCampaignCard()
    }

    private func planner(_ game: MobileCampaign) -> some View {
        let regional = ["rally", "surrogate", "ground_game", "gotv", "canvass"].contains(type)
        return VStack(alignment: .leading, spacing: 12) {
            Text("Plan your week").font(.title2.bold())
            Text("\(game.currency())\(String(format: "%.1f", game.funds()))M cash · \(game.slotsLeft()) moves left").font(.subheadline)
            Text("Planned \(game.currency())\(String(format: "%.1f", game.plannedSpend()))M · Available \(game.currency())\(String(format: "%.1f", game.availableFunds()))M")
                .font(.caption).foregroundStyle(CampaignStyle.muted)
            Text("Plan estimate: \(game.previewPlayerUnits()) \(game.unitName()) after your queued actions. Rival moves and events can change the result.")
                .font(.caption).foregroundStyle(CampaignStyle.gold)
            Picker("Action", selection: $type) { ForEach(game.actionTypes(), id: \.self) { Text(nativeActionName($0)).tag($0) } }
            Picker("Target", selection: $regionId) {
                Text("National").tag("")
                ForEach(game.regions(), id: \.id) { Text($0.name).tag($0.id) }
            }
            if regional && regionId.isEmpty { Text("Choose a region for this action.").font(.caption).foregroundStyle(CampaignStyle.gold) }
            Picker("Day", selection: $day) { ForEach(1...7, id: \.self) { Text("Day \($0)").tag($0) } }
            if type == "broadcast" {
                Picker("Broadcast mode", selection: $mode) { ForEach(["positive", "contrast", "issue"], id: \.self) { Text($0.capitalized).tag($0) } }
                Text("Spend \(game.currency())\(String(format: "%.1f", spend))M").font(.subheadline)
                Slider(value: $spend, in: 0.5...10, step: 0.25)
                Text(regionId.isEmpty ? "National reaches every region where your party stands." : "Regional spending concentrates on this target.")
                    .font(.caption).foregroundStyle(CampaignStyle.muted)
            }
            if type == "issue_pivot" || (type == "broadcast" && mode == "issue") {
                Picker("Issue", selection: $issueId) {
                    ForEach(game.issues(), id: \.id) { Text("\($0.name) · \(String(format: "%.1f", $0.salience * 100))% salience").tag($0.id) }
                }
                if let issue = game.issues().first(where: { $0.id == issueId }) {
                    Text(issue.blurb).font(.caption).foregroundStyle(CampaignStyle.muted)
                }
            }
            if type == "oppo_research" || (type == "broadcast" && mode == "contrast") {
                Picker("Rival", selection: $rival) {
                    Text("Leading rival").tag("")
                    ForEach(game.rivals(), id: \.partyId) { Text($0.name).tag($0.partyId) }
                }
            }
            Button {
                let added = game.queue(type: type, regionId: regionId.isEmpty ? nil : regionId, day: Int32(day),
                    mode: type == "broadcast" ? mode : nil,
                    spend: type == "broadcast" ? KotlinDouble(double: spend) : nil,
                    issueId: type == "issue_pivot" || (type == "broadcast" && mode == "issue") ? issueId : nil,
                    targetParty: type == "oppo_research" || (type == "broadcast" && mode == "contrast") ? (rival.isEmpty ? nil : rival) : nil)
                if added { notice = nil; session.touch() }
                else { notice = "Check your target, cash, remaining moves, and the three-move daily limit." }
            } label: { Text("Add to plan").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .disabled(game.slotsLeft() == 0 || game.hasPendingEvent() || (regional && regionId.isEmpty))
        }
        .pickerStyle(.menu)
        .nativeCampaignCard()
    }
}

private extension View {
    func nativeCampaignCard() -> some View {
        self.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
    }
}
