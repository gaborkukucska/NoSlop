package com.noslop.app.tor

import android.content.Context
import com.noslop.app.debug.Logger
import info.guardianproject.netcipher.proxy.OrbotHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class TorState { STARTING, READY, FAILED }

object TorService {

    private const val TAG = "TOR"
    const val SOCKS_PORT = 9050
    const val PROXY_HOST = "127.0.0.1"

    var onAddressCallback: ((String) -> Unit)? = null

    // Unmanaged coroutine scope is fine here — TorService is a process-lifetime singleton.
    // It is initialised once in NoSlopApp.onCreate() and never torn down independently.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _torState = MutableStateFlow(TorState.STARTING)
    val torState: StateFlow<TorState> = _torState.asStateFlow()

    /**
     * Start the embedded Tor daemon via OrbotHelper (tor-android).
     * OrbotHelper.init() registers a broadcast receiver that fires when the
     * proxy is ready — we combine that with port polling so callers get a
     * clean StateFlow<TorState> to observe.
     *
     * Falls back to polling-only if OrbotHelper.isOrbotInstalled() returns
     * true (user has external Orbot) — in that case the daemon may already
     * be running on 9050.
     */
    fun startTor(context: Context) {
        Logger.info(TAG, "Starting embedded Tor daemon via OrbotHelper...")
        _torState.value = TorState.STARTING

        try {
            val oh = OrbotHelper.get(context)

            // init() starts the embedded Tor binary bundled by tor-android.
            // It binds to the OrbotService running in the :tor process declared
            // in AndroidManifest.xml. If Orbot (external) is also installed,
            // OrbotHelper prefers the external daemon — which is fine, same port.
            oh.init()

            // Poll the SOCKS5 port until ready, up to 90 seconds.
            // The embedded Tor binary typically bootstraps in 15-45 seconds on
            // a fresh network connection. Log every attempt for debugging.
            scope.launch {
                val ready = waitForProxy(timeoutSeconds = 90)
                if (ready) {
                    Logger.info(TAG, "Tor SOCKS5 proxy ready — TorState -> READY")
                    _torState.value = TorState.READY
                    // Register hidden service and update the stored onion address
                    registerHiddenService { onionAddress ->
                        scope.launch {
                            onAddressCallback?.invoke(onionAddress)
                        }
                    }
                } else {
                    Logger.warn(TAG, "Tor proxy not ready after 90s — TorState -> FAILED")
                    _torState.value = TorState.FAILED
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "OrbotHelper.init() threw: ${e.message}")
            _torState.value = TorState.FAILED
        }
    }

    /**
     * Poll 127.0.0.1:9050 until a TCP connection succeeds (proxy accepting)
     * or until timeoutSeconds elapses. Each attempt logs at DEBUG level so
     * the in-app log viewer shows bootstrap progress without spamming INFO.
     */
    suspend fun waitForProxy(timeoutSeconds: Int = 30): Boolean =
        withContext(Dispatchers.IO) {
            Logger.info(TAG, "Polling $PROXY_HOST:$SOCKS_PORT for Tor proxy readiness...")
            for (attempt in 1..timeoutSeconds) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(PROXY_HOST, SOCKS_PORT), 1000)
                        Logger.info(TAG, "Tor SOCKS5 proxy is accepting connections (attempt $attempt)")
                        return@withContext true
                    }
                } catch (e: Exception) {
                    Logger.debug(TAG, "Poll attempt $attempt/$timeoutSeconds: ${e.message}")
                }
                delay(1000)
            }
            Logger.warn(TAG, "Proxy not ready after $timeoutSeconds seconds")
            false
        }

    /**
     * Verify we are actually routing through Tor by fetching check.torproject.org
     * through the SOCKS5 proxy. Returns Pair(isTor, statusMessage).
     */
    suspend fun checkTorConnection(): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            Logger.info(TAG, "Verifying Tor routing via check.torproject.org...")
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, SOCKS_PORT))
                val client = OkHttpClient.Builder()
                    .proxy(proxy)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://check.torproject.org/")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val isTor = body.contains("Congratulations. This browser is configured to use Tor.")
                    val detail = if (isTor) "Routed securely via Tor!" else "Proxy responded but not Tor-routed"
                    Logger.info(TAG, "Tor check complete — isTor=$isTor")
                    Pair(isTor, detail)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Tor check failed: ${e.message}")
                Pair(false, "Proxy unreachable: ${e.message}")
            }
        }

    /**
     * Register an ephemeral Tor v3 hidden service for this node's mesh listener.
     * Called automatically after the SOCKS5 proxy is confirmed ready.
     *
     * Uses jtorctl to open a control connection to Tor on port 9051,
     * then issues ADD_ONION to create an ephemeral v3 hidden service
     * pointing at our local TCP listener on 9999.
     *
     * The returned .onion address is stored via the callback so the
     * repository can persist it and include it in QR codes and handshakes.
     *
     * Why ephemeral (DETACH flag omitted): the hidden service exists only
     * while this Tor instance is running. On next start a new address is
     * generated. This is intentional for now — persistent hidden services
     * require storing the private key, which is a future hardening task.
     */
    suspend fun registerHiddenService(onAddressReady: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            Logger.info(TAG, "Registering ephemeral Tor hidden service on port 9999...")
            try {
                // Raw TCP socket — bypasses OkHttp and network_security_config.
                // Port 9051 is Tor's control port (localhost only, not a network call).
                val controlSocket = Socket(PROXY_HOST, 9051)
                
                // Workaround to access protected sendAndWaitForResponse
                val conn = object : net.freehaven.tor.control.TorControlConnection(controlSocket) {
                    @Throws(Exception::class)
                    fun sendRaw(cmd: String, expectedResp: String): List<net.freehaven.tor.control.TorControlConnection.ReplyLine> {
                        return this.sendAndWaitForResponse(cmd, expectedResp)
                    }
                }
                conn.authenticate(byteArrayOf())

                // Raw ADD_ONION command — works across all jtorctl versions
                // Response lines look like:
                //   250-ServiceID=<base32hostname>
                //   250 OK
                val responseLines = conn.sendRaw(
                    "ADD_ONION NEW:ED25519-V3 Port=9999,127.0.0.1:9999\r\n",
                    "250"
                )

                val serviceId = responseLines.asSequence().mapNotNull { lineObj ->
                    try {
                        val msgField = lineObj.javaClass.getDeclaredField("msg")
                        msgField.isAccessible = true
                        msgField.get(lineObj) as? String
                    } catch (e: Exception) {
                        null
                    }
                }
                .firstOrNull { it.startsWith("ServiceID=") }
                ?.substringAfter("ServiceID=")
                ?.trim()

                if (serviceId != null) {
                    val onionAddress = "$serviceId.onion"
                    Logger.info(TAG, "Hidden service registered: $onionAddress")
                    onAddressReady(onionAddress)
                } else {
                    Logger.error(TAG, "ADD_ONION response missing ServiceID. Raw response: $responseLines")
                }

                controlSocket.close()
            } catch (e: Exception) {
                Logger.error(TAG, "Hidden service registration failed: ${e.message}")
            }
        }
}
