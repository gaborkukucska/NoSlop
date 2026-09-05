// FILE: app/src/main/java/com/noslop/app/tor/TorService.kt
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
import kotlinx.coroutines.flow.first  // NOSLOP_TOR_CIRCUIT_V1
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
    val SOCKS_PORT = Constants.TOR_SOCKS_PORT
    const val PROXY_HOST = "127.0.0.1"

    var onAddressCallback: ((String) -> Unit)? = null

    // --- NOSLOP_TOR_CIRCUIT_V1 ---
    // Plain-language status for the UI. Null means nothing to report. This
    // exists so a Tor problem is stated to the user rather than silently
    // worked around — there is no non-Tor path to fall back to by design.
    private val _torBlockedMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val torBlockedMessage: kotlinx.coroutines.flow.StateFlow<String?> = _torBlockedMessage

    fun setTorStatusMessage(message: String?) {
        _torBlockedMessage.value = message
    }

    /**
     * NOSLOP_TOR_CIRCUIT_V1
     *
     * Ask Tor for a fresh set of circuits (SIGNAL NEWNYM).
     *
     * Why this matters: googlevideo URLs are IP-locked to the exit that
     * resolved them, and large exits are routinely blocked by Google. In the
     * captured log every one of 27 stream URLs carried ip=185.220.101.15 and
     * only one video ever played — a blocked exit, not a broken app.
     *
     * A new circuit means a new exit, which is very likely not blocked. This is
     * the privacy-preserving answer to the problem: change route, never leave
     * Tor.
     *
     * NEWNYM is rate-limited by Tor itself, so callers should not spam it.
     *
     * --- NOSLOP_NEWNYM_COOLDOWN_V1 ---
     * That instruction was advice, not a guarantee, and the callers did not
     * follow it: the 13:42 capture shows eighteen rotations in sixty-three
     * seconds, fired independently by ~10 concurrent stream resolves. NEWNYM
     * is process-wide — it discards the circuit the visible video is streaming
     * through, which is precisely why several slides sat at bufPos=0 for
     * twenty-plus seconds while the resolver "helpfully" rotated underneath
     * them.
     *
     * The gate below is now the guarantee. A rotation is a shared, destructive
     * resource: one at a time, and not more often than once every
     * [NEWNYM_MIN_INTERVAL_MS]. A caller that is refused gets `false` and
     * should treat it as "this route is what you have — try something else",
     * not as an error.
     */
    private val newnymMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var lastNewnymAtMs = 0L

    /** Protect the Tor daemon and active circuits from rotation storms.
     *  Rebuilding Tor circuits takes time; rotating faster than 90s overwhelms the daemon. */
    private const val NEWNYM_MIN_INTERVAL_MS = 90_000L

    // --- NOSLOP_ADAPTIVE_ROTATION_V1 ---
    // Minimum 75s even when idle to prevent rapid circuit churn that invalidates caches.
    private const val NEWNYM_IDLE_INTERVAL_MS = 75_000L

    /** How recently the buffer must have advanced to count as "streaming". */
    private const val MEDIA_ACTIVE_WINDOW_MS = 10_000L

    // --- NOSLOP_COOPERATIVE_ROTATION_V1 ---
    // A rotation performed by ANYONE moves EVERYONE onto a new exit — NEWNYM
    // is process-wide. So when three concurrent resolves are all refused by
    // the same gated exit and all ask to rotate, the two that lose the race
    // are still on a fresh circuit a moment later. Reporting `false` to them
    // and letting them read it as "nothing changed, give up" threw away
    // exactly the retry that would have worked.
    //
    // Within this window, "someone else just rotated" is as good as "I
    // rotated" and the caller should proceed.
    private const val CIRCUIT_CONSIDERED_FRESH_MS = 30_000L

    // --- NOSLOP_CIRCUIT_GENERATION_V1 ---
    // Monotonic count of ACTUAL exit changes. googlevideo signs a stream URL to
    // the IP that asked for it, so anything resolved under generation N is
    // worthless once the process is on generation N+1. Consumers compare the
    // generation stamped on a cached result against this value instead of
    // trusting the URL's own `expire=` deadline, which says nothing about which
    // route the URL is bound to.
    //
    // Incremented ONLY where a NEWNYM actually succeeded — not on the
    // cooperative "someone else rotated recently, reporting success" path,
    // where no rotation occurs and the sibling that did rotate has already
    // bumped it.
    @Volatile
    private var _circuitGeneration = 0L

    val circuitGeneration: Long get() = _circuitGeneration

    @Volatile
    private var lastMediaProgressAtMs = 0L

    /**
     * Called from the playback sampler whenever a player's buffer advances.
     * Cheap by design — a timestamp write, no allocation — because it runs on
     * every sample of every visible video.
     */
    fun noteMediaProgress() {
        lastMediaProgressAtMs = System.currentTimeMillis()
    }

    private fun mediaIsStreaming(): Boolean =
        System.currentTimeMillis() - lastMediaProgressAtMs < MEDIA_ACTIVE_WINDOW_MS

    suspend fun requestNewCircuit(): Boolean {
        if (!newnymMutex.tryLock()) {
            Logger.info(TAG, "Skipping NEWNYM — another rotation is already in flight")
            return false
        }
        try {
            // --- NOSLOP_ADAPTIVE_ROTATION_V1 ---
            val streaming = mediaIsStreaming()
            val requiredIntervalMs =
                if (streaming) NEWNYM_MIN_INTERVAL_MS else NEWNYM_IDLE_INTERVAL_MS
            val sinceMs = System.currentTimeMillis() - lastNewnymAtMs
            if (lastNewnymAtMs != 0L && sinceMs < requiredIntervalMs) {
                // --- NOSLOP_COOPERATIVE_ROTATION_V1 ---
                // The caller wants to know whether it is on a fresh exit, not
                // whether it personally issued the NEWNYM. A rotation seconds
                // ago — by a sibling resolve refused on the same gated exit —
                // has already given it one.
                if (!streaming && sinceMs < CIRCUIT_CONSIDERED_FRESH_MS) {
                    Logger.info(
                        TAG,
                        "Not rotating — another caller rotated ${sinceMs / 1000}s ago, so this " +
                            "circuit is already fresh. Reporting success so the caller retries " +
                            "on it instead of giving up."
                    )
                    return true
                }
                val why = if (streaming) {
                    "because a video is streaming right now and rotating would kill its circuit."
                } else {
                    "even with nothing streaming."
                }
                Logger.info(
                    TAG,
                    "Skipping NEWNYM — rotated ${sinceMs / 1000}s ago, minimum interval is " +
                        "${requiredIntervalMs / 1000}s $why"
                )
                return false
            }
            val ok = doRequestNewCircuit()
            if (ok) {
                lastNewnymAtMs = System.currentTimeMillis()
                // NOSLOP_CIRCUIT_GENERATION_V1 — everything resolved on the old
                // exit is now unusable. Bump before returning so a caller that
                // re-resolves immediately stamps the new generation.
                _circuitGeneration++
                Logger.info(TAG, "Circuit rotated — generation is now ${_circuitGeneration}")
            }
            return ok
        } finally {
            newnymMutex.unlock()
        }
    }

    private suspend fun doRequestNewCircuit(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val channel = TorControlChannel.open() ?: run {
                Logger.warn(TAG, "NEWNYM: control channel unavailable")
                return@withContext false
            }
            channel.use { ch ->
                ch.send("SIGNAL NEWNYM")
                val resp = ch.readLine()
                val ok = resp != null && resp.startsWith("250")
                Logger.info(TAG, "SIGNAL NEWNYM -> $resp")
                ok
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "requestNewCircuit failed: ${e.message}")
            false
        }
    }

    /**
     * NOSLOP_TOR_CIRCUIT_V1
     *
     * Suspend until Tor is READY, or give up after [timeoutMs].
     * Callers use this instead of firing requests at a proxy that is not up —
     * the log showed work dispatched ~40s before the SOCKS port was confirmed
     * accepting connections.
     */
    suspend fun awaitReady(timeoutMs: Long = 90_000L): Boolean {
        if (_torState.value == TorState.READY) return true
        val ok = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            _torState.first { it == TorState.READY || it == TorState.FAILED }
        }
        val ready = ok == TorState.READY
        return ready
    }

    // Unmanaged coroutine scope is fine here — TorService is a process-lifetime singleton.
    // It is initialised once in NoSlopApp.onCreate() and never torn down independently.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // FIX: Initial state is IDLE (not STARTING) so that the first startTor() call
    // always proceeds rather than hitting the idempotency guard and returning early.
    private val _torState = MutableStateFlow(TorState.IDLE)
    val torState: StateFlow<TorState> = _torState.asStateFlow()

    private var bootstrapJob: kotlinx.coroutines.Job? = null
    private var currentPrivateKeyB64: String? = null
    private var currentBurnablePrivateKeyB64: String? = null
    var currentBurnableOnionAddress: String? = null
    var onBurnableAddressCallback: ((String) -> Unit)? = null
    
    // Store active service IDs to allow unregistering
    private var activeMainServiceId: String? = null
    private var activeBurnableServiceId: String? = null


    private val torStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val status = intent?.getStringExtra(org.torproject.jni.TorService.EXTRA_STATUS)
            Logger.info(TAG, "Tor daemon status broadcast: $status")
            when (status) {
                org.torproject.jni.TorService.STATUS_ON -> {
                    // --- NOSLOP_BOOTSTRAP_TRUTH_V1 ---
                    // STATUS_ON means the daemon PROCESS is up. It says nothing
                    // about circuits, and the old code's "Circuits built" was a
                    // claim the app had not earned. Promoting to READY here is
                    // what let ~40 concurrent requests pile onto a Tor that was
                    // still bootstrapping; every one then sat on a SOCKS CONNECT
                    // until it timed out, onion peers included.
                    if (_torState.value != TorState.READY) {
                        _torState.value = TorState.PROXY_READY
                        Logger.info(TAG, "Tor daemon is ON — confirming circuit bootstrap before use")
                        confirmBootstrapThenPromote()
                    }
                }
                org.torproject.jni.TorService.STATUS_OFF -> {
                    if (_torState.value != TorState.STARTING) {
                        _torState.value = TorState.FAILED
                    }
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
    fun startTor(
        context: Context,
        privateKeyB64: String? = null,
        burnablePrivateKeyB64: String? = null,
        forceRestart: Boolean = false
    ) {
        if (forceRestart) {
            Logger.info(TAG, "Force restart requested. Resetting Tor bootstrap state and data...")
            bootstrapJob?.cancel()
            _torState.value = TorState.IDLE
            try {
                // Delete Tor data directory on force restart to clear corrupt consensus or stuck states
                val torrcDir = org.torproject.jni.TorService.getTorrc(context).parentFile
                if (torrcDir != null && torrcDir.exists()) {
                    torrcDir.listFiles()?.forEach { if (it.name != "torrc") it.deleteRecursively() }
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to clear Tor data directory: ${e.message}")
            }
        } else if (_torState.value == TorState.READY || _torState.value == TorState.STARTING || _torState.value == TorState.PROXY_READY) {
            Logger.info(TAG, "Tor already in state ${_torState.value}. Skipping redundant start.")
            val keyChanged = privateKeyB64 != null && privateKeyB64 != currentPrivateKeyB64
            currentPrivateKeyB64 = privateKeyB64
            currentBurnablePrivateKeyB64 = burnablePrivateKeyB64
            if (keyChanged && _torState.value == TorState.READY) {
                Logger.info(TAG, "Identity key changed while Tor is READY. Re-registering hidden service with correct key.")
                triggerRegistration()
            }
            return
        }

        Logger.info(TAG, "Starting embedded Tor daemon via native Intent (previous state=${_torState.value})...")
        _torState.value = TorState.STARTING
        currentPrivateKeyB64 = privateKeyB64
        currentBurnablePrivateKeyB64 = burnablePrivateKeyB64
        
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
            
            // --- NOSLOP_TOR_MIX_V1 ---
            // Android 8+ refuses startService() from the background, and the
            // old code swallowed the resulting IllegalStateException and then
            // polled for sixty seconds against a Tor that had never launched.
            // org.torproject.jni.TorService calls startForeground() itself, so
            // startForegroundService() is the supported way to launch it from
            // the background.
            var serviceStarted = false
            try {
                context.startService(intent)
                serviceStarted = true
            } catch (e: IllegalStateException) {
                Logger.warn(TAG, "startService refused (app backgrounded): ${e.message}")
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                    serviceStarted = true
                    Logger.info(TAG, "Started TorService as a foreground service instead")
                } catch (e2: Exception) {
                    Logger.error(TAG, "startForegroundService also failed: ${e2.message}")
                }
            } catch (e: Exception) {
                Logger.error(TAG, "startService failed: ${e.message}")
            }

            if (!serviceStarted) {
                // Nothing is coming. Fail fast instead of polling a port that
                // cannot open; the caller's retry loop will try again later.
                Logger.warn(TAG, "TorService could not be started at all — skipping the readiness poll")
                _torState.value = TorState.FAILED
                return
            }

            // Unified self-healing bootstrap loop: wait for proxy port, then wait for circuit bootstrap
            bootstrapJob = scope.launch {
                val proxyReady = waitForProxy(timeoutSeconds = 30)
                if (proxyReady) {
                    _torState.value = TorState.PROXY_READY
                    Logger.info(TAG, "Tor SOCKS5 proxy port is open. Awaiting circuit bootstrap...")

                    // --- NOSLOP_BOOTSTRAP_TRUTH_V1 ---
                    // `|| _torState.value == READY` let the unverified ON
                    // broadcast satisfy this branch too, so neither path ever
                    // required actual proof. 30s was also too short for a cold
                    // Tor on mobile, which meant the timeout branch was being
                    // reached and then papered over.
                    val bootstrapped = waitForBootstrap(timeoutSeconds = 120)
                    if (bootstrapped) {
                        if (_torState.value != TorState.READY) {
                            _torState.value = TorState.READY
                            Logger.info(TAG, "Tor circuits established. Promoting state to READY.")
                            setTorStatusMessage(null)
                            triggerRegistration()
                        }
                    } else {
                        // Check if connectivity check passes as fallback
                        val (isTor, _) = checkTorConnection()
                        if (isTor) {
                            _torState.value = TorState.READY
                            Logger.info(TAG, "Tor connectivity verified. Promoting state to READY.")
                            setTorStatusMessage(null)
                            triggerRegistration()
                        } else {
                            Logger.warn(
                                TAG,
                                "Tor circuits failed to establish within timeout. " +
                                    "Last bootstrap phase: ${lastBootstrapPhase ?: "control port never answered"}"
                            )
                            setTorStatusMessage("Tor could not finish connecting. Check the device's network.")
                            _torState.value = TorState.FAILED
                        }
                    }
                } else {
                    Logger.warn(TAG, "Tor proxy failed to start on $PROXY_HOST:$SOCKS_PORT.")
                    _torState.value = TorState.FAILED
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to start TorService intent: ${e.message}")
            _torState.value = TorState.FAILED
        }
    }

    var skipHiddenServiceRegistration: Boolean = false

    private fun triggerRegistration() {
        scope.launch {
            if (skipHiddenServiceRegistration) {
                Logger.info(TAG, "Skipping hidden service registration (Hub connected mode). Using Tor strictly as an outbound SOCKS5 proxy.")
            } else if (currentPrivateKeyB64 != null) {
                // FIX: Clean up any stale hidden service from a previous session or
                // a prior registration with the wrong key (e.g. ephemeral NEW key
                // registered before identity was loaded during onboarding).
                if (activeMainServiceId != null) {
                    Logger.info(TAG, "Unregistering stale hidden service $activeMainServiceId before re-registering with correct key.")
                    unregisterHiddenService(activeMainServiceId!!)
                    activeMainServiceId = null
                }
                // Small delay to ensure ControlPort is fully receptive
                delay(3000)
                registerHiddenService(currentPrivateKeyB64) { onionAddress ->
                    onAddressCallback?.invoke(onionAddress)
                }
            } else {
                if (activeMainServiceId != null) {
                    Logger.info(TAG, "Unregistering stale hidden service $activeMainServiceId before re-registering ephemeral key.")
                    unregisterHiddenService(activeMainServiceId!!)
                    activeMainServiceId = null
                }
                delay(3000)
                registerHiddenService(null) { onionAddress ->
                    onAddressCallback?.invoke(onionAddress)
                }
            }
            
            if (currentBurnablePrivateKeyB64 != null) {
                // FIX: Also clean up stale burnable service
                if (activeBurnableServiceId != null) {
                    unregisterHiddenService(activeBurnableServiceId!!)
                    activeBurnableServiceId = null
                }
                // Short delay between control port commands
                delay(1000)
                registerHiddenService(currentBurnablePrivateKeyB64) { onionAddress ->
                    currentBurnableOnionAddress = onionAddress
                    onBurnableAddressCallback?.invoke(onionAddress)
                }
            }
        }
    }

    /**
     * Unregisters the currently active hidden services.
     * This is critical when transitioning to a Hub, to prevent descriptor flapping
     * where both the Hub and the mobile app publish the same .onion address.
     */
    fun unregisterHiddenServices() {
        scope.launch {
            if (activeMainServiceId != null) {
                unregisterHiddenService(activeMainServiceId!!)
                activeMainServiceId = null
            }
            if (activeBurnableServiceId != null) {
                unregisterHiddenService(activeBurnableServiceId!!)
                activeBurnableServiceId = null
            }
        }
    }

    private suspend fun unregisterHiddenService(serviceId: String) = withContext(Dispatchers.IO) {
        Logger.info(TAG, "Unregistering hidden service $serviceId...")
        try {
            val channel = TorControlChannel.open() ?: run {
                Logger.warn(TAG, "Control channel unavailable — cannot unregister $serviceId")
                return@withContext
            }
            channel.use { ch ->
                ch.send("DEL_ONION $serviceId")
                var line: String?
                while (ch.readLine().also { line = it } != null) {
                    Logger.debug(TAG, "DEL_ONION response: $line")
                    if (line!!.startsWith("250 ") || line!!.startsWith("5")) break
                }
            }
            Logger.info(TAG, "Successfully unregistered hidden service $serviceId")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to unregister hidden service $serviceId: ${e.message}")
        }
    }

    /**
     * Updates the private key and re-registers the hidden service.
     * Used when transitioning from onboarding to an active identity.
     */
    fun updateKeyAndRegister(privateKeyB64: String, burnablePrivateKeyB64: String? = null) {
        currentPrivateKeyB64 = privateKeyB64
        currentBurnablePrivateKeyB64 = burnablePrivateKeyB64
        if (_torState.value == TorState.READY) {
            triggerRegistration()
        }
    }

    /**
     * Write a custom torrc exposing a control interface for ephemeral hidden
     * service registration.
     *
     * --- NOSLOP_CONTROL_SOCKET_V1 ---
     * This used to emit `ControlPort 9051` with `CookieAuthentication 0`, which
     * handed an unauthenticated Tor control connection to any other app on the
     * device — loopback is not app-private on Android. The control interface is
     * now a unix socket inside our own filesDir, protected by file permissions.
     * See TorControlChannel for the full reasoning and the rollback switch.
     */
    private fun writeTorrc(context: Context) {
        try {
            TorControlChannel.configure(context)

            val torrcFile = org.torproject.jni.TorService.getTorrc(context)
            // Ensure parent directory exists
            torrcFile.parentFile?.mkdirs()

            val content = buildString {
                append("SocksPort $SOCKS_PORT\n")
                append(TorControlChannel.torrcLines())
                // Left at 0 deliberately: tor-android's own control connection
                // authenticates with empty credentials, and enabling cookie auth
                // globally would break it. Access control is the socket's file
                // permissions, not a cookie.
                append("CookieAuthentication 0\n")
            }
            java.io.FileWriter(torrcFile).use { it.write(content) }
            Logger.info(TAG, "Custom torrc written to ${torrcFile.absolutePath}")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to write torrc: ${e.message}")
        }
    }

    /**
     * Poll the Tor ControlPort until Tor reports 100% bootstrap progress.
     */
    // --- NOSLOP_BOOTSTRAP_TRUTH_V1 ---
    // Last phase string Tor reported, kept so a timeout can say WHERE it got
    // stuck instead of only that it did.
    @Volatile
    private var lastBootstrapPhase: String? = null

    /** Guards against several callers confirming bootstrap at the same time. */
    private var bootstrapConfirmJob: kotlinx.coroutines.Job? = null

    /**
     * NOSLOP_BOOTSTRAP_TRUTH_V1
     *
     * Called when the daemon reports ON. Confirms with Tor itself that
     * bootstrap actually finished before anything is allowed to use the proxy.
     * Falls back to a real end-to-end routing check, which is also proof.
     */
    private fun confirmBootstrapThenPromote() {
        if (bootstrapConfirmJob?.isActive == true) return
        bootstrapConfirmJob = scope.launch {
            val bootstrapped = waitForBootstrap(timeoutSeconds = 120)
            if (bootstrapped) {
                if (_torState.value != TorState.READY) {
                    _torState.value = TorState.READY
                    Logger.info(TAG, "Tor bootstrap confirmed. Promoting state to READY.")
                    setTorStatusMessage(null)
                    triggerRegistration()
                }
                return@launch
            }
            val (isTor, _) = checkTorConnection()
            if (isTor) {
                _torState.value = TorState.READY
                Logger.info(TAG, "Tor routing verified end-to-end. Promoting state to READY.")
                setTorStatusMessage(null)
                triggerRegistration()
            } else {
                Logger.warn(
                    TAG,
                    "Tor daemon is running but bootstrap never completed. " +
                        "Last phase: ${lastBootstrapPhase ?: "unknown"}"
                )
                setTorStatusMessage("Tor is still connecting — no traffic can be sent yet.")
            }
        }
    }

    /**
     * Poll the Tor ControlPort until Tor reports 100% bootstrap progress.
     *
     * --- NOSLOP_BOOTSTRAP_TRUTH_V1 ---
     * This used to begin with `if (_torState.value == READY) return true`,
     * which meant that once the (unverified) ON broadcast had flipped READY,
     * this function returned true on its very next poll without ever asking
     * Tor a single question. "Tor bootstrap reached 100%" appears in none of
     * the captured logs for exactly that reason: the real check was never
     * allowed to run. It asks now.
     */
    private suspend fun waitForBootstrap(timeoutSeconds: Int = 120): Boolean =
        withContext(Dispatchers.IO) {
            var lastLogged: String? = null
            for (attempt in 1..timeoutSeconds) {
                try {
                    TorControlChannel.open(connectTimeoutMs = 1000, readTimeoutMs = 1500)?.use { ch ->
                        run {
                            ch.send("GETINFO status/bootstrap-phase")
                            val line = ch.readLine()
                            if (line != null) {
                                lastBootstrapPhase = line
                                // Report progress as it moves, not every second.
                                // A stuck bootstrap is then a single obvious line
                                // in the log rather than an absence of lines.
                                val progress = Regex("PROGRESS=(\\d+)").find(line)?.groupValues?.get(1)
                                if (progress != null && progress != lastLogged) {
                                    Logger.info(TAG, "Tor bootstrap $progress% | $line")
                                    lastLogged = progress
                                }
                                if (line.contains("PROGRESS=100")) {
                                    Logger.info(TAG, "Tor bootstrap reached 100%: $line")
                                    return@withContext true
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (attempt == 1) {
                        Logger.debug(TAG, "Bootstrap poll could not reach the control port: ${e.message}")
                    }
                }

                // --- NOSLOP_BOOTSTRAP_BAILOUT_V1 ---
                // If the control interface has not answered once in the first 20
                // seconds, it is not going to. Polling the remaining 100s buys
                // nothing and actively hurts: the caller's real-routing fallback,
                // which CAN promote the state to READY, only runs after this
                // returns — and in the captured log something restarted Tor every
                // ~20s, so the 120s never elapsed and the fallback never got a
                // turn. The app sat in PROXY_READY refusing every request while
                // Tor was in fact working perfectly.
                if (attempt >= 20 && !TorControlChannel.hasEverOpened()) {
                    Logger.warn(
                        TAG,
                        "Control interface never answered in ${attempt}s — abandoning bootstrap polling",
                        "falling back to an end-to-end routing check"
                    )
                    return@withContext false
                }

                delay(1000)
            }
            Logger.warn(
                TAG,
                "Tor did not reach 100% bootstrap within ${timeoutSeconds}s. " +
                    "Last phase: ${lastBootstrapPhase ?: "control interface never answered"}"
            )
            false
        }

    /**
     * Wait for the ControlPort (9051) to be ready.
     */
    private suspend fun waitForControlPort(timeoutSeconds: Int = 10): Boolean =
        withContext(Dispatchers.IO) {
            for (attempt in 1..timeoutSeconds) {
                val ch = TorControlChannel.open(connectTimeoutMs = 500, readTimeoutMs = 1500)
                if (ch != null) {
                    ch.close()
                    return@withContext true
                }
                delay(1000)
            }
            Logger.warn(TAG, "Control interface did not become available within ${timeoutSeconds}s")
            false
        }

    /**
     * Poll 127.0.0.1:9050 until a TCP connection succeeds (proxy accepting)
     * or until timeoutSeconds elapses. Each attempt logs at DEBUG level so
     * the in-app log viewer shows bootstrap progress without spamming INFO.
     */
    suspend fun waitForProxy(timeoutSeconds: Int = 60): Boolean = // FIX: Change signature default to 60
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
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://check.torproject.org/api/ip")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val isTor = body.contains("IsTor\":true") || body.contains("IsTor\": true") || body.contains("Congratulations")
                    val detail = if (isTor) "Routed securely via Tor!" else "Proxy responded but not Tor-routed"
                    Logger.info(TAG, "Tor API check complete — isTor=$isTor")
                    if (isTor) {
                        setTorStatusMessage(null)
                    }
                    Pair(isTor, detail)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Tor API check failed (${e.message}), trying fallback check...")
                try {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, SOCKS_PORT))
                    val client = OkHttpClient.Builder().proxy(proxy).connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
                    val request = Request.Builder().url("https://api.ipify.org?format=json").header("User-Agent", "Mozilla/5.0").build()
                    client.newCall(request).execute().use { response ->
                        val isTor = response.isSuccessful
                        Logger.info(TAG, "Tor fallback check (ipify) complete — isTor=$isTor")
                        if (isTor) {
                            setTorStatusMessage(null)
                        }
                        Pair(isTor, if (isTor) "Routed securely via Tor!" else "Proxy check failed")
                    }
                } catch (e2: Exception) {
                    Logger.warn(TAG, "Tor check fallback failed (${e2.message}).")
                    // Only set error status if Tor was supposed to be fully ready
                    if (_torState.value == TorState.READY) {
                        setTorStatusMessage(
                            "Tor is connected but no traffic is getting through. " +
                                "Check the device's internet connection."
                        )
                    }
                    Pair(false, "Tor connectivity check failed: ${e2.message}")
                }
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
                // Wait for control port to be ready
                waitForControlPort(timeoutSeconds = 10)

                // ADD_ONION can take a while on a slow device, so this channel gets
                // a longer read timeout than the short-lived control commands.
                val channel = TorControlChannel.open(readTimeoutMs = 15000) ?: run {
                    Logger.error(TAG, "Control channel unavailable — hidden service not registered")
                    return@withContext
                }
                val writer = channel.writer
                val reader = channel.reader

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
                val cmd = "ADD_ONION $keyParam Flags=Detach Port=${Constants.MESH_PORT},127.0.0.1:${Constants.MESH_LISTEN_PORT}"
                Logger.info(TAG, "Executing raw HS registration: ADD_ONION *** Flags=Detach Port=${Constants.MESH_PORT},127.0.0.1:${Constants.MESH_LISTEN_PORT}")
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
                    if (privateKeyB64 == currentPrivateKeyB64) {
                        activeMainServiceId = serviceId
                    } else {
                        activeBurnableServiceId = serviceId
                    }
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

                channel.close()
            } catch (e: Exception) {
                Logger.error(TAG, "Hidden service registration failed: ${e.message}")
            }
        }
}
