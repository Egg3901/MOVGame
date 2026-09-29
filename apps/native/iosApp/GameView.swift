import SwiftUI
import shared

// Native campaign desk with map, state projection, action plan, and turn recap.
struct GameView: View {
    @ObservedObject var session: GameSession
    @State private var selectedAbbr: String? = nil

    var body: some View {
        // `version` is read so the view re-renders after every mutation.
        let _ = session.version
        guard let g = session.currentGame() else {
            return AnyView(Text("No campaign. Start one from Play."))
        }
        let proj = session.projection()
        let contests = session.contestsById()
        let states = session.states()
        let abbrToId = Dictionary(
            uniqueKeysWithValues: states.map { ($0.abbr.uppercased(), $0.id) }
        )
        let selId = selectedAbbr.flatMap { abbrToId[$0] }

        return AnyView(
            VStack(spacing: 0) {
              ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("CAMPAIGN DESK").font(.caption.bold()).tracking(2).foregroundStyle(.orange)
                    Text(g.campaignLabel()).font(.title2.bold())
                    VStack(spacing: 14) {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading) {
                                Text("DEMOCRATS").font(.caption2.bold()).foregroundStyle(CampaignStyle.democrat)
                                Text("\(proj.dem)").font(.largeTitle.bold())
                            }
                            Spacer()
                            VStack {
                                Text("270 TO WIN").font(.caption2.bold()).foregroundStyle(.orange)
                                Text("\(proj.tossup) tossup").font(.caption)
                            }
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text("REPUBLICANS").font(.caption2.bold()).foregroundStyle(CampaignStyle.republican)
                                Text("\(proj.rep)").font(.largeTitle.bold())
                            }
                        }
                        ProgressView(value: Double(proj.dem) + Double(proj.tossup) / 2, total: 538)
                            .tint(CampaignStyle.democrat)
                        HStack {
                            Text("WEEK \(Int(g.turn()) + 1)/\(Int(g.totalTurns()))")
                            Spacer()
                            Text(String(format: "$%.1fM", g.playerCash() / 1_000_000))
                            Spacer()
                            Text("\(Int(g.slotsLeft())) ACTIONS")
                        }.font(.caption.bold())
                    }
                    .padding(16).background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 18))

                    GeoMapView(
                        contestsById: contests,
                        abbrToStateId: abbrToId,
                        selectedAbbr: selectedAbbr,
                        onSelect: { selectedAbbr = $0 }
                    )
                    .frame(height: 260)
                    Text("Tap a state to inspect it and target your plan.")
                        .font(.caption).foregroundStyle(.secondary)
                    if let id = selId, let state = states.first(where: { $0.id == id }),
                       let contest = contests[id] {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(state.name) · \(Int(state.electoralVotes)) EV").font(.headline)
                            Text(String(format: "Democratic projection %.1f%%", contest.demShare * 100))
                                .font(.subheadline)
                            Text("Select another state to retarget your plan.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 14))
                    }

                    ActionPlannerView(session: session, selectedStateId: selId)
                }
                .padding()
              }
              Button { session.endTurn() } label: {
                  Text("End week · \(Int(g.queuedCount())) planned →")
                      .font(.headline).frame(maxWidth: .infinity).padding(14)
              }
              .buttonStyle(.plain).foregroundStyle(.black)
              .background(.orange, in: RoundedRectangle(cornerRadius: 14))
              .padding(.horizontal, 16).padding(.vertical, 8)
              .background(Color(red: 17/255, green: 27/255, blue: 38/255))
            }
            .background(Color(red: 10/255, green: 15/255, blue: 20/255))
            .preferredColorScheme(.dark)
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
}

struct ActionPlannerView: View {
    @ObservedObject var session: GameSession
    let selectedStateId: String?
    @State private var type = "advertise"
    @State private var target = "PA"
    @State private var day = 1
    @State private var adMode = "positive"
    @State private var spend = 8.0
    @State private var issue = "economy"
    @State private var position = 0.0
    @State private var notice: String? = nil

    private let actions = [
        PlannerAction(id: "advertise", label: "Advertising"), PlannerAction(id: "rally", label: "Rally"),
        PlannerAction(id: "surrogate", label: "Surrogate"), PlannerAction(id: "fundraise", label: "Fundraise"),
        PlannerAction(id: "ground_game", label: "Field offices"), PlannerAction(id: "gotv", label: "GOTV"),
        PlannerAction(id: "oppo_research", label: "Oppo research"), PlannerAction(id: "debate_prep", label: "Debate prep"),
        PlannerAction(id: "policy_prep", label: "Policy prep"), PlannerAction(id: "issue_pivot", label: "Issue pivot"),
    ]

    private var needsState: Bool {
        ["advertise", "rally", "surrogate", "fundraise", "ground_game", "gotv"].contains(type)
    }

