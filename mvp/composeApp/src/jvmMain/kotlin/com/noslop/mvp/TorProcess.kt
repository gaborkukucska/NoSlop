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

    /**
     * Start tor and wait up to [timeoutMs] for "Bootstrapped 100%". A slow bootstrap is **not** an error: tor
     * keeps running and finishes on its own, and ADD_ONION is accepted before bootstrap completes (the
     * descriptor is published once it does). The previous behaviour threw here, which made the hub silently
     * fall back to plain TCP and print a LAN-only QR whenever a stale directory cache made the first bootstrap
     * exceed the window — exactly what happened after a four-month gap. Returns this.
     */
    fun start(timeoutMs: Long = 300_000): TorProcess {
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

        // Drain tor's output for the life of the process on a daemon thread, echoing bootstrap progress. Draining
        // forever matters: once tor's stdout pipe fills (64 KB), tor blocks on its next log write and the hub's
        // onion goes dark. The old code stopped reading after bootstrap.
        val bootstrapped = java.util.concurrent.CountDownLatch(1)
        val fatal = java.util.concurrent.atomic.AtomicReference<String?>(null)
        Thread({
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.contains("Bootstrapped")) println("[tor] ${line.substringAfter("] ").ifBlank { line }}")
                    if (line.contains("Bootstrapped 100%")) bootstrapped.countDown()
                    if (line.contains("[err]")) { fatal.set(line); bootstrapped.countDown() }
                }
            }
            bootstrapped.countDown()   // EOF: tor exited
        }, "tor-stdout").apply { isDaemon = true }.start()

        val done = bootstrapped.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        fatal.get()?.let { error("tor failed: $it") }
        if (!proc.isAlive) error("tor exited before bootstrapping (exit ${proc.exitValue()})")
        if (!done) println("[tor] not yet bootstrapped after ${timeoutMs / 1000}s — continuing; the onion publishes when bootstrap completes.")
        return this
    }

    fun stop() {
        process?.destroy()
    }
}
