import SwiftUI
import shared

// Phase 4 Setup screen (#22): ticket + difficulty, then deal in.
struct SetupView: View {
    @ObservedObject var session: GameSession
    @State private var player = "dem"
    @State private var difficulty = "normal"

    var body: some View {
        VStack(spacing: 12) {
            Spacer()
            Text("Margin of Victory").font(.largeTitle)
            Text("2020 Presidential Campaign").font(.subheadline)
            Spacer().frame(height: 12)

            Text("Choose your ticket").font(.headline)
            Picker("Ticket", selection: $player) {
                ForEach(session.candidates(), id: \.id.serial) { c in
                    Text(c.shortName).tag(c.id.serial)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            Text("Difficulty").font(.headline)
            Picker("Difficulty", selection: $difficulty) {
                ForEach(session.difficulties(), id: \.self) { d in
                    Text(d.capitalized).tag(d)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            Button("Start Campaign") {
                session.newGame(playerSerial: player, difficulty: difficulty)
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 12)
            Spacer()
        }
        .padding()
    }
}
