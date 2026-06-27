package com.stashapp.sync

import android.content.Context
import java.security.MessageDigest

object SyncSettings {
    private const val PREFS = "stash_sync_prefs"
    private const val KEY_ENABLED = "sync_enabled"
    private const val KEY_HOST = "sync_host"
    private const val KEY_PORT = "sync_port"
    private const val KEY_PASSWORD_HASH = "sync_password_hash"
    private const val KEY_DOWNLOAD_ALL = "download_all_on_sync"
    private const val KEY_USE_TLS = "use_tls"
    private const val KEY_CERT_FINGERPRINT = "cert_fingerprint"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isUseTls(ctx: Context) = prefs(ctx).getBoolean(KEY_USE_TLS, true)

    /** Pinned server-certificate SHA-256 (uppercase colon-separated), or "" until trust-on-first-use. */
    fun getCertFingerprint(ctx: Context) = prefs(ctx).getString(KEY_CERT_FINGERPRINT, "") ?: ""

    fun setCertFingerprint(ctx: Context, fingerprint: String) =
        prefs(ctx).edit().putString(KEY_CERT_FINGERPRINT, fingerprint).apply()

    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun getHost(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, "") ?: ""

    fun getPort(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, 9876)

    fun getPasswordHash(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PASSWORD_HASH, "") ?: ""

    fun isDownloadAllOnSync(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DOWNLOAD_ALL, false)

    fun save(ctx: Context, enabled: Boolean, host: String, port: Int, passwordHash: String,
             downloadAllOnSync: Boolean, useTls: Boolean) {
        // Changing the TLS toggle invalidates any previously pinned cert (different server identity).
        val e = prefs(ctx).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_PASSWORD_HASH, passwordHash)
            .putBoolean(KEY_DOWNLOAD_ALL, downloadAllOnSync)
            .putBoolean(KEY_USE_TLS, useTls)
        if (!useTls) e.putString(KEY_CERT_FINGERPRINT, "")
        e.apply()
    }

    fun isConfigured(ctx: Context) = isEnabled(ctx) && getHost(ctx).isNotBlank() && getPasswordHash(ctx).isNotBlank()

    fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
