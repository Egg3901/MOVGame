import SwiftUI

// Phase 4: Play tab switches Setup/Game/Results; Store/Account are shells
// until Phase 5. Mirrors the Android bottom nav.
struct ContentView: View {
    @ObservedObject var session: GameSession

    var body: some View {
        TabView(selection: $session.tab) {
            playTab
                .tabItem { Label("Play", systemImage: "play.fill") }
                .tag(0)
            StoreView()
                .tabItem { Label("Store", systemImage: "cart") }
                .tag(1)
            AccountView()
                .tabItem { Label("Account", systemImage: "person.crop.circle") }
                .tag(2)
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder
    private var playTab: some View {
        switch session.playScreen {
        case .home:
            HomeView(session: session)
        case .setup:
            SetupView(session: session)
        case .loading:
            VStack(spacing: 16) {
                ProgressView().tint(.orange)
                Text("Preparing the campaign trail…")
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        case .game:
            GameView(session: session)
        case .results:
            ResultsView(session: session)
        }
    }
}

#Preview {
    ContentView(session: GameSession())
}
