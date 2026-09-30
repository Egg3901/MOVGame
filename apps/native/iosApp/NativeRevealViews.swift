import SwiftUI
import shared

struct NativeElectionNightView: View {
    @ObservedObject var session: GameSession
    let night: NativeElectionNight
    @Environment(\.accessibilityReduceMotion) private var systemReducedMotion
    @AppStorage("mov_reduce_motion") private var reducedMotion = false
    @State private var index = 0
    @State private var speed: Int32 = 1
    @State private var instant = false

    var body: some View {
        let data = night.data()
        let board = night.board(index: Int32(index))
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("LIVE · ELECTION NIGHT").font(.caption.bold()).foregroundStyle(CampaignStyle.gold)
                Text(data.title).font(.title2.bold())
                HStack {
                    Button("1×") { speed = 1; instant = false }
                    Button("2×") { speed = 2; instant = false }
                    Button("Instant") { instant = true; index = Int(night.count()) }
                    Spacer()
                    Button("Skip") { session.finishReveal() }
                }.buttonStyle(.bordered)
                Text(board.status).font(.headline).foregroundStyle(CampaignStyle.gold)
                if let projection = board.projection { Text(projection).font(.title2.bold()) }
                ForEach(board.rows, id: \.id) { row in
                    VStack(alignment: .leading, spacing: 5) {
                        Text("\(row.name): \(row.units) \(data.unitLabel)").font(.headline)
                        ProgressView(value: Double(row.units), total: Double(data.totalUnits)).tint(Color(hex: row.color))
                    }
                }
                Text("\(board.calledUnits) of \(data.totalUnits) called · \(data.threshold) to win · \(board.remaining) remaining")
                    .font(.caption).foregroundStyle(CampaignStyle.muted)
                RevealMapView(map: night.map(), called: board.called)
                if let last = board.lastCall { Text(last).font(.headline) }
                if board.finished { Button("View full results") { session.finishReveal() }.buttonStyle(.borderedProminent) }
            }.padding(20)
        }.background(CampaignStyle.background).preferredColorScheme(.dark)
        .onAppear {
            if reducedMotion || systemReducedMotion { instant = true; index = Int(night.count()) }
        }
        .task(id: "\(index)-\(speed)-\(instant)") {
            if index < Int(night.count()) {
                let wait = night.delay(index: Int32(index), speed: speed)
                do { try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000)) } catch { return }
                guard !Task.isCancelled else { return }
                index += 1
            } else if instant {
                do { try await Task.sleep(nanoseconds: 1_500_000_000) } catch { return }
                guard !Task.isCancelled else { return }
                session.finishReveal()
            }
        }
        .interactiveDismissDisabled()
    }
}

private struct RevealMapView: View {
    let map: NativeMap
    let called: [NativeRevealUnit]
    var body: some View {
        Canvas { context, size in
            let factor = min(size.width / map.width, size.height / map.height)
            let left = (size.width - map.width * factor) / 2
            let top = (size.height - map.height * factor) / 2
            let byId = Dictionary(uniqueKeysWithValues: called.map { ($0.id, $0) })
            for shape in map.shapes {
                var path = Path()
                for polygon in shape.polygons {
                    for (index, point) in polygon.points.enumerated() {
                        let at = CGPoint(x: left + point.x * factor, y: top + point.y * factor)
                        if index == 0 { path.move(to: at) } else { path.addLine(to: at) }
                    }
                    path.closeSubpath()
                }
                let color = byId[shape.id].map { Color(hex: $0.winnerColor) } ?? Color(hex: "#283649")
                context.fill(path, with: .color(color))
                context.stroke(path, with: .color(CampaignStyle.background), lineWidth: 1)
            }
        }.frame(height: 240)
        .accessibilityLabel("Election map")
        .accessibilityValue(called.map { "\($0.name): \($0.winnerShort) leads" }.joined(separator: ", "))
    }
}
