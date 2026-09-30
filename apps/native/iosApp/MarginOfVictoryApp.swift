import SwiftUI

@main
struct MarginOfVictoryApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var session = GameSession()

    var body: some Scene {
        WindowGroup {
            NativeKeyboardHost(session: session)
                .onChange(of: scenePhase) { phase in if phase == .active { session.requestCloudSync(force: true) } }
                .onAppear {
                    #if targetEnvironment(simulator)
                    session.prepareSimulatorCaptureIfRequested()
                    #endif
                }
        }
    }
}
