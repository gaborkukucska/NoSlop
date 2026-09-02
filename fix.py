#!/usr/bin/env python3
"""
NoSlop surgical fix 04 — stop serving stream URLs that are bound to an exit we
no longer use, and stop speculative preloads from starving the visible video
under Tor.

    python3 patch_04_video_over_tor.py

THE BUG
-------
googlevideo signs a stream URL to the IP that requested it. TorService.kt says
so in its own header. But nothing binds the *playback* fetch to the exit that
did the *resolve*, and every NEWNYM moves the whole process onto a new exit, so
a cached URL routinely outlives the only route on which it works.

From the 2026-09-03 00:18-00:26 capture:

  * PSIrKxSq8w8 resolved 00:18:18, signedFor=192.42.116.53. ExoPlayer loaded it
    at 00:25:45 — after rotations at 00:23:30, 00:23:45, 00:24:12, 00:24:39,
    00:25:00 and 00:25:21. Result: bufPos=11593, delta=0ms, stalled. Zero bytes.
  * PTO0s8YQ49o resolved 00:18:31, loaded one second later, and played — until
    00:23:23, when it died with code=2004 (ERROR_CODE_IO_BAD_HTTP_STATUS), i.e.
    googlevideo refusing the request because the circuit had moved.
  * A third URL carried ip=1.146.240.106 — the device's own address — with
    met=1788365643 (00:14:03), four minutes before that capture starts. It was
    resolved while clearnet routing was on, cached under a six-hour TTL, then
    fetched over Tor at 00:18:16 where it could never work. That is the 12s
    stall that triggered the 00:18:27 rotation which then invalidated
    everything resolved before it.

`sourceCache` is keyed on "$rawUrl||$quality" and validated on expiry alone. It
has no notion of which route resolved an entry, so it survives both a Tor
toggle and any number of rotations.

Separately, the resolve gate is Semaphore(3) with a 60s budget per video, and
the feed preloads one slide back and two forward. Four resolves compete for
three permits, which is what "Gave up waiting 20s for a resolve slot for
nrXUUIqGioI — earlier resolves are still stuck" is: the visible video losing to
speculative ones.

THE FIX
-------
1. TorService gains a `circuitGeneration` counter, incremented only when a
   NEWNYM actually succeeds. It is deliberately NOT incremented on the
   cooperative "another caller rotated recently, reporting success" path,
   because no rotation happens there — that caller's freshness came from the
   sibling rotation that already bumped the counter.

2. Cache entries record the routing mode and the circuit generation in force
   when they were resolved. An entry is usable only if both still match:

     - routing mode changed  -> always stale (kills the ip=1.146.240.106 class)
     - generation changed    -> stale for signed URLs, which are IP-locked, and
                                stale for Unavailable, so a rotation
                                immediately reopens videos a gated exit refused
                                rather than making them wait out a 60s TTL.
                                Unsigned static assets are unaffected.

   Applied in all three places that read the cache, including the `initialSource`
   fast path in NoSlopVideoPlayer, which read the map directly and would
   otherwise have handed a stale URL straight to ExoPlayer, leaking the fix.

3. Under Tor routing, the feed preloads one slide forward instead of two, drops
   the backward preload, and waits longer before starting, so the visible
   video's resolve gets a clear run at the gate.

WHAT THIS DOES NOT FIX
----------------------
It does not make resolve and playback share an exit — it only stops the app
believing a URL that can no longer work. Videos will re-resolve more often
under Tor rather than stall silently, which is the better failure, but a
rotation between resolve and play still costs you the resolve. The real fix is
SOCKS stream isolation (per-video SOCKS credentials used for both the resolve
and the ExoPlayer datasource), tracked as finding #17 in the audit doc.

Idempotent. Run from the repo root.
"""

import os
import re
import sys

APPLIED, SKIPPED, FAILED = [], [], []

TOR = "app/src/main/java/com/noslop/app/tor/TorService.kt"
VIDEO = "app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt"
FEED = "app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt"
AUDIT_DOC = "docs/AUDIT_2026_09_03.md"
STATUS_DOC = "docs/PROJECT_STATUS.md"


