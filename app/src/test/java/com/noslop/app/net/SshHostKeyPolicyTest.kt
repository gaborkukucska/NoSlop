package com.noslop.app.net

import com.jcraft.jsch.JSchException
import com.noslop.app.net.SshHostKeyPolicy.Decision
import com.noslop.app.net.SshHostKeyPolicy.Failure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NOSLOP_SSH_HOSTKEY_V2. The message strings below are byte-for-byte what
 * com.github.mwiede:jsch 0.2.17 Session.checkHost builds (read from the jar
 * with javap).
 */
class SshHostKeyPolicyTest {

    private val host = "192.168.1.20"
    private val fp = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
    private val otherFp = "SHA256:AAAAQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"

    private fun unknownPrompt(f: String = fp) =
        "The authenticity of host '$host' can't be established.\n" +
            "ED25519 key fingerprint is $f.\n" +
            "Are you sure you want to continue connecting?"

    private val changedPrompt =
        "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!\n" +
            "IT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!\n" +
            "Someone could be eavesdropping on you right now (man-in-the-middle attack)!\n" +
            "It is also possible that the ED25519 host key has just been changed.\n" +
            "The fingerprint for the ED25519 key sent by the remote host $host is\n$fp.\n" +
            "Please contact your system administrator.\n" +
            "Add correct host key in known_hosts to get rid of this message.\n" +
            "Do you want to delete the old key and insert the new key?"

    private val rejectEx = JSchException("reject HostKey: $host")
    private val changedEx = JSchException("HostKey has been changed: $host")

    private fun newJschException(cls: String, msg: String): Exception {
        val c = Class.forName("com.jcraft.jsch.$cls").getDeclaredConstructor(String::class.java)
        c.isAccessible = true
        return c.newInstance(msg) as Exception
    }

    // --- parsing ---

    @Test fun parsesFingerprintFromPrompt() {
        assertEquals(fp, SshHostKeyPolicy.parseFingerprint(unknownPrompt()))
    }

    @Test fun parsesMd5StyleFingerprint() {
        val md5 = "9d:38:5b:83:a9:17:52:92:56:1a:5e:c4:d4:81:8e:0a"
        assertEquals(md5, SshHostKeyPolicy.parseFingerprint(unknownPrompt(md5)))
    }

    @Test fun parsesFingerprintFromUnknownHostKeyMessage() {
        // JSchUnknownHostKeyException text (ask + no UserInfo).
        val msg = "UnknownHostKey: $host. ED25519 key fingerprint is $fp"
        assertEquals(fp, SshHostKeyPolicy.parseFingerprint(msg))
    }

    @Test fun noFingerprint() {
        assertNull(SshHostKeyPolicy.parseFingerprint("reject HostKey: $host"))
        assertNull(SshHostKeyPolicy.parseFingerprint(null))
    }

    // --- classification ---

    @Test fun unknownHostPromptsWithTheRightFingerprint() {
        val probe = SshHostKeyPolicy.ProbeUserInfo("pw")
        assertFalse(probe.promptYesNo(unknownPrompt())) // probe never accepts
        assertEquals(Failure.UnknownHost(fp), SshHostKeyPolicy.classifyFailure(rejectEx, probe.lastPrompt))
    }

    @Test fun unknownHostExceptionTypeIsRecognised() {
        val e = newJschException("JSchUnknownHostKeyException", "UnknownHostKey: $host. ED25519 key fingerprint is $fp")
        assertEquals(Failure.UnknownHost(fp), SshHostKeyPolicy.classifyFailure(e, null))
    }

    @Test fun changedKeyIsRefused() {
        val probe = SshHostKeyPolicy.ProbeUserInfo("pw")
        assertFalse(probe.promptYesNo(changedPrompt))
        assertEquals(Failure.ChangedKey, SshHostKeyPolicy.classifyFailure(changedEx, probe.lastPrompt))
        // Even without a captured prompt (strict "yes" path / no UserInfo).
        assertEquals(Failure.ChangedKey, SshHostKeyPolicy.classifyFailure(changedEx, null))
        val typed = newJschException("JSchChangedHostKeyException", "HostKey has been changed: $host")
        assertEquals(Failure.ChangedKey, SshHostKeyPolicy.classifyFailure(typed, null))
    }

    @Test fun changedKeyPromptWinsOverRejectMessage() {
        // A changed-key prompt must never be read as "unknown host" even though
        // it also contains a fingerprint.
        assertEquals(Failure.ChangedKey, SshHostKeyPolicy.classifyFailure(rejectEx, changedPrompt))
    }

    @Test fun rejectionWithoutFingerprintFailsClosed() {
        assertEquals(Failure.UnverifiedRejection, SshHostKeyPolicy.classifyFailure(rejectEx, null))
    }

    @Test fun networkErrorsAreNotHostKeyFailures() {
        assertEquals(
            Failure.NotHostKey,
            SshHostKeyPolicy.classifyFailure(JSchException("timeout: socket is not established"), null)
        )
        assertEquals(Failure.NotHostKey, SshHostKeyPolicy.classifyFailure(JSchException("Auth fail"), null))
    }

    // --- decision ---

    @Test fun userIsAskedWithHostAndFingerprint() = runBlocking {
        var asked: Pair<String, String>? = null
        val d = SshHostKeyPolicy.decideUnknownHost(host, fp) { h, f -> asked = h to f; true }
        assertEquals(host to fp, asked)
        assertEquals(Decision.Approved(fp), d)
    }

    @Test fun userDeclinesMeansNoConnection() = runBlocking {
        assertEquals(Decision.Declined, SshHostKeyPolicy.decideUnknownHost(host, fp) { _, _ -> false })
    }

    @Test fun noCallbackIsRefusedNotTrustedOnFirstUse() = runBlocking {
        assertEquals(Decision.NoConfirmationHandler, SshHostKeyPolicy.decideUnknownHost(host, fp, null))
    }

    // --- pinning pass ---

    @Test fun pinningPassAcceptsExactlyTheApprovedKeyOnce() {
        val ui = SshHostKeyPolicy.PinOnceUserInfo("pw", fp)
        assertTrue(ui.promptYesNo(unknownPrompt()))
        assertFalse(ui.promptYesNo(unknownPrompt())) // only once
        assertEquals(1, ui.accepted)
    }

    @Test fun pinningPassRefusesASwappedKey() {
        val ui = SshHostKeyPolicy.PinOnceUserInfo("pw", fp)
        assertFalse(ui.promptYesNo(unknownPrompt(otherFp)))
        assertEquals(0, ui.accepted)
        // The deployer sees an UnknownHost for a different key and aborts.
        assertEquals(Failure.UnknownHost(otherFp), SshHostKeyPolicy.classifyFailure(rejectEx, ui.lastPrompt))
    }

    @Test fun pinningPassNeverAcceptsChangedKeyWarning() {
        val ui = SshHostKeyPolicy.PinOnceUserInfo("pw", fp)
        assertFalse(ui.promptYesNo(changedPrompt))
    }

    @Test fun userInfoSuppliesPassword() {
        assertEquals("pw", SshHostKeyPolicy.ProbeUserInfo("pw").password)
        assertNull(SshHostKeyPolicy.ProbeUserInfo("pw").passphrase)
    }
}
