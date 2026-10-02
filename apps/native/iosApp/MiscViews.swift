import SwiftUI
import Security
import CryptoKit
import shared

struct StoreView: View {
    @ObservedObject var session: GameSession
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store: StoreKitAdapter
    init(session: GameSession) { self.session = session; self.store = session.store }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Choose an election").font(.title2.bold())
                Text("All 49 elections across six countries are free to play during the open beta.")
                    .font(.subheadline).foregroundStyle(CampaignStyle.muted)
                Button("Browse all elections") {
                    session.dailySetup = nil
                    session.playScreen = .library
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                Text("U.S. elections").font(.headline).foregroundStyle(CampaignStyle.gold)
                ForEach(session.campaigns(), id: \.id) { campaign in
                    Button {
                        session.setupScenarioId = campaign.id
                        session.playScreen = .setup
                        dismiss()
                    } label: {
                        HStack(spacing: 14) {
                            Text(String(campaign.year))
                                .font(.system(size: 27, weight: .bold, design: .serif))
                                .foregroundStyle(CampaignStyle.gold)
                                .frame(width: 70, alignment: .leading)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(campaign.label).font(.subheadline.bold()).foregroundStyle(.white)
                                Text("\(campaign.demName) vs. \(campaign.repName)")
                                    .font(.caption).foregroundStyle(CampaignStyle.muted)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").foregroundStyle(CampaignStyle.coral)
                        }
                        .padding(14)
                        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                }
                if let notice = store.notice {
                    Text(notice).font(.caption).foregroundStyle(CampaignStyle.muted)
                }
                ForEach(store.products) { product in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(product.title).font(.headline)
                            Text((store.owned.contains(product.packId) || store.owned.contains("complete")) ? "Owned" : product.price)
                                .font(.caption)
                        }
                        Spacer()
                        if !(store.owned.contains(product.packId) || store.owned.contains("complete")) {
                            Button("Buy") {
                                Task { await store.purchase(packId: product.packId) }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
                if session.account.user?.ahdLinked == true {
                    Text("Pack ownership follows your Lakeside account across web, iOS and Android.").font(.caption)
                    Button("Restore purchases") { Task { await store.restore() } }
                }
            }
            .padding(20)
        }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }
}

struct AccountView: View {
    @ObservedObject var session: GameSession
    @ObservedObject private var account: CampaignAccount
    @State private var email = ""
    @State private var password = ""
    @State private var username = ""
    @State private var registering = false
    @State private var code = ""
    @State private var showingLakeside = false

