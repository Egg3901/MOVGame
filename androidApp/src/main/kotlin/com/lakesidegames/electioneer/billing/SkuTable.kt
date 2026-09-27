package com.lakesidegames.electioneer.billing

// Pack -> Play SKU mapping (Phase 5, #8). Per docs/billing.md this table is
// exported from MOVGame so the three stores cannot drift; the web repo is
// out of scope for the native track, so the native copy lives here and
// starts EMPTY: the adapter has nothing to sell until the first store
// product is created in Play Console and its SKU is entered below.
// Until then StoreScreen keeps its "nothing for sale yet" posture.
data class SkuEntry(val packId: String, val playSku: String)

object SkuTable {
    val entries: List<SkuEntry> = emptyList()

    fun packForSku(sku: String): String? = entries.firstOrNull { it.playSku == sku }?.packId

    fun skuForPack(packId: String): String? = entries.firstOrNull { it.packId == packId }?.playSku
}

// Store-facing product, free of BillingClient types so the UI and tests
// never touch the Play API directly.
data class StoreProduct(
    val packId: String,
    val sku: String,
    val title: String,
    val price: String,
)
