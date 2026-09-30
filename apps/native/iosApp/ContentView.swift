import SwiftUI
import WebKit

private final class AskBrowser: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    let webView: WKWebView
    private var campaignURL: URL?
    #if targetEnvironment(simulator)
    private var openSignInForSmokeTest = ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-login")
    #endif
    private let allowedHosts: Set<String> = [
        "ask.lakesidegames.net", "auth.lakesidegames.net", "auth.ahousedividedgame.com",
        "ahousedividedgame.com", "www.ahousedividedgame.com",
        "sandbox.ahousedividedgame.com", "accounts.lakesidegames.net",
        "discord.com", "accounts.google.com", "www.google.com",
    ]

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        // Ask owns its light/dark theme. A transparent webview lets the dark
        // native canvas show through a light Ask page, hiding its dark text.
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
    }

    func open(_ url: URL, refresh: Bool = false) {
        let selectedGame = webView.url.flatMap { current in
            URLComponents(url: current, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "game" })?.value
        }
        guard refresh || campaignURL != url || webView.url == nil || selectedGame != "electioneer" else { return }
        campaignURL = url
        webView.load(URLRequest(url: url))
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if url.scheme == "https", let host = url.host?.lowercased(), allowedHosts.contains(host) {
            decisionHandler(.allow)
        } else {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        }
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard let url = action.request.url else { return nil }
        if url.scheme == "https", let host = url.host?.lowercased(), allowedHosts.contains(host) {
            webView.load(URLRequest(url: url))
        } else {
            UIApplication.shared.open(url)
        }
        return nil
    }

    #if targetEnvironment(simulator)
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let url = webView.url else { return }
        if url.host == "auth.lakesidegames.net" {
            NSLog("MOV_ASK_SIGNIN_REACHED_EMBEDDED_AUTH")
        } else if openSignInForSmokeTest, url.host == "ask.lakesidegames.net", url.path == "/" {
            openSignInForSmokeTest = false
            webView.evaluateJavaScript("document.querySelector('a[href^=\"/auth/login\"]')?.click()")
        }
    }
    #endif
}

private struct AskWebView: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct ContentView: View {
    @ObservedObject var session: GameSession
    @State private var showingMenu = false
    @State private var showingAsk = false
    @StateObject private var askBrowser = AskBrowser()
    @State private var menuDestination: MenuDestination? = nil

    private enum MenuDestination: String, Identifiable {
        case store, account, credits, guide, saves
        var id: String { rawValue }
    }