def edit(path, old, new, label, marker=None):
    if not os.path.exists(path):
        FAILED.append(f"{label}: {path} not found")
        return
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    if marker and marker in src:
        SKIPPED.append(f"{label}: already applied")
        return
    if old not in src:
        FAILED.append(f"{label}: anchor text not found in {path}")
        return
    if src.count(old) != 1:
        FAILED.append(f"{label}: anchor matched {src.count(old)} times, expected 1")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new, 1))
    APPLIED.append(label)


# ===========================================================================
# 1. TorService — circuit generation counter
# ===========================================================================

TOR_FIELD_OLD = """    @Volatile
    private var lastMediaProgressAtMs = 0L"""

TOR_FIELD_NEW = """    // --- NOSLOP_CIRCUIT_GENERATION_V1 ---
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
    private var lastMediaProgressAtMs = 0L"""

TOR_BUMP_OLD = """            val ok = doRequestNewCircuit()
            if (ok) lastNewnymAtMs = System.currentTimeMillis()
            return ok"""

TOR_BUMP_NEW = """            val ok = doRequestNewCircuit()
            if (ok) {
                lastNewnymAtMs = System.currentTimeMillis()
                // NOSLOP_CIRCUIT_GENERATION_V1 — everything resolved on the old
                // exit is now unusable. Bump before returning so a caller that
                // re-resolves immediately stamps the new generation.
                _circuitGeneration++
                Logger.info(TAG, "Circuit rotated — generation is now ${_circuitGeneration}")
            }
            return ok"""

# ===========================================================================
# 2. VideoPlayer — route-aware cache validity
# ===========================================================================

VIDEO_CLASS_OLD = """private class CachedSource(val source: VideoSource, val expiresAtMs: Long)"""

VIDEO_CLASS_NEW = """// --- NOSLOP_ROUTE_AWARE_CACHE_V1 ---
// An expiry is not enough. A signed CDN URL is bound to the IP that resolved
// it, so it is dead the moment the route changes — while its `expire=` stamp
// still says it has hours left. Two more facts are recorded with every entry:
//
//   overTor            the routing mode in force at resolve time. A URL signed
//                      for the device's own address cannot be fetched through
//                      Tor, and one signed for an exit cannot be fetched
//                      directly. Either way, a toggle invalidates.
//   circuitGeneration  TorService's exit-change counter at resolve time. A
//                      rotation makes every signed URL from the previous
//                      generation unusable.
private class CachedSource(
    val source: VideoSource,
    val expiresAtMs: Long,
    val overTor: Boolean,
    val circuitGeneration: Long
)

/**
 * Why an entry can no longer be used, or null if it still can. Returning the
 * reason rather than a boolean keeps the log honest about which of the three
 * conditions fired — "expired" and "the exit moved underneath it" look
 * identical from the outside and want different fixes.
 */
private fun CachedSource.stalenessReason(): String? {
    if (expiresAtMs <= System.currentTimeMillis()) return "URL expired"

    val overTorNow = HttpClientProvider.useTorForClearnet
    if (overTor != overTorNow) {
        return if (overTorNow) {
            "resolved over clearnet, now routing through Tor — the URL is signed for this device's own address"
        } else {
            "resolved over Tor, now routing direct — the URL is signed for an exit"
        }
    }

    if (!overTorNow) return null

    val generationNow = com.noslop.app.tor.TorService.circuitGeneration
    if (circuitGeneration == generationNow) return null

    return when (val s = source) {
        // Signed URLs are IP-locked to the exit that issued them.
        is VideoSource.Direct ->
            if (SIGNED_URL_HINT_PATTERN.containsMatchIn(s.url)) {
                "circuit rotated ($circuitGeneration -> $generationNow) and the URL is signed for the old exit"
            } else null
        // A new exit is exactly the thing that might not be gated, so don't
        // make a rotation wait out the 60s failure TTL.
        is VideoSource.Unavailable ->
            "circuit rotated ($circuitGeneration -> $generationNow) — retrying on the new exit"
        else -> null
    }
}"""

VIDEO_ISCACHED_OLD = """internal fun isSourceCached(url: String): Boolean {
    val entry = sourceCache[url] ?: return false
    return entry.expiresAtMs > System.currentTimeMillis()
}"""

VIDEO_ISCACHED_NEW = """internal fun isSourceCached(url: String): Boolean {
    // NOSLOP_ROUTE_AWARE_CACHE_V1 — entries are stored under "$url||$quality",
    // so the bare-url lookup this used to do never matched anything and the
    // function always answered false. Scan the quality variants, and apply the
    // same route-aware validity test as every other reader.
    val entry = sourceCache.entries
        .firstOrNull { it.key == url || it.key.startsWith("$url||") }
        ?.value ?: return false
    return entry.stalenessReason() == null
}"""

