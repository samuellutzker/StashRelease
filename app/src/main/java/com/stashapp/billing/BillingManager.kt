package com.stashapp.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*

/**
 * Thin wrapper around Google Play Billing for the optional "Support development" tip jar.
 *
 * Tips are **consumable** one-time products so a user can tip more than once. They unlock
 * nothing functional — Stash and all its features (including sync) are free. Because nothing
 * is gated, there is no server-side verification: a purchase is acknowledged by consuming it
 * client-side, which also makes it immediately re-purchasable.
 *
 * Lifecycle: create in the owning Activity's onCreate and call [endConnection] in onDestroy.
 */
class BillingManager(
    context: Context,
    /** Called on the main thread once product details (prices) are available. */
    private val onProductsReady: (Map<String, ProductDetails>) -> Unit,
    /** Called on the main thread after a successful, consumed tip. */
    private val onThankYou: () -> Unit,
    /** Called on the main thread for user-visible errors (not for USER_CANCELED). */
    private val onError: (String) -> Unit,
) {
    companion object {
        const val TIP_COFFEE = "tip_coffee"
        const val TIP_GENEROUS = "tip_generous"
        const val TIP_PATRON = "tip_patron"
        val PRODUCT_IDS = listOf(TIP_COFFEE, TIP_GENEROUS, TIP_PATRON)
    }

    private val appContext = context.applicationContext
    private var products: Map<String, ProductDetails> = emptyMap()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handlePurchase(it) }
            BillingClient.BillingResponseCode.USER_CANCELED -> { /* silent */ }
            else -> onError("Purchase failed: ${result.debugMessage.ifBlank { "code ${result.responseCode}" }}")
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    /** Connect and load product details. Safe to call once from onCreate. */
    fun start() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
                } else {
                    onError("Billing unavailable: ${result.debugMessage.ifBlank { "code ${result.responseCode}" }}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // Left to the next start()/launch attempt to retry; tips are non-critical.
            }
        })
    }

    private fun queryProducts() {
        val productList = PRODUCT_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        billingClient.queryProductDetailsAsync(params) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                products = detailsList.associateBy { it.productId }
                onProductsReady(products)
            } else {
                onError("Couldn't load tip options: ${result.debugMessage.ifBlank { "code ${result.responseCode}" }}")
            }
        }
    }

    /** Launch the purchase flow for one of the [PRODUCT_IDS]. */
    fun launchPurchase(activity: Activity, productId: String) {
        val details = products[productId] ?: run {
            onError("Tip options aren't ready yet — try again in a moment.")
            return
        }
        val paramsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(paramsList)
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return // ignore PENDING
        // Consumable: consuming both "acknowledges" the purchase and lets the user tip again.
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.consumeAsync(consumeParams) { result, _ ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                onThankYou()
            }
        }
    }

    fun endConnection() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}
