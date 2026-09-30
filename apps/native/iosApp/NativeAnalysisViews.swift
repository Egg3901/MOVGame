import SwiftUI
import shared

struct CampaignAnalysisView: View {
    @ObservedObject var session: GameSession
    var timeline = false
    @State private var selectedRegion: String?
    @State private var expanded: Set<String> = ["polls"]

    var body: some View {
        ScrollView {
            if let document = timeline ? session.replayDocument(turn: selectedRegion) : session.analysis(regionId: selectedRegion) {
                VStack(alignment: .leading, spacing: 14) {
                    Text(timeline ? "CAMPAIGN REPLAY AND REPORT" : "CAMPAIGN ANALYSIS").font(.title2.bold()).foregroundStyle(CampaignStyle.gold)
                    ForEach(document.charts, id: \.id) { chart in
                        NativeTrendCard(chart: chart)
                    }
                    Picker(timeline ? "Campaign week" : "State or region", selection: Binding(get: { selectedRegion ?? document.selectedRegion }, set: { selectedRegion = $0 })) {
                        ForEach(document.regions, id: \.id) { region in Text(region.name).tag(region.id) }
                    }.pickerStyle(.menu)
                    ForEach(document.sections, id: \.id) { section in
                        DisclosureGroup(isExpanded: Binding(get: { expanded.contains(section.id) }, set: {
                            if $0 { expanded.insert(section.id) } else { expanded.remove(section.id) }
                        })) {
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(Array(section.rows.enumerated()), id: \.offset) { _, row in
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(row.label).font(.subheadline.bold())
                                        Text(row.value).font(.caption).foregroundStyle(CampaignStyle.muted)
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }.padding(.top, 8)
                        } label: { Text(section.title).font(.headline) }
                        .padding(14).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
                    }
                }.padding(20)
            }
        }.background(CampaignStyle.background).preferredColorScheme(.dark)
        .onAppear { if timeline { expanded = ["standings", "week-actions", "report"] } }
    }
}

private struct NativeTrendCard: View {
    let chart: NativeTrendChart

    private var maxTurn: Double { max(1.0, Double(chart.series.flatMap(\.points).map(\.turn).max() ?? 1)) }
    private var accessibleValues: String {
        chart.series.map { series in
            series.label + ": " + series.points.map { "Week \($0.turn): \(String(format: "%.1f", $0.value))" }.joined(separator: ", ")
        }.joined(separator: ". ")
    }
    private func position(_ turn: Double, _ value: Double, _ size: CGSize) -> CGPoint {
        CGPoint(x: 6 + (size.width - 12) * CGFloat(turn / maxTurn),
                y: 6 + (size.height - 12) * CGFloat(1 - (value - chart.minimum) / (chart.maximum - chart.minimum)))
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(chart.title).font(.headline)
            Text("\(Int(chart.minimum)) to \(Int(chart.maximum)) · \(chart.referenceLabel)").font(.caption).foregroundStyle(CampaignStyle.muted)
            Canvas { context, size in
                for index in 0...4 {
                    let y = 6 + (size.height - 12) * CGFloat(index) / 4
                    var line = Path()
                    line.move(to: CGPoint(x: 6, y: y)); line.addLine(to: CGPoint(x: size.width - 6, y: y))
                    context.stroke(line, with: .color(CampaignStyle.muted.opacity(0.25)), lineWidth: 1)
                }
                var reference = Path()
                reference.move(to: position(0, chart.reference, size)); reference.addLine(to: position(maxTurn, chart.reference, size))
                context.stroke(reference, with: .color(CampaignStyle.muted), lineWidth: 1)
                for series in chart.series {
                    var path = Path()
                    let color = Color(hex: series.color)
                    for (index, point) in series.points.enumerated() {
                        let at = position(Double(point.turn), point.value, size)
                        if index == 0 { path.move(to: at) } else { path.addLine(to: at) }
                        context.fill(Path(ellipseIn: CGRect(x: at.x - 3, y: at.y - 3, width: 6, height: 6)), with: .color(color))
                    }
                    context.stroke(path, with: .color(color), lineWidth: 2)
                }
            }.frame(height: 180).accessibilityLabel(chart.title).accessibilityValue(accessibleValues)
            Text("Start → Week \(Int(maxTurn)) · \(chart.series.map(\.label).joined(separator: " / "))")
                .font(.caption).foregroundStyle(CampaignStyle.muted)
        }.padding(14).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
    }
}