    init(session: GameSession) { self.session = session; self.account = session.account }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("YOUR ACCOUNT").font(.caption.bold()).tracking(1.5).foregroundStyle(CampaignStyle.gold)
                if let user = account.user {
                    Text("Welcome, \(user.username)").font(.title2.bold())
                    Text(user.email).foregroundStyle(CampaignStyle.muted)
                    Text(user.ahdLinked == true ? "Lakeside Games account linked" : "Lakeside Games account not linked").font(.caption)
                    if user.ahdLinked != true { Button("Sign in with Lakeside Games") { showingLakeside = true }.disabled(account.busy) }
                    Button("Refresh account") { Task { await account.refresh() } }.disabled(account.busy)
                    AccountPurchaseHistory(account: account)
                    Button("Sign out") { account.signOut() }.disabled(account.busy)
                        .foregroundStyle(CampaignStyle.coral)
                    Text("\(account.unlocked.count) campaigns activated on this account").font(.caption)
                    TextField("Activation code", text: $code).textInputAutocapitalization(.characters)
                    Button("Activate code") { Task { await account.activate(code: code) } }
                        .disabled(account.busy || code.trimmingCharacters(in: .whitespaces).isEmpty)
                } else {
                    Text(registering ? "Create an account" : "Sign in")
                        .font(.title2.bold())
                    Text("Use the same Margin of Victory account as the web game.")
                        .font(.subheadline).foregroundStyle(CampaignStyle.muted)
                    Button("Sign in with Lakeside Games") { showingLakeside = true }.disabled(account.busy)
                    Text("Or use email").font(.caption).foregroundStyle(CampaignStyle.muted)
                    if registering {
                        TextField("Username", text: $username)
                            .textContentType(.username)
                    }
                    TextField("Email", text: $email)
                        .textContentType(.emailAddress).keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                    SecureField("Password", text: $password)
                        .textContentType(registering ? .newPassword : .password)
                    Button(account.busy ? "Please wait…" : registering ? "Create account" : "Sign in") {
                        Task {
                            await account.authenticate(email: email, password: password,
                                                       username: registering ? username : nil)
                            if account.user != nil { password = "" }
                        }
                    }
                    .font(.headline).frame(maxWidth: .infinity).padding(15)
                    .foregroundStyle(CampaignStyle.background)
                    .background(CampaignStyle.coral, in: RoundedRectangle(cornerRadius: 12))
                    .disabled(account.busy || email.isEmpty || password.isEmpty || (registering && username.isEmpty))
                    Button(registering ? "Already have an account? Sign in" : "New here? Create an account") {
                        registering.toggle()
                        account.message = nil
                    }
                    .foregroundStyle(CampaignStyle.gold)
                }
                if let message = account.message {
                    Text(message).font(.subheadline).foregroundStyle(CampaignStyle.coral)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("CAMPAIGN SAVE").font(.caption.bold()).tracking(1).foregroundStyle(CampaignStyle.gold)
                    Text(session.hasGame ? session.savedCampaignLabel : "No campaign on this device")
                        .font(.headline)
                    Text("Daily challenges play on this device. Sign in to post finished campaigns to the shared leaderboard.")
                        .font(.subheadline).foregroundStyle(CampaignStyle.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
                NavigationLink("Manage saved campaigns") { CampaignSavesView(session: session) }
                AccountAchievements(account: account)
                if account.user != nil && !account.rankings.isEmpty {
                    DisclosureGroup("Your personal bests") {
                        ForEach(account.rankings, id: \.scenarioId) { rank in
                            Text("\(rank.scenarioId) · #\(rank.rank) · \(rank.score) · \(rank.difficulty)").font(.caption)
                        }
                    }
                }
                NavigationLink("Leaderboards and daily champions") { AccountBoardsView(account: account) }
            }
            .padding(20)
        }
        .textFieldStyle(.roundedBorder)
        .sheet(isPresented: $showingLakeside) {
            NativeLakesideLoginView { code in
                showingLakeside = false
                Task { await account.exchangeLakeside(code: code) }
            }
        }
        .onAppear {
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-lakeside-login") { showingLakeside = true }
            #endif
        }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }
}


struct AccountUser: Decodable {
    let id: String
    let username: String
    let email: String
    let ahdLinked: Bool?
}

private struct LoginResponse: Decodable {
    let token: String
    let user: AccountUser
}

private struct ProfileResponse: Decodable {
    let user: AccountUser
    let unlocked: AccountUnlocked?
}
private struct AccountUnlocked: Decodable { let scenarioIds: [String] }
struct BoardEntry: Decodable { let rank: Int; let username: String; let score: Int }
struct PersonalDailyRank: Decodable { let rank: Int; let score: Int }
private struct BoardResponse: Decodable { let entries: [BoardEntry]; let me: PersonalDailyRank? }
struct AccountPurchase: Decodable { let packName: String?; let packId: String?; let amountCents: Int?; let currency: String?; let provider: String?; let status: String; let createdAt: Double }
private struct PurchasesResponse: Decodable { let purchases: [AccountPurchase] }
private struct ScorePostResponse: Decodable { let rank: Int; let posted: Bool; let personalBest: Int }

private struct APIError: Decodable {
    let error: String
}

@MainActor
final class CampaignAccount: ObservableObject {
    @Published var user: AccountUser?
    @Published var busy = false
    @Published var message: String?
    @Published var board: [BoardEntry] = []
    @Published var unlocked: [String] = []
    @Published var cloudSaves: [CloudSaveMeta] = []
    @Published var dailyRank: PersonalDailyRank?
    @Published var boardKey = ""
    @Published var champions: NativeDailyChampions?
    @Published var rankings: [NativePlayerRanking] = []
    @Published var purchases: [AccountPurchase] = []
    @Published var purchasesLoaded = false
    @Published var awards: [NativeCollectedAward] = []
    private let progress = NativeAccountProgress.companion.restore(json: UserDefaults.standard.string(forKey: "mov_account_achievements_v1"))
    private var boardGeneration = 0

