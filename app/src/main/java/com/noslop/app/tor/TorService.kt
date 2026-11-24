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
     * Called from the playback sampler whenever a player's buffer advances.
     * Cheap by design — no allocation — because it runs on every sample of every visible video.
     * Clears any active blocked message once progress is confirmed.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun noteMediaProgress() {
        if (_torBlockedMessage.value != null) {
            _torBlockedMessage.value = null
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
        // R4: Android refused to start Tor while the app was in the background; nothing will make it
        // READY until the app is foregrounded, so callers fail fast (their work goes to the outbox).
        if (startBlockedInBackground && _torState.value == TorState.IDLE) return false
        val ok = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            _torState.first { it == TorState.READY || it == TorState.FAILED }
        }
        val ready = ok == TorState.READY
        return ready
    }

    // Unmanaged coroutine scope is fine here — TorService is a process-lifetime singleton.
    // It is initialised once in NoSlopApp.onCreate() and never torn down independently.
    // E1: uncaught exceptions are logged (AppScopes.exceptionHandler) instead of ending the process.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + com.noslop.app.util.AppScopes.exceptionHandler)

    /** NOSLOP_TOR_SINGLE_START_V1: guards the check-and-claim at the start of [startTor]. */
    private val startLock = Any()

    // FIX: Initial state is IDLE (not STARTING) so that the first startTor() call
    // always proceeds rather than hitting the idempotency guard and returning early.
    private val _torState = MutableStateFlow(TorState.IDLE)
    val torState: StateFlow<TorState> = _torState.asStateFlow()

    private var bootstrapJob: kotlinx.coroutines.Job? = null
    private var networkCallbackRegistered = false
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null
    private var currentPrivateKeyB64: String? = null
    private var currentBurnablePrivateKeyB64: String? = null
    var currentBurnableOnionAddress: String? = null
    var onBurnableAddressCallback: ((String) -> Unit)? = null
    
    // Store active service IDs to allow unregistering
    private var activeMainServiceId: String? = null
    private var activeBurnableServiceId: String? = null

    /** True after Android refused to start the Tor service from the background (cleared on the next start). */
    @Volatile var startBlockedInBackground: Boolean = false
        private set

    // --- NOSLOP_TOR_BOUND_SERVICE_V1 --- (docs/TECHNICAL_REFERENCE.md §16.60)
    // tor-android's TorService is meant to be bound (its Javadoc: "first bind to this using bindService"). Only
    // started, it was stopped by Android about a minute after the app left the screen ("Stopping service due to
    // app idle"), and its teardown aborted the whole app (2026-11-24 14:17:27: libtor.so "pthread_mutex_lock called
    // on a destroyed mutex" in tor_cond_wait; Tor frees its configuration while worker threads still wait). Halting
    // Tor ourselves crashed the same way (14:38:49). With a BIND_AUTO_CREATE binding held, Android's idle stop
    // leaves the service running; the cached app is frozen (no CPU) or killed outright, neither of which crashes.
    // Only stopTor (an explicit restart, a restore) unbinds.
    private var torServiceConnection: android.content.ServiceConnection? = null

    private fun bindTorService(context: Context) {
        synchronized(startLock) {
            if (torServiceConnection != null) return
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    Logger.debug(TAG, "Bound to the Tor service: Android's idle stop will not destroy it")
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    Logger.warn(TAG, "The Tor service's process connection was lost")
                }
            }
            try {
                val intent = android.content.Intent(context, org.torproject.jni.TorService::class.java)
                if (context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                    torServiceConnection = conn
                } else {
                    Logger.warn(TAG, "bindService to the Tor service returned false; it stays only started")
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Binding to the Tor service failed: ${e.message}")
            }
        }
    }

    private fun unbindTorService(context: Context) {
        val conn = synchronized(startLock) { torServiceConnection.also { torServiceConnection = null } } ?: return
        try {
            context.applicationContext.unbindService(conn)
        } catch (e: Exception) {
            Logger.debug(TAG, "Unbinding the Tor service: ${e.message}")
        }
    }

    // --- NOSLOP_TOR_OWN_PORTS_V1 --- (TorPortOwnership, docs/TECHNICAL_REFERENCE.md §16.55)
    // Something answering on SOCKS_PORT is not proof that it is our Tor: on 2026-11-17 the Play build
    // sent everything through the GitHub build's Tor. Our daemon proves itself by answering on our
    // private control socket or by broadcasting ON; evidence older than the current start does not count.
    /** When the current daemon was launched. */
    @Volatile private var daemonStartedAtMs: Long = 0L
    /** When our own daemon last broadcast ON (tor-android sends it after its control connection and a first circuit). */
    @Volatile private var lastOwnStatusOnAtMs: Long = 0L

    private fun ownDaemonConfirmed(): Boolean = TorPortOwnership.ownDaemonConfirmed(
        daemonStartedAtMs = daemonStartedAtMs,
        lastControlOpenAtMs = TorControlChannel.lastOpenedAtMs,
        lastOwnStatusOnAtMs = lastOwnStatusOnAtMs
    )

    /** Refuses the Tor on SOCKS_PORT: FAILED stops every Tor-routed request and mesh send, and the user is told. */
    private fun refuseForeignTor(reason: String) {
        Logger.error(
            TAG,
            "SECURITY: the Tor answering on $PROXY_HOST:$SOCKS_PORT is not NoSlop's own daemon ($reason). " +
                "Refusing to use it: another app holds NoSlop's Tor port."
        )
        _torState.value = TorState.FAILED
        setTorStatusMessage(
            com.noslop.app.util.LanguageManager.translate("Tor could not connect: another app is using NoSlop's Tor port {port}. Close that app or restart your phone, then tap Retry.")
                .replace("{port}", SOCKS_PORT.toString())
        )
    }

    /** What [waitForBootstrap] found. */
    private enum class BootstrapOutcome {
        /** Our daemon reached 100% and its SOCKS listener is ours (or could not be read; see the log). */
        COMPLETE,
        /** Not proven within the time; the caller may fall back to an end-to-end routing check. */
        INCOMPLETE,
        /** Our daemon answered and reports no SOCKS listener on our port: whatever answers there is not ours. */
        FOREIGN_SOCKS
    }

    /**
     * R4: hidden services added with ADD_ONION live inside one Tor process. When that process stops,
     * they are gone, so the "already active" bookkeeping must be forgotten — otherwise the restarted
     * daemon never gets ADD_ONION and this node's .onion stays unreachable until the app is killed.
     */
    internal fun forgetRegisteredHiddenServices(reason: String) {
        if (activeMainServiceId != null || activeBurnableServiceId != null) {
            Logger.info(TAG, "Forgetting registered hidden services ($reason); they will be re-registered on the next READY")
        }
        activeMainServiceId = null
        activeBurnableServiceId = null
    }

    /** Test/diagnostic view of the bookkeeping that decides whether ADD_ONION is skipped. */
    internal fun registeredServiceIds(): Pair<String?, String?> = activeMainServiceId to activeBurnableServiceId


    private val torStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val status = intent?.getStringExtra(org.torproject.jni.TorService.EXTRA_STATUS)
            // NOSLOP_TOR_OWN_PORTS_V1: tor-android addresses its status to our package; one naming another
            // package is not about our daemon.
            val sender = intent?.getStringExtra(org.torproject.jni.TorService.EXTRA_SERVICE_PACKAGE_NAME)
            val ourPackage = (appContext ?: context)?.packageName
            if (ourPackage != null && !TorPortOwnership.isOwnStatusBroadcast(sender, ourPackage)) {
                Logger.warn(TAG, "Ignoring a Tor status broadcast ($status) from another app: $sender")
                return
            }
            Logger.info(TAG, "Tor daemon status broadcast: $status")
            when (status) {
                org.torproject.jni.TorService.STATUS_ON -> {
                    lastOwnStatusOnAtMs = System.currentTimeMillis()
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
                    lastOwnStatusOnAtMs = 0L
                    forgetRegisteredHiddenServices("Tor daemon OFF")
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

    fun stopTor(context: Context) {
        Logger.info(TAG, "Stopping Tor daemon...")
        bootstrapJob?.cancel()
        _torState.value = TorState.IDLE
        forgetRegisteredHiddenServices("stopTor")
        try {
            TorControlChannel.open(connectTimeoutMs = 800, readTimeoutMs = 800)?.use { ch ->
                ch.send("SIGNAL HALT")
            }
        } catch (e: Exception) {
            Logger.debug(TAG, "SIGNAL HALT on stopTor was ignored or closed: ${e.message}")
        }

        // NOSLOP_TOR_BOUND_SERVICE_V1: a bound service is not destroyed by stopService alone.
        unbindTorService(context)
        try {
            val stopIntent = android.content.Intent(context, org.torproject.jni.TorService::class.java).apply {
                action = "org.torproject.android.intent.action.STOP"
            }
            context.stopService(stopIntent)
        } catch (e: Exception) {
            Logger.debug(TAG, "Stop TorService intent failed: ${e.message}")
        }

        try {
            context.unregisterReceiver(torStatusReceiver)
        } catch (e: Exception) {
            Logger.debug(TAG, "torStatusReceiver unregister notice: ${e.message}")
        }

        if (networkCallbackRegistered) {
            try {
                val cm = (appContext ?: context).getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                networkCallback?.let { cm?.unregisterNetworkCallback(it) }
                networkCallback = null
                networkCallbackRegistered = false
            } catch (e: Exception) {
                Logger.debug(TAG, "networkCallback unregister notice: ${e.message}")
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
            Logger.info(TAG, "Force restart requested. Terminating Tor daemon and purging stale cache...")
            stopTor(context)
            // Wait up to 2 seconds for Tor process and port 9050 to fully close
            var waitCount = 0
            while (waitCount < 10) {
                val stillOpen = try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(PROXY_HOST, SOCKS_PORT), 150)
                        true
                    }
                } catch (_: Exception) { false }
                if (!stillOpen) break
                try { Thread.sleep(200) } catch (_: Exception) {}
                waitCount++
            }

            try {
                val torrcDir = org.torproject.jni.TorService.getTorrc(context).parentFile
                if (torrcDir != null && torrcDir.exists()) {
                    val dataDir = java.io.File(torrcDir, "data")
                    if (dataDir.exists()) {
                        dataDir.listFiles()?.forEach { file ->
                            // Keep hidden service keys, purge cached consensus, certs and lock files
                            if (!file.name.startsWith("hs_") && file.name != "hostname") {
                                file.deleteRecursively()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to clear Tor data directory: ${e.message}")
            }
        }

        // NOSLOP_TOR_SINGLE_START_V1: the state is checked and STARTING claimed in one step. Checked and set
        // separately, three callers arriving together (device log 2026-11-21, 17:29:05.901-.903) all saw IDLE,
        // and each wrote the torrc, launched the daemon and started a bootstrap job.
        val previousState: TorState? = synchronized(startLock) {
            val current = _torState.value
            if (!forceRestart && (current == TorState.READY || current == TorState.STARTING || current == TorState.PROXY_READY)) {
                null
            } else {
                _torState.value = TorState.STARTING
                current
            }
        }
        if (previousState == null) {
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

        Logger.info(TAG, "Starting embedded Tor daemon via native Intent (previous state=$previousState)...")
        // A daemon being (re)started has no hidden services yet.
        forgetRegisteredHiddenServices("daemon start")
        daemonStartedAtMs = System.currentTimeMillis() // NOSLOP_TOR_OWN_PORTS_V1
        currentPrivateKeyB64 = privateKeyB64
        currentBurnablePrivateKeyB64 = burnablePrivateKeyB64
        
        bootstrapJob?.cancel()

        appContext = context.applicationContext
        writeTorrc(context)

        // Register network callback once to automatically restart Tor when network connectivity returns
        if (!networkCallbackRegistered) {
            try {
                val cm = (appContext ?: context).getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        val state = _torState.value
                        // R4: a Tor started while offline parks in PROXY_READY ("waiting for network") and
                        // nothing promoted it later, so every mesh send was refused. Re-check bootstrap now.
                        if (state == TorState.PROXY_READY && bootstrapJob?.isActive != true) {
                            Logger.info(TAG, "Network became available while Tor was PROXY_READY. Re-checking bootstrap...")
                            confirmBootstrapThenPromote()
                            return
                        }
                        if ((state == TorState.FAILED || state == TorState.IDLE) && bootstrapJob?.isActive != true) {
                            Logger.info(TAG, "Network became available while Tor was in state $state. Triggering auto-recovery...")
                            scope.launch {
                                delay(1000L)
                                val curState = _torState.value
                                val targetCtx = appContext ?: context
                                if ((curState == TorState.FAILED || curState == TorState.IDLE) && bootstrapJob?.isActive != true) {
                                    startTor(targetCtx, currentPrivateKeyB64, currentBurnablePrivateKeyB64, forceRestart = false)
                                }
                            }
                        }
                    }
                }
                cm?.registerDefaultNetworkCallback(callback)
                networkCallback = callback
                networkCallbackRegistered = true
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to register network callback: ${e.message}")
            }
        }

        // Register for status broadcasts
        try {
            val filter = android.content.IntentFilter(org.torproject.jni.TorService.ACTION_STATUS)
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                torStatusReceiver,
                filter,
                // NOSLOP_TOR_OWN_PORTS_V1: our daemon runs in this app, so its broadcasts come from our own
                // uid. Exported, any app could send a fake OFF (Tor marked FAILED) or ON.
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
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
            // NOSLOP_TOR_BOUND_SERVICE_V1: when Tor's thread ended by itself (FAILED), its stopSelf() could not destroy
            // the service while we held a binding, and Tor only starts in the service's onCreate: release the binding
            // so a stopped service is re-created. (A forced restart has already unbound in stopTor.)
            if (previousState == TorState.FAILED || previousState == TorState.IDLE) unbindTorService(context)
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
                Logger.warn(TAG, "TorService could not be started while backgrounded — leaving in IDLE until app foregrounded")
                startBlockedInBackground = true
                _torState.value = TorState.IDLE
                return
            }
            startBlockedInBackground = false
            bindTorService(context) // NOSLOP_TOR_BOUND_SERVICE_V1

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
                    val bootstrap = waitForBootstrap(timeoutSeconds = 120)
                    if (bootstrap == BootstrapOutcome.FOREIGN_SOCKS) {
                        refuseForeignTor("NoSlop's daemon reports no SOCKS listener on that port")
                    } else if (bootstrap == BootstrapOutcome.COMPLETE) {
                        if (_torState.value != TorState.READY) {
                            _torState.value = TorState.READY
                            Logger.info(TAG, "Tor circuits established. Promoting state to READY.")
                            setTorStatusMessage(null)

                            // P0-3: Verify TCP control port is NOT open on loopback
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(PROXY_HOST, Constants.TOR_CONTROL_PORT), 400)
                                    Logger.error(TAG, "SECURITY ALERT: Unauthenticated TCP control port ${Constants.TOR_CONTROL_PORT} is accessible on loopback!")
                                }
                            } catch (_: Exception) {
                                // Expected: TCP control port should be closed
                            }

                            triggerRegistration()
                        }
                    } else {
                        val (isTor, _) = checkTorConnection()
                        if (isTor && !ownDaemonConfirmed()) {
                            // NOSLOP_TOR_OWN_PORTS_V1: a Tor routes, but nothing shows it is ours. A daemon that
                            // cannot bind its SOCKS port exits before its control socket appears, so this is
                            // exactly what another app's Tor on our port looks like. A later ON from our own
                            // daemon still promotes (confirmBootstrapThenPromote).
                            refuseForeignTor("a Tor routes there, but NoSlop's daemon never answered its control socket or reported ON")
                        } else if (isTor) {
                            _torState.value = TorState.READY
                            Logger.info(TAG, "Tor connectivity verified. Promoting state to READY.")
                            setTorStatusMessage(null)
                            triggerRegistration()
                        } else if (!isNetworkAvailable(context)) {
                            Logger.info(TAG, "No internet connection detected. Tor daemon is idle waiting for network.")
                            setTorStatusMessage("Waiting for internet connection...")
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

    /**
     * NOSLOP_HUB_CREATOR_HOSTING_V1 — the creator (burnable) onion is published by the linked Home HUB.
     * Separate from [skipHiddenServiceRegistration] (the personal onion, which a linked Hub always
     * publishes): the phone keeps publishing the creator onion until the Hub reports that it holds it,
     * and takes it back when the Hub gives it up ([setCreatorHostedByHub]). Before this switch, linking
     * a Hub took the creator onion off the network, because the Hub never received the creator identity.
     */
    @Volatile
    var skipCreatorHiddenServiceRegistration: Boolean = false

    /**
     * NOSLOP_HUB_CREATOR_HOSTING_V1 — hands the creator onion to the Hub ([hosted] = true: withdrawn
     * here) or takes it back ([hosted] = false: published here again with [burnablePrivateKeyB64]).
     */
    fun setCreatorHostedByHub(hosted: Boolean, burnablePrivateKeyB64: String?) {
        skipCreatorHiddenServiceRegistration = hosted
        scope.launch {
            if (hosted) {
                val serviceId = activeBurnableServiceId
                if (serviceId != null) {
                    Logger.info(TAG, "Creator onion is now published by the Home HUB; withdrawing it here.")
                    unregisterHiddenService(serviceId)
                    activeBurnableServiceId = null
                }
            } else if (burnablePrivateKeyB64 != null) {
                currentBurnablePrivateKeyB64 = burnablePrivateKeyB64
                if (_torState.value == TorState.READY) {
                    Logger.info(TAG, "Creator onion is published here again (the Home HUB no longer holds it).")
                    triggerRegistration()
                }
            }
        }
    }

    private fun triggerRegistration() {
        scope.launch {
            if (skipHiddenServiceRegistration) {
                Logger.info(TAG, "Skipping hidden service registration (Hub connected mode). Using Tor strictly as an outbound SOCKS5 proxy.")
            } else if (currentPrivateKeyB64 != null) {
                val pubBytes = com.noslop.app.crypto.CryptoService.getPublicKeyFromPrivateKey(currentPrivateKeyB64!!)
                val expectedServiceId = com.noslop.app.crypto.CryptoService.deriveOnionAddress(pubBytes).removeSuffix(".onion")
                if (activeMainServiceId != expectedServiceId) {
                    if (activeMainServiceId != null) {
                        Logger.info(TAG, "Unregistering stale hidden service $activeMainServiceId before re-registering with correct key.")
                        unregisterHiddenService(activeMainServiceId!!)
                        activeMainServiceId = null
                    }
                    delay(1000)
                    registerHiddenService(currentPrivateKeyB64) { onionAddress ->
                        onAddressCallback?.invoke(onionAddress)
                    }
                } else {
                    Logger.info(TAG, "Main hidden service $activeMainServiceId is already active. Skipping re-registration.")
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
            
            if (skipCreatorHiddenServiceRegistration) {
                Logger.info(TAG, "Skipping creator hidden service registration: the Home HUB publishes the creator onion.")
            } else if (currentBurnablePrivateKeyB64 != null) {
                val pubBytes = com.noslop.app.crypto.CryptoService.getPublicKeyFromPrivateKey(currentBurnablePrivateKeyB64!!)
                val expectedBurnableId = com.noslop.app.crypto.CryptoService.deriveOnionAddress(pubBytes).removeSuffix(".onion")
                if (activeBurnableServiceId != expectedBurnableId) {
                    if (activeBurnableServiceId != null) {
                        unregisterHiddenService(activeBurnableServiceId!!)
                        activeBurnableServiceId = null
                    }
                    delay(500)
                    registerHiddenService(currentBurnablePrivateKeyB64) { onionAddress ->
                        currentBurnableOnionAddress = onionAddress
                        onBurnableAddressCallback?.invoke(onionAddress)
                    }
                } else {
                    Logger.info(TAG, "Burnable hidden service $activeBurnableServiceId is already active. Skipping re-registration.")
                }
            }
        }
    }

    /**
     * R5: asks Tor to fetch the current descriptor of [onionAddress] from the hidden-service directories
     * (control command HSFETCH). Tor answers at once and fetches in the background; the next connection
     * uses the fresh introduction points. Only while READY; failures are logged, never thrown.
     */
    fun refreshOnionDescriptor(onionAddress: String) {
        if (_torState.value != TorState.READY) return
        val serviceId = onionAddress.removeSuffix(".onion").lowercase()
        if (!ONION_V3_SERVICE_ID.matches(serviceId)) return
        scope.launch {
            try {
                val channel = TorControlChannel.open(connectTimeoutMs = 1000, readTimeoutMs = 3000) ?: run {
                    Logger.debug(TAG, "HSFETCH skipped: control channel unavailable")
                    return@launch
                }
                channel.use { ch ->
                    ch.send("HSFETCH $serviceId")
                    val reply = ch.readLine()
                    if (reply != null && reply.startsWith("250")) {
                        Logger.info(TAG, "HSFETCH requested for ${serviceId.take(6)}...${serviceId.takeLast(4)}.onion")
                    } else {
                        Logger.warn(TAG, "HSFETCH for ${serviceId.take(6)}... was refused: $reply")
                    }
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "HSFETCH failed: ${e.message}")
            }
        }
    }

    /** A v3 onion service id: 56 base32 characters. */
    private val ONION_V3_SERVICE_ID = Regex("^[a-z2-7]{56}$")

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
            // NOSLOP_HUB_CREATOR_HOSTING_V1: the creator onion stays until the Hub holds it.
            if (skipCreatorHiddenServiceRegistration && activeBurnableServiceId != null) {
                unregisterHiddenService(activeBurnableServiceId!!)
                activeBurnableServiceId = null
            }
        }
    }

    suspend fun unregisterBurnableHiddenService() {
        val serviceId = activeBurnableServiceId
        if (serviceId != null) {
            unregisterHiddenService(serviceId)
            activeBurnableServiceId = null
            currentBurnablePrivateKeyB64 = null
            currentBurnableOnionAddress = null
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

            // Content (incl. HTTPTunnelPort 0) lives in TorControlChannel.buildTorrc
            // so it can be unit-tested.
            val content = TorControlChannel.buildTorrc(SOCKS_PORT, TorControlChannel.torrcLines())
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
        if (bootstrapConfirmJob?.isActive == true || bootstrapJob?.isActive == true) return
        bootstrapConfirmJob = scope.launch {
            val bootstrap = waitForBootstrap(timeoutSeconds = 120)
            if (bootstrap == BootstrapOutcome.FOREIGN_SOCKS) {
                refuseForeignTor("NoSlop's daemon reports no SOCKS listener on that port")
                return@launch
            }
            if (bootstrap == BootstrapOutcome.COMPLETE) {
                if (_torState.value != TorState.READY) {
                    _torState.value = TorState.READY
                    Logger.info(TAG, "Tor bootstrap confirmed. Promoting state to READY.")
                    setTorStatusMessage(null)
                    triggerRegistration()
                }
                return@launch
            }
            val (isTor, _) = checkTorConnection()
            if (isTor && !ownDaemonConfirmed()) {
                refuseForeignTor("a Tor routes there, but NoSlop's daemon never answered its control socket or reported ON")
            } else if (isTor) {
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
     * Diagnostic for NOSLOP_HTTPTUNNEL_OFF_V1: log which proxy listeners tor
     * actually opened, so a device log confirms the HTTP tunnel is off (expect
     * `250-net/listeners/httptunnel=` with an empty value). Best-effort only.
     */
    private fun logProxyListeners(ch: TorControlChannel.Channel): List<String> {
        // NOSLOP_TOR_OWN_PORTS_V1: the socks reply is returned so the caller can check the port is ours.
        var socksReply: List<String> = emptyList()
        try {
            for (key in listOf("net/listeners/socks", "net/listeners/httptunnel")) {
                ch.send("GETINFO $key")
                // Reply is "250-key=value" then "250 OK"; bounded, and skips the unread tail of the
                // bootstrap-phase reply that used to shift every answer by one (TorPortOwnership.readGetInfoReply).
                val parts = TorPortOwnership.readGetInfoReply(key) { ch.readLine() }
                Logger.info(TAG, "Tor listeners", parts.joinToString(" | "))
                if (key == "net/listeners/socks") socksReply = parts
            }
        } catch (e: Exception) {
            Logger.debug(TAG, "Listener diagnostics failed: ${e.message}")
        }
        return socksReply
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
    private suspend fun waitForBootstrap(timeoutSeconds: Int = 120): BootstrapOutcome =
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
                                    val socksReply = logProxyListeners(ch)
                                    // NOSLOP_TOR_OWN_PORTS_V1
                                    when (TorPortOwnership.socksListenerOwnership(socksReply, SOCKS_PORT)) {
                                        TorPortOwnership.Ownership.OURS ->
                                            Logger.info(TAG, "Tor SOCKS listener $PROXY_HOST:$SOCKS_PORT is NoSlop's own daemon")
                                        TorPortOwnership.Ownership.NOT_OURS ->
                                            return@withContext BootstrapOutcome.FOREIGN_SOCKS
                                        TorPortOwnership.Ownership.UNKNOWN ->
                                            Logger.warn(TAG, "Could not read NoSlop's daemon's SOCKS listeners; ownership of port $SOCKS_PORT not confirmed")
                                    }
                                    return@withContext BootstrapOutcome.COMPLETE
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
                    return@withContext BootstrapOutcome.INCOMPLETE
                }

                delay(1000)
            }
            Logger.warn(
                TAG,
                "Tor did not reach 100% bootstrap within ${timeoutSeconds}s. " +
                    "Last phase: ${lastBootstrapPhase ?: "control interface never answered"}"
            )
            BootstrapOutcome.INCOMPLETE
        }

    /**
     * Wait for the Tor control channel to be ready.
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
            if (_torState.value == TorState.READY || _torState.value == TorState.PROXY_READY) {
                return@withContext true
            }
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
                    .connectTimeout(18, TimeUnit.SECONDS)
                    .readTimeout(18, TimeUnit.SECONDS)
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
                    if (privateKeyB64 != null) {
                        val pubBytes = com.noslop.app.crypto.CryptoService.getPublicKeyFromPrivateKey(privateKeyB64)
                        val derived = com.noslop.app.crypto.CryptoService.deriveOnionAddress(pubBytes)
                        val serviceId = derived.removeSuffix(".onion")
                        if (privateKeyB64 == currentPrivateKeyB64) {
                            activeMainServiceId = serviceId
                        } else {
                            activeBurnableServiceId = serviceId
                        }
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
