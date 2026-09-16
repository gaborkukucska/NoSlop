package com.noslop.app.feeds.api

import okhttp3.Request

/**
 * P4-1: Shared proxy authentication and header signing for external API requests.
 */
object ProxyAuth {
    @Volatile
    private var customProxyUrl: String? = null
    @Volatile
    private var customProxySecret: String? = null

    val PROXY_URL: String
        get() = customProxyUrl?.takeIf { it.isNotBlank() } ?: com.noslop.app.BuildConfig.PROXY_URL

    val PROXY_SECRET: String
        get() = customProxySecret?.takeIf { it.isNotBlank() } ?: com.noslop.app.BuildConfig.PROXY_SECRET

    fun setCustomProxy(url: String?, secret: String?) {
        customProxyUrl = url?.trim()?.removeSuffix("/")
        customProxySecret = secret?.trim()
    }

    fun applyProxyAuthHeaders(builder: Request.Builder, payloadStr: String) {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signatureInput = "$timestamp:$payloadStr"
        val hmacSig = try {
            val sha256HMAC = javax.crypto.Mac.getInstance("HmacSHA256")
            val secretKey = javax.crypto.spec.SecretKeySpec(PROXY_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
            sha256HMAC.init(secretKey)
            val hash = sha256HMAC.doFinal(signatureInput.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.debug("PROXY_AUTH", "Failed to compute HMAC signature: ${e.message}")
            ""
        }

        if (com.noslop.app.BuildConfig.PROXY_SEND_LEGACY_SECRET) {
            builder.header("X-Proxy-Secret", PROXY_SECRET)
        }
        builder.header("X-Proxy-Timestamp", timestamp)
        builder.header("X-Proxy-Signature", hmacSig)
    }
}
