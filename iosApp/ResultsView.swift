import SwiftUI
import shared

// Phase 4 Results screen (#22): mirrors androidApp ResultsScreen.
struct ResultsView: View {
    @ObservedObject var session: GameSession

    var body: some View {
        let _ = session.version
        guard let g = session.currentGame(), g.hasResult() else {
            return AnyView(
                VStack {
                    Text("No result yet.")
                    Button("Back to Setup") { session.playScreen = .setup }
                }
            )
        }
        let names = Dictionary(
            uniqueKeysWithValues: g.stateList().map { ($0.id, $0.name) }
        )
        let rows = g.resultStates().sorted {
            if $0.margin != $1.margin { return $0.margin > $1.margin }
            return (names[$0.stateId] ?? "") < (names[$1.stateId] ?? "")
        }

        return AnyView(
            ScrollView {
                VStack(spacing: 8) {
                    Text("Final Result").font(.title2)
                    Text(g.resultWinnerName()).font(.title)
                    Text("DEM \(Int(g.resultDemEv())) - \(Int(g.resultRepEv())) REP")
                        .font(.headline)
                    Text(String(format: "Popular vote: Dem %.1f%%", g.resultDemPopularShare() * 100))
                        .font(.caption)

                    ForEach(rows, id: \.stateId) { sr in
                        HStack {
                            Text(names[sr.stateId] ?? sr.stateId).font(.caption)
                            Spacer()
                            Text("\(Int(sr.electoralVotes)) EV").font(.caption)
                            Text(String(format: "+%.1f", sr.margin))
                                .font(.caption)
                                .foregroundColor(sr.winner.serial == "dem" ? .blue : .red)
                        }
                    }

                    let causes = g.resultCauses()
                    if !causes.isEmpty {
                        Text("What decided it").font(.headline)
                        ForEach(causes, id: \.self) { cause in
                            Text(cause).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }

                    Button("Play Again") {
                        session.playScreen = .setup
                        session.tab = 0
                    }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                }
                .padding()
            }
        )
    }
}
