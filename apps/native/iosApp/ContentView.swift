import shared
import SwiftUI
import WebKit

private final class AskBrowser: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    let webView: WKWebView
    private var campaignURL: URL?
    #if targetEnvironment(simulator)
    private var checkingSignInForSmokeTest = false
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
            if !checkingSignInForSmokeTest {
                checkingSignInForSmokeTest = true
                clickSignInWhenReady(attempts: 30)
            }
        }
    }
    private func clickSignInWhenReady(attempts: Int) {
        guard openSignInForSmokeTest, webView.url?.host == "ask.lakesidegames.net" else { return }
        webView.evaluateJavaScript("(() => { const link = document.querySelector('a[href^=\"/auth/login\"]'); if (!link) return false; link.click(); return true; })()") { [weak self] result, _ in
            guard let self else { return }
            if result as? Bool == true {
                self.openSignInForSmokeTest = false
                NSLog("MOV_ASK_SIGNIN_LINK_CLICKED")
            } else if attempts > 1 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.clickSignInWhenReady(attempts: attempts - 1) }
            } else { NSLog("MOV_ASK_SIGNIN_LINK_NOT_READY") }
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
    @Environment(\.sizeCategory) private var systemSizeCategory
    private var captureSizeCategory: ContentSizeCategory {
        #if targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--mov-capture-large-text") { return .accessibilityLarge }
        #endif
        return systemSizeCategory
    }
    @State private var showingMenu = false
    @State private var showingAsk = false
    @StateObject private var askBrowser = AskBrowser()
    @State private var menuDestination: MenuDestination? = nil

    private enum MenuDestination: String, Identifiable {
        case store, account, credits, guide, saves, analysis, replay, boards, settings, editor
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
                Button("Campaign analysis") { menuDestination = .analysis }
                if session.canViewReplay() { Button("Campaign replay and report") { menuDestination = .replay } }
            }
            Button("Start a new campaign") { session.playScreen = .library }
            Button("Campaign library") { session.playScreen = .library }
            Button("Scenario editor") { menuDestination = .editor }
            Button("Settings") { menuDestination = .settings }
            Button("How to play") { menuDestination = .guide }
            Button(session.hasGame ? "Ask about this campaign" : "Ask about Margin of Victory") {
                openAsk()
            }
            Button("Leaderboards and daily champions") { menuDestination = .boards }
            Button("Account and saves") { menuDestination = .account }
            Button("Image credits") { menuDestination = .credits }
        }
        .sheet(item: $menuDestination) { destination in
            NavigationStack {
                Group {
                    switch destination {
                    case .store: StoreView(session: session)
                    case .account: AccountView(session: session)
                    case .boards: AccountBoardsView(account: session.account)
                    case .saves: CampaignSavesView(session: session)
                    case .analysis: CampaignAnalysisView(session: session)
                    case .replay: CampaignAnalysisView(session: session, timeline: true)
                    case .credits: ImageCreditsView()
                    case .editor: NativeEditorView(session: session)
                    case .guide: CampaignGuideView()
                    case .settings: NativeSettingsView(settings: session.settings)
                    }
                }
                .navigationTitle(destination == .editor ? "Scenario editor" : destination == .settings ? "Settings" : destination == .store ? "Campaign library" : destination == .account ? "Account and saves" : destination == .saves ? "Saved campaigns" : destination == .analysis ? "Campaign analysis" : destination == .replay ? "Campaign replay" : destination == .boards ? "Leaderboards" : destination == .guide ? "How to play" : "Image credits")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { menuDestination = nil }
                    }
                }
            }
        }
        .fullScreenCover(isPresented: $session.showReveal) {
            if let night = session.electionNight { NativeElectionNightView(session: session, night: night) }
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
        .onChange(of: session.shortcutSequence) { _ in
            if session.shortcut == "?" { menuDestination = .settings }
            if session.shortcut == UIKeyCommand.inputEscape { menuDestination = nil; showingMenu = false; showingAsk = false }
        }
        .onAppear {
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-account") || ProcessInfo.processInfo.arguments.contains("--mov-capture-lakeside-login") { menuDestination = .account }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-editor") { menuDestination = .editor }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-settings") { menuDestination = .settings }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-guide") { menuDestination = .guide }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-champions") { menuDestination = .boards }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-saves") { menuDestination = .saves }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-analysis") { menuDestination = .analysis }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-replay") { menuDestination = .replay }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-ask") ||
                ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-login") {
                session.prepareSimulatorCaptureIfRequested()
                openAsk()
            }
            #endif
        }
        .environment(\.sizeCategory, captureSizeCategory)
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
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("HOW TO PLAY").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
                ForEach(Array(NativeHelp.companion.guide().enumerated()), id: \.offset) { _, lesson in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(lesson.title).font(.headline).foregroundStyle(CampaignStyle.gold)
                        Text(lesson.body).font(.subheadline)
                    }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 14))
                }
            }.padding(20)
        }.background(CampaignStyle.background).preferredColorScheme(.dark)
    }
}