    private let service = "net.lakesidegames.marginofvictory.account"
    private let account = "session-token"

    init() {
        awards = progress.awards()
        if readToken() != nil { Task { await refresh() } }
    }

    func authenticate(email: String, password: String, username: String?) async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            let path = username == nil ? "login" : "register"
            var fields = ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "password": password]
            if let username { fields["username"] = username.trimmingCharacters(in: .whitespacesAndNewlines) }
            let payload = try await request(path: path, fields: fields)
            let response = try JSONDecoder().decode(LoginResponse.self, from: payload)
            try saveToken(response.token)
            user = response.user
            await refresh()
        } catch {
            message = error.localizedDescription
        }
    }

    func refresh() async {
        guard let token = readToken(),
              let url = URL(string: "https://sim.ahousedividedgame.com/api/auth/me") else { return }
        busy = true
        defer { busy = false }
        do {
            var request = URLRequest(url: url)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw AccountError("Could not reach account server") }
            if http.statusCode == 401 {
                if token == readToken() { signOut() }
                return
            }
            guard (200..<300).contains(http.statusCode) else { throw AccountError(serverMessage(data)) }
            let profile = try JSONDecoder().decode(ProfileResponse.self, from: data)
            guard token == readToken() else { return }
            user = profile.user
            unlocked = profile.unlocked?.scenarioIds ?? []
            await readDetails()
        } catch {
            message = "Account could not be refreshed: \(error.localizedDescription)"
        }
    }

    func signOut() {
        let query = tokenQuery()
        SecItemDelete(query as CFDictionary)
        user = nil
        unlocked = []
        cloudSaves = []
        purchases = []; purchasesLoaded = false; dailyRank = nil
        boardGeneration += 1; board = []; boardKey = ""; rankings = []
        message = nil
    }

    func storeSessionKey() -> String? {
        readToken().map { SHA256.hash(data: Data($0.utf8)).map { String(format: "%02x", $0) }.joined() }
    }

    func call(path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        guard let url = URL(string: "https://sim.ahousedividedgame.com\(path)") else { throw AccountError("Account service is unavailable") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = path.hasPrefix("/api/store/") ? 75 : 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let credential = readToken()
        if let token = credential { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AccountError("Could not reach account server") }
        guard credential == readToken() else { throw AccountError("Account changed during the request. Try again.") }
        if http.statusCode == 401, credential != nil, path != "/api/lakeside/exchange" { signOut() }
        guard (200..<300).contains(http.statusCode) else { throw AccountError(serverMessage(data), status: http.statusCode, conflict: ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["conflict"] as? Bool == true) }
        return data
    }

    func mirrorSave(id: String, payload: String, owner: String) async -> NativeCloudOutcome {
        guard user?.id == owner, !busy else { return NativeCloudOutcome(status: "retry", version: -1, message: "Sign in to resume cloud sync.") }
        busy = true
        defer { busy = false }
        do {
            let list = try await call(path: "/api/saves")
            guard let capabilities = try JSONSerialization.jsonObject(with: list) as? [String: Any], capabilities["versionPreconditions"] as? Bool == true else {
                return NativeCloudOutcome(status: "unsupported", version: -1, message: "Cloud save service needs an update.")
            }
            let data = try await call(path: "/api/saves/\(id)", method: "PUT", body: Data(payload.utf8))
            guard user?.id == owner, let response = try JSONSerialization.jsonObject(with: data) as? [String: Any], let version = response["updatedAt"] as? NSNumber else {
                return NativeCloudOutcome(status: "retry", version: -1, message: "Account changed during sync.")
            }
            if let record = try JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any], let name = record["name"] as? String, let turn = record["turn"] as? NSNumber {
                cloudSaves = (cloudSaves.filter { $0.id != id } + [CloudSaveMeta(id: id, name: name, turn: turn.intValue, updatedAt: version.int64Value)]).sorted { $0.updatedAt > $1.updatedAt }
            }
            return NativeCloudOutcome(status: "synced", version: version.int64Value, message: "")
        } catch {
            let code = (error as? AccountError)?.status
            let rejected = code.map { (400..<500).contains($0) && ![401, 408, 429].contains($0) } ?? false
            return NativeCloudOutcome(status: (error as? AccountError)?.conflict == true ? "conflict" : rejected ? "rejected" : "retry", version: -1, message: error.localizedDescription)
        }
    }

    func activate(code: String) async {
        guard !busy else { return }
        busy = true; message = nil
        defer { busy = false }
        do {
            let body = try JSONSerialization.data(withJSONObject: ["code": code.trimmingCharacters(in: .whitespaces)])
            _ = try await call(path: "/api/auth/activate", method: "POST", body: body)
            await refresh()
            message = "Campaign code activated."
        } catch { message = error.localizedDescription }
    }

    func loadBoard(date: String, scenarioId: String? = nil) async {
        boardGeneration += 1
        let generation = boardGeneration
        boardKey = scenarioId ?? "daily:\(date)"
        board = []; dailyRank = nil
        do {
            let data = try await call(path: scenarioId.map { "/api/leaderboard?scenario=\($0)&limit=20" } ?? "/api/daily/board?date=\(date)")
            guard generation == boardGeneration else { return }
            let response = try JSONDecoder().decode(BoardResponse.self, from: data)
            board = response.entries; dailyRank = response.me
        } catch { if generation == boardGeneration { message = "Leaderboard unavailable. You can keep playing offline." } }
    }

    func loadChampions() async {
        boardGeneration += 1
        let generation = boardGeneration
        boardKey = "champions"; champions = nil
        do {
            let data = try await call(path: "/api/daily/champions")
            guard generation == boardGeneration, let json = String(data: data, encoding: .utf8),
                  let result = NativeDailyChampions.companion.parse(json: json) else { return }
            champions = result
        } catch { if generation == boardGeneration { message = "Daily champions unavailable. You can keep playing offline." } }
    }

    func postScore(payload: String, daily: Bool) async {
        guard !busy else { return }
        busy = true; message = nil
        defer { busy = false }
        do {
            let data = try await call(path: daily ? "/api/daily" : "/api/leaderboard", method: "POST", body: Data(payload.utf8))
            let posted = try JSONDecoder().decode(ScorePostResponse.self, from: data)
            if daily { await loadBoard(date: nativeUTCDay()) }
            message = posted.posted ? "Score posted. Rank #\(posted.rank)." : "Kept your personal best (\(posted.personalBest)). Rank #\(posted.rank)."
        } catch { message = error.localizedDescription }
    }

    func exchangeLakeside(code: String) async {
        guard !busy else { return }
        busy = true; message = nil
        defer { busy = false }
        do {
            let body = try JSONSerialization.data(withJSONObject: ["code": code])
            let data = try await call(path: "/api/lakeside/exchange", method: "POST", body: body)
            let response = try JSONDecoder().decode(LoginResponse.self, from: data)
            try saveToken(response.token)
            user = response.user
            await refresh()
        } catch { message = error.localizedDescription }
    }

    private func persistProgress() {
        UserDefaults.standard.set(progress.json(), forKey: "mov_account_achievements_v1")
        awards = progress.awards()
    }
    func recordAchievementSnapshot(_ snapshot: String) {
        guard progress.recordSnapshot(snapshot: snapshot) else { return }
        persistProgress()
        if user != nil && !busy { Task { await syncAchievements() } }
    }
    private func syncProgress() async throws {
        guard let owner = user?.id else { return }
        let data = try await call(path: "/api/achievements")
        guard user?.id == owner else { throw AccountError("Account changed during achievement sync.") }
        guard let json = String(data: data, encoding: .utf8) else { throw AccountError("Achievement response unreadable.") }
        guard let uploads = progress.uploads(serverJson: json) else { throw AccountError("Achievement response unreadable.") }
        persistProgress()
        for upload in uploads {
            guard user?.id == owner else { throw AccountError("Account changed during achievement sync.") }
            _ = try await call(path: "/api/achievements", method: "POST", body: Data(upload.payload.utf8))
        }
    }
    func syncAchievements() async {
        guard !busy else { return }
        busy = true; message = nil
        defer { busy = false }
        do { try await syncProgress(); message = "Achievements synced." }
        catch { message = "Achievements are saved on this device. Refresh your account to retry sync." }
    }
    private func readDetails() async {
        do {
            let data = try await call(path: "/api/leaderboard/me")
            guard let json = String(data: data, encoding: .utf8), let result = NativePlayerRankings.companion.parse(json: json) else { throw AccountError("Rankings unreadable.") }
            rankings = result.rankings
        } catch { message = "Personal rankings unavailable. Refresh your account to try again." }
        do {
            let data = try await call(path: "/api/my-entitlements")
            purchases = try JSONDecoder().decode(PurchasesResponse.self, from: data).purchases
            purchasesLoaded = true
        } catch { message = "Purchase history unavailable. Refresh your account to try again." }
        if user != nil {
            do { try await syncProgress() }
            catch { message = "Achievements are saved on this device. Refresh your account to retry sync." }
        }
    }

    private func request(path: String, fields: [String: String]) async throws -> Data {
        guard let url = URL(string: "https://sim.ahousedividedgame.com/api/auth/\(path)") else {
            throw AccountError("Account service is unavailable")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = path.hasPrefix("/api/store/") ? 75 : 20
        request.httpBody = try JSONSerialization.data(withJSONObject: fields)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AccountError("Could not reach account server") }
        guard (200..<300).contains(http.statusCode) else { throw AccountError(serverMessage(data)) }
        return data
    }

    private func serverMessage(_ data: Data) -> String {
        (try? JSONDecoder().decode(APIError.self, from: data).error) ?? "Account request failed"
    }

    private func tokenQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    private func readToken() -> String? {
        var query = tokenQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func saveToken(_ token: String) throws {
        SecItemDelete(tokenQuery() as CFDictionary)
        var query = tokenQuery()
        query[kSecValueData as String] = Data(token.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        if SecItemAdd(query as CFDictionary, nil) != errSecSuccess {
            throw AccountError("Sign-in succeeded, but this device could not save the session.")
        }
    }
}

struct ScorePosting: View {
    @ObservedObject var session: GameSession
    @ObservedObject private var account: CampaignAccount
    @State private var showingAccount = false
    init(session: GameSession) { self.session = session; self.account = session.account }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
        ResultJourneyCard(session: session)
        if let payload = session.scorePayload() {
            VStack(alignment: .leading, spacing: 8) {
                if session.isDaily() { Text("DAILY CHALLENGE · Best \(session.dailyBest() ?? 0) · \(session.dailyStreak())-day streak").font(.caption.bold()).foregroundStyle(CampaignStyle.gold) }
                if account.user == nil {
                    Button("Sign in to post your score") { showingAccount = true }
                } else {
                    Button(account.busy ? "Posting…" : session.isDaily() ? "Post daily score" : "Post to leaderboard") {
                        Task { await account.postScore(payload: payload, daily: session.isDaily()) }
                    }.disabled(account.busy)
                }
                if let message = account.message { Text(message).font(.caption).foregroundStyle(CampaignStyle.muted) }
                if session.isDaily() { Button("Replay today's challenge") { session.openDaily(restart: true) } }
            }
            .sheet(isPresented: $showingAccount) {
                NavigationStack { AccountView(session: session).toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showingAccount = false } } } }
            }
        }
        }
    }
}

struct AccountError: LocalizedError {
    let detail: String
    let status: Int?
    let conflict: Bool
    init(_ detail: String, status: Int? = nil, conflict: Bool = false) { self.detail = detail; self.status = status; self.conflict = conflict }
    var errorDescription: String? { detail }
}

struct ImageCreditsView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Campaign photography").font(.title2.bold())
                Text("The U.S. Capitol and most election photos are public domain or CC0 images. The 2016 election photo is by Gage Skidmore, licensed CC BY-SA 3.0.")
                Link("2016 photo source", destination: URL(string: "https://commons.wikimedia.org/wiki/File:Hillary_Clinton_by_Gage_Skidmore_2.jpg")!)
                Link("CC BY-SA 3.0 license", destination: URL(string: "https://creativecommons.org/licenses/by-sa/3.0/")!)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(22)
        }
        .background(CampaignStyle.background)
    }
}
