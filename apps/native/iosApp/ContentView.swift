import SwiftUI

struct ContentView: View {
    @ObservedObject var session: GameSession
    @State private var showingMenu = false
    @State private var menuDestination: MenuDestination? = nil

    private enum MenuDestination: String, Identifiable {
        case store, account, credits, guide
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
            Button("How to play") { menuDestination = .guide }
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
                    case .guide: CampaignGuideView()
                    }
                }
                .navigationTitle(destination == .store ? "Campaign library" : destination == .account ? "Account and saves" : destination == .guide ? "How to play" : "Image credits")
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
