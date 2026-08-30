// FILE: app/src/main/java/com/noslop/app/net/HttpClientProvider.kt
package com.noslop.app.net

import com.noslop.app.debug.Logger
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    private const val TAG = "DNS"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Bootstrap InetAddress values for DoH servers, constructed from raw byte
     * arrays rather than via InetAddress.getByName("1.1.1.1").
     *
     * InetAddress.getByName() on a bare IP string goes through the JVM's
     * hostname resolution path on some Android versions — it can call into the
     * system resolver and block (or fail) when the network is in a restricted
     * state (captive portal, VPN, Tor bootstrap). Using getByAddress() with
     * the literal bytes bypasses that path entirely.
     */
    private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    /** A plain OkHttpClient with NO custom DNS — used only to bootstrap DoH.
     *  It falls through to system DNS, which is fine: it only ever contacts
     *  numeric-IP DoH endpoints so no hostname resolution is needed in practice.
     *  Short timeouts so a dead bootstrap doesn't stall app start. */
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Cloudflare DoH — bootstrapped to 1.1.1.1 / 1.0.0.1 via raw bytes */
    private val cloudflareDoh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(ipv4(1, 1, 1, 1), ipv4(1, 0, 0, 1)))
            .build()
    }

    /** Google DoH — bootstrapped to 8.8.8.8 / 8.8.4.4 via raw bytes */
    private val googleDoh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(ipv4(8, 8, 8, 8), ipv4(8, 8, 4, 4)))
            .build()
    }

    /**
     * Cascading DNS resolver used by [clearnetClient].
     *
     * Resolution order (each level tried only if the previous throws):
     *   1. Android system DNS  — fastest on a normal network; always tried first.
     *   2. Cloudflare DoH      — bypasses captive portals / broken system resolvers.
     *   3. Google DoH          — independent fallback if Cloudflare is unreachable.
     *
     * Any NXDOMAIN from system DNS is re-thrown immediately (the domain doesn't
     * exist, DoH won't help). Only network-level failures (timeout, IOException)
     * cascade to the next resolver.
     */
    internal val cascadingDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // 1. System DNS — fast path; works on almost every network
            try {
                val result = Dns.SYSTEM.lookup(hostname)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                // NXDOMAIN produces an UnknownHostException whose message ends
                // with the bare hostname (no ":" suffix).  That means the domain
                // truly doesn't exist — DoH won't give a different answer, and
                // hammering DoH servers with NXDOMAIN queries is wasteful.
                val msg = e.message ?: ""
                if (msg.endsWith(hostname) && !msg.contains(":")) {
                    // FIX: Local networks blocks (Pi-hole, ISP) return NXDOMAIN to block.
                    // By NOT throwing the exception here, we force the fallback to DoH!
                    Logger.warn(TAG, "System DNS returned NXDOMAIN for $hostname, falling back to DoH to bypass potential local block…")
                } else {
                    Logger.warn(TAG, "System DNS failed for $hostname (${e.message}), trying Cloudflare DoH…")
                }
            }

            // 2. Cloudflare DoH
            try {
                val result = cloudflareDoh.lookup(hostname)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                Logger.warn(TAG, "Cloudflare DoH failed for $hostname (${e.message}), trying Google DoH…")
            }

            // 3. Google DoH — last resort
            return try {
                val result = googleDoh.lookup(hostname)
                if (result.isNotEmpty()) return result
                throw java.net.UnknownHostException("All DNS resolvers returned empty for $hostname")
            } catch (e: Exception) {
                Logger.error(TAG, "All DNS resolvers failed for $hostname: ${e.message}")
                throw e
            }
        }
    }

    /**
     * The main HTTP client used for all clearnet requests (feeds, Invidious API,
     * archive.org, Vimeo config, etc.).
     *
     * - Custom cascading DNS resolver (system → Cloudflare DoH → Google DoH)
     * - Browser User-Agent injected via interceptor so every request looks like
     *   a desktop browser (some RSS hosts and APIs reject non-browser UAs)
     * - 30 s connect / read timeouts — generous enough for slow servers, tight
     *   enough to surface failures instead of hanging indefinitely
     */
    
    @Volatile
    var useTorForClearnet: Boolean = true

    @Volatile
    var isAutoUpdateEnabled: Boolean = true

    val activeClearnetClient: OkHttpClient
        get() = if (useTorForClearnet) torClient else rawClearnetClient

    // --- NOSLOP_TOR_CIRCUIT_V1 ---
    // There is deliberately NO media-specific client and no direct fallback.
    // If Tor cannot carry it, NoSlop does not fetch it — the alternative is
    // handing the user's IP to the very services the project exists to keep at
    // arm's length.
    //
    // Media and its resolution share this one client so that the ip= lock on a
    // googlevideo URL is issued to, and used by, the same exit.
    val activeMediaClient: OkHttpClient
        get() = activeClearnetClient

    /**
     * True when it is safe to dispatch network work: either Tor is off by
     * configuration, or Tor is fully READY.
     */
    val isNetworkReady: Boolean
        get() = !useTorForClearnet ||
            com.noslop.app.tor.TorService.torState.value == com.noslop.app.tor.TorState.READY

    /**
     * Suspend until network work can safely be dispatched. Returns false if Tor
     * never came up, in which case the caller should surface that rather than
     * proceed.
     */
    suspend fun awaitNetworkReady(timeoutMs: Long = 90_000L): Boolean {
        if (!useTorForClearnet) return true
        return com.noslop.app.tor.TorService.awaitReady(timeoutMs)
    }

    private val userAgentInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.header("User-Agent") != null) {
            chain.proceed(request)
        } else {
            chain.proceed(
                request.newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()
            )
        }
    }

    val rawClearnetClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY) // Force direct connection for LAN/clearnet, ignoring Tor system properties
            .dns(cascadingDns)
            .addInterceptor(userAgentInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val torGuardInterceptor = okhttp3.Interceptor { chain ->
        if (useTorForClearnet && com.noslop.app.tor.TorService.torState.value != com.noslop.app.tor.TorState.READY) {
            Logger.warn(TAG, "Tor is disconnected — blocking outbound request over Tor for privacy: ${chain.request().url}")
            throw java.io.IOException("Tor is disconnected — blocking outbound request over Tor for privacy")
        }
        chain.proceed(chain.request())
    }

    private object DummySslSession : javax.net.ssl.SSLSession {
        override fun getId(): ByteArray = byteArrayOf()
        override fun getSessionContext(): javax.net.ssl.SSLSessionContext? = null
        override fun getCreationTime(): Long = System.currentTimeMillis()
        override fun getLastAccessedTime(): Long = System.currentTimeMillis()
        override fun invalidate() {}
        override fun isValid(): Boolean = true
        override fun putValue(name: String?, value: Any?) {}
        override fun getValue(name: String?): Any? = null
        override fun removeValue(name: String?) {}
        override fun getValueNames(): Array<String> = emptyArray()
        override fun getPeerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        override fun getLocalCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = emptyArray()
        override fun getPeerPrincipal(): java.security.Principal? = null
        override fun getLocalPrincipal(): java.security.Principal? = null
        override fun getCipherSuite(): String = "SSL_NULL_WITH_NULL_NULL"
        override fun getProtocol(): String = "NONE"
        override fun getPeerHost(): String = ""
        override fun getPeerPort(): Int = 0
        override fun getPacketBufferSize(): Int = 32768
        override fun getApplicationBufferSize(): Int = 32768
    }

    private class PassthroughSSLSocket(private val delegate: java.net.Socket) : javax.net.ssl.SSLSocket() {
        override fun getInputStream(): java.io.InputStream = delegate.getInputStream()
        override fun getOutputStream(): java.io.OutputStream = delegate.getOutputStream()
        override fun close() = delegate.close()
        override fun isClosed(): Boolean = delegate.isClosed()
        override fun isConnected(): Boolean = delegate.isConnected()
        override fun isBound(): Boolean = delegate.isBound()
        override fun getInetAddress(): java.net.InetAddress? = delegate.inetAddress
        override fun getPort(): Int = delegate.port
        override fun getLocalAddress(): java.net.InetAddress? = delegate.localAddress
        override fun getLocalPort(): Int = delegate.localPort
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
        override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

        override fun startHandshake() {}
        override fun getSession(): javax.net.ssl.SSLSession = DummySslSession
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation(): Boolean = false
        override fun addHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {}
        override fun removeHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {}
    }

    private class PassthroughSslSocketFactory : javax.net.ssl.SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = emptyArray()
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket = PassthroughSSLSocket(s)
        override fun createSocket(host: String, port: Int): java.net.Socket = PassthroughSSLSocket(java.net.Socket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): java.net.Socket = PassthroughSSLSocket(java.net.Socket(host, port))
        override fun createSocket(host: InetAddress, port: Int): java.net.Socket = PassthroughSSLSocket(java.net.Socket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): java.net.Socket = PassthroughSSLSocket(java.net.Socket(address, port))
    }

    private val dummyTrustManager = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    private val onionSchemeInterceptor = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val url = req.url
        val newReq = if (url.scheme == "http" && url.host.endsWith(".onion")) {
            val portToUse = if (url.port == 80 || url.port == -1) 80 else url.port
            req.newBuilder()
                .url(url.newBuilder().scheme("https").port(portToUse).build())
                .build()
        } else req
        chain.proceed(newReq)
    }

    /**
     * Tor SOCKS5 proxy client.  Used by MeshTransport and any route that should
     * go through Tor.  No custom DNS needed — the SOCKS proxy resolves hostnames
     * on the exit node.
     */
    val torClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(torGuardInterceptor)
            .addInterceptor(onionSchemeInterceptor)
            .addInterceptor(userAgentInterceptor)
            .sslSocketFactory(PassthroughSslSocketFactory(), dummyTrustManager)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", com.noslop.app.BuildConfig.TOR_SOCKS_PORT)))
            .connectTimeout(60, TimeUnit.SECONDS) // FIX: Bumped to 60s for better mesh reliability
            .readTimeout(60, TimeUnit.SECONDS)    // FIX: Bumped to 60s
            .writeTimeout(60, TimeUnit.SECONDS)   // FIX: Bumped to 60s
            .build()
    }
}