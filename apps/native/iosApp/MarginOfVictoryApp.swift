import SwiftUI

@main
struct MarginOfVictoryApp: App {
    @StateObject private var session = GameSession()

    var body: some Scene {
        WindowGroup {
            ContentView(session: session)
                .onAppear {
                    #if targetEnvironment(simulator)
                    session.prepareSimulatorCaptureIfRequested()
                    #endif
                }
        }
    }
}
