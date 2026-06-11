package com.noslop.app.tor

import android.content.Context
import com.noslop.app.debug.Logger
import com.noslop.app.util.Constants
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

// IDLE = process just started, startTor() has not been called yet.
// This is the critical fix: initialising to STARTING caused the guard in
// startTor() to bail out immediately on every cold launch, so the daemon
// was never actually started.
enum class TorState { IDLE, STARTING, PROXY_READY, READY, FAILED }

object TorService {

    private const val TAG = "TOR"
    const val SOCKS_PORT = 9050
    const val PROXY_HOST = "127.0.0.1"

    var onAddressCallback: ((String) -> Unit)? = null

    // Unmanaged coroutine scope is fine here — TorService is a process-lifetime singleton.
    // It is initialised once in NoSlopApp.onCreate() and never torn down independently.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // FIX: Initial state is IDLE (not STARTING) so that the first startTor() call
    // always proceeds rather than hitting the idempotency guard and returning early.
    private val _torState = MutableStateFlow(TorState.IDLE)
    val torState: StateFlow<TorState> = _torState.asStateFlow()

    private var bootstrapJob: kotlinx.coroutines.Job? = null
    private var currentPrivateKeyB64: String? = null

    private val torStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val status = intent?.getStringExtra(org.torproject.jni.TorService.EXTRA_STATUS)
            Logger.info(TAG, "Tor daemon status broadcast: $status")
            when (status) {
                org.torproject.jni.TorService.STATUS_ON -> {
                    if (_torState.value != TorState.READY) {
                        _torState.value = TorState.READY
                        Logger.info(TAG, "Tor is ON — Circuits built")
                        triggerRegistration()
                    }
                }
                org.torproject.jni.TorService.STATUS_OFF -> {
                    _torState.value = TorState.FAILED
                }
                org.torproject.jni.TorService.STATUS_STARTING -> {
                    _torState.value = TorState.STARTING
                }
            }
        }
    }

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
    fun startTor(context: Context, privateKeyB64: String? = null) {
        // FIX: Guard now excludes IDLE — IDLE means "not started yet" and must
        // always proceed. FAILED also now falls through so the Retry button works.
        if (_torState.value == TorState.READY || _torState.value == TorState.STARTING || _torState.value == TorState.PROXY_READY) {
            Logger.info(TAG, "Tor already in state ${_torState.value}. Skipping redundant start.")
            // Still update the key in case it changed (e.g. after onboarding)
            currentPrivateKeyB64 = privateKeyB64
            return
        }

        Logger.info(TAG, "Starting embedded Tor daemon via native Intent (previous state=${_torState.value})...")
        _torState.value = TorState.STARTING
        currentPrivateKeyB64 = privateKeyB64
        
        bootstrapJob?.cancel()

        writeTorrc(context)

        // Register for status broadcasts
        try {
            val filter = android.content.IntentFilter(org.torproject.jni.TorService.ACTION_STATUS)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(torStatusReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(torStatusReceiver, filter)
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to register torStatusReceiver: ${e.message}")
        }

        try {
            val intent = android.content.Intent(context, org.torproject.jni.TorService::class.java)
            intent.action = org.torproject.jni.TorService.ACTION_START
            context.startService(intent)

            // Unified self-healing bootstrap loop
            bootstrapJob = scope.launch {
                // 1. Wait for SOCKS5 proxy to be reachable
                val proxyReady = waitForProxy(timeoutSeconds = 45)
                if (proxyReady) {
                    _torState.value = TorState.PROXY_READY
                    
                    // 2. Continually check for circuit availability via Tor check.
                    // This acts as a fallback for cases where the STATUS_ON broadcast is missed.
                    for (attempt in 1..20) {
                        if (_torState.value == TorState.READY) break
                        
                        val (isTor, _) = checkTorConnection()
                        if (isTor) {
                            Logger.info(TAG, "Self-healing bootstrap: Connectivity verified. Moving to READY.")
                            _torState.value = TorState.READY
                            triggerRegistration()
                            break
                        }
                        delay(5000)
                    }
                    
                    if (_torState.value != TorState.READY) {
                        Logger.warn(TAG, "Tor proxy ready but circuits failed to build after 100s.")
                        _torState.value = TorState.FAILED
                    }
                } else {
                    Logger.warn(TAG, "Tor proxy failed to start.")
                    _torState.value = TorState.FAILED
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to start TorService intent: ${e.message}")
            _torState.value = TorState.FAILED
        }
    }

    private fun triggerRegistration() {
        scope.launch {
            // Small delay to ensure ControlPort is fully receptive
            delay(3000)
            registerHiddenService(currentPrivateKeyB64) { onionAddress ->
                onAddressCallback?.invoke(onionAddress)
            }
        }
    }

    /**
     * Updates the private key and re-registers the hidden service.
     * Used when transitioning from onboarding to an active identity.
     */
    fun updateKeyAndRegister(privateKeyB64: String) {
        currentPrivateKeyB64 = privateKeyB64
        if (_torState.value == TorState.READY) {
            triggerRegistration()
        }
    }

    /**
     * Write a custom torrc to enable ControlPort 9051.
     * Required for ephemeral hidden service registration via jtorctl.
     */
    private fun writeTorrc(context: Context) {
        try {
            val torrcFile = org.torproject.jni.TorService.getTorrc(context)
            // Ensure parent directory exists
            torrcFile.parentFile?.mkdirs()
            
            val content = "ControlPort 9051\nCookieAuthentication 1\n"
            java.io.FileWriter(torrcFile).use { it.write(content) }
            Logger.info(TAG, "Custom torrc written to ${torrcFile.absolutePath}")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to write torrc: ${e.message}")
        }
    }

    /**
     * Wait for the ControlPort (9051) to be ready.
     */
    private suspend fun waitForControlPort(timeoutSeconds: Int = 10): Boolean =
        withContext(Dispatchers.IO) {
            for (attempt in 1..timeoutSeconds) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(PROXY_HOST, 9051), 500)
                        return@withContext true
                    }
                } catch (e: Exception) {
                    delay(1000)
                }
            }
            false
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
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
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
     * Register a persistent or ephemeral Tor v3 hidden service for this node's mesh listener.
     *
     * If privateKeyB64 is provided, uses it to register a persistent address.
     * Otherwise registers a NEW ephemeral address.
     */
    suspend fun registerHiddenService(privateKeyB64: String? = null, onAddressReady: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            Logger.info(TAG, "Registering Tor hidden service on port ${Constants.MESH_PORT} (persistent=${privateKeyB64 != null})...")
            try {
                // Wait for control port 9051 to be ready
                waitForControlPort(timeoutSeconds = 10)

                val controlSocket = Socket(PROXY_HOST, 9051)
                val writer = java.io.PrintWriter(controlSocket.getOutputStream(), true)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(controlSocket.getInputStream()))

                // Authenticate (CookieAuthentication 0 means empty password)
                writer.print("AUTHENTICATE\r\n")
                writer.flush()
                val authResp = reader.readLine()
                Logger.info(TAG, "Control AUTHENTICATE response: $authResp")
                if (authResp == null || !authResp.startsWith("250")) {
                    Logger.error(TAG, "Control port authentication failed: $authResp")
                    controlSocket.close()
                    return@withContext
                }

                // Build key parameter
                val keyParam = if (privateKeyB64 != null) {
                    val rawSeed = com.noslop.app.crypto.CryptoService.getRawEd25519Seed(privateKeyB64)
                    if (rawSeed != null) {
                        "ED25519-V3:$rawSeed"
                    } else {
                        Logger.warn(TAG, "Could not extract raw seed, falling back to NEW key")
                        "NEW:ED25519-V3"
                    }
                } else {
                    "NEW:ED25519-V3"
                }

                // Build and send ADD_ONION command as raw text
                // Syntax: ADD_ONION KeyType:KeyBlob [Flags=Detach] Port=VirtPort[,Target]
                val cmd = "ADD_ONION $keyParam Flags=Detach Port=${Constants.MESH_PORT},127.0.0.1:${Constants.MESH_PORT}"
                Logger.info(TAG, "Executing raw HS registration: ADD_ONION *** Flags=Detach Port=${Constants.MESH_PORT},127.0.0.1:${Constants.MESH_PORT}")
                writer.print("$cmd\r\n")
                writer.flush()

                // Read all response lines (multi-line responses use "250-" prefix, final line is "250 OK")
                val responseLines = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    responseLines.add(line!!)
                    Logger.debug(TAG, "ADD_ONION response line: $line")
                    // Stop on final success (250 OK) or any error (5xx)
                    if (line!!.startsWith("250 ") || line!!.startsWith("5")) break
                }

                Logger.info(TAG, "ADD_ONION full response: $responseLines")

                // Extract ServiceID from response lines like "250-ServiceID=xxxxx"
                val serviceId = responseLines
                    .firstOrNull { it.contains("ServiceID=") }
                    ?.substringAfter("ServiceID=")
                    ?.trim()
                    ?.split(" ")?.first()

                if (serviceId != null) {
                    val onionAddress = "$serviceId.onion"
                    Logger.info(TAG, "Hidden service registered: $onionAddress")
                    onAddressReady(onionAddress)
                } else if (responseLines.any { it.contains("550 Onion address collision") }) {
                    Logger.info(TAG, "Onion address collision: Hidden service already active.")
                    // If we have a private key, we can derive the address to trigger the UI callback
                    if (privateKeyB64 != null) {
                        val pubBytes = android.util.Base64.decode(privateKeyB64, android.util.Base64.DEFAULT)
                        val derived = com.noslop.app.crypto.CryptoService.deriveOnionAddress(pubBytes)
                        Logger.info(TAG, "Derived onion from persistent key: $derived")
                        onAddressReady(derived)
                    }
                } else {
                    Logger.error(TAG, "ADD_ONION response missing ServiceID. Raw response: $responseLines")
                }

                controlSocket.close()
            } catch (e: Exception) {
                Logger.error(TAG, "Hidden service registration failed: ${e.message}")
            }
        }
}
