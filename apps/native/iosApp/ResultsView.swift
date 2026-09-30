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
                        HStack(spacing: 8) {
                            Image("MOVMark")
                                .resizable()
                                .frame(width: 28, height: 28)
                                .accessibilityHidden(true)
                            Text("ELECTION NIGHT").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold)
                        }
                        Text(g.resultWinnerSerial() == g.playerSerial() ? "Victory" : "The race is over")
                            .font(.largeTitle.bold())
                        Text(g.resultWinnerSerial() == "tie" ? "Electoral College tied. No candidate reaches 270." : "\(g.resultWinnerName()) wins the presidency").font(.title3)
                        HStack {
                            Text("DEM \(Int(g.resultDemEv()))").foregroundStyle(CampaignStyle.democrat)
                            Spacer()
                            Text("\(Int(g.resultRepEv())) REP").foregroundStyle(CampaignStyle.republican)
                        }
                        .font(.title2.bold())
                        Text(String(format: "Democratic popular vote %.1f%%", g.resultDemPopularShare() * 100))
                            .font(.caption).foregroundStyle(.secondary)
                        Text(String(format: "Republican popular vote %.1f%%", (1 - g.resultDemPopularShare()) * 100))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(20)
                    .background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 18))
                    if let summary = g.resultSummary() {
                        VStack(alignment: .leading, spacing: 8) {
                            if summary.score >= 0 {
                                Text("CAMPAIGN SCORE \(summary.score) / 1000").font(.headline).foregroundStyle(CampaignStyle.gold)
                                Text("\(summary.difficulty.capitalized) difficulty · EV margin \(summary.unitMargin)")
                            } else {
                                Text("Score unavailable for this older save").font(.subheadline)
                            }
                            Text(String(format: "Popular vote margin %+.1f points", summary.popularMargin)).font(.subheadline)
                            if !summary.standardLength { Text("Short and long campaigns are casual runs. Standard nine-week campaigns are comparable on the leaderboard.").font(.caption).foregroundStyle(CampaignStyle.muted) }
                        }
                        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
                    }
                    ScorePosting(session: session)
                    if !g.resultAchievements().isEmpty {
                        Text("ACHIEVEMENTS EARNED").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
                        ForEach(g.resultAchievements(), id: \.id) { award in
                            VStack(alignment: .leading, spacing: 4) {
                                Text("\(award.icon) \(award.name)").font(.subheadline.bold())
                                Text(award.blurb).font(.caption).foregroundStyle(CampaignStyle.muted)
                            }.padding(.vertical, 4)
                        }
                    }
                    DisclosureGroup("Compare with history") {
                        ForEach(g.resultHistory(), id: \.id) { region in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(region.name).font(.subheadline.bold())
                                Text("\(region.units) EV now · \(region.historicalUnits) historically")
                                Text(String(format: "Vote share swing %+.1f points", region.shareSwing))
                            }.font(.caption).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4)
                        }
                    }.padding(.vertical, 10)
                    Text("STATE RESULTS").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold).padding(.top, 10)

                    ForEach(rows, id: \.stateId) { sr in
                        HStack {
                            Text(names[sr.stateId] ?? sr.stateId).font(.caption)
                            Spacer()
                            Text("\(Int(sr.electoralVotes)) EV").font(.caption)
                            Text(String(format: "+%.1f", sr.margin))
                                .font(.caption)
                                .foregroundColor(sr.winner.serial == "dem" ? CampaignStyle.democrat : CampaignStyle.republican)
                        }
                    }

                    let causes = g.resultCauses()
                    if !causes.isEmpty {
                        Text("WHAT DECIDED IT").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold).padding(.top, 10)
                        ForEach(causes, id: \.self) { cause in
                            Text(cause).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }

                    Button("Play Again") {
                        session.playScreen = .setup
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
