// FILE: app/src/main/java/com/noslop/app/data/HubClient.kt
package com.noslop.app.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.net.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * NOSLOP_HUB_OWNER_API_V1 — the phone's client for its own HAI-Net Home HUB.
 *
 * Every request is signed by the owner's personal identity (the Hub holds the same identity), in
 * four headers: `X-NoSlop-Hub-Key`, `X-NoSlop-Hub-Ts`, `X-NoSlop-Hub-Nonce` and `X-NoSlop-Hub-Sig` =
 * Ed25519 over `encodeForSigning("noslop-hub-api-v1", METHOD, PATH, ts, nonce, sha256hex(body bytes))`.
 * The Hub refuses anything unsigned, stale (±5 min), replayed or signed by another key; being able to
 * reach port 8080 on the LAN or the onion is no longer enough (hainet-noslop `hub::api`).
 *
 * Bodies are built with Gson ([com.noslop.app.util.Json.gson]) so packets the Hub passes on keep
 * their member order: the envelope signature covers Gson's rendering of the payload, and org.json
 * reorders members.
 *
 * Route: the private LAN address the Hub was linked at (cleartext only where the network security
 * config allows it), else the Hub's onion on port 8080 over Tor, as before.
 */
object HubClient {
    private const val TAG = "HUB_API"
    private const val LABEL = "noslop-hub-api-v1"
    private val JSON = "application/json".toMediaType()
    private val OCTETS = "application/octet-stream".toMediaType()
    private val random = SecureRandom()

    /** Whether the last request reached the Hub (for the Hub page). */
    @Volatile var lastReachedAt: Long = 0L

    // NOSLOP_HUB_LINK_RESILIENCE_V1 (2026-11-25): the background sync loop runs every 5 s and makes several Hub
    // calls in a row. While the Hub's onion cannot be reached each of them waited out its own connect timeout, so
    // one round took minutes and a reachable Hub was tried again only after all of them. Now, after a failed onion
    // connection, the next calls skip the onion for [ONION_RETRY_GAP_MS] (the next round tries again), the
    // connection gets [ONION_CONNECT_TIMEOUT_S] (a rendezvous with a cold circuit often takes 20-40 s on a phone),
    // and Tor is asked for the Hub's current descriptor (at most every [DESCRIPTOR_REFRESH_GAP_MS]), so a Hub whose
    // Tor restarted is found at its new introduction points. A refresh the user asks for clears the gap.
    @Volatile private var onionFailedAt: Long = 0L
    @Volatile private var descriptorRefreshedAt: Long = 0L
    private const val ONION_RETRY_GAP_MS = 15_000L
    private const val ONION_CONNECT_TIMEOUT_S = 45L
    private const val DESCRIPTOR_REFRESH_GAP_MS = 120_000L

    /** When this process started (the Hub counts as silent only from then on). */
    val startedAt: Long = System.currentTimeMillis()

    /** The user asked for a refresh: try the Hub's onion at once. */
    fun clearOnionBackoff() { onionFailedAt = 0L }

    private fun onionInBackoff(now: Long = System.currentTimeMillis()): Boolean =
        onionFailedAt > lastReachedAt && now - onionFailedAt < ONION_RETRY_GAP_MS

