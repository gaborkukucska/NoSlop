package com.noslop.mvp

import java.io.File

/**
 * Launches and supervises a **bundled `tor`** for the desktop HUB (ADR-009: bundle, don't require a system
 * install). Writes a minimal torrc (SOCKS + control port, password-authed control), spawns tor, and waits
 * for "Bootstrapped 100%". Once up, [TorControl.addOnion] uses [controlPort]/[controlPassword] to publish the
 * hub's onion, and [socksPort] is what a co-located leaf would dial through.
 *
 * Binary resolution order: explicit [torBinary] arg → `NOSLOP_TOR_BINARY` env → a bundled `tor` next to the
 * app (`build/tor/tor`) → `tor` on PATH. Shipping = drop the per-OS Tor Expert Bundle binary at the bundled
 * path (a `downloadTor` Gradle task can fetch it); this launcher is binary-source-agnostic.
 */
class TorProcess(
    private val torBinary: String? = null,
    private val dataDir: File = File(System.getProperty("user.home"), ".noslop-hub/tor"),
    val socksPort: Int = 9050,
    val controlPort: Int = 9051,
    val controlPassword: String = "noslop-hub-control",
) {
    private var process: Process? = null

    private fun resolveBinary(): String =
        torBinary
            ?: System.getenv("NOSLOP_TOR_BINARY")
            ?: sequenceOf(
                File("build/tor/tor/tor"),                                   // bundled by the downloadTor task
                File(System.getProperty("user.dir"), "build/tor/tor/tor"),
                File(System.getProperty("user.dir"), "composeApp/build/tor/tor/tor"),
            ).firstOrNull { it.canExecute() }?.absolutePath
            ?: "tor" // last resort: PATH

    /** Start tor and block until it reports a full bootstrap (or [timeoutMs] elapses). Returns this. */
    fun start(timeoutMs: Long = 90_000): TorProcess {
        dataDir.mkdirs()
        val bin = resolveBinary()

        // Control port needs auth; hash our password with tor itself and pin it in the torrc.
        val hashed = ProcessBuilder(bin, "--hash-password", controlPassword)
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim().lines().last { it.startsWith("16:") }

        val torrc = File(dataDir, "torrc").apply {
            writeText(
                """
                SocksPort $socksPort
                ControlPort $controlPort
                HashedControlPassword $hashed
                DataDirectory ${File(dataDir, "data").absolutePath}
                """.trimIndent(),
            )
        }

        val proc = ProcessBuilder(bin, "-f", torrc.absolutePath)
            .redirectErrorStream(true).start()
        process = proc

        // Wait for bootstrap, echoing tor's progress.
        val reader = proc.inputStream.bufferedReader()
        val deadline = nowMillis() + timeoutMs
        while (nowMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.contains("Bootstrapped")) println("[tor] ${line.substringAfter("] ").ifBlank { line }}")
            if (line.contains("Bootstrapped 100%")) return this
            if (line.contains("[err]")) error("tor failed: $line")
        }
        error("tor did not bootstrap within ${timeoutMs}ms")
    }

    fun stop() {
        process?.destroy()
    }
}
