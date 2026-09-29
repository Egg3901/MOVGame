import SwiftUI
import shared

private enum DeskSection: String, Hashable {
    case map, plan
}

struct Battleground: Identifiable {
    let state: StateContest
    let contest: ContestProjection
    var id: String { state.id }
}

final class PlannerDraft: ObservableObject {
    @Published var type = "advertise"
    @Published var target = ""
    @Published var day = 1
    @Published var adMode = "positive"
    @Published var spend = 8.0
    @Published var issue = "economy"
    @Published var position = 0.0
    @Published var notice: String? = nil

    var needsState: Bool {
        ["advertise", "rally", "surrogate", "fundraise", "ground_game", "gotv"].contains(type)
    }

    func add(to session: GameSession) {
        let added = session.queueConfiguredAction(typeSerial: type, stateId: needsState ? target : nil,
            day: day, adModeSerial: type == "advertise" ? adMode : nil,
            spendMillions: type == "advertise" ? spend : nil,
            issueSerial: (type == "issue_pivot" || (type == "advertise" && adMode == "issue")) ? issue : nil,
            newPosition: type == "issue_pivot" ? position : nil)
        notice = added ? "Added to day \(day)." : "Check the day, available action slots, and cash."
    }
}

// Native campaign desk with map, state projection, action plan, and turn recap.
struct GameView: View {
    @ObservedObject var session: GameSession
    @State private var selectedAbbr: String? = nil
    @State private var deskSection: DeskSection = .map
    @StateObject private var draft = PlannerDraft()