    var body: some View {
        let _ = session.version
        let states = session.states().filter { !$0.blocs.isEmpty }
        let issues = session.issues()
        let plan = session.plannedActions()
        let dayCount = plan.filter { Int($0.day) == day }.count

        VStack(alignment: .leading, spacing: 11) {
            Text("WEEK PLAN").font(.caption.bold()).tracking(2).foregroundStyle(.orange)
            Text("Choose an action, set the target, then add it to a day.")
                .font(.caption).foregroundStyle(.secondary)
            Text(String(format: "Planned ads $%.1fM · Available $%.1fM", (session.currentGame()?.plannedSpend() ?? 0) / 1_000_000, (session.currentGame()?.availableCash() ?? 0) / 1_000_000))
                .font(.caption).foregroundStyle(.secondary)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(actions) { action in
                    Button {
                        type = action.id
                        if type == "issue_pivot" { position = session.playerIssuePosition(issue) }
                        notice = nil
                    } label: {
                        Text(action.label).font(.subheadline.bold()).frame(maxWidth: .infinity).padding(10)
                    }
                    .buttonStyle(.plain)
                    .background(type == action.id ? Color.orange.opacity(0.28) : Color.black.opacity(0.35),
                                in: RoundedRectangle(cornerRadius: 11))
                }
            }
            if needsState {
                Menu {
                    ForEach(states, id: \.id) { state in
                        Button("\(state.name) · \(Int(state.electoralVotes)) EV") { target = state.id }
                    }
                } label: {
                    Label("Target: \(states.first(where: { $0.id == target })?.name ?? "Pennsylvania")", systemImage: "map")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .tint(.orange)
            }
            if type == "advertise" {
                Picker("Ad mode", selection: $adMode) {
                    Text("Positive").tag("positive")
                    Text("Contrast").tag("contrast")
                    Text("Issue").tag("issue")
                }.pickerStyle(.segmented)
                Text("Spend: $\(Int(spend))M").font(.subheadline.bold())
                Slider(value: $spend, in: 1...30, step: 1).tint(.orange)
            }
            if type == "issue_pivot" || (type == "advertise" && adMode == "issue") {
                Menu {
                    ForEach(issues, id: \.id.serial) { item in
                        Button(item.name) {
                            issue = item.id.serial
                            position = session.playerIssuePosition(issue)
                        }
                    }
                } label: {
                    Label("Issue: \(issues.first(where: { $0.id.serial == issue })?.name ?? issue)", systemImage: "text.book.closed")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .tint(.orange)
            }
            if type == "issue_pivot" {
                Text(String(format: "Position: %.2f · left −1 to right +1", position)).font(.subheadline.bold())
                Slider(value: $position, in: -1...1, step: 0.05).tint(.orange)
            }
            Text("ADD TO DAY").font(.caption.bold()).tracking(1).foregroundStyle(.orange)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 8) {
                ForEach(1...7, id: \.self) { n in
                    Button("\(n)") { day = n }
                        .buttonStyle(.plain).frame(maxWidth: .infinity).padding(9)
                        .background(day == n ? Color.orange.opacity(0.28) : Color.black.opacity(0.35),
                                    in: RoundedRectangle(cornerRadius: 10))
                }
            }
            Text("Day \(day) · \(dayCount)/3 actions")
                .font(.caption).foregroundStyle(.secondary)
            Button {
                let added = session.queueConfiguredAction(typeSerial: type, stateId: needsState ? target : nil,
                    day: day, adModeSerial: type == "advertise" ? adMode : nil,
                    spendMillions: type == "advertise" ? spend : nil,
                    issueSerial: (type == "issue_pivot" || (type == "advertise" && adMode == "issue")) ? issue : nil,
                    newPosition: type == "issue_pivot" ? position : nil)
                notice = added ? nil : "Check your day, action slots, and available cash."
            } label: {
                Text("Add to day \(day)").font(.headline).frame(maxWidth: .infinity).padding(12)
            }
            .buttonStyle(.plain).foregroundStyle(.black)
            .background(.orange, in: RoundedRectangle(cornerRadius: 12))
            .disabled(dayCount >= 3 || (session.currentGame()?.slotsLeft() ?? 0) == 0 || (type == "advertise" && spend * 1_000_000 > (session.currentGame()?.availableCash() ?? 0)))
            if dayCount >= 3 {
                Text("Day \(day) is full. Choose another day.").font(.caption).foregroundStyle(.secondary)
            } else if (session.currentGame()?.slotsLeft() ?? 0) == 0 {
                Text("Weekly action pool is spent.").font(.caption).foregroundStyle(.secondary)
            }
            if let notice = notice { Text(notice).font(.caption).foregroundStyle(.red) }
            HStack {
                Text("\(plan.count) planned").font(.headline)
                Spacer()
                Button("Clear all") { session.clearQueue() }.disabled(plan.isEmpty)
            }
            ForEach(1...7, id: \.self) { n in
                HStack(alignment: .top) {
                    Text("DAY \(n)").font(.caption.bold()).foregroundStyle(.orange).frame(width: 48, alignment: .leading)
                    let items = plan.filter { Int($0.day) == n }
                    if items.isEmpty {
                        Text("Open").font(.caption).foregroundStyle(.secondary)
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
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(14)
        .background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 18))
        .onChange(of: selectedStateId) { next in
            if let next = next, states.contains(where: { $0.id == next }) { target = next }
        }
    }
}
