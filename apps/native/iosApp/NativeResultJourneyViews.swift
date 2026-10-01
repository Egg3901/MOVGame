import SwiftUI
import shared

struct ResultJourneyCard: View {
    @ObservedObject var session: GameSession
    @ObservedObject private var account: CampaignAccount
    init(session: GameSession) { self.session = session; self.account = session.account }

    var body: some View {
        if let journey = session.resultJourney() {
            VStack(alignment: .leading, spacing: 10) {
                if journey.daily && journey.score >= 0 {
                    Text("DAILY · \(nativeUTCDay()) · \(journey.score) / 1000").font(.headline).foregroundStyle(CampaignStyle.gold)
                    if account.boardKey == "daily:\(nativeUTCDay())",
                       let percentile = NativeResultsJourney.companion.percentile(score: journey.score,
                        boardScores: account.board.map { KotlinInt(int: Int32($0.score)) }, rank: account.dailyRank.map { KotlinInt(int: Int32($0.rank)) }) {
                        Text("Top \(percentile)% vs today's board").font(.subheadline)
                        if let rank = account.dailyRank { Text("Rank #\(rank.rank)").font(.caption) }
                    }
                    Button("Refresh daily ranking") { Task { await account.loadBoard(date: nativeUTCDay()) } }
                }
                ShareLink(item: journey.shareText) { Label("Share campaign result", systemImage: "square.and.arrow.up") }
                if let next = journey.next {
                    Text("NEXT CAMPAIGN").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
                    Text(next.label).font(.headline)
                    Text(next.blurb).font(.caption).foregroundStyle(CampaignStyle.muted)
                    Button("Play \(next.electionId)") { session.playNextCampaign() }.buttonStyle(.borderedProminent)
                }
            }
            .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
            .task(id: "\(journey.shareText):\(account.user?.id ?? "guest")") {
                if journey.daily { await account.loadBoard(date: nativeUTCDay()) }
            }
        }
    }
}

struct AccountBoardsView: View {
    @ObservedObject var account: CampaignAccount
    @State private var selected = "champions"
    @State private var date = nativeUTCDay()
    private var dateValid: Bool { date.range(of: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", options: .regularExpression) != nil }
    private var elections: [NativeElection] {
        MobileCampaign.companion.countries().flatMap { MobileCampaign.companion.elections(countryId: $0.id) }
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("LEADERBOARDS").font(.title2.bold()).foregroundStyle(CampaignStyle.gold)
                Picker("Board", selection: $selected) {
                    Text("Daily champions").tag("champions")
                    Text("Daily challenge").tag("daily")
                    ForEach(elections, id: \.scenarioId) { election in Text("\(election.flag) \(election.label)").tag(election.scenarioId) }
                }.pickerStyle(.menu)
                if selected == "daily" {
                    TextField("Daily date (YYYY-MM-DD, UTC)", text: $date).textInputAutocapitalization(.never).textFieldStyle(.roundedBorder)
                    if !dateValid { Text("Enter a UTC date as YYYY-MM-DD.").font(.caption).foregroundStyle(CampaignStyle.coral) }
                }
                if selected == "champions" {
                    if let table = account.champions {
                        Text("\(table.totalDays) days played").font(.subheadline)
                        if table.entries.isEmpty { Text("No daily champions yet.") }
                        ForEach(table.entries, id: \.rank) { entry in
                            VStack(alignment: .leading, spacing: 4) {
                                Text("#\(entry.rank) \(entry.username)").font(.headline)
                                Text("\(entry.wins) wins · \(entry.podiums) podiums · \(entry.played) played · \(entry.totalScore) points").font(.caption)
                            }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                                .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                } else {
                    if let rank = account.dailyRank { Text("Your daily rank: #\(rank.rank) · \(rank.score)").foregroundStyle(CampaignStyle.gold) }
                    if account.board.isEmpty { Text("No scores to show yet. You can keep playing offline.").font(.caption) }
                    ForEach(account.board, id: \.rank) { entry in Text("#\(entry.rank) \(entry.username) · \(entry.score)") }
                }
                Button("Refresh leaderboard") { Task { await refresh() } }
                if let message = account.message { Text(message).font(.caption).foregroundStyle(CampaignStyle.coral) }
            }.padding(20)
        }
        .task(id: "\(selected):\(date):\(account.user?.id ?? "guest")") { await refresh() }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }
    private func refresh() async {
        if selected == "champions" { await account.loadChampions() }
        else if dateValid { await account.loadBoard(date: date, scenarioId: selected == "daily" ? nil : selected) }
    }
}
