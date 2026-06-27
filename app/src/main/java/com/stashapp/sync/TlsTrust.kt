package com.stashapp.sync

import android.content.Context
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for self-hosted servers. Home servers use a self-signed certificate, so instead of a
 * CA we pin the server's certificate by its SHA-256 fingerprint, trust-on-first-use: the first
 * cert we see is remembered, and every later connection must present the same one (otherwise the
 * connection is refused — basic MITM protection). The user can verify the pinned value against the
 * fingerprint the server logs / shows in its web UI.
 */
object TlsTrust {

    /** Uppercase colon-separated SHA-256 of a certificate — matches the server's log format. */
    fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /** Configure [builder] to pin the server cert (trust-on-first-use) for this context's settings. */
    fun apply(builder: OkHttpClient.Builder, ctx: Context): OkHttpClient.Builder {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("no server certificate")
                val fp = fingerprintOf(leaf)
                val pinned = SyncSettings.getCertFingerprint(ctx)
                when {
                    pinned.isBlank() -> {
                        SyncSettings.setCertFingerprint(ctx, fp)   // trust-on-first-use
                        TransferLog.log("TLS pinned new cert fingerprint $fp")
                    }
                    !pinned.equals(fp, ignoreCase = true) ->
                        throw CertificateException(
                            "Server certificate changed (possible MITM). pinned=$pinned got=$fp"
                        )
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        builder.sslSocketFactory(ssl.socketFactory, trustManager)
        // A self-signed cert's CN won't match the LAN IP/host; identity is established by the pin.
        builder.hostnameVerifier { _, _ -> true }
        return builder
    }
}
