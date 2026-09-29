import SwiftUI
import shared

// The web game's 10-column electoral cartogram, including split EV units.
private let tilePositions: [String: (Int, Int)] = [
    "AK": (0, 0), "VT": (0, 8), "NH": (0, 9),
    "WA": (1, 0), "ID": (1, 1), "MT": (1, 2), "ND": (1, 3), "MN": (1, 4), "WI": (1, 5), "MI": (1, 7), "NY": (1, 8), "MA": (1, 9),
    "OR": (2, 0), "NV": (2, 1), "WY": (2, 2), "SD": (2, 3), "IA": (2, 4), "IL": (2, 5), "IN": (2, 6), "OH": (2, 7), "PA": (2, 8), "CT": (2, 9),
    "CA": (3, 0), "UT": (3, 1), "CO": (3, 2), "MO": (3, 5), "KY": (3, 6), "WV": (3, 7), "VA": (3, 8), "NJ": (3, 9),
    "AZ": (4, 1), "NM": (4, 2), "KS": (4, 3), "AR": (4, 4), "TN": (4, 5), "NC": (4, 6), "MD": (4, 7), "DE": (4, 8), "RI": (4, 9),
    "HI": (5, 0), "OK": (5, 3), "LA": (5, 4), "MS": (5, 5), "AL": (5, 6), "SC": (5, 7), "DC": (5, 8),
    "TX": (6, 3), "GA": (6, 6), "FL": (6, 7),
]

struct TileMapView: View {
    let contestsById: [String: ContestProjection]
    let states: [StateContest]
    let selectedId: String?
    let onSelect: (String) -> Void

    private var byId: [String: StateContest] { Dictionary(uniqueKeysWithValues: states.map { ($0.id, $0) }) }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            GeometryReader { geometry in
                let side = (geometry.size.width - 9 * 3) / 10
                VStack(spacing: 3) {
                    ForEach(0..<7, id: \.self) { row in
                        HStack(spacing: 3) {
                            ForEach(0..<10, id: \.self) { col in
                                if let id = tilePositions.first(where: { $0.value.0 == row && $0.value.1 == col })?.key,
                                   let state = byId[id] {
                                    tile(state, label: id, side: side)
                                } else {
                                    Color.clear.frame(width: side, height: side)
                                }
                            }
                        }
                    }
                }
            }
            .aspectRatio(10.0 / 7.0, contentMode: .fit)
            splitRow("MAINE", ids: ["ME-1", "ME-2", "ME-AL"])
            splitRow("NEBRASKA", ids: ["NE-1", "NE-2", "NE-3", "NE-AL"])
        }
    }

    private func splitRow(_ title: String, ids: [String]) -> some View {
        HStack(spacing: 4) {
            Text(title).font(.system(size: 10, weight: .bold)).foregroundStyle(CampaignStyle.muted)
                .frame(width: 73, alignment: .leading)
            ForEach(ids, id: \.self) { id in
                if let state = byId[id] {
                    tile(state, label: id == "ME-AL" ? "ME" : id == "NE-AL" ? "NE" : id, side: 43)
                }
            }
        }
    }

    private func tile(_ state: StateContest, label: String, side: CGFloat) -> some View {
        let contest = contestsById[state.id]
        let tossup = contest.map { abs(MapMargin.points($0)) <= 3 } ?? false
        let selected = selectedId == state.id
        return Button { onSelect(state.id) } label: {
            VStack(spacing: 1) {
                Text(label).font(.system(size: label.count > 3 ? 8 : 10, weight: .bold)).lineLimit(1)
                Text("\(Int(state.electoralVotes))").font(.system(size: 9, weight: .semibold))
            }
            .foregroundStyle(.white)
            .frame(width: side, height: side)
            .background(Color(hex: MapMargin.color(contest)), in: RoundedRectangle(cornerRadius: 5))
            .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(selected ? .white : tossup ? CampaignStyle.gold : .clear, lineWidth: selected ? 2.5 : 1.5))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(state.name), \(Int(state.electoralVotes)) electoral votes, \(contest.map(MapMargin.label) ?? "no projection")")
    }
}

private extension Color {
    init(hex: String) {
        let value = Int(hex.dropFirst(), radix: 16) ?? 0
        self.init(red: Double((value >> 16) & 255) / 255,
                  green: Double((value >> 8) & 255) / 255,
                  blue: Double(value & 255) / 255)
    }
}