VIDEO_FRESH_OLD = """    fun freshOrNull(): VideoSource? {
        val entry = sourceCache[cacheKey] ?: return null
        if (entry.expiresAtMs > System.currentTimeMillis()) return entry.source
        Logger.info("VIDEO_RESOLVE", "Cached source for $rawUrl expired — re-resolving")
        sourceCache.remove(cacheKey)
        return null
    }"""

VIDEO_FRESH_NEW = """    fun freshOrNull(): VideoSource? {
        val entry = sourceCache[cacheKey] ?: return null
        // NOSLOP_ROUTE_AWARE_CACHE_V1 — expiry alone used to decide this.
        val reason = entry.stalenessReason() ?: return entry.source
        Logger.info("VIDEO_RESOLVE", "Cached source for $rawUrl unusable ($reason) — re-resolving")
        sourceCache.remove(cacheKey)
        return null
    }"""

VIDEO_STORE_OLD = """        sourceCache[cacheKey] = CachedSource(result, expiryMs)"""

VIDEO_STORE_NEW = """        // NOSLOP_ROUTE_AWARE_CACHE_V1 — stamp the route this was resolved on.
        sourceCache[cacheKey] = CachedSource(
            source = result,
            expiresAtMs = expiryMs,
            overTor = HttpClientProvider.useTorForClearnet,
            circuitGeneration = com.noslop.app.tor.TorService.circuitGeneration
        )"""

VIDEO_INITIAL_OLD = """        val exactKey = "$url||$q"
        sourceCache[exactKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.source
            ?: sourceCache.entries.find { it.key.startsWith("$url||") && it.value.expiresAtMs > System.currentTimeMillis() }?.value?.source"""

VIDEO_INITIAL_NEW = """        val exactKey = "$url||$q"
        // NOSLOP_ROUTE_AWARE_CACHE_V1 — this fast path read the map directly and
        // checked only expiry, so it would hand ExoPlayer a URL bound to an exit
        // we have since rotated away from. That is the stall the 00:25:45
        // capture shows: bufPos frozen at 11593 with delta=0ms.
        sourceCache[exactKey]?.takeIf { it.stalenessReason() == null }?.source
            ?: sourceCache.entries.find { it.key.startsWith("$url||") && it.value.stalenessReason() == null }?.value?.source"""

# ===========================================================================
# 3. UnifiedFeedTab — preload throttling while routing over Tor
# ===========================================================================

FEED_OLD = """        // 1. Scan backwards to preload the immediate previous video
        for (i in pagerState.currentPage - 1 downTo maxOf(0, pagerState.currentPage - 5)) {
            val preloadData = getPreloadDataFromItem(unifiedItems[i], context)
            if (preloadData != null) {
                val (rawUrl, forcedUrl) = preloadData
                val urlToCheck = forcedUrl ?: rawUrl
                if (!urlToCheck.startsWith("file://")) {
                    preloadScope.launch { com.noslop.app.ui.PreloadManager.preWarm(context, rawUrl, forcedUrl) }
                }
                break // Only keep 1 previous video warm
            }
        }

        // 2. Scan forwards to preload upcoming slides near the viewport
        var preloadedForwardCount = 0
        for (i in pagerState.currentPage + 1..minOf(unifiedItems.size - 1, pagerState.currentPage + 10)) {
            val preloadData = getPreloadDataFromItem(unifiedItems[i], context)
            if (preloadData != null) {
                val (rawUrl, forcedUrl) = preloadData
                val urlToCheck = forcedUrl ?: rawUrl
                if (!urlToCheck.startsWith("file://")) {
                    val targetIndex = i
                    val delayMs = 2000L + (preloadedForwardCount * 1500L)
                    preloadScope.launch { 
                        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                        if (kotlin.math.abs(pagerState.currentPage - targetIndex) <= 2) {
                            com.noslop.app.ui.PreloadManager.preWarm(context, rawUrl, forcedUrl) 
                        }
                    }
                }
                preloadedForwardCount++
                if (preloadedForwardCount >= 2) break // Keep up to 2 forward slides warm
            }
        }"""

