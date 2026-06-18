package com.noslop.mvp

/**
 * Raw per-device persistence for the user's saved hubs — a single opaque blob (the [SavedNodes] list,
 * newline-joined invite URIs). A pairing code, not a secret, so plain device storage (iOS NSUserDefaults /
 * Android SharedPreferences / a JVM file) is fine. `load()` is null until the first hub is saved. Mirrors
 * [HandleStore]; the list semantics live in [SavedNodes] so the platform actuals stay trivial.
 */
expect object HubStore {
    fun load(): String?
    fun save(blob: String)
    fun clear()
}

/**
 * The user's saved hubs, newest-first, deduped by `host:port`. Persisted via [HubStore] as newline-joined
 * `noslop://…` invite URIs. The app auto-reconnects to these on launch (try-each), so a paired phone never
 * has to re-scan. Scanning a QR [add]s a node; the connect screen can [remove] stale ones.
 */
object SavedNodes {
    private fun key(i: MeshInvite) = "${i.host}:${i.port}"

    /** All saved nodes, newest first. Silently drops any unparseable lines. */
    fun list(): List<MeshInvite> =
        HubStore.load()?.lineSequence()
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(MeshInvite::parse) }
            ?.toList()
            ?: emptyList()

    /** Add (or refresh) a node from its invite URI, moved to the front. No-op if the URI doesn't parse. */
    fun add(inviteUri: String) {
        val invite = MeshInvite.parse(inviteUri) ?: return
        val deduped = listOf(invite) + list().filter { key(it) != key(invite) }
        persist(deduped)
    }

    fun remove(invite: MeshInvite) = persist(list().filter { key(it) != key(invite) })

    private fun persist(nodes: List<MeshInvite>) {
        if (nodes.isEmpty()) HubStore.clear() else HubStore.save(nodes.joinToString("\n") { it.toUri() })
    }
}
