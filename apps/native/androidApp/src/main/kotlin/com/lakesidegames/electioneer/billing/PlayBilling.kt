package com.lakesidegames.electioneer.billing

import android.app.Activity
import android.content.Context
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
import com.lakesidegames.electioneer.ui.CampaignAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

// Store receipts are delivered to the authenticated Lakeside bridge. Only its
// shared wallet grants rights; acknowledgement is durable and server-side.
class PlayBilling(
    context: Context,
    private val account: CampaignAccount,
    private val scope: CoroutineScope,
) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private var skuToPack: Map<String, String> = emptyMap()
    private var binding: JSONObject? = null
    private var bindingSession: String? = null
    private var purchasesEnabled = false
    private suspend fun configure() {
        val session = account.storeSessionKey() ?: error("Sign in with your Lakeside account to manage purchases.")
        val response = account.storeBinding()
        check(session == account.storeSessionKey()) { "Account changed during store setup." }
        val rows = response.getJSONArray("products")
        skuToPack = (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            if (row.isNull("googleSku")) null else row.getString("googleSku") to row.getString("packId")
        }.toMap()
        binding = response
        bindingSession = session
        purchasesEnabled = response.getBoolean("purchasesEnabled")
    }
    private fun currentBinding() = bindingSession != null && bindingSession == account.storeSessionKey()

    private var onChange: (() -> Unit)? = null

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    private var connected = false
    private var connecting = false
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
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
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
                connecting = false
            }
        })
    }

    fun listProducts(onDone: (List<StoreProduct>) -> Unit) {
        scope.launch {
            try {
                configure()
                if (!purchasesEnabled || skuToPack.isEmpty()) { onDone(emptyList()); return@launch }
                queryProducts(onDone)
            } catch (_: Exception) { onDone(emptyList()) }
        }
    }
    private fun queryProducts(onDone: (List<StoreProduct>) -> Unit) {
        val session = bindingSession
        ensureConnected {
            val params = QueryProductDetailsParams.newBuilder().setProductList(skuToPack.keys.map { sku ->
                QueryProductDetailsParams.Product.newBuilder().setProductId(sku).setProductType(BillingClient.ProductType.INAPP).build()
            }).build()
            client.queryProductDetailsAsync(params) { result, details ->
                if (session != account.storeSessionKey() || !currentBinding()) { onDone(emptyList()); return@queryProductDetailsAsync }
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    detailsBySku = details.productDetailsList.associateBy { it.productId }
                    onDone(details.productDetailsList.mapNotNull { d ->
                        val pack = skuToPack[d.productId] ?: return@mapNotNull null
                        val offer = d.oneTimePurchaseOfferDetails ?: return@mapNotNull null
                        StoreProduct(packId = pack, sku = d.productId, title = d.title, price = offer.formattedPrice)
                    })
                } else { lastNotice = "Product lookup failed."; onDone(emptyList()) }
                changed()
            }
        }
    }

    fun purchase(activity: Activity, packId: String) {
        scope.launch {
            try {
                configure()
                check(purchasesEnabled) { "Paid packs are not available yet." }
                val owned = account.refreshStoreOwnership()
                check(packId !in owned && "complete" !in owned) { "You already own this pack on your Lakeside account." }
                val sku = skuToPack.entries.firstOrNull { it.value == packId }?.key ?: error("This pack is unavailable.")
                queryProducts { products ->
                    if (products.any { it.sku == sku }) detailsBySku[sku]?.let { details -> launchFlow(activity, details) }
                    else { lastNotice = "This pack is currently unavailable."; changed() }
                }
            } catch (error: Exception) { lastNotice = error.message; changed() }
        }
    }
    private fun launchFlow(activity: Activity, details: ProductDetails) {
        if (!currentBinding() || !purchasesEnabled) { lastNotice = "Refresh your Lakeside account before purchasing."; changed(); return }
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        details.oneTimePurchaseOfferDetails?.offerToken?.takeIf { it.isNotBlank() }?.let { item.setOfferToken(it) }
        val params = BillingFlowParams.newBuilder()
            .setObfuscatedAccountId(binding!!.getString("playAccountId"))
            .setProductDetailsParamsList(listOf(item.build())).build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) { lastNotice = "Purchase could not start."; changed() }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch {
                for (purchase in purchases) deliver(purchase)
                refreshOwnership()
                changed()
            }
        } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            lastNotice = "Purchase canceled."
            changed()
        } else if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            lastNotice = "Purchase failed (${result.responseCode})."
            changed()
        }
    }

    fun restore(onDone: (Set<String>) -> Unit = {}) {
        scope.launch {
            try { configure() }
            catch (error: Exception) { lastNotice = error.message; refreshOwnership(); onDone(entitlements()); changed(); return@launch }
            ensureConnected {
                val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
                client.queryPurchasesAsync(params) { result, purchases ->
                    scope.launch {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            for (purchase in purchases) deliver(purchase)
                        } else lastNotice = "Store restore unavailable. Shared ownership will still refresh."
                        refreshOwnership()
                        onDone(entitlements())
                        changed()
                    }
                }
            }
        }
    }
    private suspend fun deliver(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) { lastNotice = "Purchase pending."; return }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.products.none { it in skuToPack }) return
        try {
            val result = account.verifyStorePurchase(purchase.purchaseToken)
            lastNotice = when {
                result.getString("status") != "paid" -> "Purchase is not active."
                result.getString("environment") == "Sandbox" -> "Test purchase verified. Production ownership is unchanged."
                else -> "Purchase delivered to your Lakeside account."
            }
        } catch (error: Exception) { lastNotice = error.message ?: "Purchase delivery will retry when you restore." }
    }
    private suspend fun refreshOwnership() {
        try { account.refreshStoreOwnership() }
        catch (error: Exception) { if (lastNotice == null) lastNotice = error.message ?: "Shared purchases will refresh when online." }
    }
    fun close() { onChange = null; pendingOnConnect.clear(); client.endConnection() }
    fun entitlements(): Set<String> = account.cachedStorePacks()
}
