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
    val clearnetClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(cascadingDns)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .build()
                )
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Tor SOCKS5 proxy client.  Used by MeshTransport and any route that should
     * go through Tor.  No custom DNS needed — the SOCKS proxy resolves hostnames
     * on the exit node.
     */
    val torClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
            .connectTimeout(60, TimeUnit.SECONDS) // FIX: Bumped to 60s for better mesh reliability
            .readTimeout(60, TimeUnit.SECONDS)    // FIX: Bumped to 60s
            .writeTimeout(60, TimeUnit.SECONDS)   // FIX: Bumped to 60s
            .build()
    }
}