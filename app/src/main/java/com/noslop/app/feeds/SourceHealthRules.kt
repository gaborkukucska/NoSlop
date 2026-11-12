// FILE: app/src/main/java/com/noslop/app/feeds/SourceHealthRules.kt
package com.noslop.app.feeds

/**
 * NOSLOP_SOURCE_STATUS_V1 — when a source counts as not responding. Pure, so it is tested on its own;
 * [SourceHealth] holds the state.
 */
object SourceHealthRules {

    /** Attempts in a row that must fail before a host is reported. */
    const val FAILURES_TO_REPORT = 3

    /** Hosts remembered at most; the least recently heard from are dropped first. */
    const val MAX_HOSTS = 500

    data class HostState(
        val consecutiveFailures: Int = 0,
        /** When the current run of failures began, or 0. */
        val streakStartedAt: Long = 0L,
        val lastSuccessAt: Long = 0L,
        /** "HTTP 429", or the exception's class name, for the last failure. */
        val lastProblem: String? = null
    ) {
        val lastHeardAt: Long get() = maxOf(lastSuccessAt, streakStartedAt)
    }

    /** API services (the part of a SourceLibrary api url before ":") and the hosts their requests go to. */
    val SERVICE_HOSTS: Map<String, List<String>> = mapOf(
        "youtube" to listOf("www.youtube.com"),
        "reddit" to listOf("www.reddit.com", "old.reddit.com"),
        "pexels" to listOf("api.pexels.com"),
        "archive" to listOf("archive.org"),
        "jamendo" to listOf("api.jamendo.com"),
        "openverse" to listOf("api.openverse.org"),
        "wikipedia" to listOf("wikipedia.org"),
        "hackernews" to listOf("hn.algolia.com"),
        "podcastindex" to listOf("api.podcastindex.org"),
        "guardian" to listOf("content.guardianapis.com"),
        "nasa" to listOf("api.nasa.gov", "images-api.nasa.gov"),
        "vimeo" to listOf("api.vimeo.com"),
        "wikimedia" to listOf("commons.wikimedia.org"),
        "artic" to listOf("api.artic.edu")
    )

    val SERVICE_NAMES: Map<String, String> = mapOf(
        "youtube" to "YouTube", "reddit" to "Reddit", "pexels" to "Pexels", "archive" to "Internet Archive",
        "jamendo" to "Jamendo", "openverse" to "Openverse", "wikipedia" to "Wikipedia", "hackernews" to "Hacker News",
        "podcastindex" to "Podcast Index", "guardian" to "The Guardian", "nasa" to "NASA", "vimeo" to "Vimeo",
        "wikimedia" to "Wikimedia Commons", "artic" to "Art Institute of Chicago"
    )

    fun isFailureStatus(code: Int): Boolean = code == 401 || code == 403 || code == 429 || code >= 500

    /** Hosts that never count: mesh peers and the phone itself. */
    fun isIgnored(host: String): Boolean =
        host.endsWith(".onion") || host == "localhost" || host == "127.0.0.1" || host == "::1"

    /** [map] after one attempt at [host] that [failed] (with [problem]) at [now]. */
    fun next(map: Map<String, HostState>, host: String, failed: Boolean, problem: String?, now: Long): Map<String, HostState> {
        val h = host.lowercase()
        if (isIgnored(h)) return map
        val old = map[h] ?: HostState()
        val updated = if (failed) {
            old.copy(
                consecutiveFailures = old.consecutiveFailures + 1,
                streakStartedAt = if (old.consecutiveFailures == 0) now else old.streakStartedAt,
                lastProblem = problem
            )
        } else {
            HostState(lastSuccessAt = now)
        }
        val out = map + (h to updated)
        if (out.size <= MAX_HOSTS) return out
        return out.entries.sortedByDescending { it.value.lastHeardAt }.take(MAX_HOSTS).associate { it.key to it.value }
    }

    /**
     * The hosts not responding: [FAILURES_TO_REPORT] attempts in a row failed, and another host answered
     * after the first of them, so the network itself was up.
     */
    fun failing(map: Map<String, HostState>): Map<String, HostState> {
        val latestSuccess = map.entries.sortedByDescending { it.value.lastSuccessAt }.take(2)
        return map.filter { (host, s) ->
            s.consecutiveFailures >= FAILURES_TO_REPORT &&
                latestSuccess.any { (other, o) -> other != host && o.lastSuccessAt > s.streakStartedAt }
        }
    }

    fun matches(host: String, pattern: String): Boolean = host == pattern || host.endsWith(".$pattern")

    /** The hosts behind a source: its API service's hosts, or its feed's host. */
    fun hostsOf(url: String, feedType: String): List<String> =
        if (feedType == "api") SERVICE_HOSTS[url.substringBefore(":")].orEmpty()
        else listOfNotNull(try { java.net.URI(url).host?.lowercase() } catch (e: Exception) { null })

    /** The failing state behind a source, or null while it answers. */
    fun problemFor(url: String, feedType: String, failing: Map<String, HostState>): HostState? =
        hostsOf(url, feedType).firstNotNullOfOrNull { pattern -> failing.entries.firstOrNull { matches(it.key, pattern) }?.value }

    /** Names of the API services whose hosts are failing, in a fixed order. */
    fun failingServiceNames(failing: Map<String, HostState>): List<String> =
        SERVICE_HOSTS.filter { (_, hosts) -> hosts.any { p -> failing.keys.any { matches(it, p) } } }
            .keys.mapNotNull { SERVICE_NAMES[it] }
}