    var body: some View {
        // `version` is read so the view re-renders after every mutation.
        let _ = session.version
        guard let g = session.currentGame() else {
            return AnyView(Text("No campaign. Start one from Home."))
        }
        let proj = session.projection()
        let contests = session.contestsById()
        let states = session.states()
        let abbrToId = Dictionary(
            uniqueKeysWithValues: states.map { ($0.abbr.uppercased(), $0.id) }
        )
        let selId = selectedAbbr.flatMap { abbrToId[$0] }
        let battlegrounds = states.compactMap { state -> Battleground? in
            guard let contest = contests[state.id], !state.blocs.isEmpty else { return nil }
            return Battleground(state: state, contest: contest)
        }.sorted { abs(MapMargin.points($0.contest)) < abs(MapMargin.points($1.contest)) }
        let focused = selId.flatMap { id in battlegrounds.first(where: { $0.state.id == id }) }
            ?? battlegrounds.first
        let addDisabled = Int(g.slotsLeft()) == 0 ||
            session.plannedActions().filter { Int($0.day) == draft.day }.count >= 3 ||
            (draft.type == "advertise" && draft.spend * 1_000_000 > g.availableCash())

        return AnyView(
            VStack(spacing: 0) {
              ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 8) {
                        Image("MOVMark")
                            .resizable()
                            .frame(width: 28, height: 28)
                            .accessibilityHidden(true)
                        Text("CAMPAIGN DESK").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold)
                    }
                    Text(g.campaignLabel()).font(.title2.bold())
                    if deskSection == .map {
                    VStack(spacing: 14) {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading) {
                                Text("DEMOCRATS").font(.caption2.bold()).foregroundStyle(CampaignStyle.democrat)
                                Text("\(proj.dem)").font(.largeTitle.bold())
                            }
                            Spacer()
                            VStack(spacing: 1) {
                                Text("TOSS-UP EV").font(.caption2.bold()).foregroundStyle(CampaignStyle.gold)
                                Text("\(proj.tossup)").font(.largeTitle.bold()).foregroundStyle(CampaignStyle.gold)
                            }
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text("REPUBLICANS").font(.caption2.bold()).foregroundStyle(CampaignStyle.republican)
                                Text("\(proj.rep)").font(.largeTitle.bold())
                            }
                        }
                        GeometryReader { geometry in
                            HStack(spacing: 0) {
                                CampaignStyle.democrat.frame(width: geometry.size.width * CGFloat(proj.dem) / 538)
                                CampaignStyle.gold.frame(width: geometry.size.width * CGFloat(proj.tossup) / 538)
                                CampaignStyle.republican
                            }
                            .clipShape(Capsule())
                            Rectangle().fill(.white)
                                .frame(width: 2, height: 16)
                                .position(x: geometry.size.width * 270 / 538, y: 6)
                        }
                        .frame(height: 12)
                        Text("270 TO WIN").font(.caption2.bold()).foregroundStyle(CampaignStyle.muted)
                            .frame(maxWidth: .infinity)
                        HStack {
                            Text("WEEK \(Int(g.turn()) + 1)/\(Int(g.totalTurns()))")
                            Spacer()
                            Text(String(format: "$%.1fM", g.playerCash() / 1_000_000))
                            Spacer()
                            Text("\(Int(g.slotsLeft())) slots left")
                        }.font(.caption.bold())
                    }
                    .padding(16).background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 18))
                    } else {
                        HStack {
                            Text("WEEK \(Int(g.turn()) + 1)/\(Int(g.totalTurns()))")
                                .foregroundStyle(CampaignStyle.gold)
                            Spacer()
                            Text("\(proj.dem) D · \(proj.tossup) toss · \(proj.rep) R")
                        }
                        .font(.subheadline.bold())
                        .padding(12).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
                    }

                    HStack(spacing: 6) {
                        deskButton("Electoral map", section: .map)
                        deskButton("Week plan", section: .plan)
                    }
                    .padding(5).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 13))

                    if deskSection == .map {
                        GeoMapView(
                            contestsById: contests,
                            abbrToStateId: abbrToId,
                            selectedAbbr: focused?.state.abbr.uppercased(),
                            onSelect: { selectedAbbr = $0 }
                        )
                        .frame(height: 260)
                        .overlay(alignment: .bottom) {
                            if let focused {
                                HStack(spacing: 10) {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(focused.state.name).font(.headline)
                                        Text("\(Int(focused.state.electoralVotes)) EV · \(MapMargin.label(focused.contest))")
                                            .font(.caption).foregroundStyle(CampaignStyle.muted)
                                    }
                                    Spacer(minLength: 0)
                                    Button("Plan  →") {
                                        selectedAbbr = focused.state.abbr.uppercased()
                                        draft.target = focused.state.id
                                        deskSection = .plan
                                    }
                                    .font(.subheadline.bold()).foregroundStyle(CampaignStyle.gold)
                                }
                                .padding(12)
                                .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
                            }
                        }
                        HStack {
                            legendItem("Dem", color: CampaignStyle.democrat)
                            Spacer()
                            legendItem("Toss-up", color: CampaignStyle.gold)
                            Spacer()
                            legendItem("GOP", color: CampaignStyle.republican)
                        }
                        .font(.subheadline)
                        Text("Light: lean 3-10 pts · Mid: likely 10-20 · Dark: safe 20+")
                            .font(.caption).foregroundStyle(CampaignStyle.muted)
                        HStack {
                            Text("Tap a state to set your target.")
                                .font(.subheadline).foregroundStyle(CampaignStyle.muted)
                            Spacer()
                            Menu {
                                ForEach(states.sorted(by: { $0.name < $1.name }), id: \.id) { state in
                                    Button(state.name) { selectedAbbr = state.abbr.uppercased() }
                                }
                            } label: {
                                Label("Find state", systemImage: "magnifyingglass")
                                    .font(.caption.bold())
                            }
                            .tint(CampaignStyle.gold)
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text("CLOSEST CONTESTS").font(.caption.bold()).tracking(1.5).foregroundStyle(CampaignStyle.gold)
                            ForEach(Array(battlegrounds.prefix(3))) { item in
                                Button {
                                    selectedAbbr = item.state.abbr.uppercased()
                                    draft.target = item.state.id
                                } label: {
                                    HStack {
                                        Text(item.state.name).font(.subheadline.bold())
                                        Spacer()
                                        Text("\(Int(item.state.electoralVotes)) EV")
                                        Text(MapMargin.label(item.contest))
                                            .frame(width: 155, alignment: .trailing)
                                    }
                                    .font(.caption).foregroundStyle(.white)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(14)
                        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
                    } else {
                        ActionPlannerView(session: session, draft: draft, selectedStateId: selId,
                                          battlegrounds: Array(battlegrounds.prefix(5)))
                    }
                }
                .padding()
              }
              Button {
                  if deskSection == .map {
                      if let focused {
                          selectedAbbr = focused.state.abbr.uppercased()
                          draft.target = focused.state.id
                      }
                      deskSection = .plan
                  } else {
                      draft.add(to: session)
                  }
              } label: {
                  Text(deskSection == .map ? "Plan an action  →" : "Add to day \(draft.day)  →")
                      .font(.headline).frame(maxWidth: .infinity).padding(14)
              }
              .buttonStyle(.plain).foregroundStyle(CampaignStyle.background)
              .background(deskSection == .plan && addDisabled ? CampaignStyle.muted : CampaignStyle.gold,
                          in: RoundedRectangle(cornerRadius: 14))
              .disabled(deskSection == .plan && addDisabled)
              .padding(.horizontal, 16).padding(.vertical, 8)
              .background(CampaignStyle.card)
            }
            .background(Color(red: 10/255, green: 15/255, blue: 20/255))
            .preferredColorScheme(.dark)
            .onAppear {
                if draft.target.isEmpty, let first = battlegrounds.first {
                    draft.target = first.state.id
                }
                #if targetEnvironment(simulator)
                if ProcessInfo.processInfo.arguments.contains("--mov-capture-plan") {
                    deskSection = .plan
                }
                #endif
            }
            .onChange(of: draft.target) { next in
                if let state = states.first(where: { $0.id == next }) {
                    selectedAbbr = state.abbr.uppercased()
                }
            }
            .alert("Week \(Int(g.turn())) recap", isPresented: $session.showRecap) {
                Button("OK") { session.dismissRecap() }
            } message: {
                Text(session.recapLines.joined(separator: "\n"))
            }
            .sheet(isPresented: eventVisible()) {
                if let eid = session.eventId {
                    EventDialogView(session: session, eventId: eid)
                }
            }
        )
    }

    private func legendItem(_ title: String, color: Color) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(title).foregroundStyle(CampaignStyle.muted)
        }
    }

    private func deskButton(_ title: String, section: DeskSection) -> some View {
        Button(title) { deskSection = section }
            .font(.subheadline.bold()).frame(maxWidth: .infinity).padding(.vertical, 10)
            .foregroundStyle(deskSection == section ? CampaignStyle.background : .white)
            .background(deskSection == section ? CampaignStyle.gold : CampaignStyle.card,
                        in: RoundedRectangle(cornerRadius: 10))
            .buttonStyle(.plain)
    }

    // Presents the pending-event sheet; hidden while the recap is up so the
    // recap reads first, matching the Android dialog order.
    private func eventVisible() -> Binding<Bool> {
        Binding(
            get: { !session.showRecap && session.eventId != nil },
            set: { if !$0 { session.closeEvent() } }
        )
    }
}

