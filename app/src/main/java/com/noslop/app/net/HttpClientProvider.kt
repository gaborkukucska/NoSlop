// FILE: app/src/main/java/com/noslop/app/net/HttpClientProvider.kt
package com.noslop.app.net

import com.noslop.app.debug.Logger
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    val clearnetClient: OkHttpClient by lazy {
        // FIX: The previous DoH URL was "https://1.1.1.1/dns-query".
        // Cloudflare's TLS certificate at IP 1.1.1.1 is issued for the hostname
        // "one.one.one.one" — not for the bare IP address. OkHttp's hostname
        // verifier rejects the connection because the cert CN doesn't match "1.1.1.1",
        // causing every DoH lookup to fail with an UnknownHostException whose
        // message is just the target hostname. System DNS then also fails (slow
        // network / captive portal), so all API calls throw DNS errors in the log.
        //
        // Fix: use "https://cloudflare-dns.com/dns-query" as the DoH endpoint.
        // The bootstrapDnsHosts pins Cloudflare's IPs (1.1.1.1 / 1.0.0.1) so OkHttp
        // can reach the server without needing working DNS, and the TLS cert for
        // "cloudflare-dns.com" now correctly matches the SNI. A second DoH resolver
        // pointing at Google (8.8.8.8 / 8.8.4.4) is chained as a further fallback.
        // Explicit timeouts are also added to the bootstrap client so a slow DoH
        // server doesn't cause an indefinite hang.

        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        // Primary DoH: Cloudflare — hostname cert matches, IPs bootstrapped
        val cloudflareDoh = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                listOf(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1")
                )
            )
            .build()

        // Secondary DoH: Google — independent fallback if Cloudflare is unreachable
        val googleDoh = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                listOf(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4")
                )
            )
            .build()

        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    // 1. Try Android system DNS (fast path — works on most networks)
                    try {
                        return Dns.SYSTEM.lookup(hostname)
                    } catch (e: Exception) {
                        Logger.warn("DNS", "System DNS failed for $hostname, trying Cloudflare DoH...")
                    }
                    // 2. Cloudflare DoH
                    try {
                        return cloudflareDoh.lookup(hostname)
                    } catch (e: Exception) {
                        Logger.warn("DNS", "Cloudflare DoH failed for $hostname, trying Google DoH...")
                    }
                    // 3. Google DoH — last resort
                    return try {
                        googleDoh.lookup(hostname)
                    } catch (de: Exception) {
                        Logger.error("DNS", "All DNS resolvers failed for $hostname: ${de.message}")
                        throw de
                    }
                }
            })
            .build()
    }

    val torClient: OkHttpClient by lazy {
        val torProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
        OkHttpClient.Builder()
            .proxy(torProxy)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