FEED_NEW = """        // --- NOSLOP_TOR_PRELOAD_BUDGET_V1 ---
        // Resolving a stream costs a permit on YouTubeInternalClient's
        // Semaphore(3) and up to a 60s budget. Over clearnet that is cheap and
        // preloading four slides is free performance. Over Tor it is the
        // scarcest resource in the app, and the visible video competes for it on
        // equal terms with speculative work it does not need yet — which is what
        // "Gave up waiting 20s for a resolve slot for nrXUUIqGioI — earlier
        // resolves are still stuck" was: the slide the user is looking at losing
        // the race to slides they may never reach.
        //
        // Under Tor: one slide ahead, none behind, and a longer head start for
        // the visible resolve. Over clearnet, nothing changes.
        val overTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        val forwardPreloadLimit = if (overTor) 1 else 2
        val preloadPreviousSlide = !overTor
        val firstPreloadDelayMs = if (overTor) 8000L else 2000L

        // 1. Scan backwards to preload the immediate previous video
        if (preloadPreviousSlide) {
            for (i in pagerState.currentPage - 1 downTo maxOf(0, pagerState.currentPage - 5)) {
                val preloadData = getPreloadDataFromItem(unifiedItems[i], context)
                if (preloadData != null) {
                    val (rawUrl, forcedUrl) = preloadData
                    val urlToCheck = forcedUrl ?: rawUrl
                    if (!urlToCheck.startsWith("file://")) {
                        preloadScope.launch { com.noslop.app.ui.PreloadManager.preWarm(context, rawUrl, forcedUrl) }
                    }
                    break // Only keep 1 previous video warm
                }
            }
        }

        // 2. Scan forwards to preload upcoming slides near the viewport
        var preloadedForwardCount = 0
        for (i in pagerState.currentPage + 1..minOf(unifiedItems.size - 1, pagerState.currentPage + 10)) {
            val preloadData = getPreloadDataFromItem(unifiedItems[i], context)
            if (preloadData != null) {
                val (rawUrl, forcedUrl) = preloadData
                val urlToCheck = forcedUrl ?: rawUrl
                if (!urlToCheck.startsWith("file://")) {
                    val targetIndex = i
                    val delayMs = firstPreloadDelayMs + (preloadedForwardCount * 1500L)
                    preloadScope.launch { 
                        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                        if (kotlin.math.abs(pagerState.currentPage - targetIndex) <= 2) {
                            com.noslop.app.ui.PreloadManager.preWarm(context, rawUrl, forcedUrl) 
                        }
                    }
                }
                preloadedForwardCount++
                if (preloadedForwardCount >= forwardPreloadLimit) break
            }
        }"""


# ===========================================================================
# 4. Documentation
# ===========================================================================

AUDIT_MARKER_START = "<!-- NOSLOP_AUDIT_REGISTER_START -->"
AUDIT_MARKER_END = "<!-- NOSLOP_AUDIT_REGISTER_END -->"

