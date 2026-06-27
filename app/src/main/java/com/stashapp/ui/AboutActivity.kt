package com.stashapp.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.ProductDetails
import com.stashapp.R
import com.stashapp.billing.BillingManager

/**
 * About screen with the optional "Support development" tip jar.
 * Tips unlock nothing — see [BillingManager].
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var billing: BillingManager
    private lateinit var btnCoffee: Button
    private lateinit var btnGenerous: Button
    private lateinit var btnPatron: Button
    private lateinit var tvThankYou: TextView

    private val buttonForProduct: Map<String, Button> by lazy {
        mapOf(
            BillingManager.TIP_COFFEE to btnCoffee,
            BillingManager.TIP_GENEROUS to btnGenerous,
            BillingManager.TIP_PATRON to btnPatron,
        )
    }
    private val baseLabel = mapOf(
        BillingManager.TIP_COFFEE to "☕ Buy me a coffee",
        BillingManager.TIP_GENEROUS to "🍰 Generous tip",
        BillingManager.TIP_PATRON to "🌟 Patron",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "About" }

        btnCoffee = findViewById(R.id.btnTipCoffee)
        btnGenerous = findViewById(R.id.btnTipGenerous)
        btnPatron = findViewById(R.id.btnTipPatron)
        tvThankYou = findViewById(R.id.tvThankYou)

        findViewById<TextView>(R.id.tvVersion).text = "Version ${appVersionName()}"

        btnCoffee.setOnClickListener { billing.launchPurchase(this, BillingManager.TIP_COFFEE) }
        btnGenerous.setOnClickListener { billing.launchPurchase(this, BillingManager.TIP_GENEROUS) }
        btnPatron.setOnClickListener { billing.launchPurchase(this, BillingManager.TIP_PATRON) }

        billing = BillingManager(
            context = this,
            onProductsReady = { details -> runOnUiThread { showPrices(details) } },
            onThankYou = { runOnUiThread { tvThankYou.visibility = View.VISIBLE } },
            onError = { msg -> runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() } },
        )
        billing.start()
    }

    private fun showPrices(details: Map<String, ProductDetails>) {
        buttonForProduct.forEach { (id, button) ->
            val price = details[id]?.oneTimePurchaseOfferDetails?.formattedPrice
            if (price != null) {
                button.text = "${baseLabel[id]}  ·  $price"
                button.isEnabled = true
            }
        }
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    } catch (e: Exception) {
        "—"
    }

    override fun onDestroy() {
        billing.endConnection()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); true }
        else super.onOptionsItemSelected(item)
}
