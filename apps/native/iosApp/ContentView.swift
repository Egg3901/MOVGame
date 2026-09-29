import SwiftUI

struct ContentView: View {
    @ObservedObject var session: GameSession
    @State private var showingMenu = false
    @State private var menuDestination: MenuDestination? = nil

    private enum MenuDestination: String, Identifiable {
        case store, account, credits
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
            Button("Start a new campaign") { session.playScreen = .setup }
            Button("Campaign library") { menuDestination = .store }
            Button("Account and saves") { menuDestination = .account }
            Button("Image credits") { menuDestination = .credits }
        }
        .sheet(item: $menuDestination) { destination in
            NavigationStack {
                Group {
                    switch destination {
                    case .store: StoreView()
                    case .account: AccountView()
                    case .credits: ImageCreditsView()
                    }
                }
                .navigationTitle(destination == .store ? "Campaign library" : destination == .account ? "Account and saves" : "Image credits")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { menuDestination = nil }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var currentScreen: some View {
        switch session.playScreen {
        case .home:
            HomeView(session: session)
        case .setup:
            SetupView(session: session)
        case .loading:
            VStack(spacing: 16) {
                ProgressView().tint(CampaignStyle.gold)
                Text("Preparing the campaign trail…")
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        case .game:
            GameView(session: session)
        case .results:
            ResultsView(session: session)
        }
    }

    private var navigationBar: some View {
        HStack(spacing: 8) {
            navigationButton("Home", icon: "house.fill", selected: session.playScreen == .home) {
                session.playScreen = .home
            }
            navigationButton("Campaign", icon: "flag.fill", selected: session.playScreen == .game || session.playScreen == .results,
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

#Preview {
    ContentView(session: GameSession())
}