AUDIT_BODY = """# External audit — running register

Audit of the legacy Android app (`app/`) begun 2026-09-02 against README,
PROJECT_STATUS, TECHNICAL_REFERENCE and PRIVACY_AND_SECURITY_PROPOSAL as they
stood *after* the 2026-09-02 self-audit. This file is the working register: what
has been fixed, and what is still open. It is updated by the patch scripts as
they run.

""" + AUDIT_MARKER_START + """

## Done

| # | Item | Where | Patch |
|---|------|-------|-------|
| — | SSH host keys were auto-accepted on every connection, and `known_hosts` was written to a directory that does not persist on Android. Deployment ships the SSH password and the full private identity, so an attacker answering on that IP received all of it. Now: persistent pin in `filesDir`, two-phase connect, unconditional refusal on a changed key, fingerprint surfaced, optional confirmation callback, `clearPinnedHostKey()` helper. | `net/SshDeployer.kt` | 01 |
| — | Keepalive moved off `GlobalScope` onto an owned scope. | `net/SshDeployer.kt` | 01 |
| — | Packets were relayed to every trusted peer *before* any signature check, since all verification lived in handlers that run after `processIncoming()`. Now verified at gossip step 4.4 for the 20 types carrying a self-contained signature. | `mesh/MeshPacketVerifier.kt`, `mesh/GossipService.kt` | 02 |
| — | Attacker-supplied `packet.id` entered the dedup LRU before authentication, so a forged packet could displace the real one it impersonated. Dedup split into a check (unchanged position, so rate limiting sees the same traffic) and a record that runs only after verification. | `mesh/GossipService.kt` | 02 |
| — | **Firewall buffer never worked.** A `MESSAGE` from an untrusted sender was recorded in the dedup cache and then buffered; `flushFirewallBuffer()` replayed it into a duplicate-drop and it was lost. Repaired as a side effect of the dedup split. | `mesh/GossipService.kt` | 02 |
| — | Resolved stream URLs survived a Tor toggle and every circuit rotation, despite being IP-locked to the route that resolved them. Entries now carry the routing mode and `TorService.circuitGeneration`; all three readers apply the same validity test. | `tor/TorService.kt`, `ui/components/VideoPlayer.kt` | 04 |
| — | `isSourceCached()` looked up a bare URL while entries are keyed `"$url||$quality"`, so it always answered false. | `ui/components/VideoPlayer.kt` | 04 |
| — | Speculative preloads competed with the visible video for three resolve permits. Under Tor: one slide ahead, none behind, longer head start. | `ui/UnifiedFeedTab.kt` | 04 |

## Open — ordered by severity

**1. Host key confirmation is not wired to the UI.** `onHostKeyPrompt` exists
and defaults to null, so the first connection to a host is still
trust-on-first-use. Every connection after that is protected. Four call sites in
`HubSetupScreen` (~258, ~627, ~728, ~761) need to pass a callback that blocks on
a dialog showing the fingerprint. Add a Settings entry for
`SshDeployer.clearPinnedHostKey()` at the same time.

**2. The Word Cloud cannot restore an identity.** `deriveSeed()` is used only
for backup-archive encryption; the identity is a random keypair from
`CryptoService.generateIdentity()`. `BackupManager` and README both say the
identity "must be re-derived from the mnemonic" and no code can do that. A user
who loses their phone loses their identity permanently, having been told to
write down twelve words. Either derive both keys from `deriveSeed()` through a
domain-separated KDF and add a restore path — a one-way door for existing users,
whose identities cannot be retrofitted — or correct the copy to say the Word
Cloud is a backup password. Existing archives survive either way, since
`deriveSeed()` runs PBKDF2 over the mnemonic string.

**3. `sign()` and `encryptDM()` fail silently.** Both return empty strings on
exception; 39 `sign()` call sites, none checking. The packet goes out unsigned,
every receiver drops it, and the sender shows a local echo. Make them throw or
return null and surface the error.

**4. Signed payloads are delimiter-concatenated user input.** `handle`, `bio`,
`link` and `content` can all contain the delimiter, so two different messages
can produce the same signing string. The optional-append pattern makes it worse:
"content X, no avatar" and "content X|Y with avatar Y" are indistinguishable.
Needs one canonical encoder — length-prefixed or sorted-key JSON — shared by
sender, handler and `MeshPacketVerifier`. That also removes the three-way
duplication `MeshPacketVerifierTest` currently exists to police. Wire-format
change, so it needs a version field and a transition window.

**5. OTA APK lands in external storage.** `getExternalFilesDir(DIRECTORY_DOWNLOADS)`
is reachable by any app holding `READ_EXTERNAL_STORAGE` on API 24-28, so the
file can be swapped between the hash check and the installer launch. Download to
`filesDir` and add a `<files-path>` entry to `file_paths.xml`.

**6. `runBlocking` inside an OkHttp interceptor.** `HttpClientProvider` line ~193
blocks the calling thread up to 30s. Callers arrive on `Dispatchers.IO` (64
threads) via a dispatcher allowing 64 concurrent requests, so a Tor outage can
starve IO app-wide. Throw `IOException` and let callers use `awaitNetworkReady()`.

**7. DM decryption falls back to the Ed25519 key as an X25519 key.**
`DmPacketHandler` line ~43. The fallback cannot succeed — wrong key type and
encoding — and exists only to produce a `FATAL` log line. The real condition is
"no encryption key for this peer yet"; detect it and request a handshake.

**8. Media and announce packets bypass the trust firewall.** All `MEDIA_*`,
plus `ANNOUNCE_DISCOVERABLE`, `IDENTITY_UPDATE` and `USER_EXIT`, skip both the
firewall and the rate limiter. README says untrusted senders are "dropped
outright". Media relay genuinely needs to work between untrusted nodes, so the
fix is a separate byte-rate budget for `MEDIA_*`, not a blanket block — plus a
correction to the README sentence.

**9. The proxy secret is not a secret.** `NoSlopRocks2026` is committed as the
default and any injected value ships in `BuildConfig` inside an open-source APK.
Drop `X-Proxy-Secret` and the HMAC, treat the endpoint as public, rate-limit
server-side.

**10. Room: `exportSchema = false`, eleven hand-written migrations, no migration
tests.** No `androidTest` source set exists. For an app with no cloud backup, a
bad migration is unrecoverable data loss. Export schemas, commit `app/schemas/`,
add `MigrationTestHelper` tests. The header comment in `NoSlopDatabase.kt` still
discusses `fallbackToDestructiveMigration()`, which the builder no longer calls.

**11. The database is not encrypted at rest.** Posts, comments, contacts, peer
addresses and history are plaintext SQLite. 1:1 DMs are correctly stored as
ciphertext, but **group messages are stored decrypted** — the one E2EE surface
kept in the clear. Encrypt group bodies under a Keystore-backed key at minimum;
SQLCipher for the whole file is the fuller answer.

**12. ProGuard keeps essentially the whole app.** Five package wildcards defeat
`isMinifyEnabled`. Annotate the Gson models `@Keep` and remove the wildcards one
at a time. Note that `IdentityRepository.isEncryptionActive()` currently depends
on `-keep class androidx.security.crypto.**` to work, so fix that check first.

**13. Private keys leave the device during Hub deployment.** By design, but
README says the identity "never leaves your device unless you export it
yourself". Qualify the claim and say plainly on the deploy screen what is sent.

**14. The LAN Hub fast path is documented as working and is not.**
`network_security_config.xml` blocks cleartext globally and its own header says
HubSetupScreen will report the Hub unreachable. README still advertises seamless
LAN discovery. Either document the onion fallback, or give the Hub a self-signed
certificate and pin it.

**15. Smaller items.** 4MiB frame cap x 16 connections is ~128MB of UTF-16 char
data worst case. `secureFallbackWrite()` returns plaintext on failure;
`secureFallbackRead()` returns raw ciphertext on failure. The session lock is a
UI gate only — keys stay readable regardless. 32 empty catch blocks. `fix.py` and
`get-git.sh` are force-added past a `.gitignore` that excludes their extensions.

**16. Structure and coverage.** 1,612 test lines against 44,826 production
(3.6%), no instrumentation tests. `NoSlopViewModel` is 2,458 lines with 31 state
flows and 121 functions; the repositories beneath it are already split along
lines the ViewModel could follow.

**17. Video over Tor still has no exit affinity.** Patch 04 stops the app
trusting URLs bound to a route it has left, but resolve and playback still use
whatever circuit is current. googlevideo signs to the resolving IP, so a
rotation between the two costs the resolve. The fix is SOCKS stream isolation:
give each video its own SOCKS username so Tor's `IsolateSOCKSAuth` pins it to
one circuit, and use the same credentials for the resolve *and* for the
`OkHttpDataSource` in `VideoPlayer.kt` (~1112) and `PreloadManager.kt` (~311).
That also removes the need for NEWNYM — escaping a gated exit becomes a
per-video nonce bump instead of a process-wide rotation that kills the playing
video's circuit. Java only sends SOCKS credentials via the global
`java.net.Authenticator`, so this needs a small custom `SocketFactory` doing the
SOCKS5 handshake, plus a per-identity `OkHttpClient` with its own
`ConnectionPool`.

**18. `requestNewCircuit()` reports success when it declines to rotate.** The
cooperative path returns `true` on the reasoning that a sibling's recent
rotation already gave the caller a fresh exit. The caller at
`YouTubeInternalClient.kt` line ~909 reads that as "the route changed", evicts
the pool, waits 2s and retries the whole client list. Needs to be a tri-state:
rotated / already-fresh / declined.

**19. The Invidious and Piped fallback tier is dead, not degraded.** Every
instance failed in the 2026-09-03 capture: 502, 403, 404, 500, unreachable,
timeout. `invidious.projectsegfau.lt` returned malformed JSON eight times, each
costing a Tor circuit. Mark an instance dead for the session on first hard
failure, prune the list, and move `channel joined date` lookups off the path
that competes with playback.

""" + AUDIT_MARKER_END + """

Verified-accurate claims, and the reasoning behind each finding, are in the
original audit report.
"""

