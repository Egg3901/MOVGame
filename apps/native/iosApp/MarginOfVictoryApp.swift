import SwiftUI

@main
struct MarginOfVictoryApp: App {
    @StateObject private var session = GameSession()

    var body: some Scene {
        WindowGroup {
            NativeKeyboardHost(session: session)
                .onAppear {
                    #if targetEnvironment(simulator)
                    session.prepareSimulatorCaptureIfRequested()
                    #endif
                }
        }
    }
}
