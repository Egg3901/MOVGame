import SwiftUI
import WebKit
import shared

enum MapMargin {
    static func points(_ contest: ContestProjection) -> Double {
        (contest.demShare - 0.5) * 200
    }

    static func tier(_ contest: ContestProjection) -> String {
        let margin = abs(points(contest))
        if margin <= 3 { return "Toss-up" }
        if margin <= 10 { return "Lean" }
        if margin <= 20 { return "Likely" }
        return "Safe"
    }

    static func label(_ contest: ContestProjection) -> String {
        let margin = points(contest)
        if abs(margin) <= 3 { return String(format: "Toss-up · %.1f pt margin", abs(margin)) }
        return String(format: "%@ %@ · %.1f pt margin", tier(contest), margin > 0 ? "Dem" : "GOP", abs(margin))
    }

    static func color(_ contest: ContestProjection?) -> String {
        guard let contest else { return "#556271" }
        let margin = points(contest)
        if abs(margin) <= 3 { return "#f5b942" }
        if margin > 0 {
            if margin <= 10 { return "#82b2ff" }
            if margin <= 20 { return "#397fe5" }
            return "#174b9b"
        }
        if margin >= -10 { return "#ff9a91" }
        if margin >= -20 { return "#e45656" }
        return "#96333e"
    }
}

// The same state outlines used by the web game, rendered locally with no network access.
struct GeoMapView: UIViewRepresentable {
    var contestsById: [String: ContestProjection]
    var abbrToStateId: [String: String]
    var selectedAbbr: String?
    var onSelect: (String) -> Void

    private static let paths: [String: String] = {
        guard let url = Bundle.main.url(forResource: "statePaths", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let paths = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return paths
    }()

    func makeCoordinator() -> Coordinator { Coordinator(onSelect: onSelect) }

    func makeUIView(context: Context) -> WKWebView {
        let controller = WKUserContentController()
        controller.add(context.coordinator, name: "stateSelected")
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false
        view.backgroundColor = UIColor.clear
        view.scrollView.isScrollEnabled = false
        view.scrollView.backgroundColor = UIColor.clear
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.onSelect = onSelect
        let html = markup()
        if context.coordinator.lastMarkup != html {
            context.coordinator.lastMarkup = html
            view.loadHTMLString(html, baseURL: nil)
        }
    }

    private func markup() -> String {
        let shapes = Self.paths.keys.sorted().map { abbr -> String in
            let contest = abbrToStateId[abbr].flatMap { contestsById[$0] }
            let color = MapMargin.color(contest)
            let selected = abbr == selectedAbbr
            let stroke = selected ? "#f5b942" : "#101a27"
            let width = selected ? "3" : "1.3"
            let description = contest.map { "\(abbr), \(MapMargin.label($0)), \($0.ev) electoral votes" } ?? abbr
            return "<path d='\(Self.paths[abbr] ?? "")' fill='\(color)' stroke='\(stroke)' stroke-width='\(width)' data-state='\(abbr)' aria-label='\(description)' tabindex='0'><title>\(description)</title></path>"
        }.joined()
        return """
        <!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>
        <style>html,body{margin:0;background:transparent;overflow:hidden}svg{width:100%;height:100%;display:block}path{cursor:pointer}path:focus{outline:none;stroke:#f5b942;stroke-width:3}</style>
        </head><body><svg viewBox='0 0 1000 650' preserveAspectRatio='xMidYMid meet' role='img' aria-label='United States electoral map'>\(shapes)</svg>
        <script>document.querySelectorAll('path').forEach(function(p){
        function choose(){window.webkit.messageHandlers.stateSelected.postMessage(p.dataset.state)}
        p.addEventListener('click',choose);p.addEventListener('keydown',function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();choose()}})
        })</script></body></html>
        """
    }

    final class Coordinator: NSObject, WKScriptMessageHandler {
        var onSelect: (String) -> Void
        var lastMarkup: String?

        init(onSelect: @escaping (String) -> Void) { self.onSelect = onSelect }

        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            guard let abbr = message.body as? String, GeoMapView.paths[abbr] != nil else { return }
            onSelect(abbr)
        }
    }
}
