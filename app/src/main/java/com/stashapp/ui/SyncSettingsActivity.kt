package com.stashapp.ui

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.stashapp.R
import com.stashapp.StashApp
import com.stashapp.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import java.util.concurrent.TimeUnit

class SyncSettingsActivity : AppCompatActivity() {

    private lateinit var switchEnabled: Switch
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etPassword: EditText
    private lateinit var cbDownloadAll: CheckBox
    private lateinit var cbUseTls: CheckBox
    private lateinit var btnScanQr: Button
    private lateinit var btnResetFingerprint: Button
    private lateinit var btnTest: Button
    private lateinit var btnAbout: Button
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { applyQrResult(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "Sync Settings" }

        switchEnabled = findViewById(R.id.switchSyncEnabled)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etPassword = findViewById(R.id.etPassword)
        cbDownloadAll = findViewById(R.id.cbDownloadAll)
        cbUseTls = findViewById(R.id.cbUseTls)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnResetFingerprint = findViewById(R.id.btnResetFingerprint)
        btnTest = findViewById(R.id.btnTestConnection)
        btnAbout = findViewById(R.id.btnAbout)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)

        // Populate current settings
        switchEnabled.isChecked = SyncSettings.isEnabled(this)
        etHost.setText(SyncSettings.getHost(this))
        etPort.setText(SyncSettings.getPort(this).toString())
        cbDownloadAll.isChecked = SyncSettings.isDownloadAllOnSync(this)
        cbUseTls.isChecked = SyncSettings.isUseTls(this)
        // Don't pre-fill password — user must re-enter to change

        btnScanQr.setOnClickListener { launchQrScanner() }
        btnResetFingerprint.setOnClickListener { resetFingerprint() }
        btnTest.setOnClickListener { testConnection() }
        btnAbout.setOnClickListener { startActivity(android.content.Intent(this, AboutActivity::class.java)) }
        btnSave.setOnClickListener { saveAndApply() }
    }

    private fun launchQrScanner() {
        scanLauncher.launch(ScanOptions().apply {
            setPrompt("Scan the QR code from the Stash server")
            setBeepEnabled(false)
            setOrientationLocked(false)
            setCaptureActivity(ScanQrActivity::class.java)
        })
    }

    private fun applyQrResult(text: String) {
        // Format: stash://HOST:PORT?tls=1&fp=FINGERPRINT
        try {
            val uri = Uri.parse(text)
            if (uri.scheme != "stash") {
                tvStatus.text = "Not a Stash QR code"
                return
            }
            val host = uri.host ?: run { tvStatus.text = "Invalid QR: missing host"; return }
            val port = if (uri.port > 0) uri.port else 9876
            val tls = uri.getQueryParameter("tls") == "1"
            etHost.setText(host)
            etPort.setText(port.toString())
            cbUseTls.isChecked = tls
            switchEnabled.isChecked = true
            tvStatus.text = "QR scanned — enter password and save"
        } catch (e: Exception) {
            tvStatus.text = "Invalid QR code"
        }
    }

    private fun resetFingerprint() {
        SyncSettings.setCertFingerprint(this, "")
        tvStatus.text = "Pinned certificate cleared — will re-pin on next connect"
    }

    private fun testConnection() {
        val host = etHost.text.toString().trim()
        val portStr = etPort.text.toString().trim()
        val password = etPassword.text.toString()
        val port = portStr.toIntOrNull() ?: 9876

        if (host.isBlank()) { tvStatus.text = "Enter a hostname first"; return }
        if (password.isBlank() && SyncSettings.getPasswordHash(this).isBlank()) {
            tvStatus.text = "Enter a password first"; return
        }

        val pwHash = if (password.isNotBlank()) SyncSettings.hashPassword(password)
                     else SyncSettings.getPasswordHash(this)

        tvStatus.text = "Connecting…"
        btnTest.isEnabled = false
        val useTls = cbUseTls.isChecked

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { testWs(host, port, pwHash, useTls) }
            tvStatus.text = result
            btnTest.isEnabled = true
        }
    }

    private fun testWs(host: String, port: Int, pwHash: String, useTls: Boolean): String {
        var result = "Unknown error"
        val latch = java.util.concurrent.CountDownLatch(1)
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
        if (useTls) com.stashapp.sync.TlsTrust.apply(builder, this)
        val client = builder.build()

        val scheme = if (useTls) "wss" else "ws"
        val req = Request.Builder().url("$scheme://$host:$port").build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = com.stashapp.sync.ServerMsg.parse(text)
                when (msg) {
                    is com.stashapp.sync.ServerMsg.Challenge -> {
                        webSocket.send(com.stashapp.sync.ClientMsg.auth(pwHash))
                    }
                    is com.stashapp.sync.ServerMsg.AuthOk -> {
                        result = if (useTls) {
                            val fp = com.stashapp.sync.SyncSettings.getCertFingerprint(this@SyncSettingsActivity)
                            "Connected (TLS). Pinned cert:\n$fp\nVerify it matches the server."
                        } else "Connected successfully"
                        webSocket.close(1000, "test done")
                        latch.countDown()
                    }
                    is com.stashapp.sync.ServerMsg.AuthFail -> {
                        result = "Wrong password"
                        webSocket.close(1000, "auth fail")
                        latch.countDown()
                    }
                    else -> {}
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result = "Connection failed: ${t.message}"
                latch.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })
        latch.await(8, TimeUnit.SECONDS)
        ws.cancel()
        return result
    }

    private fun saveAndApply() {
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull() ?: 9876
        val password = etPassword.text.toString()
        val downloadAll = cbDownloadAll.isChecked
        val useTls = cbUseTls.isChecked
        val enabled = switchEnabled.isChecked

        if (enabled && host.isBlank()) {
            Toast.makeText(this, "Enter a hostname", Toast.LENGTH_SHORT).show()
            return
        }
        if (enabled && password.isBlank() && SyncSettings.getPasswordHash(this).isBlank()) {
            Toast.makeText(this, "Enter a password", Toast.LENGTH_SHORT).show()
            return
        }

        val pwHash = if (password.isNotBlank()) SyncSettings.hashPassword(password)
                     else SyncSettings.getPasswordHash(this)

        SyncSettings.save(this, enabled, host, port, pwHash, downloadAll, useTls)
        (application as StashApp).syncClient.restart()
        Toast.makeText(this, if (enabled) "Sync enabled" else "Sync disabled", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); true }
        else super.onOptionsItemSelected(item)
}
