package com.lakesidegames.electioneer.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.lakesidegames.electioneer.store.VerifiedPurchase
import com.lakesidegames.electioneer.store.activePacks

// Play Billing adapter (Phase 5, #8): the billing.md surface
// listProducts / purchase / restore / entitlements for non-consumables.
// Purchases are acknowledged (or Google auto-refunds); restore re-delivers
// via queryPurchases; entitlements() unions verified purchases with the
// signed local cache under the shared grace policy. RTDN refunds and the
// receipt-bridge endpoint are server-side (web repo, out of scope); the
// client picks refunds up on the next queryPurchases because a refunded
// purchase stops being returned.
class PlayBilling(
    context: Context,
    playPublicKeyBase64: String,
) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val cache = EntitlementCache(appContext, playPublicKeyBase64)
    private var onChange: (() -> Unit)? = null

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    private var connected = false
    private val pendingOnConnect = mutableListOf<() -> Unit>()
    private var detailsBySku: Map<String, ProductDetails> = emptyMap()

    var lastNotice: String? = null
        private set

    fun setOnChangeListener(listener: (() -> Unit)?) {
        onChange = listener
    }

    private fun changed() = onChange?.invoke()

    private fun ensureConnected(run: () -> Unit) {
        if (connected) {
            run()
            return
        }
        pendingOnConnect.add(run)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    connected = true
                    val queued = pendingOnConnect.toList()
                    pendingOnConnect.clear()
                    queued.forEach { it() }
                } else {
                    lastNotice = "Billing unavailable (${result.responseCode})."
                    pendingOnConnect.clear()
                    changed()
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
            }
        })
    }

    // listProducts: details for every SKU in the table (empty until the
    // first Play Console product exists).
    fun listProducts(onDone: (List<StoreProduct>) -> Unit) {
        val skus = SkuTable.entries.map { it.playSku }
        if (skus.isEmpty()) {
            onDone(emptyList())
            return
        }
        ensureConnected {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    skus.map { sku ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(sku)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    },
                )
                .build()
            client.queryProductDetailsAsync(params) { result, details ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    detailsBySku = details.productDetailsList.associateBy {
                        it.productId
                    }
                    onDone(details.productDetailsList.mapNotNull { d ->
                        val packId = SkuTable.packForSku(d.productId) ?: return@mapNotNull null
                        StoreProduct(
                            packId = packId,
                            sku = d.productId,
                            title = d.title,
                            price = d.oneTimePurchaseOfferDetails?.formattedPrice ?: "",
                        )
                    })
                } else {
                    lastNotice = "Product lookup failed (${result.responseCode})."
                    onDone(emptyList())
                }
                changed()
            }
        }
    }

    // purchase: launch the Play flow for a pack.
    fun purchase(activity: Activity, packId: String) {
        val sku = SkuTable.skuForPack(packId) ?: return
        ensureConnected {
            val details = detailsBySku[sku]
            if (details == null) {
                listProducts { products ->
                    val retry = products.firstOrNull { it.sku == sku }
                        ?.let { detailsBySku[sku] }
                    if (retry != null) launchFlow(activity, retry) else changed()
                }
                return@ensureConnected
            }
            launchFlow(activity, details)
        }
    }

    private fun launchFlow(activity: Activity, details: ProductDetails) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) handlePurchase(purchase)
        } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            lastNotice = "Purchase canceled."
            changed()
        } else if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            lastNotice = "Purchase failed (${result.responseCode})."
            changed()
        }
    }

    // restore + refresh: re-deliver every owned non-consumable, acknowledge
    // anything still pending, and reconcile the cache (refunds vanish from
    // queryPurchases, so they drop out of entitlements here).
    fun restore(onDone: (Set<String>) -> Unit = {}) {
        ensureConnected {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            client.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    lastNotice = "Restore failed (${result.responseCode})."
                    onDone(entitlements())
                    changed()
                    return@queryPurchasesAsync
                }
                for (purchase in purchases) handlePurchase(purchase)
                // Drop cache entries the store no longer reports (refunds).
                val liveSkus = purchases
                    .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .flatMap { it.products }
                    .toSet()
                for (entry in SkuTable.entries) {
                    if (entry.playSku !in liveSkus) cache.drop(entry.packId)
                }
                onDone(entitlements())
                changed()
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { _ -> }
        }
        val now = System.currentTimeMillis()
        for (sku in purchase.products) {
            val packId = SkuTable.packForSku(sku)
                ?: EntitlementCache.packIdFromPurchaseData(purchase.originalJson)
                ?: continue
            cache.store(packId, purchase.originalJson, purchase.signature, now)
        }
        lastNotice = "Purchase complete."
        changed()
    }

    // entitlements: verified live purchases plus the signed cache under the
    // shared grace policy. Query path is synchronous (cache only); call
    // restore() first for a live check.
    fun entitlements(): Set<String> {
        val now = System.currentTimeMillis()
        return activePacks(emptyList(), cache.load(now), now)
    }
}
