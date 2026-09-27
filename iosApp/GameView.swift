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
                VStack(spacing: 8) {
                    HStack {
                        Text("DEM \(proj.dem)").foregroundColor(.blue)
                        Spacer()
                        Text("270 to win").font(.caption)
                        Spacer()
                        Text("\(proj.rep) REP").foregroundColor(.red)
                    }
                    ProgressView(value: Double(proj.dem) + Double(proj.tossup) / 2, total: 538)
                    HStack {
                        Text("Week \(Int(g.turn()) + 1)/\(Int(g.totalTurns()))").font(.caption)
                        Spacer()
                        Text(String(format: "Cash $%.1fM", g.playerCash() / 1_000_000)).font(.caption)
                        Spacer()
                        Text("Actions \(Int(g.slotsLeft()))").font(.caption)
                    }

                    TileMapView(
                        contestsById: contests,
                        abbrToStateId: abbrToId,
                        selectedAbbr: selectedAbbr,
                        onSelect: { selectedAbbr = $0 }
                    )

                    if let st = selState, let ct = selContest {
                        Text("\(st.name): \(Int(st.electoralVotes)) EV, Dem \(Int(ct.demShare * 100))%")
                            .font(.headline)
                        HStack {
                            actionButton("Ads") { session.queueAction(typeSerial: "advertise", stateId: st.id) }
                            actionButton("Rally") { session.queueAction(typeSerial: "rally", stateId: st.id) }
                            actionButton("Ground") { session.queueAction(typeSerial: "ground_game", stateId: st.id) }
                            actionButton("GOTV") { session.queueAction(typeSerial: "gotv", stateId: st.id) }
                        }
                    } else {
                        Text("Tap a state, then queue actions.").font(.caption)
                    }

                    HStack {
                        actionButton("Fundraise") { session.queueAction(typeSerial: "fundraise", stateId: nil) }
                        Spacer()
                        Text("Queued \(Int(g.queuedCount()))").font(.caption)
                        Button("Clear") { session.clearQueue() }
                    }

                    Button("End Week") { session.endTurn() }
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity)
                }
                .padding()
            }
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
