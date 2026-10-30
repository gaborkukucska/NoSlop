// FILE: app/src/main/java/com/noslop/app/net/SshHostKeyPolicy.kt
package com.noslop.app.net

import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.UserInfo

/**
 * --- NOSLOP_SSH_HOSTKEY_V2 ---
 * Pure host-key decision logic for [SshDeployer], kept free of Android and of
 * any network I/O so it can be unit-tested.
 *
 * Why V2: V1 probed with `StrictHostKeyChecking=yes` and waited for an
 * exception whose message starts with "UnknownHostKey". The bundled JSch
 * (com.github.mwiede:jsch 0.2.17, Session.checkHost) never produces that under
 * "yes": it throws a plain `JSchException("reject HostKey: <host>")` before
 * consulting UserInfo. `UnknownHostKey:` / [JSchUnknownHostKeyException] only
 * appear under "ask" with NO UserInfo set. So a new host fell through to the
 * generic retry path, failed three times, and deploying to a new Hub was broken.
 *
 * Under "ask" with a UserInfo, JSch 0.2.17 calls promptYesNo with:
 *  - unknown host: "The authenticity of host '<h>' can't be established.\n
 *                   <TYPE> key fingerprint is <fp>.\nAre you sure you want to
 *                   continue connecting?"  → on false: "reject HostKey: <h>"
 *  - changed key:  "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! ...
 *                   Do you want to delete the old key and insert the new key?"
 *                   → on false: "HostKey has been changed: <h>"
 * So the probe answers no to everything and records the prompt; the prompt
 * text tells us which case we are in and carries the fingerprint.
 */
internal object SshHostKeyPolicy {

    enum class PromptKind { UNKNOWN_HOST, CHANGED_KEY, OTHER }

    /** Which host-key question JSch asked. */
    fun classifyPrompt(message: String?): PromptKind = when {
        message == null -> PromptKind.OTHER
        message.contains("REMOTE HOST IDENTIFICATION HAS CHANGED") -> PromptKind.CHANGED_KEY
        message.contains("can't be established") && message.contains("key fingerprint is") ->
            PromptKind.UNKNOWN_HOST
        else -> PromptKind.OTHER
    }

    /**
     * The fingerprint from a JSch prompt or UnknownHostKey message, e.g.
     * "SHA256:abc..." or "aa:bb:...". Null if none is present.
     */
    fun parseFingerprint(message: String?): String? {
        if (message == null) return null
        val marker = "key fingerprint is "
        val idx = message.indexOf(marker)
        if (idx < 0) return null
        val fp = message.substring(idx + marker.length)
            .substringBefore('\n')
            .trim()
            .removeSuffix(".")
            .trim()
        return fp.ifEmpty { null }
    }

    sealed class Failure {
        /** The pinned key no longer matches. Never overridable from here. */
        object ChangedKey : Failure()

        /** First sight of this host; ask the user about [fingerprint]. */
        data class UnknownHost(val fingerprint: String) : Failure()

        /** JSch rejected the host key but we could not tell why. Fail closed. */
        object UnverifiedRejection : Failure()

        /** Not a host-key problem (network, auth, ...). Normal retry rules. */
        object NotHostKey : Failure()
    }

    /**
     * Classify a failed connect, using the exception and the promptYesNo text
     * the probe captured (if any).
     */
    fun classifyFailure(e: Throwable, capturedPrompt: String?): Failure {
        val msg = e.message ?: ""
        val kind = classifyPrompt(capturedPrompt)

        if (e is JSchChangedHostKeyException ||
            msg.startsWith("HostKey has been changed") ||
            kind == PromptKind.CHANGED_KEY
        ) return Failure.ChangedKey

        val hostKeyRejected = e is JSchUnknownHostKeyException ||
            msg.startsWith("UnknownHostKey") ||
            msg.startsWith("reject HostKey")
        if (kind == PromptKind.UNKNOWN_HOST || hostKeyRejected) {
            val fp = parseFingerprint(capturedPrompt) ?: parseFingerprint(msg)
            return if (fp != null) Failure.UnknownHost(fp) else Failure.UnverifiedRejection
        }
        return Failure.NotHostKey
    }

    sealed class Decision {
        data class Approved(val fingerprint: String) : Decision()
        object Declined : Decision()
        object NoConfirmationHandler : Decision()
    }

    /**
     * Ask the user about an unknown host. Without a handler there is nobody to
     * ask, and the deploy sends the SSH password and the private identity, so
     * it fails closed rather than trusting on first use.
     */
    suspend fun decideUnknownHost(
        host: String,
        fingerprint: String,
        onHostKeyPrompt: (suspend (host: String, fingerprint: String) -> Boolean)?
    ): Decision {
        val handler = onHostKeyPrompt ?: return Decision.NoConfirmationHandler
        return if (handler(host, fingerprint)) Decision.Approved(fingerprint) else Decision.Declined
    }

    /**
     * Whether a promptYesNo during the pinning reconnect may be answered yes:
     * only the unknown-host question, and only for exactly the fingerprint the
     * user approved. A key swapped between probe and reconnect is refused, and
     * a changed-key warning is never answered yes.
     */
    fun mayAcceptOnPinningPass(message: String?, approvedFingerprint: String): Boolean =
        classifyPrompt(message) == PromptKind.UNKNOWN_HOST &&
            parseFingerprint(message) == approvedFingerprint

    /** Supplies the password; subclasses decide host-key questions. */
    abstract class PasswordUserInfo(
        private val pass: String,
        private val onMessage: (String) -> Unit
    ) : UserInfo {
        /** The last promptYesNo text JSch showed, for [classifyFailure]. */
        @Volatile var lastPrompt: String? = null
            protected set
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pass
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = true
        override fun showMessage(message: String?) { onMessage(message ?: "") }
    }

    /**
     * Probe pass ("ask"): records every host-key question and answers no, so
     * nothing is ever accepted or pinned here.
     */
    class ProbeUserInfo(pass: String, onMessage: (String) -> Unit = {}) :
        PasswordUserInfo(pass, onMessage) {
        override fun promptYesNo(message: String?): Boolean {
            lastPrompt = message
            return false
        }
    }

    /**
     * Pinning pass ("ask"): yes once, and only for the approved fingerprint, so
     * JSch writes exactly that key to known_hosts. Anything else is refused.
     */
    class PinOnceUserInfo(
        pass: String,
        private val approvedFingerprint: String,
        onMessage: (String) -> Unit = {}
    ) : PasswordUserInfo(pass, onMessage) {
        @Volatile var accepted: Int = 0
            private set
        override fun promptYesNo(message: String?): Boolean {
            lastPrompt = message
            if (accepted == 0 && mayAcceptOnPinningPass(message, approvedFingerprint)) {
                accepted++
                return true
            }
            return false
        }
    }
}
