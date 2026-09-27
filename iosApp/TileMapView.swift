import SwiftUI
import shared

// Phase 4 tile map (#22): same 12x8 cartogram as androidApp TileMap.
let tilePos: [String: (Int, Int)] = [
    "ME": (11, 0),
    "WI": (3, 1), "MI": (7, 1), "VT": (10, 1), "NH": (11, 1),
    "WA": (0, 2), "ID": (1, 2), "MT": (2, 2), "ND": (3, 2),
    "MN": (4, 2), "NY": (8, 2), "MA": (10, 2), "RI": (11, 2),
    "OR": (0, 3), "NV": (1, 3), "WY": (2, 3), "SD": (3, 3),
    "IA": (4, 3), "IL": (5, 3), "IN": (6, 3), "OH": (7, 3),
    "PA": (8, 3), "NJ": (9, 3), "CT": (10, 3),
    "CA": (0, 4), "UT": (1, 4), "CO": (2, 4), "NE": (3, 4),
    "MO": (4, 4), "KY": (5, 4), "WV": (6, 4), "VA": (7, 4),
    "MD": (8, 4), "DE": (9, 4), "DC": (10, 4),
    "AZ": (1, 5), "NM": (2, 5), "KS": (3, 5), "AR": (4, 5),
    "TN": (5, 5), "NC": (7, 5), "SC": (8, 5),
    "AK": (0, 6), "HI": (1, 6), "OK": (3, 6), "LA": (4, 6),
    "MS": (5, 6), "AL": (6, 6), "GA": (7, 6),
    "TX": (3, 7), "FL": (8, 7),
]

private let tileCols = 12
private let tileRows = 8

func tileColor(lean: String?, demShare: Double) -> Color {
    if lean == "dem" {
        let t = min(1.0, max(0.15, (demShare - 0.5) * 2))
        return Color.blue.opacity(0.35 + 0.65 * t)
    }
    if lean == "rep" {
        let t = min(1.0, max(0.15, (0.5 - demShare) * 2))
        return Color.red.opacity(0.35 + 0.65 * t)
    }
    return Color.gray.opacity(0.55)
}

struct TileMapView: View {
    var contestsById: [String: ContestProjection]
    var abbrToStateId: [String: String]
    var selectedAbbr: String?
    var onSelect: (String) -> Void

    var body: some View {
        VStack(spacing: 2) {
            ForEach(0..<tileRows, id: \.self) { r in
                HStack(spacing: 2) {
                    ForEach(0..<tileCols, id: \.self) { c in
                        tileCell(col: c, row: r)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func tileCell(col: Int, row: Int) -> some View {
        let abbr = tilePos.first(where: { $0.value == (col, row) })?.key
        if let abbr = abbr {
            let contest = abbrToStateId[abbr].flatMap { contestsById[$0] }
            let isSel = abbr == selectedAbbr
            Text(abbr)
                .font(.system(size: 9))
                .foregroundColor(isSel ? .yellow : .white)
                .bold(isSel)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .aspectRatio(1, contentMode: .fit)
                .background(
                    RoundedRectangle(cornerRadius: 4)
                        .fill(tileColor(lean: contest?.lean, demShare: contest?.demShare ?? 0.5))
                )
                .onTapGesture {
                    if abbrToStateId[abbr] != nil { onSelect(abbr) }
                }
        } else {
            Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
                .aspectRatio(1, contentMode: .fit)
        }
    }
}