STATUS_ANCHOR = "## Next Steps (Planned)"

STATUS_INSERT = """## External Audit Fixes (2026-09-03)

An external review of `app/` following the 2026-09-02 self-audit. Applied via
surgical patch scripts; the running register of what is fixed and what remains
is [AUDIT_2026_09_03.md](AUDIT_2026_09_03.md).

### Security

*   **SSH host keys were never verified** (`NOSLOP_SSH_HOSTKEY_V1`).
    `SshDeployer` installed a `UserInfo` whose `promptYesNo()` returned `true`
    unconditionally, and wrote `known_hosts` to `java.io.tmpdir`, which is not a
    stable app-private directory on Android. Deployment sends the SSH password
    and the complete private identity — Ed25519 key, X25519 key, expanded onion
    seed — so an attacker answering on that IP received all of it. Now a
    persistent pin in `filesDir`, a two-phase connect that refuses rather than
    prompts, unconditional refusal on a changed key, and
    `clearPinnedHostKey()` for the genuine rebuild case. **Still
    trust-on-first-use** until `onHostKeyPrompt` is wired into HubSetupScreen.
*   **Packets were forwarded before being verified**
    (`NOSLOP_VERIFY_BEFORE_FORWARD_V1`). `GossipService.processIncoming()` runs
    `forwardPacket()`, and every signature check lived in a handler that runs
    after it returns — so a node relayed forgeries to its entire trusted peer
    set at every hop out to TTL 6. New `MeshPacketVerifier` checks the 20 types
    carrying a self-contained signature at step 4.4. Handler checks are
    unchanged and still run. `MeshPacketVerifier.enforce = false` disables
    dropping without touching the pipeline; everything logs under `SIGVERIFY`.
*   **Dedup could be poisoned by an unauthenticated id.** Step 2 recorded
    `packet.id` before anything verified it, so a forged packet carrying an
    expected id silently displaced the real one. Split into a check (unchanged
    position, so rate limiting sees identical traffic) and a record that runs
    only after verification.

### Bugs found while fixing the above

*   **The firewall buffer never worked.** A `MESSAGE` from an untrusted sender
    was recorded in the dedup cache at step 2 and buffered at step 4; when
    `flushFirewallBuffer()` replayed it after the peer became trusted, the step
    2 check dropped it as a duplicate. Every buffered message was lost. Repaired
    by the dedup split.
*   **`isSourceCached()` always returned false.** It looked up a bare URL while
    entries are keyed `"$url||$quality"`.

### Video playback over Tor

*   **Stream URLs outlived the route they were bound to**
    (`NOSLOP_ROUTE_AWARE_CACHE_V1`). googlevideo signs a URL to the IP that
    requested it — `TorService.kt` says so in its own header — but `sourceCache`
    validated entries on `expire=` alone, so a URL survived both a Tor toggle
    and every NEWNYM. In the 2026-09-03 capture, `PSIrKxSq8w8` was resolved at
    00:18:18 for exit 192.42.116.53 and played at 00:25:45 after six rotations:
    zero bytes, buffer frozen. A third URL carried the device's own address and
    was fetched over Tor, which can never work. Entries now record the routing
    mode and `TorService.circuitGeneration`, and all three readers — including
    the `initialSource` fast path, which bypassed the check entirely — apply the
    same test. A rotation also now reopens `Unavailable` results immediately
    instead of making them wait out a 60s TTL.
*   **Preloads starved the visible video** (`NOSLOP_TOR_PRELOAD_BUDGET_V1`).
    Four resolves competed for `Semaphore(3)` with a 60s budget each, which is
    what "Gave up waiting 20s for a resolve slot" was. Under Tor: one slide
    ahead, none behind, 8s head start. Clearnet behaviour unchanged.

**Not yet fixed:** resolve and playback still use whatever circuit is current,
so a rotation between them costs the resolve. SOCKS stream isolation is the real
answer — see finding #17 in the audit register.

"""