struct EventDialogView: View {
    @ObservedObject var session: GameSession
    var eventId: String

    var body: some View {
        let _ = session.version
        guard let g = session.currentGame() else { return AnyView(EmptyView()) }
        return AnyView(
            NavigationView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(g.eventTitle(eventId: eventId)).font(.headline)
                    Text(session.eventResult ?? g.eventPrompt(eventId: eventId))
                    if session.eventResult == nil {
                        ForEach(g.eventChoices(eventId: eventId), id: \.id) { choice in
                            Button(choice.text) { session.answerEvent(choiceId: choice.id) }
                                .buttonStyle(.bordered)
                        }
                    } else {
                        Button("Continue") { session.closeEvent() }
                            .buttonStyle(.borderedProminent)
                    }
                    Spacer()
                }
                .padding()
            }
        )
    }
}

private struct PlannerAction: Identifiable {
    let id: String
    let label: String
    let icon: String
    let hint: String
}

struct ActionPlannerView: View {
    @ObservedObject var session: GameSession
    @ObservedObject var draft: PlannerDraft
    let selectedStateId: String?
    let battlegrounds: [Battleground]

    private let actions = [
        PlannerAction(id: "advertise", label: "Advertising", icon: "megaphone.fill", hint: "$1-30M · move voters"),
        PlannerAction(id: "rally", label: "Rally", icon: "person.3.fill", hint: "Build momentum"),
        PlannerAction(id: "surrogate", label: "Surrogate", icon: "person.crop.circle.badge.checkmark", hint: "$0.25M · local lift"),
        PlannerAction(id: "fundraise", label: "Fundraise", icon: "dollarsign.circle.fill", hint: "Raise campaign cash"),
        PlannerAction(id: "ground_game", label: "Field offices", icon: "building.2.fill", hint: "Organize the state"),
        PlannerAction(id: "gotv", label: "GOTV", icon: "checkmark.seal.fill", hint: "Boost turnout"),
        PlannerAction(id: "oppo_research", label: "Oppo research", icon: "magnifyingglass", hint: "Find vulnerabilities"),
        PlannerAction(id: "debate_prep", label: "Debate prep", icon: "mic.fill", hint: "Prepare for debates"),
        PlannerAction(id: "policy_prep", label: "Policy prep", icon: "doc.text.fill", hint: "Strengthen policy"),
        PlannerAction(id: "issue_pivot", label: "Issue pivot", icon: "arrow.triangle.branch", hint: "Change your stance"),
    ]

