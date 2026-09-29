import Foundation
import StoreKit

// StoreKit 2 adapter (Phase 5, #8): the billing.md surface
// listProducts / purchase / restore / entitlements for non-consumables.
//
// Product IDs come from the SKU table (docs/billing.md); the table is
// exported from MOVGame, so this starts EMPTY and the campaign library keeps its
// "nothing for sale yet" posture until the first App Store Connect product
// exists. Entitlements are the on-device verified set
// (Transaction.currentEntitlements, Apple-signed, no server needed) plus a
// timestamped cache for offline grace; refunds vanish from
// currentEntitlements, so they drop out on the next refresh. Server
// notifications and the receipt-bridge endpoint are web-repo work (#23).

// Mirror of SkuTable on Android; fill from the MOVGame SKU export.
let storeSkuTable: [(packId: String, sku: String)] = []

private let cacheDefaultsKey = "mov.entitlements"
private let cacheDateKey = "mov.entitlements.confirmedAt"
private let graceDays = 7.0

struct StoreProduct: Identifiable {
    var id: String { packId }
    let packId: String
    let sku: String
    let title: String
    let price: String
}

@MainActor
final class StoreKitAdapter: ObservableObject {
    @Published var products: [StoreProduct] = []
    @Published var owned: Set<String> = []
    @Published var notice: String? = nil

    private var updatesTask: Task<Void, Never>? = nil

    init() {
        updatesTask = Task { await self.listenForTransactions() }
        refresh()
    }

    deinit {
        updatesTask?.cancel()
    }

    func refresh() {
        Task {
            await loadProducts()
            await refreshEntitlements()
        }
    }

    // listProducts: App Store details for every SKU in the table.
    func loadProducts() async {
        let skus = storeSkuTable.map { $0.sku }
        guard !skus.isEmpty else {
            products = []
            return
        }
        do {
            let storeProducts = try await Product.products(for: skus)
            let packFor = Dictionary(uniqueKeysWithValues: storeSkuTable.map { ($0.sku, $0.packId) })
            products = storeProducts.compactMap { p in
                guard let packId = packFor[p.id] else { return nil }
                return StoreProduct(
                    packId: packId,
                    sku: p.id,
                    title: p.displayName,
                    price: p.displayPrice
                )
            }
        } catch {
            notice = "Product lookup failed."
        }
    }

    // purchase: StoreKit 2 flow for a pack.
    func purchase(packId: String) async {
        guard let sku = storeSkuTable.first(where: { $0.packId == packId })?.sku else { return }
        do {
            let storeProducts = try await Product.products(for: [sku])
            guard let product = storeProducts.first else { return }
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                await finish(verification: verification)
            case .userCancelled:
                notice = "Purchase canceled."
            case .pending:
                notice = "Purchase pending."
            @unknown default:
                notice = "Purchase failed."
            }
        } catch {
            notice = "Purchase failed."
        }
    }

    // restore: re-read the verified on-device entitlement set.
    func restore() async {
        await refreshEntitlements()
    }

    private func listenForTransactions() async {
        for await update in Transaction.updates {
            await finish(verification: update)
        }
    }

    private func finish(verification: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = verification else { return }
        if transaction.revocationDate == nil {
            saveCache(packIds: await verifiedPackIds())
        }
        await transaction.finish()
        await refreshEntitlements()
        notice = "Purchase complete."
    }

    private func skuToPack() -> [String: String] {
        Dictionary(uniqueKeysWithValues: storeSkuTable.map { ($0.sku, $0.packId) })
    }

    private func verifiedPackIds() async -> Set<String> {
        var out = Set<String>()
        let map = skuToPack()
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            if transaction.revocationDate != nil { continue }
            if let packId = map[transaction.productID] {
                out.insert(packId)
            }
        }
        return out
    }

    // entitlements: the verified live set plus the fresh cache, minus
    // packs with a known revocation. The live read is authoritative when it
    // succeeds; the cache covers offline gaps inside the grace window. A
    // refund Apple reports (revocationDate) drops the pack immediately and
    // stays dropped via the persisted revoked set.
    func refreshEntitlements() async {
        let live = await verifiedPackIds()
        let revoked = await revokedPackIds()
        var revokedStillOut = revoked.subtracting(live)
        var merged = live
        if let cached = loadCache() {
            let age = Date().timeIntervalSince(cached.confirmedAt)
            if age <= graceDays * 86_400 {
                merged.formUnion(cached.packs.subtracting(revokedStillOut))
            }
        }
        revokedStillOut.formUnion(await revokedPackIds())
        saveRevoked(revokedStillOut.subtracting(live))
        saveCache(packIds: merged)
        owned = merged
    }

    private func revokedPackIds() async -> Set<String> {
        var out = Set(loadRevoked())
        let map = skuToPack()
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            if transaction.revocationDate != nil, let packId = map[transaction.productID] {
                out.insert(packId)
            }
        }
        return out
    }

    private struct CachedPacks {
        let packs: Set<String>
        let confirmedAt: Date
    }

    private func loadCache() -> CachedPacks? {
        let defaults = UserDefaults.standard
        guard let packs = defaults.stringArray(forKey: cacheDefaultsKey) else { return nil }
        let at = defaults.object(forKey: cacheDateKey) as? Date ?? Date.distantPast
        return CachedPacks(packs: Set(packs), confirmedAt: at)
    }

    private func saveCache(packIds: Set<String>) {
        let defaults = UserDefaults.standard
        defaults.set(Array(packIds), forKey: cacheDefaultsKey)
        defaults.set(Date(), forKey: cacheDateKey)
    }

    private let revokedDefaultsKey = "mov.entitlements.revoked"

    private func loadRevoked() -> [String] {
        UserDefaults.standard.stringArray(forKey: revokedDefaultsKey) ?? []
    }

    private func saveRevoked(_ packIds: Set<String>) {
        UserDefaults.standard.set(Array(packIds), forKey: revokedDefaultsKey)
    }
}