    var body: some View {
        VStack(spacing: 0) {
            currentScreen
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            navigationBar
        }
        .background(CampaignStyle.background)
        .preferredColorScheme(.dark)
        .confirmationDialog("Campaign menu", isPresented: $showingMenu) {
            if session.hasGame {
                Button("Continue campaign") { session.resumeGame() }
            }
            Button("Start a new campaign") { session.playScreen = .library }
            Button("Campaign library") { session.playScreen = .library }
            Button("How to play") { menuDestination = .guide }
            Button(session.hasGame ? "Ask about this campaign" : "Ask about Margin of Victory") {
                openAsk()
            }
            Button("Account and saves") { menuDestination = .account }
            Button("Image credits") { menuDestination = .credits }
        }
        .sheet(item: $menuDestination) { destination in
            NavigationStack {
                Group {
                    switch destination {
                    case .store: StoreView(session: session)
                    case .account: AccountView(session: session)
                    case .saves: CampaignSavesView(session: session)
                    case .credits: ImageCreditsView()
                    case .guide: CampaignGuideView()
                    }
                }
                .navigationTitle(destination == .store ? "Campaign library" : destination == .account ? "Account and saves" : destination == .saves ? "Saved campaigns" : destination == .guide ? "How to play" : "Image credits")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { menuDestination = nil }
                    }
                }
            }
        }
        .sheet(isPresented: $showingAsk) {
            NavigationStack {
                AskWebView(webView: askBrowser.webView)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .navigationTitle("Ask")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("Done") { showingAsk = false }
                        }
                        ToolbarItem(placement: .topBarTrailing) {
                            Button { askBrowser.open(session.askURL(), refresh: true) } label: {
                                Image(systemName: "arrow.clockwise")
                            }
                            .accessibilityLabel("Refresh campaign snapshot")
                        }
                    }
            }
        }
        .onAppear {
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-account") { menuDestination = .account }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-saves") { menuDestination = .saves }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-ask") ||
                ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-login") {
                session.prepareSimulatorCaptureIfRequested()
                openAsk()
            }
            #endif
        }
    }

    private func openAsk() {
        askBrowser.open(session.askURL())
        showingAsk = true
    }

    @ViewBuilder
    private var currentScreen: some View {
        switch session.playScreen {
        case .home:
            HomeView(session: session, onAsk: openAsk)
        case .setup:
            SetupView(session: session)
        case .loading:
            VStack(spacing: 16) {
                ProgressView().tint(CampaignStyle.gold)
                Text("Preparing the campaign trail…")
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        case .game:
            GameView(session: session, onAsk: openAsk)
        case .results:
            ResultsView(session: session)
        case .library:
            NativeCampaignLibrary(session: session)
        case .worldGame:
            NativeWorldCampaign(session: session)
        }
    }

    private var navigationBar: some View {
        HStack(spacing: 8) {
            navigationButton("Home", icon: "house.fill", selected: session.playScreen == .home) {
                session.playScreen = .home
            }
            navigationButton("Campaign", icon: "flag.fill", selected: session.playScreen == .game || session.playScreen == .results || session.playScreen == .worldGame,
                             enabled: session.hasGame) {
                session.resumeGame()
            }
            navigationButton("Menu", icon: "line.3.horizontal", selected: false) {
                showingMenu = true
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .background(CampaignStyle.card)
    }

    private func navigationButton(_ title: String, icon: String, selected: Bool,
                                  enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.title3).frame(height: 24)
                Text(title).font(.caption.bold()).frame(height: 17)
            }
            .foregroundStyle(selected ? CampaignStyle.gold : CampaignStyle.muted)
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .background(selected ? CampaignStyle.background : .clear, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

struct CampaignGuideView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("mov.hasSeenCampaignGuide") private var hasSeenCampaignGuide = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("THE ROAD TO 270").font(.caption.bold()).tracking(2).foregroundStyle(CampaignStyle.gold)
                Text("Win the map, one week at a time.").font(.largeTitle.bold())
                guideStep("1", "Find the close races", "Gray tiles with amber borders are toss-ups. Tap a state to inspect its electoral votes and projected margin. Blue and red shades show how secure each side is.")
                guideStep("2", "Build your week", "Pick a target, choose a move, then adjust its settings. The margin estimate updates as you change ad spend. Each day holds up to three moves.")
                guideStep("3", "See the consequences", "Add your moves before ending the week. Events and the opposing campaign can change the result, so revisit the map after each recap.")
                Text("Reach 270 electoral votes by Election Day.")
                    .font(.headline).foregroundStyle(CampaignStyle.gold)
                Button("Start playing  →") { dismiss() }
                    .font(.headline).frame(maxWidth: .infinity).padding(15)
                    .foregroundStyle(CampaignStyle.background)
                    .background(CampaignStyle.coral, in: RoundedRectangle(cornerRadius: 13))
                Button("Replay the eight-step tour") {
                    hasSeenCampaignGuide = false
                    dismiss()
                }
                .font(.subheadline.bold()).foregroundStyle(CampaignStyle.gold)
            }
            .padding(20)
        }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }

    private func guideStep(_ number: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number).font(.headline.bold()).foregroundStyle(CampaignStyle.background)
                .frame(width: 30, height: 30).background(CampaignStyle.gold, in: Circle())
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(CampaignStyle.muted)
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
    }
}

#Preview {
    ContentView(session: GameSession())
}
