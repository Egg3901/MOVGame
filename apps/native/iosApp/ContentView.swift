import shared
import SwiftUI
import WebKit

#if targetEnvironment(simulator)
private final class AskSmokeTransport: NSObject, WKURLSchemeHandler {
    static var mode: String? {
        if ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-offline") { return "offline" }
        if ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-blank") { return "blank" }
        if ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-retry") { return "retry" }
        if ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-stalled") { return "stalled" }
        return nil
    }
    private let mode: String
    private var requests = 0
    init(mode: String) { self.mode = mode }
    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        if mode == "stalled" { return }
        requests += 1
        if mode == "offline" || (mode == "retry" && requests == 1) {
            task.didFailWithError(NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet))
        } else {
            let response = URLResponse(url: task.request.url!, mimeType: "text/html", expectedContentLength: -1, textEncodingName: "utf-8")
            task.didReceive(response)
            let html = mode == "blank" ? "<!doctype html><html><body></body></html>" : """
                <!doctype html><html><body style="background:#10131c;color:white;font:24px system-ui;padding:32px">
                <h1>Ask recovered</h1><p>This document loaded after the first navigation failed. Campaign questions are ready inside the app.</p>
                </body></html>
                """
            task.didReceive(Data(html.utf8))
            task.didFinish()
        }
    }
    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}
}
#endif