    private fun noteOnionFailure(baseUrl: String) {
        val now = System.currentTimeMillis()
        onionFailedAt = now
        if (now - descriptorRefreshedAt < DESCRIPTOR_REFRESH_GAP_MS) return
        descriptorRefreshedAt = now
        val host = baseUrl.removePrefix("http://").substringBefore(':')
        try {
            com.noslop.app.tor.TorService.refreshOnionDescriptor(host)
        } catch (e: Exception) {
            Logger.debug(TAG, "Descriptor refresh for the Hub not started: ${e.message}")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun nonce(): String {
        val b = ByteArray(24)
        random.nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /** The signature headers for one request (internal for tests). */
    internal fun signatureHeaders(keys: CryptoService.IdentityKeys, method: String, path: String, bodySha256Hex: String, now: Long = System.currentTimeMillis(), nonce: String = nonce()): Map<String, String> {
        val signed = CryptoService.encodeForSigning(LABEL, method, path, now.toString(), nonce, bodySha256Hex)
        return mapOf(
            "X-NoSlop-Hub-Key" to keys.publicKeyB64,
            "X-NoSlop-Hub-Ts" to now.toString(),
            "X-NoSlop-Hub-Nonce" to nonce,
            "X-NoSlop-Hub-Sig" to CryptoService.sign(signed, keys.privateKeyB64)
        )
    }

    private data class Route(val baseUrl: String, val client: OkHttpClient)

    private suspend fun routes(repo: NoSlopRepository, longTransfer: Boolean): List<Route> {
        val hubStatus = repo.getAppSetting("hub_deployment_status") ?: return emptyList()
        val out = mutableListOf<Route>()
        val isLegacy = hubStatus == "Active (Legacy Connection)"
        val lanIp = if (isLegacy) null else hubStatus.substringAfter("Active at ").trim()
        val canCleartextLan = NoSlopRepository.isPrivateLanAddress(lanIp) && lanIp != null && try {
            android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(lanIp)
        } catch (_: Throwable) { false }
        if (canCleartextLan && lanIp != null) {
            out.add(Route("http://$lanIp:8080", HttpClientProvider.rawClearnetClient.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(if (longTransfer) 300 else 30, TimeUnit.SECONDS)
                .writeTimeout(if (longTransfer) 300 else 30, TimeUnit.SECONDS)
                .build()))
        }
        val onion = repo.getLocalIdentity()?.onionAddress
        if (!onion.isNullOrBlank()) {
            val tor = if (longTransfer) HttpClientProvider.torClient.newBuilder().connectTimeout(ONION_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS).writeTimeout(600, TimeUnit.SECONDS).build()
            else HttpClientProvider.torClient.newBuilder().connectTimeout(ONION_CONNECT_TIMEOUT_S, TimeUnit.SECONDS).build()
            out.add(Route("http://$onion:8080", tor))
        }
        return out
    }

    /**
     * Sends one signed request, trying each route in turn. [handle] reads the response (called only for
     * a 2xx); returns null when no route answered with success.
     */
    suspend fun <T> request(
        repo: NoSlopRepository,
        method: String,
        path: String,
        body: ByteArray,
        contentType: okhttp3.MediaType = JSON,
        longTransfer: Boolean = false,
        handle: (Response) -> T
    ): T? = requestWith(repo, method, path, sha256Hex(body), { body.toRequestBody(contentType) }, longTransfer, handle)

    /** As [request], for a body given by a factory (a file streamed from disk) with its SHA-256. */
    suspend fun <T> requestWith(
        repo: NoSlopRepository,
        method: String,
        path: String,
        bodySha256Hex: String,
        bodyFactory: () -> okhttp3.RequestBody,
        longTransfer: Boolean,
        handle: (Response) -> T
    ): T? = withContext(Dispatchers.IO) {
        val keys = repo.getLocalIdentity() ?: return@withContext null
        for (route in routes(repo, longTransfer)) {
            val isOnion = route.baseUrl.endsWith(".onion:8080")
            if (isOnion && onionInBackoff()) {
                Logger.debug(TAG, "$method $path: the Hub's onion failed moments ago; skipped this time")
                continue
            }
            try {
                val builder = Request.Builder().url(route.baseUrl + path)
                // A fresh signature per attempt: the nonce must never be reused.
                signatureHeaders(keys, method, path, bodySha256Hex).forEach { (k, v) -> builder.header(k, v) }
                if (method == "POST") builder.post(bodyFactory()) else builder.get()
                route.client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        lastReachedAt = System.currentTimeMillis()
                        return@withContext handle(response)
                    }
                    val err = response.body?.string()?.take(300)
                    Logger.warn(TAG, "$method $path via ${route.baseUrl.take(24)} returned HTTP ${response.code}: $err")
                    if (response.code == 401) return@withContext null // a refused signature won't pass on another route
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "$method $path via ${route.baseUrl.take(24)} failed: ${e.message}")
                if (isOnion && e is java.io.IOException) noteOnionFailure(route.baseUrl)
            }
        }
        null
    }

    /** A reply from the Hub: the HTTP status and the body text. */
    data class Reply(val code: Int, val body: String)

    /**
     * NOSLOP_HUB_QR_LOGIN_ROUTE_V1 — an unsigned POST to one of the Hub's login routes (`/api/auth/...`),
     * which check their own signature inside the body. Same routes as [request]: the LAN address where
     * the network security config allows cleartext, else the Hub's onion over Tor. [scannedHost], the
     * address a QR code named, is tried after the LAN address when it is an onion or an allowed LAN host.
     * The first route that answers at all gives the reply; null when none did.
     */
    suspend fun postUnsigned(repo: NoSlopRepository, path: String, body: ByteArray, scannedHost: String? = null): Reply? = withContext(Dispatchers.IO) {
        val base = routes(repo, longTransfer = false)
        val scanned = scannedHost?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { scannedRoute(it) }
            ?.takeIf { s -> base.none { it.baseUrl == s.baseUrl } }
        // The first connection to an onion after Tor (re)starts can take well over the usual 20 s, and someone is
        // waiting at the browser: onion routes get 60 s to connect here.
        val ordered = (base.filterNot { it.baseUrl.endsWith(".onion:8080") } + listOfNotNull(scanned) +
            base.filter { it.baseUrl.endsWith(".onion:8080") }).map { r ->
            if (r.baseUrl.endsWith(".onion:8080")) r.copy(client = r.client.newBuilder().connectTimeout(60, TimeUnit.SECONDS).build()) else r
        }
        for (route in ordered) {
            try {
                val req = Request.Builder().url(route.baseUrl + path).post(body.toRequestBody(JSON)).build()
                route.client.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.isSuccessful) lastReachedAt = System.currentTimeMillis()
                    return@withContext Reply(r.code, text)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "POST $path via ${route.baseUrl.take(24)} failed: ${e.message}")
            }
        }
        null
    }

    private fun scannedRoute(host: String): Route? = when {
        host.endsWith(".onion") -> Route("http://$host:8080", HttpClientProvider.torClient)
        NoSlopRepository.isPrivateLanAddress(host) && try {
            android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
        } catch (_: Throwable) { false } -> Route("http://$host:8080", HttpClientProvider.rawClearnetClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build())
        else -> null
    }

    /** `/api/invoke` with a Gson body; the reply as a Gson object. */
    suspend fun invoke(repo: NoSlopRepository, cmd: String, args: JsonObject = JsonObject(), longTransfer: Boolean = false): JsonObject? {
        val body = JsonObject().apply { addProperty("cmd", cmd); add("args", args) }
        val bytes = com.noslop.app.util.Json.gson.toJson(body).toByteArray(Charsets.UTF_8)
        return request(repo, "POST", "/api/invoke", bytes, longTransfer = longTransfer) { r ->
            val text = r.body?.string().orEmpty().ifBlank { "{}" }
            try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { JsonObject() }
        }
    }

    /** `/api/invoke` with a raw JSON args string (org.json callers); the reply as text. */
    suspend fun invokeRaw(repo: NoSlopRepository, cmd: String, argsJson: String): String? {
        val bytes = "{\"cmd\":${com.noslop.app.util.Json.gson.toJson(cmd)},\"args\":$argsJson}".toByteArray(Charsets.UTF_8)
        return request(repo, "POST", "/api/invoke", bytes) { r -> r.body?.string().orEmpty().ifBlank { "{}" } }
    }

    /** `hub_send`: each packet to the onion the phone chose. */
    suspend fun send(repo: NoSlopRepository, items: List<Pair<String, com.noslop.app.mesh.NetworkPacket>>): Boolean {
        if (items.isEmpty()) return true
        val arr = JsonArray()
        for ((onion, packet) in items) {
            arr.add(JsonObject().apply {
                addProperty("onion", onion)
                add("packet", com.noslop.app.util.Json.gson.toJsonTree(packet))
            })
        }
        val res = invoke(repo, "hub_send", JsonObject().apply { add("items", arr) }) ?: return false
        val rejected = res.get("rejected")?.takeIf { it.isJsonArray }?.asJsonArray
        if (rejected != null && rejected.size() > 0) Logger.warn(TAG, "hub_send: the Hub refused ${rejected.size()} item(s): $rejected")
        return true
    }

    /** `POST /api/backup/push`: the encrypted backup file as the raw body. */
    suspend fun pushBackup(repo: NoSlopRepository, file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        requestWith(repo, "POST", "/api/backup/push", sha, { file.asRequestBody(OCTETS) }, longTransfer = true) { true } ?: false
    }

    fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
    fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    fun JsonElement.objOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
}
