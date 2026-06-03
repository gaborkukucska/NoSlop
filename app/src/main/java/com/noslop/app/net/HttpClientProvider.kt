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
        val bootstrapClient = OkHttpClient.Builder().build()
        val doh = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")))
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
                    return try {
                        Dns.SYSTEM.lookup(hostname)
                    } catch (e: Exception) {
                        Logger.warn("DNS", "System DNS failed for $hostname, using DoH fallback...")
                        try {
                            doh.lookup(hostname)
                        } catch (de: Exception) {
                            Logger.error("DNS", "DoH fallback failed for $hostname: ${de.message}")
                            throw de
                        }
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