def write_docs():
    # --- audit register ---
    if os.path.exists(AUDIT_DOC):
        with open(AUDIT_DOC, "r", encoding="utf-8") as f:
            existing = f.read()
        if AUDIT_MARKER_START in existing and AUDIT_MARKER_END in existing:
            new_block = AUDIT_BODY.split(AUDIT_MARKER_START, 1)[1].rsplit(AUDIT_MARKER_END, 1)[0]
            updated = re.sub(
                re.escape(AUDIT_MARKER_START) + r".*?" + re.escape(AUDIT_MARKER_END),
                AUDIT_MARKER_START + new_block + AUDIT_MARKER_END,
                existing,
                flags=re.DOTALL,
            )
            if updated != existing:
                with open(AUDIT_DOC, "w", encoding="utf-8") as f:
                    f.write(updated)
                APPLIED.append(f"{AUDIT_DOC}: register refreshed")
            else:
                SKIPPED.append(f"{AUDIT_DOC}: register already current")
        else:
            SKIPPED.append(f"{AUDIT_DOC}: exists without markers, left alone")
    else:
        os.makedirs(os.path.dirname(AUDIT_DOC), exist_ok=True)
        with open(AUDIT_DOC, "w", encoding="utf-8") as f:
            f.write(AUDIT_BODY)
        APPLIED.append(f"{AUDIT_DOC}: created")

    # --- project status ---
    if not os.path.exists(STATUS_DOC):
        FAILED.append(f"{STATUS_DOC} not found")
        return
    with open(STATUS_DOC, "r", encoding="utf-8") as f:
        status = f.read()
    if "## External Audit Fixes (2026-09-03)" in status:
        SKIPPED.append(f"{STATUS_DOC}: section already present")
        return
    if STATUS_ANCHOR not in status:
        FAILED.append(f"{STATUS_DOC}: '{STATUS_ANCHOR}' heading not found")
        return
    with open(STATUS_DOC, "w", encoding="utf-8") as f:
        f.write(status.replace(STATUS_ANCHOR, STATUS_INSERT + STATUS_ANCHOR, 1))
    APPLIED.append(f"{STATUS_DOC}: audit section inserted")


