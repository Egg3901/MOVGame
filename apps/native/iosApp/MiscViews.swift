import SwiftUI

// Phase 4 shells (#22): same copy contract as androidApp MiscScreens.
struct StoreView: View {
    @StateObject private var store = StoreKitAdapter()

    var body: some View {
        if store.products.isEmpty {
            shell(
                eyebrow: "CAMPAIGN LIBRARY",
                title: "History is yours to play",
                feature: "17 campaigns included",
                body: "All 17 U.S. presidential campaigns are available in New Campaign today. There are no purchases in the app yet."
            )
        } else {
            List {
                if let notice = store.notice {
                    Text(notice).font(.caption)
                }
                ForEach(store.products) { product in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(product.title).font(.headline)
                            Text(store.owned.contains(product.packId) ? "Owned" : product.price)
                                .font(.caption)
                        }
                        Spacer()
                        if !store.owned.contains(product.packId) {
                            Button("Buy") {
                                Task { await store.purchase(packId: product.packId) }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
                Button("Restore purchases") {
                    Task { await store.restore() }
                }
            }
            .navigationTitle("Store")
        }
    }
}

struct AccountView: View {
    var body: some View {
        shell(
            eyebrow: "YOUR PROFILE",
            title: "The campaign stays with you",
            feature: "Saved on this device",
            body: "Campaign progress is saved on this device. Sign in and cross-device sync are not available yet."
        )
    }
}

private func shell(eyebrow: String, title: String, feature: String, body: String) -> some View {
    ScrollView {
        VStack(alignment: .leading, spacing: 20) {
            Spacer().frame(height: 28)
            Text(eyebrow).font(.caption.bold()).tracking(2).foregroundStyle(.orange)
            Text(title).font(.largeTitle.bold())
            VStack(alignment: .leading, spacing: 10) {
                Text(feature).font(.title3.bold())
                Text(body).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(20)
            .background(Color(red: 17/255, green: 27/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 18))
        }
        .padding(22)
    }
    .background(Color(red: 10/255, green: 15/255, blue: 20/255))
    .preferredColorScheme(.dark)
}
