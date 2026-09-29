import SwiftUI
import Security

struct StoreView: View {
    @ObservedObject var session: GameSession
    @Environment(\.dismiss) private var dismiss
    @StateObject private var store = StoreKitAdapter()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Choose an election").font(.title2.bold())
                Text("Every U.S. campaign in this TestFlight build is playable.")
                    .font(.subheadline).foregroundStyle(CampaignStyle.muted)
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
                            Text(store.owned.contains(product.packId) ? "Owned" : product.price)
                                .font(.caption)
                        }
                        Spacer()
                        if !store.owned.contains(product.packId) {
                            Button("Buy") {
                                Task { await store.purchase(packId: product.packId) }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
                if !store.products.isEmpty {
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
    @StateObject private var account = CampaignAccount()
    @State private var email = ""
    @State private var password = ""
    @State private var username = ""
    @State private var registering = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("YOUR ACCOUNT").font(.caption.bold()).tracking(1.5).foregroundStyle(CampaignStyle.gold)
                if let user = account.user {
                    Text("Welcome, \(user.username)").font(.title2.bold())
                    Text(user.email).foregroundStyle(CampaignStyle.muted)
                    Button("Sign out") { account.signOut() }
                        .foregroundStyle(CampaignStyle.coral)
                } else {
                    Text(registering ? "Create an account" : "Sign in")
                        .font(.title2.bold())
                    Text("Use the same Margin of Victory account as the web game.")
                        .font(.subheadline).foregroundStyle(CampaignStyle.muted)
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
                    Text("Campaign progress currently stays on this device. Signing in enables your account and web leaderboard; native cloud save sync is still in development.")
                        .font(.subheadline).foregroundStyle(CampaignStyle.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
            }
            .padding(20)
        }
        .textFieldStyle(.roundedBorder)
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }
}

private struct AccountUser: Decodable {
    let username: String
    let email: String
}

private struct LoginResponse: Decodable {
    let token: String
    let user: AccountUser
}

private struct ProfileResponse: Decodable {
    let user: AccountUser
}

private struct APIError: Decodable {
    let error: String
}

@MainActor
private final class CampaignAccount: ObservableObject {
    @Published var user: AccountUser?
    @Published var busy = false
    @Published var message: String?

    private let service = "net.lakesidegames.marginofvictory.account"
    private let account = "session-token"

    init() {
        if readToken() != nil { Task { await refresh() } }
    }

    func authenticate(email: String, password: String, username: String?) async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            let path = username == nil ? "login" : "register"
            var fields = ["email": email, "password": password]
            if let username { fields["username"] = username }
            let payload = try await request(path: path, fields: fields)
            let response = try JSONDecoder().decode(LoginResponse.self, from: payload)
            saveToken(response.token)
            user = response.user
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
                signOut()
                return
            }
            guard (200..<300).contains(http.statusCode) else { throw AccountError(serverMessage(data)) }
            user = try JSONDecoder().decode(ProfileResponse.self, from: data).user
        } catch {
            message = "Account could not be refreshed: \(error.localizedDescription)"
        }
    }

    func signOut() {
        let query = tokenQuery()
        SecItemDelete(query as CFDictionary)
        user = nil
        message = nil
    }

    private func request(path: String, fields: [String: String]) async throws -> Data {
        guard let url = URL(string: "https://sim.ahousedividedgame.com/api/auth/\(path)") else {
            throw AccountError("Account service is unavailable")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
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

    private func saveToken(_ token: String) {
        SecItemDelete(tokenQuery() as CFDictionary)
        var query = tokenQuery()
        query[kSecValueData as String] = Data(token.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        if SecItemAdd(query as CFDictionary, nil) != errSecSuccess {
            message = "Sign-in succeeded, but this device could not save the session."
        }
    }
}

private struct AccountError: LocalizedError {
    let detail: String
    init(_ detail: String) { self.detail = detail }
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