def main():
    if not os.path.exists("app/build.gradle.kts"):
        print("Run this from the NoSlop repo root (app/build.gradle.kts not found).")
        sys.exit(1)

    edit(TOR, TOR_FIELD_OLD, TOR_FIELD_NEW, "TorService: circuitGeneration counter",
         marker="NOSLOP_CIRCUIT_GENERATION_V1")
    edit(TOR, TOR_BUMP_OLD, TOR_BUMP_NEW, "TorService: bump generation on real rotation",
         marker="Circuit rotated — generation is now")

    edit(VIDEO, VIDEO_CLASS_OLD, VIDEO_CLASS_NEW, "VideoPlayer: CachedSource + stalenessReason()",
         marker="NOSLOP_ROUTE_AWARE_CACHE_V1")
    edit(VIDEO, VIDEO_ISCACHED_OLD, VIDEO_ISCACHED_NEW, "VideoPlayer: isSourceCached key + validity",
         marker="Scan the quality variants")
    edit(VIDEO, VIDEO_FRESH_OLD, VIDEO_FRESH_NEW, "VideoPlayer: freshOrNull uses stalenessReason",
         marker="unusable ($reason)")
    edit(VIDEO, VIDEO_STORE_OLD, VIDEO_STORE_NEW, "VideoPlayer: stamp route on write",
         marker="stamp the route this was resolved on")
    edit(VIDEO, VIDEO_INITIAL_OLD, VIDEO_INITIAL_NEW, "VideoPlayer: initialSource fast path",
         marker="this fast path read the map directly")

    edit(FEED, FEED_OLD, FEED_NEW, "UnifiedFeedTab: preload budget under Tor",
         marker="NOSLOP_TOR_PRELOAD_BUDGET_V1")

    write_docs()

    print("\n=== patch 04: video over Tor ===")
    for a in APPLIED:
        print(f"  APPLIED  {a}")
    for s in SKIPPED:
        print(f"  SKIPPED  {s}")
    for f in FAILED:
        print(f"  FAILED   {f}")
    print()
    if FAILED:
        print("Some edits did not apply. Nothing partial was written for those.")
        sys.exit(1)
    print("Verify with:  ./gradlew :app:testDebugUnitTest")
    print("Then:         ./gradlew :app:assembleRelease")


if __name__ == "__main__":
    main()