private final class AskBrowser: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    let webView: WKWebView
    enum Phase: Equatable {
        case loading, ready, failed(String)
    }
    @Published private(set) var phase: Phase = .loading
    private var campaignURL: URL?
    private var navigation: WKNavigation?
    private var generation = 0
    private var retries = 0
    private var mayRetryAutomatically = true
    private var timeout: DispatchWorkItem?
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
        #if targetEnvironment(simulator)
        if let mode = AskSmokeTransport.mode {
            configuration.setURLSchemeHandler(AskSmokeTransport(mode: mode), forURLScheme: "mov-ask-smoke")
        }
        #endif
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
        retries = 0
        loadCampaign()
    }

    func retry() {
        retries = 0
        loadCampaign()
    }

    private func loadCampaign() {
        guard var url = campaignURL else { return }
        #if targetEnvironment(simulator)
        if AskSmokeTransport.mode != nil { url = URL(string: "mov-ask-smoke://fixture/?game=electioneer")! }
        #endif
        generation += 1
        timeout?.cancel()
        webView.stopLoading()
        phase = .loading
        mayRetryAutomatically = true
        navigation = webView.load(URLRequest(url: url, timeoutInterval: 20))
        armTimeout()
    }

    private func armTimeout() {
        timeout?.cancel()
        let current = generation
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.generation == current, self.phase == .loading else { return }
            self.recover("Ask did not finish loading. Try again.")
        }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 25, execute: work)
    }

    private func recover(_ message: String) {
        timeout?.cancel()
        generation += 1
        navigation = nil
        webView.stopLoading()
        if retries == 0 && mayRetryAutomatically {
            retries += 1
            let current = generation
            #if targetEnvironment(simulator)
            NSLog("MOV_ASK_AUTOMATIC_RETRY")
            #endif
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                guard let self, self.generation == current else { return }
                self.loadCampaign()
            }
        } else {
            phase = .failed(message)
            #if targetEnvironment(simulator)
            NSLog("MOV_ASK_ERROR_SHOWN")
            #endif
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if action.targetFrame?.isMainFrame == true {
            // Recovery returns to the campaign GET. Never replay an authentication POST.
            mayRetryAutomatically = action.request.httpMethod == "GET" && url.host == "ask.lakesidegames.net"
            #if targetEnvironment(simulator)
            if url.scheme == "mov-ask-smoke" { mayRetryAutomatically = true }
            #endif
        }
        #if targetEnvironment(simulator)
        if AskSmokeTransport.mode != nil && url.scheme == "mov-ask-smoke" && url.host == "fixture" {
            decisionHandler(.allow)
            return
        }
        #endif
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

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        self.navigation = navigation
        generation += 1
        phase = .loading
        armTimeout()
    }

    private func navigationFailed(_ failed: WKNavigation?, error: Error) {
        let failure = error as NSError
        guard failure.code != NSURLErrorCancelled || failure.domain != NSURLErrorDomain else { return }
        guard failed == navigation, phase == .loading else { return }
        recover(failure.domain == NSURLErrorDomain && failure.code == NSURLErrorNotConnectedToInternet
                ? "Check your connection and try again." : "Ask could not connect. Try again.")
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        navigationFailed(navigation, error: error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        navigationFailed(navigation, error: error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        recover("Ask stopped responding. Try again.")
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard navigation == self.navigation, phase == .loading else { return }
        checkDocument(generation: generation, attempts: 5)
    }

    private func checkDocument(generation current: Int, attempts: Int) {
        webView.evaluateJavaScript("document.body?.innerText?.trim().length ?? 0") { [weak self] result, _ in
            guard let self, self.generation == current, self.phase == .loading else { return }
            // The snapshot handoff briefly displays a short redirect message.
            // Keep waiting until the destination has actual page content.
            if let length = result as? Int, length >= 60 {
                self.timeout?.cancel()
                self.phase = .ready
                self.retries = 0
                #if targetEnvironment(simulator)
                self.documentReadyForSmokeTest()
                #endif
            } else if attempts > 1 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                    self?.checkDocument(generation: current, attempts: attempts - 1)
                }
            } else {
                self.recover("Ask opened an empty page. Try again.")
            }
        }
    }

    #if targetEnvironment(simulator)
    private func documentReadyForSmokeTest() {
        guard let url = webView.url else { return }
        if url.host == "auth.lakesidegames.net" {
            NSLog("MOV_ASK_SIGNIN_REACHED_EMBEDDED_AUTH")
        } else if url.host == "ask.lakesidegames.net" || url.scheme == "mov-ask-smoke" {
            NSLog("MOV_ASK_DOCUMENT_READY")
            if url.host == "ask.lakesidegames.net" {
                let current = generation
                webView.evaluateJavaScript("(() => { try { const s = JSON.parse(sessionStorage.getItem('ask.movSnapshot')); return s?.version === 1 && s?.game === 'electioneer' && typeof s?.scenario === 'string' ? s.country : null; } catch { return null; } })()") { [weak self] result, _ in
                    guard let self, self.generation == current, self.phase == .ready,
                          let country = result as? String,
                          ["US", "UK", "CA", "DE", "FR", "AU"].contains(country) else { return }
                    NSLog("MOV_ASK_SNAPSHOT_COUNTRY_%@", country)
                }
            }
            if openSignInForSmokeTest, url.path == "/", !checkingSignInForSmokeTest {
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

private struct AskContentView: View {
    @ObservedObject var browser: AskBrowser

    var body: some View {
        ZStack {
            CampaignStyle.background.ignoresSafeArea()
            AskWebView(webView: browser.webView)
                .opacity(browser.phase == .ready ? 1 : 0)
                .allowsHitTesting(browser.phase == .ready)
                .accessibilityHidden(browser.phase != .ready)
            switch browser.phase {
            case .loading:
                VStack(spacing: 16) {
                    ProgressView().tint(CampaignStyle.gold)
                    Text("Opening Ask…")
                }
            case .failed(let message):
                VStack(spacing: 18) {
                    Image(systemName: "wifi.exclamationmark").font(.largeTitle).foregroundStyle(CampaignStyle.gold)
                    Text("Ask couldn’t open").font(.title2.bold())
                    Text(message).multilineTextAlignment(.center)
                    Button("Try again", action: browser.retry).buttonStyle(.borderedProminent)
                        .tint(CampaignStyle.gold).foregroundStyle(.black)
                        .accessibilityIdentifier("ask-retry")
                }.padding(32)
            case .ready:
                EmptyView()
            }
        }
    }
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
            Button("Store") { menuDestination = .store }
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
                AskContentView(browser: askBrowser)
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
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-store") { menuDestination = .store }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-settings") { menuDestination = .settings }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-guide") { menuDestination = .guide }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-champions") { menuDestination = .boards }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-saves") { menuDestination = .saves }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-analysis") { menuDestination = .analysis }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-replay") { menuDestination = .replay }
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-ask") ||
                ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-de") ||
                ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-login") ||
                ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-offline") ||
                ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-blank") ||
                ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-retry") ||
                ProcessInfo.processInfo.arguments.contains("--mov-capture-ask-stalled") {
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
