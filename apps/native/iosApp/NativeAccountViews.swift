import SwiftUI
import WebKit
import shared

@MainActor
private final class LakesideBrowser: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    let webView: WKWebView
    @Published var notice: String?
    var onCode: ((String) -> Void)?
    private let flow = NativeLakesideLogin(nonce: UUID().uuidString.replacingOccurrences(of: "-", with: ""))

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
    }

    func open() {
        guard let url = URL(string: flow.startUrl()), webView.url == nil else { return }
        webView.load(URLRequest(url: url))
    }

    private func accepts(_ url: URL, mainFrame: Bool) -> Bool {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false), parts.user == nil, parts.password == nil else { return false }
        let port = Int32(parts.port ?? -1)
        if flow.isCallback(scheme: parts.scheme, host: parts.host, port: port, path: parts.path) {
            let states = (parts.queryItems ?? []).filter { $0.name == "state" }.map { $0.value ?? "" }
            let codes = (parts.queryItems ?? []).filter { $0.name == "lakeside_code" }.map { $0.value ?? "" }
            if let code = flow.takeCode(scheme: parts.scheme, host: parts.host, port: port, path: parts.path,
                                       states: states, codes: codes, fragment: parts.fragment, mainFrame: mainFrame) {
                onCode?(code)
            } else { notice = "This sign-in return could not be verified. Close and try again." }
            return false
        }
        if !flow.allows(scheme: parts.scheme, host: parts.host, port: port) {
            notice = "This page cannot be opened during sign-in."
            return false
        }
        return true
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        decisionHandler(accepts(url, mainFrame: action.targetFrame?.isMainFrame == true) ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url, accepts(url, mainFrame: false) { webView.load(URLRequest(url: url)) }
        return nil
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled { notice = "Sign-in could not be loaded. Close and try again." }
    }

    #if targetEnvironment(simulator)
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let host = webView.url?.host, ["auth.lakesidegames.net", "auth.ahousedividedgame.com", "ahousedividedgame.com", "www.ahousedividedgame.com"].contains(host) else { return }
        NSLog("MOV_LAKESIDE_SIGNIN_REACHED_AUTH")
    }
    #endif
}

private struct LakesideWebView: UIViewRepresentable {
    let browser: WKWebView
    func makeUIView(context: Context) -> WKWebView { browser }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct NativeLakesideLoginView: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var browser = LakesideBrowser()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let notice = browser.notice { Text(notice).padding().foregroundStyle(CampaignStyle.coral) }
                LakesideWebView(browser: browser.webView)
            }
            .navigationTitle("Lakeside sign-in").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } } }
        }
        .onAppear { browser.onCode = onCode; browser.open() }
        .onDisappear { browser.onCode = nil; browser.webView.stopLoading() }
    }
}

struct AccountPurchaseHistory: View {
    @ObservedObject var account: CampaignAccount
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("PURCHASES").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
            if !account.purchasesLoaded {
                Text(account.busy ? "Loading purchase history…" : "Refresh your account to load purchase history.").font(.caption)
            } else if account.purchases.isEmpty {
                Text("No purchases yet. Campaigns are free to play during the open beta.").font(.caption)
            }
            ForEach(Array(account.purchases.enumerated()), id: \.offset) { _, purchase in
                VStack(alignment: .leading, spacing: 3) {
                    Text(purchase.packName ?? purchase.packId ?? "Campaign").font(.subheadline.bold())
                    Text("\(amount(purchase)) · \(Date(timeIntervalSince1970: purchase.createdAt / 1000).formatted(date: .abbreviated, time: .omitted))\(purchase.status == "refunded" ? " · Refunded" : "")").font(.caption)
                }
            }
        }
    }
    private func amount(_ purchase: AccountPurchase) -> String {
        if purchase.provider == "apple" { return "App Store" }
        if purchase.provider == "google" { return "Google Play" }
        guard let cents = purchase.amountCents, let currency = purchase.currency else { return "Purchase" }
        if cents == 0 { return "Code" }
        return (Double(cents) / 100).formatted(.currency(code: currency.uppercased()))
    }
}

struct AccountAchievements: View {
    @ObservedObject var account: CampaignAccount
    var body: some View {
        DisclosureGroup("Achievements (\(account.awards.count))") {
            VStack(alignment: .leading, spacing: 8) {
                if account.awards.isEmpty { Text("Finish a U.S. campaign to earn achievements.").font(.caption) }
                ForEach(Array(account.awards.enumerated()), id: \.offset) { _, entry in
                    Text("\(entry.award.icon) \(entry.award.name) · \(entry.scenarioLabel)").font(.subheadline.bold())
                    Text(entry.award.blurb).font(.caption).foregroundStyle(CampaignStyle.muted)
                }
                if account.user != nil {
                    Button("Sync achievements") { Task { await account.syncAchievements() } }.disabled(account.busy)
                } else { Text("Achievements are saved on this device. Sign in to sync them.").font(.caption) }
            }
        }
    }
}