    var body: some View {
        let _ = session.version
        let states = session.states().filter { !$0.blocs.isEmpty }
        let issues = session.issues()
        let plan = session.plannedActions()
        let dayCount = plan.filter { Int($0.day) == draft.day }.count

        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("BUILD THE WEEK").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold)
                Spacer()
                Text("\(plan.count) planned").font(.subheadline.bold())
            }
            Text(String(format: "Ad budget $%.1fM available", (session.currentGame()?.availableCash() ?? 0) / 1_000_000))
                .font(.subheadline).foregroundStyle(CampaignStyle.muted)
            Menu {
                ForEach(1...7, id: \.self) { n in
                    Button("Day \(n) · \(plan.filter { Int($0.day) == n }.count)/3 planned") { draft.day = n }
                }
            } label: {
                Label("DAY \(draft.day) · \(dayCount)/3 moves scheduled", systemImage: "calendar")
                    .font(.subheadline.bold()).foregroundStyle(CampaignStyle.gold)
            }

            if draft.needsState {
                VStack(alignment: .leading, spacing: 6) {
                    Menu {
                        ForEach(states, id: \.id) { state in
                            Button("\(state.name) · \(Int(state.electoralVotes)) EV") { draft.target = state.id }
                        }
                    } label: {
                        Label("TARGET · \(states.first(where: { $0.id == draft.target })?.name ?? "Choose a state")", systemImage: "map")
                            .font(.subheadline.bold()).foregroundStyle(CampaignStyle.gold)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(battlegrounds) { item in
                                Button {
                                    draft.target = item.state.id
                                    draft.notice = nil
                                } label: {
                                    Text("\(item.state.abbr.uppercased()) · \(Int(item.state.electoralVotes)) EV")
                                    .font(.subheadline.bold())
                                    .padding(.horizontal, 12).padding(.vertical, 9)
                                    .foregroundStyle(draft.target == item.state.id ? CampaignStyle.background : .white)
                                    .background(draft.target == item.state.id ? CampaignStyle.gold : CampaignStyle.background,
                                                in: RoundedRectangle(cornerRadius: 11))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }

            if draft.type == "advertise" {
                Text("ADVERTISING SETTINGS").font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
                HStack(spacing: 6) {
                    ForEach(["positive", "contrast", "issue"], id: \.self) { mode in
                        Button(mode.capitalized) { draft.adMode = mode }
                            .font(.subheadline.bold()).frame(maxWidth: .infinity).padding(.vertical, 9)
                            .foregroundStyle(draft.adMode == mode ? CampaignStyle.background : .white)
                            .background(draft.adMode == mode ? CampaignStyle.gold : CampaignStyle.background,
                                        in: RoundedRectangle(cornerRadius: 9))
                            .buttonStyle(.plain)
                    }
                }
                Text("Ad spend: $\(Int(draft.spend))M").font(.subheadline.bold())
                Slider(value: $draft.spend, in: 1...30, step: 1)
                    .tint(CampaignStyle.gold).padding(.vertical, 8)
            }
            if draft.type == "issue_pivot" || (draft.type == "advertise" && draft.adMode == "issue") {
                Menu {
                    ForEach(issues, id: \.id.serial) { item in
                        Button(item.name) {
                            draft.issue = item.id.serial
                            draft.position = session.playerIssuePosition(draft.issue)
                        }
                    }
                } label: {
                    Label("Issue: \(issues.first(where: { $0.id.serial == draft.issue })?.name ?? draft.issue)", systemImage: "text.book.closed")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .tint(CampaignStyle.gold)
            }
            if draft.type == "issue_pivot" {
                Text(String(format: "Position: %.2f · left -1 to right +1", draft.position)).font(.subheadline.bold())
                Slider(value: $draft.position, in: -1...1, step: 0.05)
                    .tint(CampaignStyle.gold).padding(.vertical, 8)
            }


            Text("CAMPAIGN MOVE").font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(actions) { action in
                        Button {
                            draft.type = action.id
                            if draft.type == "issue_pivot" { draft.position = session.playerIssuePosition(draft.issue) }
                            draft.notice = nil
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Image(systemName: action.icon).font(.title3)
                                Text(action.label).font(.subheadline.bold()).lineLimit(1)
                                Text(action.hint).font(.caption).lineLimit(2)
                                    .foregroundStyle(draft.type == action.id ? CampaignStyle.background.opacity(0.8) : CampaignStyle.muted)
                            }
                            .frame(width: 140, height: 86, alignment: .leading)
                            .padding(10)
                            .foregroundStyle(draft.type == action.id ? CampaignStyle.background : .white)
                            .background(draft.type == action.id ? CampaignStyle.gold : CampaignStyle.background,
                                        in: RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            Text("Swipe for more moves").font(.caption).foregroundStyle(CampaignStyle.muted)

            if dayCount >= 3 {
                Text("Day \(draft.day) is full. Choose another day.").font(.subheadline).foregroundStyle(CampaignStyle.gold)
            } else if (session.currentGame()?.slotsLeft() ?? 0) == 0 {
                Text("No action slots remain this week.").font(.subheadline).foregroundStyle(CampaignStyle.gold)
            }
            if let notice = draft.notice {
                Text(notice).font(.subheadline).foregroundStyle(CampaignStyle.gold)
            }

            HStack {
                Text("WEEK SCHEDULE").font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
                Spacer()
                Button("Clear all") { session.clearQueue() }.disabled(plan.isEmpty)
                    .font(.subheadline)
            }
            ForEach(1...7, id: \.self) { n in
                HStack(alignment: .top) {
                    Text("DAY \(n)").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
                        .frame(width: 48, alignment: .leading)
                    let items = plan.filter { Int($0.day) == n }
                    if items.isEmpty {
                        Text("Open").font(.subheadline).foregroundStyle(CampaignStyle.muted)
                    } else {
                        VStack(alignment: .leading) {
                            ForEach(items, id: \.index) { item in
                                Button("\(item.label)  ×") { session.removeAction(Int(item.index)) }
                                    .font(.subheadline).buttonStyle(.plain)
                            }
                        }
                    }
                    Spacer()
                }
            }
            Button { session.endTurn() } label: {
                Text("End week · \(plan.count) planned  →")
                    .font(.subheadline.bold()).frame(maxWidth: .infinity).padding(12)
            }
            .buttonStyle(.plain)
            .foregroundStyle(plan.isEmpty ? CampaignStyle.muted : .white)
            .background(CampaignStyle.background, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(CampaignStyle.muted.opacity(0.5)))
            .disabled(plan.isEmpty)
            if plan.isEmpty {
                Text("Add a move before ending the week.").font(.subheadline).foregroundStyle(CampaignStyle.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(14)
        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 18))
        .onChange(of: selectedStateId) { next in
            if let next = next, states.contains(where: { $0.id == next }) { draft.target = next }
        }
        .onAppear {
            if let selectedStateId, states.contains(where: { $0.id == selectedStateId }) {
                draft.target = selectedStateId
            }
        }
    }
}
