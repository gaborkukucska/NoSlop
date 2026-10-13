package com.noslop.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round E1 (review round 2, D11): source rules that keep the D11 items from coming back.
 *
 * 1. Every `CoroutineScope(...)` built in app code has a SupervisorJob and a logging exception
 *    handler (Logger.kt excepted: its handler would log through itself). Fire-and-forget work goes
 *    through [AppScopes].
 * 2. Empty `catch` blocks are allowed only where listed in [KEPT_SILENT] (cleanup such as
 *    `socket.close()`, best-effort parsing, expected failures). A new one, or one that was fixed
 *    but is still listed, fails the test: log the failure, or update the list with a reason.
 * 3. INFO/WARN/ERROR logs never interpolate a search term or video id directly; use
 *    `Logger.redact(...)`. (A property of a payload named `query`, e.g. `${query.groupId}`, is fine.)
 * 4. The manifest can see the Tor Browser packages (API 30+ package visibility).
 */
class CodeHygieneArchitectureTest {

    private val srcMain: File by lazy {
        val root = File(".").canonicalFile
        val dir = File(root, "src/main").let { if (it.exists()) it else File(root, "app/src/main") }
        assertTrue("Could not locate src/main directory", dir.exists())
        dir
    }
    private val javaRoot: File get() = File(srcMain, "java")

    private fun sources(): List<File> = javaRoot.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .sortedBy { it.path }
        .toList()

    private fun rel(f: File) = f.relativeTo(javaRoot).invariantSeparatorsPath

