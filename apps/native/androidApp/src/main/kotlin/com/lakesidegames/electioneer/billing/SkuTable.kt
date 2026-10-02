package com.lakesidegames.electioneer.billing

// Store-facing product, free of BillingClient types so the UI and tests
// never touch the Play API directly.
data class StoreProduct(
    val packId: String,
    val sku: String,
    val title: String,
    val price: String,
)
