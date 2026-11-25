// FILE: app/src/main/java/com/noslop/app/net/SshDeployer.kt
package com.noslop.app.net

import com.jcraft.jsch.JSch
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class OverwriteStrategy {
    PROMPT,
    RESET_IDENTITY,
    FULL_WIPE,
    UPDATE_HUB
}

class ExistingDeploymentException(message: String = "Existing HAI-Net deployment found") : Exception(message)

object SshDeployer {
    private const val TAG = "SshDeployer"

    // NOSLOP_SSH_HOSTKEY_V1 — the NAT keepalive used to run on GlobalScope,
    // which is unstructured and uncancellable. It now has an owner.
    private val keepAliveScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob() + com.noslop.app.util.AppScopes.exceptionHandler
    )

    /**
     * Forget the pinned SSH host key for one host, or for every host when
     * [host] is null. Call this from Settings when the user has genuinely
     * rebuilt a Hub machine. Returns true if anything was removed.
     */
    fun clearPinnedHostKey(host: String? = null): Boolean {
        return try {
            val file = java.io.File(
                com.noslop.app.NoSlopApp.repository.context.filesDir,
                "ssh_known_hosts"
            )
            if (!file.exists()) return false
            if (host == null) {
                Logger.info(TAG, "Clearing all pinned SSH host keys")
                file.delete()
            } else {
                val kept = file.readLines().filterNot { it.trim().startsWith("$host ") }
                val changed = kept.size != file.readLines().size
                file.writeText(kept.joinToString("\n").let { if (it.isEmpty()) it else it + "\n" })
                Logger.info(TAG, "Cleared pinned SSH host key for $host", "changed=$changed")
                changed
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to clear pinned host key: ${e.message}")
            false
        }
    }

    // --- NOSLOP_HUB_DEPLOY_DETACHED_V1 helpers ---------------------------------------------------------------

    private fun runDir(runId: String) = "\"${'$'}HOME/.hainet_deploy_runs/$runId\""

    /** Stores the script (from stdin) in an owner-only run folder. No secret is on any command line. */
    internal fun uploadCommand(runId: String): String =
        "umask 077; mkdir -p \"${'$'}HOME/.hainet_deploy_runs\" && chmod 700 \"${'$'}HOME/.hainet_deploy_runs\" && " +
            "mkdir -p ${runDir(runId)} && cat > ${runDir(runId)}/deploy.sh"

    /**
     * Starts the uploaded script detached (setsid, nohup, its own log), one deployment at a time (flock on
     * ~/.hainet_deploy.lock). Prints NOSLOP_DEPLOY_STARTED <pid>, or NOSLOP_DEPLOY_BUSY <run id> when an earlier
     * deployment still holds the lock. Also clears run folders older than two days and any uploaded script
     * older than ten minutes that never started.
     */
    internal fun launchCommand(runId: String): String = """
        RUNS="${'$'}HOME/.hainet_deploy_runs"; D=${runDir(runId)}; LOCK="${'$'}HOME/.hainet_deploy.lock"
        find "${'$'}RUNS" -mindepth 2 -maxdepth 2 -name deploy.sh -mmin +10 -delete 2>/dev/null
        find "${'$'}RUNS" -mindepth 1 -maxdepth 1 -type d ! -name '$runId' -mtime +2 -exec rm -rf {} + 2>/dev/null
        if command -v flock >/dev/null 2>&1; then
          exec 9>"${'$'}LOCK"
          if ! flock -n 9; then echo "NOSLOP_DEPLOY_BUSY ${'$'}(cat "${'$'}RUNS/current" 2>/dev/null)"; exit 0; fi
          exec 9>&-
        fi
        cd "${'$'}HOME"
        setsid nohup bash -c 'exec 9>"${'$'}2"; command -v flock >/dev/null 2>&1 && flock -w 7200 9; export NOSLOP_RUN_DIR="${'$'}1"; bash "${'$'}1/deploy.sh" 9>&-; echo ${'$'}? > "${'$'}1/exit"' _ "${'$'}D" "${'$'}LOCK" > "${'$'}D/log" 2>&1 < /dev/null &
        P=${'$'}!
        echo "${'$'}P" > "${'$'}D/pid"; echo '$runId' > "${'$'}RUNS/current"
        echo "NOSLOP_DEPLOY_STARTED ${'$'}P"
    """.trimIndent()

    /** Prints the run's log from byte [offset] on, until the run's process ends. */
    internal fun followCommand(runId: String, offset: Long): String =
        "D=${runDir(runId)}; P=${'$'}(cat \"${'$'}D/pid\" 2>/dev/null); " +
            "if [ -n \"${'$'}P\" ] && kill -0 \"${'$'}P\" 2>/dev/null; then tail -c +${offset + 1} --pid=\"${'$'}P\" -f \"${'$'}D/log\"; " +
            "else tail -c +${offset + 1} \"${'$'}D/log\"; fi"

    internal fun exitCommand(runId: String): String = "cat ${runDir(runId)}/exit 2>/dev/null"

    internal fun cleanupCommand(runId: String): String =
        "rm -rf ${runDir(runId)}; [ \"${'$'}(cat \"${'$'}HOME/.hainet_deploy_runs/current\" 2>/dev/null)\" = '$runId' ] && " +
            "rm -f \"${'$'}HOME/.hainet_deploy_runs/current\"; true"

    private fun disconnectQuietly(s: com.jcraft.jsch.Session) {
        try { s.disconnect() } catch (e: Exception) { Logger.debug(TAG, "SSH disconnect: ${e.message}") }
    }

    /** Runs [command] and returns its exit status and output (stdout; commands redirect stderr themselves). */
    private suspend fun execCollect(session: com.jcraft.jsch.Session, command: String, stdin: ByteArray? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val ch = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand(command)
            ch.setErrStream(null)
            val input = ch.inputStream
            val out = ch.outputStream
            ch.connect(10000)
            if (stdin != null) { out.write(stdin); out.flush() }
            out.close()
            val text = input.readBytes().toString(Charsets.UTF_8)
            while (!ch.isClosed) kotlinx.coroutines.delay(50)
            val code = ch.exitStatus
            ch.disconnect()
            code to text
        }

    /**
     * Follows a detached run's log to its end, re-establishing the SSH session when it drops (up to 20 failed
     * attempts in a row; any progress resets the count). Returns the run's exit code (-2 when it ended without
     * one) or null when the Hub could not be reached any more, and the session last used.
     */
    private suspend fun followRun(
        first: com.jcraft.jsch.Session,
        reconnect: suspend () -> com.jcraft.jsch.Session,
        runId: String,
        onLog: (String) -> Unit,
        output: StringBuilder?
    ): Pair<Int?, com.jcraft.jsch.Session> = withContext(Dispatchers.IO) {
        var s = first
        var offset = 0L
        var failures = 0
        var carry = ByteArray(0)
        while (true) {
            try {
                if (!s.isConnected) {
                    s = reconnect()
                    Logger.info(TAG, "Reconnected to the Hub to follow deployment $runId at byte $offset")
                    withContext(Dispatchers.Main) { onLog("\n[Reconnected to the Hub; the deployment kept running there.]\n") }
                }
                val ch = s.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(followCommand(runId, offset))
                ch.setErrStream(null)
                val input = ch.inputStream
                ch.connect(10000)
                val buf = ByteArray(4096)
                while (true) {
                    val len = input.read(buf)
                    if (len < 0) break
                    offset += len
                    failures = 0
                    val (text, rest) = decodeUtf8Prefix(carry + buf.copyOf(len))
                    carry = rest
                    if (text.isNotEmpty()) {
                        output?.append(text)
                        Logger.info(TAG, text.trimEnd())
                        withContext(Dispatchers.Main) { onLog(text) }
                    }
                }
                while (!ch.isClosed) kotlinx.coroutines.delay(100)
                val tailStatus = ch.exitStatus
                ch.disconnect()
                if (tailStatus != 0) throw java.io.IOException("following the log ended with status $tailStatus")
                val exit = execCollect(s, exitCommand(runId)).second.trim().toIntOrNull() ?: -2
                return@withContext exit to s
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failures++
                Logger.warn(TAG, "Following deployment $runId: ${e.message} (attempt $failures)")
                if (failures > 20) return@withContext null to s
                disconnectQuietly(s)
                kotlinx.coroutines.delay(minOf(30_000L, 3_000L * failures))
            }
        }
        null to s
    }

    /** Splits [bytes] into the text of its complete UTF-8 characters and the bytes of an incomplete last one. */
    internal fun decodeUtf8Prefix(bytes: ByteArray): Pair<String, ByteArray> {
        var cut = bytes.size
        var i = bytes.size - 1
        var seen = 0
        while (i >= 0 && seen < 4) {
            val b = bytes[i].toInt() and 0xFF
            if (b and 0xC0 != 0x80) { // a lead byte (or ASCII)
                val need = when { b < 0x80 -> 1; b >= 0xF0 -> 4; b >= 0xE0 -> 3; b >= 0xC0 -> 2; else -> 1 }
                if (bytes.size - i < need) cut = i
                break
            }
            i--; seen++
        }
        return String(bytes, 0, cut, Charsets.UTF_8) to bytes.copyOfRange(cut, bytes.size)
    }

    /**
     * NOSLOP_HUB_DEPLOY_KEEPALIVE_V1 (§16.63): the deployment runs over this app's SSH session, and the build on
     * the Hub runs inside that session. When the user switched away, Android froze the app, the session dropped
     * and the build stopped ("Deployment failed (exit code -1)" during "Compiling hainet-core", 2026-11-25). The
     * foreground service now keeps the app running (and the CPU awake) for the whole deployment.
     */
    suspend fun deployHaiNetHub(
        ip: String,
        user: String,
        pass: String,
        sharedFolder: String,
        identity: CryptoService.IdentityKeys?,
        strategy: OverwriteStrategy = OverwriteStrategy.PROMPT,
        onLog: (String) -> Unit = {},
        onHostKeyPrompt: (suspend (host: String, fingerprint: String) -> Boolean)? = null
    ): Result<String> {
        val appContext = try { com.noslop.app.NoSlopApp.repository.context.applicationContext } catch (_: Throwable) { null }
        val keptAlive = appContext != null && com.noslop.app.mesh.NoSlopForegroundService.startKeepAlive(appContext)
        if (keptAlive) Logger.info(TAG, "Keeping NoSlop in the foreground while the Hub is deployed")
        try {
            return deployHaiNetHubInSession(ip, user, pass, sharedFolder, identity, strategy, onLog, onHostKeyPrompt)
        } finally {
            if (keptAlive && appContext != null) com.noslop.app.mesh.NoSlopForegroundService.stopKeepAlive(appContext)
        }
    }

    private suspend fun deployHaiNetHubInSession(
        ip: String,
        user: String,
        pass: String,
        sharedFolder: String,
        identity: CryptoService.IdentityKeys?,
        strategy: OverwriteStrategy = OverwriteStrategy.PROMPT,
        onLog: (String) -> Unit = {},
        // NOSLOP_SSH_HOSTKEY_V1
        // Called on the FIRST connection to a host whose key is not yet pinned.
        // Return true to pin and continue, false to abort. When null, an unpinned
        // host is refused (NOSLOP_SSH_HOSTKEY_V2: no trust-on-first-use). A key that has
        // CHANGED is never routed here — that case is refused unconditionally.
        onHostKeyPrompt: (suspend (host: String, fingerprint: String) -> Boolean)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onLog("Connecting to $user@$ip:22...\n")
            Logger.info(TAG, "Connecting to $user@$ip:22")

            // --- NOSLOP_SSH_HOSTKEY_V1 ---
            // known_hosts lives in app-private internal storage so pins actually
            // survive between deployments. The old location (java.io.tmpdir) is
            // not a stable directory on Android, so nothing was ever pinned.
            val jsch = JSch()
            val knownHostsFile = java.io.File(
                com.noslop.app.NoSlopApp.repository.context.filesDir,
                "ssh_known_hosts"
            )
            if (!knownHostsFile.exists()) knownHostsFile.createNewFile()
            jsch.setKnownHosts(knownHostsFile.absolutePath)

            // --- NOSLOP_SSH_HOSTKEY_V2 ---
            // Both passes run StrictHostKeyChecking=ask. The probe answers no to
            // every host-key question and records it; the pinning pass answers
            // yes only for the fingerprint the user approved. The decision logic
            // and the JSch 0.2.17 behaviour it depends on are in SshHostKeyPolicy.
            fun newSession(ui: com.jcraft.jsch.UserInfo): com.jcraft.jsch.Session {
                val s = jsch.getSession(user, ip, 22)
                s.setPassword(pass)
                s.userInfo = ui
                s.setConfig("StrictHostKeyChecking", "ask")
                s.serverAliveInterval = 0
                return s
            }

            val logSshMessage: (String) -> Unit = { Logger.info(TAG, "SSH Message: $it") }

            var connectionAttempts = 0
            var session: com.jcraft.jsch.Session
            var approvedFingerprint: String? = null

            while (true) {
                val ui: SshHostKeyPolicy.PasswordUserInfo = approvedFingerprint?.let {
                    SshHostKeyPolicy.PinOnceUserInfo(pass, it, logSshMessage)
                } ?: SshHostKeyPolicy.ProbeUserInfo(pass, logSshMessage)
                try {
                    connectionAttempts++
                    session = newSession(ui)

                    session.connect(15000)
                    session.timeout = 0 // Remove socket timeout after connection is established
                    if (ui is SshHostKeyPolicy.PinOnceUserInfo && ui.accepted > 0) {
                        Logger.info(TAG, "Pinned approved SSH host key for $ip", "fingerprint=$approvedFingerprint")
                    }

                    // Start manual keepalive to keep NAT alive without dropping connection on missed replies
                    // We open a dummy channel and write to its stdin to generate client-to-server traffic
                    val dummyChannel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    dummyChannel.setCommand("cat > /dev/null")
                    dummyChannel.connect()
                    val dummyOut = dummyChannel.outputStream

                    val keepAliveSession = session
                    keepAliveScope.launch {
                        while (keepAliveSession.isConnected) {
                            kotlinx.coroutines.delay(10000)
                            try {
                                dummyOut.write(0)
                                dummyOut.flush()
                            } catch (e: Exception) {
                                // Ignore if connection drops
                            }
                        }
                    }

                    break
                } catch (e: Exception) {
                    when (val failure = SshHostKeyPolicy.classifyFailure(e, ui.lastPrompt)) {
                        // --- The key we pinned no longer matches. Never overridable. ---
                        SshHostKeyPolicy.Failure.ChangedKey -> {
                            Logger.error(TAG, "SSH HOST KEY MISMATCH for $ip — refusing to connect")
                            withContext(Dispatchers.Main) {
                                onLog(
                                    "\n[SECURITY] REFUSED: the SSH host key for $ip does not match " +
                                        "the one NoSlop pinned on a previous deployment.\n" +
                                        "This is what an interception attempt looks like. It is also what " +
                                        "a genuine OS reinstall looks like.\n" +
                                        "Nothing was sent. If you rebuilt this machine yourself, clear the " +
                                        "pin from Settings and deploy again.\n"
                                )
                            }
                            return@withContext Result.failure(
                                Exception(
                                    "SSH host key for $ip has changed since the last deployment. " +
                                        "Refusing to send your identity. Clear the stored host key if " +
                                        "you rebuilt this machine."
                                )
                            )
                        }

                        // --- First sight of this host. Confirm, then pin. ---
                        is SshHostKeyPolicy.Failure.UnknownHost -> {
                            if (approvedFingerprint != null) {
                                // The pinning pass was offered a key other than the one
                                // the user approved (swapped between probe and reconnect).
                                Logger.error(
                                    TAG, "SSH host key for $ip changed during confirmation — refusing",
                                    "approved=$approvedFingerprint offered=${failure.fingerprint}"
                                )
                                withContext(Dispatchers.Main) {
                                    onLog(
                                        "\n[SECURITY] REFUSED: $ip presented a different host key " +
                                            "(${failure.fingerprint}) from the one you approved. Nothing was sent.\n"
                                    )
                                }
                                return@withContext Result.failure(
                                    Exception("SSH host key for $ip changed during confirmation")
                                )
                            }
                            val fingerprint = failure.fingerprint
                            Logger.info(TAG, "Unpinned SSH host key for $ip", "fingerprint=$fingerprint")
                            withContext(Dispatchers.Main) {
                                onLog(
                                    "\n[SECURITY] First connection to $ip.\n" +
                                        "Host key fingerprint: $fingerprint\n" +
                                        "Your SSH password and private identity are about to be sent to " +
                                        "whoever holds this key. Compare it against the Hub before trusting it.\n"
                                )
                            }

                            when (SshHostKeyPolicy.decideUnknownHost(ip, fingerprint, onHostKeyPrompt)) {
                                is SshHostKeyPolicy.Decision.Approved -> {
                                    approvedFingerprint = fingerprint
                                    connectionAttempts-- // the probe is not a failed attempt
                                    continue
                                }
                                SshHostKeyPolicy.Decision.Declined -> {
                                    Logger.info(TAG, "User rejected SSH host key for $ip")
                                    withContext(Dispatchers.Main) { onLog("Host key rejected. Deployment cancelled.\n") }
                                    return@withContext Result.failure(Exception("SSH host key rejected by user"))
                                }
                                SshHostKeyPolicy.Decision.NoConfirmationHandler -> {
                                    // Fail closed: this deploy sends the SSH password and the
                                    // private identity, so an unconfirmed key is never trusted
                                    // on first use.
                                    Logger.warn(TAG, "No host-key confirmation handler supplied — refusing unpinned host $ip")
                                    withContext(Dispatchers.Main) {
                                        onLog("Host key could not be confirmed. Deployment cancelled.\n")
                                    }
                                    return@withContext Result.failure(
                                        Exception("SSH host key for $ip is not pinned and could not be confirmed")
                                    )
                                }
                            }
                        }

                        SshHostKeyPolicy.Failure.UnverifiedRejection -> {
                            Logger.error(TAG, "SSH host key for $ip rejected without a fingerprint: ${e.message}")
                            withContext(Dispatchers.Main) {
                                onLog("\n[SECURITY] Could not verify the SSH host key for $ip. Nothing was sent.\n")
                            }
                            return@withContext Result.failure(
                                Exception("SSH host key for $ip could not be verified")
                            )
                        }

                        SshHostKeyPolicy.Failure.NotHostKey -> {
                            if (connectionAttempts >= 3) throw e
                            Logger.warn(TAG, "SSH Connection attempt $connectionAttempts failed: ${e.message}. Retrying in 2s...")
                            withContext(Dispatchers.Main) { onLog("Connection attempt $connectionAttempts failed. Retrying in 2s...\n") }
                            kotlinx.coroutines.delay(2000)
                        }
                    }
                }
            }

            onLog("SSH connected. Preparing deployment...\n")
            Logger.info(TAG, "SSH session established to $ip")

            // Build the hub_config.json payload using JSONObject for safe escaping
            val configJson = JSONObject().apply {
                put("shared_folder", sharedFolder)
                if (identity != null) {
                    val idJson = JSONObject().apply {
                        put("public_key", identity.publicKeyB64)
                        put("private_key", identity.privateKeyB64)
                        put("enc_public_key", identity.encPublicKeyB64)
                        put("enc_private_key", identity.encPrivateKeyB64)
                        put("onion_address", identity.onionAddress)
                        put("display_name", identity.displayName)
                    }
                    put("identity", idJson)
                }
            }

            // Base64-encode the JSON to avoid all shell escaping issues
            val configB64 = android.util.Base64.encodeToString(
                configJson.toString().toByteArray(), 
                android.util.Base64.NO_WRAP
            )
            
            // Base64-encode the password to safely pass to sudo without escaping issues
            val passB64 = android.util.Base64.encodeToString(
                pass.toByteArray(), 
                android.util.Base64.NO_WRAP
            )

            val expandedSeedB64 = identity?.privateKeyB64?.let { com.noslop.app.crypto.CryptoService.getRawEd25519Seed(it)?.removePrefix("ED25519-V3:")?.trim() } ?: ""

            val script = """
                #!/bin/bash
                set -e
                exec 2>&1
                # NOSLOP_HUB_DEPLOY_DETACHED_V1 (§16.64): this script runs detached from a file the app uploaded
                # (owner-only, it holds the sudo password): remove the file now (bash keeps reading it), and keep
                # this run's sudo helpers in its own folder so a second deployment cannot delete them.
                case "${'$'}0" in */.hainet_deploy_runs/*/deploy.sh) rm -f -- "${'$'}0" 2>/dev/null || true ;; esac
                RUN_TMP="${'$'}{NOSLOP_RUN_DIR:-${'$'}(mktemp -d)}"
                # Helpers left behind by an older app version's run that was killed.
                find "${'$'}HOME" -maxdepth 1 -name .hainet_askpass -mmin +60 -delete 2>/dev/null || true
                export GIT_TERMINAL_PROMPT=0
                export GIT_MERGE_AUTOEDIT=no
                export DEBIAN_FRONTEND=noninteractive
                echo '=== HAI-Net Hub Deployment ==='
                echo "Target: ${'$'}(hostname) (${'$'}(uname -m))"
                echo ""
                
                # Setup sudo helper
                export SUDO_PASS=${'$'}(echo "$passB64" | base64 -d)
                
                # Self-healing: remove stray sudo shim from previous deployments
                rm -f "${'$'}HOME/.cargo/bin/sudo"
                
                # Setup transient askpass helper
                # F19: Restrictive default umask for secret creation
                umask 077

                cat << 'EOF_ASKPASS' > "${'$'}RUN_TMP/askpass"
#!/bin/bash
echo "${'$'}SUDO_PASS"
EOF_ASKPASS
                chmod 700 "${'$'}RUN_TMP/askpass"
                export SUDO_ASKPASS="${'$'}RUN_TMP/askpass"
                
                # Setup transient sudo shim for Rust binaries
                mkdir -p "${'$'}RUN_TMP/bin"
                cat << 'EOF_SHIM' > "${'$'}RUN_TMP/bin/sudo"
#!/bin/bash
if [ "${'$'}(id -u)" -eq 0 ]; then
    /usr/bin/sudo "${'$'}@"
else
    /usr/bin/sudo -A "${'$'}@"
fi
EOF_SHIM
                chmod 700 "${'$'}RUN_TMP/bin/sudo"
                export PATH="${'$'}RUN_TMP/bin:${'$'}PATH"
                
                DEPLOY_DIR="${'$'}PWD"
                cleanup() {
                    rm -f "${'$'}RUN_TMP/askpass"
                    rm -rf "${'$'}RUN_TMP/bin"
                    [ -z "${'$'}{NOSLOP_RUN_DIR:-}" ] && rm -rf "${'$'}RUN_TMP"
                    rm -f "${'$'}DEPLOY_DIR/gen_tor.py" "${'$'}DEPLOY_DIR/reset_ident.py" "${'$'}DEPLOY_DIR/hai/hub_config.json" "${'$'}DEPLOY_DIR/hs_ed25519_secret_key"
                    unset SUDO_PASS
                    unset SUDO_ASKPASS
                }
                trap cleanup EXIT
                
                run_sudo() {
                    sudo "${'$'}@"
                }

                # NOSLOP_HUB_CREATOR_HOSTING_V1: hainet-core publishes the creator identity's onion through
                # Tor's control interface (ADD_ONION, held only while the Hub runs). The service user gets
                # Tor's group (control socket and cookie), via a systemd drop-in so no re-login is needed,
                # and a cookie-authenticated loopback ControlPort where Tor's defaults have no control socket.
                ensure_tor_control_access() {
                    TOR_GROUP=${'$'}(getent group debian-tor >/dev/null 2>&1 && echo "debian-tor" || echo "tor")
                    if getent group "${'$'}TOR_GROUP" >/dev/null 2>&1; then
                        run_sudo usermod -aG "${'$'}TOR_GROUP" "${'$'}(id -un)" || true
                        printf '[Service]\nSupplementaryGroups=%s\n' "${'$'}TOR_GROUP" > noslop-tor.conf
                        run_sudo mkdir -p /etc/systemd/system/hainet-core.service.d
                        run_sudo mv noslop-tor.conf /etc/systemd/system/hainet-core.service.d/noslop-tor.conf
                        run_sudo systemctl daemon-reload || true
                    fi
                    run_sudo grep -q '^ControlPort' /etc/tor/torrc || run_sudo bash -c "echo 'ControlPort 127.0.0.1:9051' >> /etc/tor/torrc"
                    run_sudo grep -q '^CookieAuthentication' /etc/tor/torrc || run_sudo bash -c "echo 'CookieAuthentication 1' >> /etc/tor/torrc"
                    run_sudo grep -q '^CookieAuthFileGroupReadable' /etc/tor/torrc || run_sudo bash -c "echo 'CookieAuthFileGroupReadable 1' >> /etc/tor/torrc"
                    echo "  Tor control access for the Hub: group ${'$'}TOR_GROUP"
                }

                setup_tor_hidden_service() {
                    cat << 'PYEOF' > gen_tor.py
import base64, sys, os
os.umask(0o077)
b64_str = "$expandedSeedB64"
if not b64_str:
    sys.exit(0)
try:
    expanded = base64.b64decode(b64_str)
    header = b"== ed25519v1-secret: type0 ==" + bytes([0, 0, 0])
    with open("hs_ed25519_secret_key", "wb") as f:
        f.write(header + expanded)
except Exception as e:
    print("Error generating tor key:", e)
PYEOF
                    python3 gen_tor.py
                    run_sudo rm -rf /var/lib/tor/hainet/
                    run_sudo mkdir -p /var/lib/tor/hainet/
                    run_sudo mv hs_ed25519_secret_key /var/lib/tor/hainet/hs_ed25519_secret_key
                    TOR_USER=${'$'}(id -u debian-tor >/dev/null 2>&1 && echo "debian-tor" || echo "tor")
                    run_sudo chown -R ${'$'}TOR_USER:${'$'}TOR_USER /var/lib/tor/hainet/
                    run_sudo chmod 700 /var/lib/tor/hainet/
                    run_sudo chmod 600 /var/lib/tor/hainet/hs_ed25519_secret_key
                    rm -f gen_tor.py
                }
                
                STRATEGY="${strategy.name}"
                if [ "${'$'}STRATEGY" == "PROMPT" ]; then
                    if [ -f "/etc/systemd/system/hainet-core.service" ] || [ -d "hai" ] || [ -d "${'$'}HOME/.hainet" ]; then
                        echo "EXISTING_DEPLOYMENT_FOUND"
                        exit 99
                    fi
                fi
                
                if [ "${'$'}STRATEGY" == "FULL_WIPE" ]; then
                    echo "Wiping existing deployment..."
                    run_sudo systemctl stop hainet-core.service 2>/dev/null || true
                    run_sudo systemctl disable hainet-core.service 2>/dev/null || true
                    run_sudo rm -f /etc/systemd/system/hainet-core.service
                    rm -rf hai
                    run_sudo rm -rf ~/.hainet /var/lib/hainet /etc/hainet /var/lib/tor/hainet /var/lib/tor/hainet_hidden_service
                fi

                if [ "${'$'}STRATEGY" == "RESET_IDENTITY" ]; then
                    echo "Resetting identity for existing deployment..."
                    run_sudo chown -R "${'$'}(id -un):${'$'}(id -gn)" ~/.hainet 2>/dev/null || true
                    cat << EOF > reset_ident.py
import json, base64, os, sys
os.umask(0o077)
try:
    config_b64 = "$configB64"
    config_json = json.loads(base64.b64decode(config_b64).decode('utf-8'))
    identity = config_json.get('identity')
    if not identity:
        print("No identity found in config")
        sys.exit(0)
    ident_dir = os.path.expanduser('~/.hainet/identity')
    os.makedirs(ident_dir, exist_ok=True)
    
    with open(os.path.join(ident_dir, 'ed25519_pub.b64'), 'w') as f:
        f.write(identity['public_key'])
    with open(os.path.join(ident_dir, 'ed25519_priv.b64'), 'w') as f:
        f.write(identity['private_key'])
    with open(os.path.join(ident_dir, 'x25519_pub.b64'), 'w') as f:
        f.write(identity['enc_public_key'])
    with open(os.path.join(ident_dir, 'x25519_priv.b64'), 'w') as f:
        f.write(identity['enc_private_key'])
    with open(os.path.join(ident_dir, 'onion_address'), 'w') as f:
        f.write(identity['onion_address'])
    with open(os.path.join(ident_dir, 'display_name'), 'w') as f:
        f.write(identity['display_name'])
except Exception as e:
    print("Error resetting identity:", e)
    sys.exit(1)
EOF
                    python3 reset_ident.py
                    rm -f reset_ident.py
                    
                    # Remove the stale auth state so the Web UI auto-initializes QR login
                    # for the new identity instead of showing a passphrase form
                    rm -f ~/.hainet/auth.json
                    
                    setup_tor_hidden_service
                    
                    # Ensure full HiddenService block exists in torrc
                    if ! run_sudo grep -q "HiddenServiceDir.*hainet" /etc/tor/torrc; then
                        run_sudo bash -c "echo '' >> /etc/tor/torrc"
                        run_sudo bash -c "echo '# HAI-Net Hidden Service' >> /etc/tor/torrc"
                        run_sudo bash -c "echo 'HiddenServiceDir /var/lib/tor/hainet/' >> /etc/tor/torrc"
                        run_sudo bash -c "echo 'HiddenServicePort 8080 127.0.0.1:8080' >> /etc/tor/torrc"
                        run_sudo bash -c "echo 'HiddenServicePort 9999 127.0.0.1:9999' >> /etc/tor/torrc"
                    elif ! run_sudo grep -q "HiddenServicePort 9999" /etc/tor/torrc; then
                        run_sudo sed -i '/HiddenServicePort 8080/a HiddenServicePort 9999 127.0.0.1:9999' /etc/tor/torrc
                    fi
                    
                    ensure_tor_control_access
                    run_sudo systemctl restart tor || true
                    run_sudo systemctl restart hainet-core.service || true
                    
                    echo "Identity reset complete!"
                    exit 0
                fi

                if [ "${'$'}STRATEGY" == "UPDATE_HUB" ]; then
                    echo "Updating existing deployment..."
                    if [ ! -d "hai" ]; then
                        echo "No deployment found to update in ${'$'}PWD/hai!"
                        exit 1
                    fi
                    cd hai
                    
                    echo "Pulling latest changes from Git..."
                    git pull || (git fetch origin && git reset --hard origin/master) || true
                    
                    export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                    
                    echo "Building React UI..."
                    if [ -d "hainet-portal" ]; then
                        cd hainet-portal
                        npm install
                        npm run build
                        cd ..
                    fi
                    
                    echo "Building HAI-Net Core..."
                    (while true; do echo "."; sleep 5; done) &
                    KEEPALIVE_PID=${'$'}!
                    
                    set +e
                    cargo build --jobs 1 --release --package hainet-core
                    CARGO_EXIT=${'$'}?
                    set -e
                    
                    kill ${'$'}KEEPALIVE_PID 2>/dev/null || true
                    echo ""
                    
                    if [ ${'$'}CARGO_EXIT -ne 0 ]; then
                        echo "Core build failed with exit code ${'$'}CARGO_EXIT"
                        exit ${'$'}CARGO_EXIT
                    fi
                    
                    ensure_tor_control_access
                    run_sudo systemctl restart tor || true
                    run_sudo systemctl restart hainet-core.service
                    
                    # Ensure full HiddenService block exists in torrc for existing deployments
                    if ! run_sudo grep -q "HiddenServiceDir.*hainet" /etc/tor/torrc; then
                        # Detect which directory holds the key material
                        HS_DIR="/var/lib/tor/hainet/"
                        if run_sudo test -d /var/lib/tor/hainet_hidden_service; then
                            HS_DIR="/var/lib/tor/hainet_hidden_service/"
                        fi
                        run_sudo bash -c "echo '' >> /etc/tor/torrc"
                        run_sudo bash -c "echo '# HAI-Net Hidden Service' >> /etc/tor/torrc"
                        run_sudo bash -c "echo 'HiddenServiceDir ${'$'}HS_DIR' >> /etc/tor/torrc"
                        run_sudo bash -c "echo 'HiddenServicePort 8080 127.0.0.1:8080' >> /etc/tor/torrc"
                        run_sudo bash -c "echo 'HiddenServicePort 9999 127.0.0.1:9999' >> /etc/tor/torrc"
                        run_sudo systemctl restart tor || true
                        echo "Injected full HiddenService block into Tor configuration."
                    elif ! run_sudo grep -q "HiddenServicePort 9999" /etc/tor/torrc; then
                        # Dir exists but port 9999 is missing (legacy deploy)
                        run_sudo sed -i '/HiddenServicePort 8080/a HiddenServicePort 9999 127.0.0.1:9999' /etc/tor/torrc
                        run_sudo systemctl restart tor || true
                        echo "Injected missing port 9999 to existing Tor configuration."
                    fi

                    echo "Hub Update Complete!"
                    exit 0
                fi

                # Ensure Swap Space to prevent OOM during Rust compilation
                echo 'Ensuring sufficient swap space...'
                if command -v free &> /dev/null; then
                    SWAP_MEGS=${'$'}(free -m | awk '/^Swap:/ {print ${'$'}2}')
                    if [ -z "${'$'}SWAP_MEGS" ] || [ "${'$'}SWAP_MEGS" -lt 2048 ]; then
                        echo '  Creating 2GB temporary swap file...'
                        run_sudo fallocate -l 2G /swapfile || run_sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
                        run_sudo chmod 600 /swapfile || true
                        run_sudo mkswap /swapfile || true
                        run_sudo swapon /swapfile || true
                    fi
                fi

                # Step 0: Ensure essential build tools are present
                echo '[STEP 0/5] Checking build prerequisites...'
                # Only what is missing gets installed. Reinstalling a tool the server already has from another
                # source (e.g. Node.js from NodeSource, whose nodejs conflicts with the distribution's npm) can fail.
                MISSING=""
                command -v cc &> /dev/null || MISSING="${'$'}MISSING cc"
                command -v git &> /dev/null || MISSING="${'$'}MISSING git"
                command -v curl &> /dev/null || MISSING="${'$'}MISSING curl"
                command -v cmake &> /dev/null || MISSING="${'$'}MISSING cmake"
                command -v npm &> /dev/null || MISSING="${'$'}MISSING node"
                # hainet-persona's build script needs protoc; openssl-sys needs pkg-config and the OpenSSL headers.
                command -v protoc &> /dev/null || MISSING="${'$'}MISSING protoc"
                command -v pkg-config &> /dev/null || MISSING="${'$'}MISSING pkgconfig"
                pkg-config --exists openssl 2> /dev/null || MISSING="${'$'}MISSING openssl"
                python3 -c 'import ensurepip' &> /dev/null || MISSING="${'$'}MISSING venv"

                # Package names for each missing tool, per package manager (apt, dnf or pacman).
                packages_for() {
                    for TOOL in ${'$'}MISSING; do
                        case "${'$'}1:${'$'}TOOL" in
                            apt:cc) echo build-essential ;;
                            dnf:cc) echo gcc gcc-c++ make ;;
                            pacman:cc) echo base-devel ;;
                            *:git) echo git ;;
                            *:curl) echo curl ;;
                            *:cmake) echo cmake ;;
                            *:node) echo nodejs npm ;;
                            pacman:protoc) echo protobuf ;;
                            *:protoc) echo protobuf-compiler ;;
                            apt:pkgconfig) echo pkg-config ;;
                            dnf:pkgconfig) echo pkgconfig ;;
                            pacman:pkgconfig) echo pkgconf ;;
                            apt:openssl) echo libssl-dev ;;
                            dnf:openssl) echo openssl-devel ;;
                            pacman:openssl) echo openssl ;;
                            apt:venv) echo python3-venv ;;
                            dnf:venv) echo python3 ;;
                            pacman:venv) echo python ;;
                        esac
                    done
                }

                if [ -n "${'$'}MISSING" ]; then
                    if command -v apt-get &> /dev/null; then
                        PKGS=${'$'}(packages_for apt | tr '\n' ' ')
                        echo "  Installing: ${'$'}PKGS"
                        # One broken third-party source (e.g. an expired signing key) must not stop the install:
                        # the distribution's own package lists are usually still fine.
                        if ! run_sudo apt-get update -qq; then
                            echo '  [WARN] apt-get update reported errors (see above); installing from the package lists this server already has.'
                        fi
                        if ! run_sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ${'$'}PKGS; then
                            echo "[ERROR] Could not install: ${'$'}PKGS"
                            echo '        Fix the package sources reported above (or install these by hand), then deploy again.'
                            exit 1
                        fi
                    elif command -v dnf &> /dev/null; then
                        PKGS=${'$'}(packages_for dnf | tr '\n' ' ')
                        echo "  Installing: ${'$'}PKGS"
                        run_sudo dnf install -y ${'$'}PKGS
                    elif command -v pacman &> /dev/null; then
                        PKGS=${'$'}(packages_for pacman | tr '\n' ' ')
                        echo "  Installing: ${'$'}PKGS"
                        run_sudo pacman -Sy --noconfirm ${'$'}PKGS
                    else
                        echo "[ERROR] Unsupported package manager. Please install these by hand:${'$'}MISSING"
                        exit 1
                    fi
                    echo '  Build tools installed.'
                else
                    echo '  Build tools already present.'
                fi
                echo ""
                
                # Step 1: Check for / install Rust
                export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                if ! command -v cargo &> /dev/null; then
                    echo '[STEP 1/5] Rust/Cargo not found. Installing Rust...'
                    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
                    echo '  Rust installed successfully.'
                else
                    echo '[STEP 1/5] Rust/Cargo is already installed.'
                fi
                echo "  cargo: ${'$'}(cargo --version)"
                echo ""
                
                # Step 2: Clone or update the repository
                echo '[STEP 2/5] Cloning/updating HAI-Net repository...'
                if [ -d "hai" ]; then
                    cd hai
                    git pull || { echo '[ERROR] Failed to update repository (merge conflicts?)'; exit 1; }
                    cd ..
                else
                    git clone https://github.com/gaborkukucska/hai.git || { echo '[ERROR] Failed to clone repository'; exit 1; }
                fi
                

                
                echo ""
                
                # Step 3: Write config from base64 payload
                echo '[STEP 3/5] Writing hub configuration...'
                touch hai/hub_config.json
                chmod 600 hai/hub_config.json
                echo "$configB64" | base64 -d > hai/hub_config.json
                echo "  Config written (${'$'}(wc -c < hai/hub_config.json) bytes)"
                
                # Pre-create /etc/hainet and /var/lib/hainet so the installer doesn't fail with permissions errors
                run_sudo mkdir -p /etc/hainet /var/lib/hainet
                run_sudo chown -R "${'$'}(id -un):${'$'}(id -gn)" /etc/hainet /var/lib/hainet
                run_sudo chmod 700 /var/lib/hainet
                echo ""
                
                # Step 4: Run the seed installer
                echo '[STEP 4/5] Running HAI-Net seed installer (this may take several minutes)...'
                cd hai
                
                # Start a background keep-alive loop to prevent NAT idle timeouts during long silent builds
                (while true; do echo "."; sleep 5; done) &
                KEEPALIVE_PID=${'$'}!
                
                # Run the installer
                set +e # Disable exit on error temporarily so we can reliably kill the keepalive
                export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                cargo run --jobs 1 --package hainet-seed --bin hainet-seed -- install --config hub_config.json < /dev/null
                CARGO_EXIT=${'$'}?
                set -e
                
                # Kill the keepalive process
                kill ${'$'}KEEPALIVE_PID 2>/dev/null || true
                echo ""
                
                # Cleanup
                rm -f hub_config.json
                
                if [ ${'$'}CARGO_EXIT -ne 0 ]; then
                    echo "Installer failed with exit code ${'$'}CARGO_EXIT"
                    exit ${'$'}CARGO_EXIT
                fi
                echo ""
                
                # Step 5: Build and Start Core Daemon (which includes Web UI)
                echo '[STEP 5/5] Building React UI and HAI-Net Core...'
                if [ -d "hainet-portal" ]; then
                    cd hainet-portal
                    echo '  Installing NPM dependencies...'
                    npm install
                    echo '  Building React frontend...'
                    npm run build
                    cd ..
                fi
                
                echo '  Building Core daemon...'
                export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                
                # Start a keep-alive loop to prevent NAT timeouts during build/linking
                (while true; do echo "."; sleep 5; done) &
                KEEPALIVE_PID=${'$'}!
                
                set +e
                cargo build --jobs 1 --release --package hainet-core
                CARGO_EXIT=${'$'}?
                set -e
                
                kill ${'$'}KEEPALIVE_PID 2>/dev/null || true
                echo ""
                
                if [ ${'$'}CARGO_EXIT -ne 0 ]; then
                    echo "Core build failed with exit code ${'$'}CARGO_EXIT"
                    exit ${'$'}CARGO_EXIT
                fi
                
                echo '  Setting up systemd service for Core...'
                
                # Ensure existing services are cleanly stopped
                run_sudo systemctl stop hainet-portal.service 2>/dev/null || true
                run_sudo systemctl disable hainet-portal.service 2>/dev/null || true
                run_sudo systemctl stop hainet-core.service 2>/dev/null || true
                run_sudo systemctl disable hainet-core.service 2>/dev/null || true
                run_sudo rm -f /etc/systemd/system/hainet-core.service 2>/dev/null || true
                
                # Create the unit file locally with variable expansion (unquoted EOF)
                cat << EOF > hainet-core.service
[Unit]
Description=HAI-Net Core Daemon
After=network.target

[Service]
Type=simple
User=${'$'}(id -un)
WorkingDirectory=${'$'}PWD
Environment="NODE_ENV=production"
Environment="RUST_LOG=info"
ExecStart=${'$'}PWD/target/release/hainet-core
Restart=always
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
                
                # Securely move it into systemd directory
                run_sudo mv hainet-core.service /etc/systemd/system/hainet-core.service
                run_sudo systemctl daemon-reload
                run_sudo systemctl enable hainet-core.service
                run_sudo systemctl restart hainet-core.service
                
                echo '  Core Daemon is now running on port 8080.'
                
                # --- TOR SETUP INJECTION ---
                echo '  Setting up Tor Hidden Service for Global Connectivity...'
                run_sudo apt-get update -qq >/dev/null 2>&1 || true
                run_sudo apt-get install -y -qq tor >/dev/null 2>&1 || true
                
                setup_tor_hidden_service
                
                echo "${identity?.onionAddress ?: ""}" > onion.txt
                run_sudo mv onion.txt /var/lib/hainet/onion.txt
                run_sudo chown ${'$'}(id -un):${'$'}(id -gn) /var/lib/hainet/onion.txt
                run_sudo chmod 644 /var/lib/hainet/onion.txt
                rm -f gen_tor.py
                
                run_sudo grep -q "HAI-Net Hidden Service" /etc/tor/torrc || (
                    run_sudo bash -c "echo '' >> /etc/tor/torrc"
                    run_sudo bash -c "echo '# HAI-Net Hidden Service' >> /etc/tor/torrc"
                    run_sudo bash -c "echo 'HiddenServiceDir /var/lib/tor/hainet/' >> /etc/tor/torrc"
                    run_sudo bash -c "echo 'HiddenServicePort 8080 127.0.0.1:8080' >> /etc/tor/torrc"
                    run_sudo bash -c "echo 'HiddenServicePort 9999 127.0.0.1:9999' >> /etc/tor/torrc"
                )
                
                # Check for missing port 9999 in existing deployments
                if ! run_sudo grep -q "HiddenServicePort 9999" /etc/tor/torrc; then
                    run_sudo sed -i '/HiddenServicePort 8080/a HiddenServicePort 9999 127.0.0.1:9999' /etc/tor/torrc
                fi
                
                # Clear Tor cache to prevent HSDir corruption from VM clock-skew issues on startup
                run_sudo systemctl stop tor@default || true
                run_sudo systemctl stop tor || true
                run_sudo rm -f /var/lib/tor/cached-*
                run_sudo rm -f /var/lib/tor/state
                run_sudo rm -f /var/lib/tor/sr-state
                run_sudo rm -rf /var/lib/tor/diff-cache
                ensure_tor_control_access
                run_sudo systemctl start tor@default || true
                run_sudo systemctl start tor || true
                run_sudo systemctl restart hainet-core.service || true
                
                echo '  Tor Hidden Service configured!'
                # --- END TOR SETUP ---
                
                echo ""
                echo '=== Deployment Complete ==='
            """.trimIndent()

            // --- NOSLOP_HUB_DEPLOY_DETACHED_V1 --- (docs/TECHNICAL_REFERENCE.md §16.64)
            // The deployment used to run inside this SSH channel: when the connection dropped (the app frozen in
            // the background, Wi-Fi, a sleeping screen) the app reported "exit code -1" although the Hub carried on,
            // and a second attempt then ran alongside the first, which deleted its sudo helper ("sudo: A terminal is
            // required to authenticate", 2026-11-25). Now the script is uploaded (stdin, owner-only file), started
            // detached under a lock, and followed through its log; a dropped connection is re-established and the
            // log picked up where it stopped. The result is the script's own exit code.
            session.serverAliveInterval = 15000 // a dead link is noticed within ~2 min and then reconnected
            session.serverAliveCountMax = 8
            val runId = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
            val reconnect: suspend () -> com.jcraft.jsch.Session = {
                // The host key is pinned by now: a fresh session checks it and refuses a changed one.
                val s = newSession(SshHostKeyPolicy.ProbeUserInfo(pass, logSshMessage))
                s.connect(15000)
                s.serverAliveInterval = 15000
                s.serverAliveCountMax = 8
                s
            }

            val upload = execCollect(session, uploadCommand(runId), script.toByteArray())
            if (upload.first != 0) {
                val errorMsg = "Could not upload the deployment script (exit ${upload.first}): ${upload.second.trim().take(300)}"
                withContext(Dispatchers.Main) { onLog("\n[ERROR] $errorMsg\n") }
                disconnectQuietly(session)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val output = StringBuilder()
            var current = session
            var started = false
            for (attempt in 1..3) {
                val launch = execCollect(current, launchCommand(runId))
                val line = launch.second.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("NOSLOP_DEPLOY_") } ?: ""
                if (line.startsWith("NOSLOP_DEPLOY_STARTED")) { started = true; break }
                val other = line.removePrefix("NOSLOP_DEPLOY_BUSY").trim()
                if (!line.startsWith("NOSLOP_DEPLOY_BUSY") || other.isEmpty() || other == runId) break
                Logger.info(TAG, "An earlier deployment ($other) is still running on the Hub; following it first")
                withContext(Dispatchers.Main) {
                    onLog("\nAn earlier Hub deployment is still running on this machine. Following it until it finishes...\n")
                }
                val (otherExit, s) = followRun(current, reconnect, other, onLog, null)
                current = s
                withContext(Dispatchers.Main) {
                    onLog("\nThe earlier deployment finished (exit ${otherExit ?: "unknown"}). Starting this one...\n\n")
                }
            }
            if (!started) {
                execCollect(current, cleanupCommand(runId))
                val errorMsg = "Could not start the deployment on the Hub"
                withContext(Dispatchers.Main) { onLog("\n[ERROR] $errorMsg\n") }
                disconnectQuietly(current)
                return@withContext Result.failure(Exception(errorMsg))
            }
            val (followedExit, last) = followRun(current, reconnect, runId, onLog, output)
            current = last
            // -1: the app could not reach the Hub any more; -2: the run ended without recording a result.
            val exitStatus = followedExit ?: -1
            if (followedExit != null) {
                try { execCollect(current, cleanupCommand(runId)) } catch (e: Exception) { Logger.warn(TAG, "Could not remove the run folder $runId on the Hub: ${e.message}") }
            } else {
                withContext(Dispatchers.Main) {
                    onLog("\n[ERROR] Lost the connection to the Hub. The deployment keeps running there; deploy again to follow it.\n")
                }
            }
            disconnectQuietly(current)

            Logger.info(TAG, "Deployment finished with exit code: $exitStatus")

            if (exitStatus != 0) {
                if (exitStatus == 99) {
                    val errorMsg = "Existing deployment found. Please resolve via UI."
                    withContext(Dispatchers.Main) { onLog("\n[ERROR] $errorMsg\n") }
                    return@withContext Result.failure(ExistingDeploymentException())
                }
                val errorMsg = "Deployment failed (exit code $exitStatus)"
                withContext(Dispatchers.Main) { onLog("\n[ERROR] $errorMsg\n") }
                return@withContext Result.failure(Exception(errorMsg))
            }

            Result.success(output.toString())
        } catch (e: Exception) {
            Logger.error(TAG, "SSH Deployment failed: ${e.message}")
            withContext(Dispatchers.Main) { onLog("\n[ERROR] ${e.message}\n") }
            Result.failure(e)
        }
    }
}