    @Test
    fun everyCoroutineScopeHasSupervisorJobAndHandler() {
        val ctor = Regex("""(?<![A-Za-z_])CoroutineScope\s*\(""")
        val violations = mutableListOf<String>()
        for (file in sources()) {
            val text = file.readText()
            for (m in ctor.findAll(text)) {
                val open = m.range.last
                var depth = 0
                var end = -1
                for (i in open until text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) { end = i; break } }
                    }
                }
                val args = if (end > open) text.substring(open + 1, end) else ""
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                val where = "${rel(file)}:$line"
                if (!args.contains("SupervisorJob")) violations += "$where has no SupervisorJob (use AppScopes)"
                if (file.name != "Logger.kt" && !args.contains("exceptionhandler", ignoreCase = true)) {
                    violations += "$where has no exception handler (add AppScopes.exceptionHandler or use AppScopes)"
                }
            }
        }
        assertTrue("Coroutine scope rule:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun emptyCatchBlocksAreOnlyTheListedOnes() {
        val sameLine = Regex("""catch\s*\([^)]*\)\s*\{\s*(/\*.*?\*/|//.*)?\s*\}""")
        val openOnly = Regex("""catch\s*\([^)]*\)\s*\{\s*(//.*)?$""")
        val found = mutableMapOf<String, Int>()
        val where = mutableMapOf<String, MutableList<Int>>()
        for (file in sources()) {
            val lines = file.readLines()
            for ((i, l) in lines.withIndex()) {
                val empty = if (sameLine.containsMatchIn(l) && !l.contains("function(){}")) {
                    true
                } else if (openOnly.containsMatchIn(l)) {
                    var k = i + 1
                    while (k < lines.size && lines[k].trim().startsWith("//")) k++
                    k < lines.size && lines[k].trim() == "}"
                } else false
                if (empty) {
                    val key = rel(file) + "|" + l.trim()
                    found[key] = (found[key] ?: 0) + 1
                    where.getOrPut(key) { mutableListOf() } += i + 1
                }
            }
        }
        val problems = mutableListOf<String>()
        for ((key, n) in found) {
            val allowed = KEPT_SILENT[key] ?: 0
            if (n > allowed) problems += "New empty catch: $key (lines ${where[key]})"
        }
        for ((key, allowed) in KEPT_SILENT) {
            val n = found[key] ?: 0
            if (n < allowed) problems += "Listed but no longer present (remove it from KEPT_SILENT): $key"
        }
        assertTrue("Empty catch rule:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun logsNeverInterpolateSearchTermsOrVideoIds() {
        val logCall = Regex("""\bLogger\.(info|warn|error)\s*\(""")
        val raw = Regex("""\$\{?\s*(query|videoId|searchQuery|cleanQuery|searchTerm)\b(?!\s*\.)""")
        val violations = mutableListOf<String>()
        for (file in sources()) {
            val lines = file.readLines()
            for ((i, l) in lines.withIndex()) {
                if (!logCall.containsMatchIn(l)) continue
                val chunk = if (l.count { it == '(' } > l.count { it == ')' }) {
                    lines.subList(i, minOf(lines.size, i + 4)).joinToString(" ")
                } else l
                if (raw.containsMatchIn(chunk)) violations += "${rel(file)}:${i + 1}: ${l.trim()}"
            }
        }
        assertTrue("Use Logger.redact(...) for:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun manifestCanSeeTorBrowser() {
        val manifest = File(srcMain, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\"org.torproject.torbrowser\""))
        assertTrue(manifest.contains("android:name=\"org.torproject.torbrowser_alpha\""))
    }

    private companion object {
        val KEPT_SILENT: Map<String, Int> = mapOf(
        "com/noslop/app/data/FeedRepository.kt|} catch (_: Exception) {}" to 1,
        "com/noslop/app/data/NoSlopRepository.kt|} catch (e: Exception) {}" to 1,
        "com/noslop/app/feeds/FeedParser.kt|} catch (e: Exception) {" to 1,
        "com/noslop/app/feeds/api/InvidiousApiClient.kt|} catch (_: Exception) {}" to 2,
        "com/noslop/app/feeds/api/RedditApiClient.kt|} catch (_: Exception) {}" to 1,
        "com/noslop/app/feeds/api/VimeoApiClient.kt|} catch (_: Exception) {}" to 1,
        "com/noslop/app/feeds/api/WikipediaApiClient.kt|} catch (_: Exception) {}" to 1,
        "com/noslop/app/feeds/api/YouTubeInternalClient.kt|} catch (e: Exception) {}" to 1,
        "com/noslop/app/mesh/DmPacketHandler.kt|} catch (e: Exception) {" to 1,
        "com/noslop/app/mesh/DmPacketHandler.kt|} catch (e: Exception) { /* not JSON -- an ordinary 1:1 DM */ }" to 1,
        "com/noslop/app/mesh/MediaProxyService.kt|try { clientSocket.close() } catch (_: Exception) {}" to 1,
        "com/noslop/app/mesh/MediaProxyService.kt|try { clientSocket.close() } catch (e: Exception) {}" to 1,
        "com/noslop/app/mesh/MediaProxyService.kt|try { clientSocket?.soTimeout = remaining } catch (_: Exception) {}" to 1,
        "com/noslop/app/mesh/MediaProxyService.kt|try { serverSocket?.close() } catch (e: Exception) {}" to 1,
        "com/noslop/app/mesh/MediaProxyService.kt|} catch (e: Exception) {}" to 1,
        "com/noslop/app/mesh/MeshTransport.kt|try { serverSocket?.close() } catch (_: Exception) {}" to 1,
        "com/noslop/app/mesh/MeshTransport.kt|try { socket.close() } catch (e: Exception) {}" to 1,
        "com/noslop/app/mesh/MeshTransport.kt|try { socket?.close() } catch (_: Exception) {}" to 2,
        "com/noslop/app/mesh/MeshTransport.kt|} catch (e: Exception) {" to 1,
        "com/noslop/app/net/HubDiscoveryService.kt|} catch (e: Exception) {" to 1,
        "com/noslop/app/net/SshDeployer.kt|} catch (e: Exception) {" to 1,
        "com/noslop/app/tor/TorControlChannel.kt|try { reader.close() } catch (_: Exception) {}" to 1,
        "com/noslop/app/tor/TorControlChannel.kt|try { underlying.close() } catch (_: Exception) {}" to 1,
        "com/noslop/app/tor/TorControlChannel.kt|try { writer.close() } catch (_: Exception) {}" to 1,
        "com/noslop/app/tor/TorService.kt|try { Thread.sleep(200) } catch (_: Exception) {}" to 1,
        "com/noslop/app/tor/TorService.kt|} catch (_: Exception) {" to 1,
        "com/noslop/app/ui/ContentPreferencesScreen.kt|try { com.noslop.app.feeds.api.InvidiousApiClient.preWarmInstances() } catch (_: Exception) {}" to 1,
        "com/noslop/app/ui/OnboardingScreen.kt|try { com.noslop.app.feeds.api.InvidiousApiClient.preWarmInstances() } catch (_: Exception) {}" to 1,
        "com/noslop/app/ui/PreloadManager.kt|try { p.release() } catch (_: Exception) {}" to 1,
        "com/noslop/app/ui/QRScanScreen.kt|try { cameraProviderRef.get()?.unbindAll() } catch (_: Exception) {}" to 1,
        "com/noslop/app/ui/QRScanScreen.kt|} catch (e: Exception) {" to 1,
        "com/noslop/app/ui/UnifiedFeedTab.kt|} catch (_: Exception) {}" to 1,
        "com/noslop/app/ui/components/AndroidGifTextField.kt|} catch (_: Exception) {}" to 1,
        "com/noslop/app/ui/components/AudioPlayer.kt|} catch (e: Exception) { /* player may be mid-release; ignore */ }" to 1,
        "com/noslop/app/util/UpdateChecker.kt|} catch (_: Exception) { /* ignore corrupt cache */ }" to 1,
        "com/noslop/app/util/UpdateManager.kt|try { destFile.delete() } catch (_: Exception) {}" to 1
        )
    }
}
