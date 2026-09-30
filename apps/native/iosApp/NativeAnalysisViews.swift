import SwiftUI
import Charts
import shared

struct CampaignAnalysisView: View {
    @ObservedObject var session: GameSession
    @State private var selectedRegion: String?
    @State private var expanded: Set<String> = ["polls"]

    var body: some View {
        ScrollView {
            if let document = session.analysis(regionId: selectedRegion) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("CAMPAIGN ANALYSIS").font(.title2.bold()).foregroundStyle(CampaignStyle.gold)
                    ForEach(document.charts, id: \.id) { chart in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(chart.title).font(.headline)
                            Chart {
                                RuleMark(y: .value("Reference", chart.reference))
                                    .foregroundStyle(CampaignStyle.muted).lineStyle(StrokeStyle(dash: [4]))
                                    .annotation(position: .top, alignment: .trailing) { Text(chart.referenceLabel).font(.caption2) }
                                ForEach(chart.series, id: \.label) { series in
                                    ForEach(series.points, id: \.turn) { point in
                                        LineMark(x: .value("Week", Int(point.turn)), y: .value(series.label, point.value), series: .value("Candidate", series.label))
                                            .foregroundStyle(Color(hex: series.color)).symbol(.circle)
                                    }
                                }
                            }.chartYScale(domain: chart.minimum...chart.maximum).frame(height: 180)
                            Text(chart.series.map(\.label).joined(separator: " / ")).font(.caption).foregroundStyle(CampaignStyle.muted)
                        }.padding(14).background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
                    }
                    Picker("State or region", selection: Binding(get: { selectedRegion ?? document.selectedRegion }, set: { selectedRegion = $0 })) {
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
    }
}
