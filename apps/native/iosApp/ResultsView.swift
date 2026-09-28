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
                VStack(alignment: .leading, spacing: 10) {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("ELECTION NIGHT").font(.caption.bold()).tracking(2).foregroundStyle(.orange)
                        Text(g.resultWinnerSerial() == g.playerSerial() ? "Victory" : "The race is over")
                            .font(.largeTitle.bold())
                        Text("\(g.resultWinnerName()) wins the presidency").font(.title3)
                        Text("DEM \(Int(g.resultDemEv()))   ·   \(Int(g.resultRepEv())) REP").font(.title2.bold())
                        Text(String(format: "Democratic popular vote %.1f%%", g.resultDemPopularShare() * 100))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(20)
                    .background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 18))
                    Text("STATE RESULTS").font(.caption.bold()).tracking(2).foregroundStyle(.orange).padding(.top, 10)

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
                        Text("WHAT DECIDED IT").font(.caption.bold()).tracking(2).foregroundStyle(.orange).padding(.top, 10)
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
            .background(Color(red: 10/255, green: 15/255, blue: 20/255))
            .preferredColorScheme(.dark)
        )
    }
}
