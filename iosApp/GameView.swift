import SwiftUI
import shared

// Phase 4 Game screen (#22): EV bar, tile map, state panel, end turn,
// recap sheet, event dialogs. Mirrors androidApp GameScreen.
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
        let selState = states.first(where: { $0.id == selId })
        let selContest = selId.flatMap { contests[$0] }

        return AnyView(
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("CAMPAIGN DESK").font(.caption.bold()).tracking(2).foregroundStyle(.orange)
                    Text(g.campaignLabel()).font(.title2.bold())
                    VStack(spacing: 14) {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading) {
                                Text("DEMOCRATS").font(.caption2.bold()).foregroundStyle(Color(red: 0.5, green: 0.66, blue: 1))
                                Text("\(proj.dem)").font(.largeTitle.bold())
                            }
                            Spacer()
                            VStack {
                                Text("270 TO WIN").font(.caption2.bold()).foregroundStyle(.orange)
                                Text("\(proj.tossup) tossup").font(.caption)
                            }
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text("REPUBLICANS").font(.caption2.bold()).foregroundStyle(Color(red: 1, green: 0.54, blue: 0.51))
                                Text("\(proj.rep)").font(.largeTitle.bold())
                            }
                        }
                        ProgressView(value: Double(proj.dem) + Double(proj.tossup) / 2, total: 538)
                            .tint(Color(red: 0.5, green: 0.66, blue: 1))
                        HStack {
                            Text("WEEK \(Int(g.turn()) + 1)/\(Int(g.totalTurns()))")
                            Spacer()
                            Text(String(format: "$%.1fM", g.playerCash() / 1_000_000))
                            Spacer()
                            Text("\(Int(g.slotsLeft())) ACTIONS")
                        }.font(.caption.bold())
                    }
                    .padding(16).background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 18))

                    TileMapView(
                        contestsById: contests,
                        abbrToStateId: abbrToId,
                        selectedAbbr: selectedAbbr,
                        onSelect: { selectedAbbr = $0 }
                    )

                    if let st = selState, let ct = selContest {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("\(st.name)  ·  \(Int(st.electoralVotes)) EV").font(.headline)
                            Text("Democratic projection \(Int(ct.demShare * 100))%")
                                .font(.subheadline).foregroundStyle(.secondary)
                            HStack {
                                actionButton("Ads") { session.queueAction(typeSerial: "advertise", stateId: st.id) }
                                actionButton("Rally") { session.queueAction(typeSerial: "rally", stateId: st.id) }
                            }
                            HStack {
                                actionButton("Ground") { session.queueAction(typeSerial: "ground_game", stateId: st.id) }
                                actionButton("GOTV") { session.queueAction(typeSerial: "gotv", stateId: st.id) }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading).padding(14)
                        .background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 16))
                    } else {
                        Text("Tap a state, then queue actions.").font(.caption)
                    }

                    HStack {
                        actionButton("Fundraise") { session.queueAction(typeSerial: "fundraise", stateId: nil) }
                        Spacer()
                        Text("Queued \(Int(g.queuedCount()))").font(.caption)
                        Button("Clear") { session.clearQueue() }
                    }

                    Button { session.endTurn() } label: {
                        Text("End week  →").font(.headline).frame(maxWidth: .infinity).padding(16)
                    }
                    .buttonStyle(.plain).foregroundStyle(.black)
                    .background(.orange, in: RoundedRectangle(cornerRadius: 14))
                }
                .padding()
            }
            .background(Color(red: 10/255, green: 15/255, blue: 20/255))
            .preferredColorScheme(.dark)
            .alert("Week \(Int(g.turn()) + 1) recap", isPresented: $session.showRecap) {
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

    private func actionButton(_ label: String, run: @escaping () -> Void) -> some View {
        Button(label, action: run).buttonStyle(.bordered)
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
