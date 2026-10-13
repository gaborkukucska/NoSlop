package com.noslop.app.data

import com.noslop.app.debug.Logger
import com.noslop.app.mesh.MediaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** D10d: what a creator identity file holds, or what a restore brought in. */
data class CreatorArchiveSummary(
    val handle: String,
    val contacts: Int,
    val posts: Int,
    val mediaFiles: Int,
    /** Posts whose media file was not on the exporting device (the post is kept, its media is not). */
    val missingMedia: Int
)

/** D10d: the outcome of restoring a creator identity file. */
sealed class CreatorRestoreResult {
    /** Restored. [skippedContacts] were already this device's personal contacts and were left alone. */
    data class Restored(val summary: CreatorArchiveSummary, val skippedContacts: Int) : CreatorRestoreResult()

    /**
     * This device already has a different secondary identity. Nothing was changed; restoring again with
     * `replaceExisting` replaces it (the creators it follows and the contacts that know it lose it).
     */
    object ConfirmReplace : CreatorRestoreResult()

    /** Nothing was changed. */
    data class Failed(val reason: String) : CreatorRestoreResult()
}

/**
 * D10d: exports and restores the creator identity on its own, to back it up or move it to another
 * device. The file holds the creator keys, the Studio profile and donation link, the contacts bound to
 * the creator identity (followers, and creators it follows), the creator's posts and their media files.
 *
 * File format "NSC1" (integers big-endian):
 *
 *     header = "NSC1" | version (1 byte) | Argon2id iterations (1 byte) | Argon2id memory in KiB (4) |
 *              salt (16) | nonce prefix (7)
 *     key    = Argon2id(passphrase, salt, iterations, memory, parallelism 1), 32 bytes
 *     body   = chunks, each: last flag (1 byte, 1 on the last chunk) | length (4) |
 *              AES-256-GCM of at most 1 MiB of plaintext, nonce = nonce prefix | chunk number (4) | last
 *              flag, associated data = the header
 *
 * The last chunk is always written (it may be empty) and nothing may follow it, so a truncated,
 * reordered or extended file is refused. The plaintext is a zip: manifest.json, contacts.json,
 * posts.json and media/<media id>.
 *
 * Each chunk is authenticated on its own, so a large video never has to fit in memory (the full
 * backup's single GCM stream has to be held until its tag is checked). A restore stages everything and
 * changes nothing on the device until the whole file has been read and authenticated.
 */
object CreatorIdentityArchive {
    private const val TAG = "CREATOR_ARCHIVE"
    private val MAGIC = "NSC1".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val FORMAT = 1
    internal const val HEADER_SIZE = 4 + 1 + 1 + 4 + 16 + 7
    internal const val CHUNK_SIZE = 1024 * 1024
    private const val GCM_TAG_BYTES = 16
    internal const val KDF_ITERATIONS = 3
    internal const val KDF_MEMORY_KIB = 65536
    private const val MAX_JSON_BYTES = 64L * 1024 * 1024

    /** The shortest passphrase accepted (after trimming spaces at either end). */
    const val MIN_PASSPHRASE_LENGTH = 10

    /** File name extension for creator identity files. */
    const val FILE_EXTENSION = "nsc"

    private const val ERR_NOT_A_CREATOR_FILE = "This is not a creator identity file"
    private const val ERR_PASSPHRASE = "The passphrase does not open this file, or the file is damaged"
    private const val ERR_DAMAGED = "The file is damaged or was modified"

    /** A chunk failed authentication. [chunk] is its number (0 for the first). */
    internal class ChunkAuthenticationException(val chunk: Int, cause: Throwable?) :
        IOException("Chunk $chunk failed authentication", cause)

    // ------------------------------------------------------------------ export

    /**
     * Writes the creator identity file to [target] and closes it. Throws when there is no creator
     * identity or the passphrase is too short.
     */
    suspend fun export(repo: NoSlopRepository, passphrase: String, target: OutputStream): CreatorArchiveSummary =
        withContext(Dispatchers.IO) {
            val secret = passphrase.trim()
            require(secret.length >= MIN_PASSPHRASE_LENGTH) { "The passphrase must have at least $MIN_PASSPHRASE_LENGTH characters" }
            val creator = repo.getBurnableIdentity() ?: throw IllegalStateException("There is no creator identity on this device")
            val handle = repo.secondaryIdentityProfile().handle
            val profile = repo.getCreatorProfile()
            val fundMeLink = repo.getAppSetting("creator_fundme_link")?.takeIf { it.isNotBlank() }

            val contacts = repo.peerDao.getAllPeersList().filter { peer ->
                peer.publicKeyB64 != creator.publicKeyB64 &&
                    repo.getAppSetting("contact_identity_${peer.publicKeyB64}") == "burnable"
            }
            val posts = repo.postDao.getPostsSince(0L).filter { it.authorPublicKeyB64 == creator.publicKeyB64 && !it.isOrphaned }

            val random = SecureRandom()
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val noncePrefix = ByteArray(7).also { random.nextBytes(it) }
            val header = buildHeader(KDF_ITERATIONS, KDF_MEMORY_KIB, salt, noncePrefix)
            val key = deriveKey(secret, salt, KDF_ITERATIONS, KDF_MEMORY_KIB)

            var mediaFiles = 0
            var missingMedia = 0
            val digests = JSONObject()
            target.write(header)
            val sealed = ChunkedAeadOutputStream(target, key, header, noncePrefix)
            ZipOutputStream(BufferedOutputStream(sealed, 64 * 1024)).use { zip ->
                writeEntry(zip, "contacts.json", JSONArray().apply { contacts.forEach { put(peerToJson(it)) } }.toString())
                writeEntry(zip, "posts.json", JSONArray().apply { posts.forEach { put(postToJson(it)) } }.toString())

                val written = mutableSetOf<String>()
                for (post in posts) {
                    val mediaId = mediaIdOf(post) ?: continue
                    if (mediaId in written) continue
                    val file = MediaManager.getLocalFile(mediaId, post.mediaType)
                    if (file == null || !file.isFile) {
                        missingMedia++
                        continue
                    }
                    zip.putNextEntry(ZipEntry("media/$mediaId"))
                    file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                    written.add(mediaId)
                    mediaFiles++
                    repo.mediaDigestFor(mediaId)?.let { digests.put(mediaId, it) }
                }

                val manifest = JSONObject().apply {
                    put("format", FORMAT)
                    put("exportedAt", System.currentTimeMillis())
                    put("creator", JSONObject().apply {
                        put("publicKeyB64", creator.publicKeyB64)
                        put("privateKeyB64", creator.privateKeyB64)
                        put("encPublicKeyB64", creator.encPublicKeyB64)
                        put("encPrivateKeyB64", creator.encPrivateKeyB64)
                        put("tripcode", creator.tripcode)
                        put("onionAddress", creator.onionAddress)
                        put("displayName", creator.displayName)
                    })
                    put("profile", JSONObject().apply {
                        put("displayName", profile.displayName)
                        put("bio", profile.bio)
                        profile.avatarB64?.takeIf { it.isNotBlank() }?.let { put("avatarB64", it) }
                    })
                    fundMeLink?.let { put("fundMeLink", it) }
                    put("mediaDigests", digests)
                    put("handle", handle)
                    put("contacts", contacts.size)
                    put("posts", posts.size)
                    put("mediaFiles", mediaFiles)
                    put("missingMedia", missingMedia)
                }
                writeEntry(zip, "manifest.json", manifest.toString())
            }
            Logger.info(TAG, "Creator identity exported", "contacts=${contacts.size} | posts=${posts.size} | media=$mediaFiles | missingMedia=$missingMedia")
            CreatorArchiveSummary(handle, contacts.size, posts.size, mediaFiles, missingMedia)
        }

    // ------------------------------------------------------------------ restore

    /**
     * Restores a creator identity file from [source] (not closed here). Makes this device the creator:
     * installs the keys, the Studio profile and donation link, turns creator mode on, and adds the
     * contacts, posts and media files. Restoring the same file again is safe; existing rows are kept.
     */
    suspend fun restore(
        repo: NoSlopRepository,
        passphrase: String,
        source: InputStream,
        replaceExisting: Boolean
    ): CreatorRestoreResult = withContext(Dispatchers.IO) {
        val stageDir = File(repo.context.noBackupFilesDir, "creator_restore_${System.currentTimeMillis()}")
        try {
            val input = BufferedInputStream(source, 64 * 1024)
            val header = ByteArray(HEADER_SIZE)
            if (!readFully(input, header) || !header.copyOfRange(0, 4).contentEquals(MAGIC)) {
                return@withContext CreatorRestoreResult.Failed(ERR_NOT_A_CREATOR_FILE)
            }
            if ((header[4].toInt() and 0xFF) != VERSION) {
                return@withContext CreatorRestoreResult.Failed("This creator identity file was made by a newer version of NoSlop")
            }
            val iterations = header[5].toInt() and 0xFF
            val memoryKiB = intAt(header, 6)
            // Bounds keep a crafted file from asking for absurd work or memory.
            if (iterations !in 1..10 || memoryKiB !in 8192..262144) {
                return@withContext CreatorRestoreResult.Failed(ERR_DAMAGED)
            }
            val salt = header.copyOfRange(10, 26)
            val noncePrefix = header.copyOfRange(26, 33)
            val key = deriveKey(passphrase.trim(), salt, iterations, memoryKiB)

            // 1. Read and authenticate the whole file into the staging folder. Nothing changes yet.
            stageDir.mkdirs()
            var manifestText: String? = null
            var contactsText: String? = null
            var postsText: String? = null
            val stagedMedia = mutableMapOf<String, File>()
            val sealed = ChunkedAeadInputStream(input, key, header, noncePrefix)
            try {
                val zip = ZipInputStream(sealed)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == "manifest.json" -> manifestText = readText(zip)
                        name == "contacts.json" -> contactsText = readText(zip)
                        name == "posts.json" -> postsText = readText(zip)
                        name.startsWith("media/") -> {
                            val mediaId = name.removePrefix("media/")
                            if (MediaManager.isValidMediaId(mediaId) && !mediaId.contains('/') && !mediaId.contains('\\')) {
                                val staged = File(stageDir, mediaId)
                                BufferedOutputStream(FileOutputStream(staged)).use { zip.copyTo(it, 64 * 1024) }
                                stagedMedia[mediaId] = staged
                            }
                        }
                    }
                    zip.closeEntry()
                }
                // The zip ends before the file does: read the rest, so the last chunk is checked and
                // nothing may follow it.
                sealed.drainToEnd()
            } catch (e: ChunkAuthenticationException) {
                Logger.warn(TAG, "Creator identity file failed authentication at chunk ${e.chunk}")
                return@withContext CreatorRestoreResult.Failed(if (e.chunk == 0) ERR_PASSPHRASE else ERR_DAMAGED)
            } catch (e: IOException) {
                Logger.warn(TAG, "Creator identity file could not be read: ${e.message}")
                return@withContext CreatorRestoreResult.Failed(ERR_DAMAGED)
            }

            // 2. Check what was read.
            val manifest = try {
                JSONObject(manifestText ?: return@withContext CreatorRestoreResult.Failed(ERR_DAMAGED))
            } catch (e: Exception) {
                return@withContext CreatorRestoreResult.Failed(ERR_DAMAGED)
            }
            if (manifest.optInt("format", -1) != FORMAT) {
                return@withContext CreatorRestoreResult.Failed("This creator identity file was made by a newer version of NoSlop")
            }
            val creatorObj = manifest.optJSONObject("creator") ?: return@withContext CreatorRestoreResult.Failed(ERR_DAMAGED)
            repo.burnableIdentityProblem(creatorObj)?.let { problem ->
                Logger.warn(TAG, "Creator keys in the file are not usable: $problem")
                return@withContext CreatorRestoreResult.Failed("The creator keys in this file are not valid")
            }
            val creatorPub = creatorObj.getString("publicKeyB64")
            val main = repo.getLocalIdentity()
                ?: return@withContext CreatorRestoreResult.Failed("Set up this device's own identity first")
            if (creatorPub == main.publicKeyB64) {
                return@withContext CreatorRestoreResult.Failed("This file holds this device's personal identity")
            }
            val contacts = try {
                JSONArray(contactsText ?: "[]").let { arr -> (0 until arr.length()).map { peerFromJson(arr.getJSONObject(it)) } }
            } catch (e: Exception) {
                return@withContext CreatorRestoreResult.Failed(ERR_DAMAGED)
            }
            val posts = try {
                JSONArray(postsText ?: "[]").let { arr -> (0 until arr.length()).map { postFromJson(arr.getJSONObject(it)) } }
            } catch (e: Exception) {
                return@withContext CreatorRestoreResult.Failed(ERR_DAMAGED)
            }
            if (posts.any { it.authorPublicKeyB64 != creatorPub }) {
                return@withContext CreatorRestoreResult.Failed(ERR_DAMAGED)
            }

            val existing = repo.getBurnableIdentity()
            val sameIdentity = existing?.publicKeyB64 == creatorPub
            if (existing != null && !sameIdentity && !replaceExisting) {
                return@withContext CreatorRestoreResult.ConfirmReplace
            }

            // 3. Apply. Keys first; every later step only adds, so restoring again completes a restore
            // that stopped part way.
            if (!sameIdentity) {
                if (existing != null) repo.clearBurnableIdentity()
                val installed = repo.restoreBurnableIdentity(creatorObj)
                if (installed !is IdentityRestoreResult.Success) {
                    Logger.error(TAG, "Installing the creator keys failed: $installed")
                    return@withContext CreatorRestoreResult.Failed("The creator keys could not be stored on this device")
                }
            }
            manifest.optJSONObject("profile")?.let { p ->
                repo.saveCreatorProfile(
                    UserProfile(
                        displayName = p.optString("displayName", ""),
                        bio = p.optString("bio", ""),
                        avatarB64 = optNullableString(p, "avatarB64")
                    )
                )
            }
            repo.putAppSetting("creator_fundme_link", optNullableString(manifest, "fundMeLink") ?: "")
            repo.putAppSetting(CREATOR_MODE_KEY, "true")

            var restoredContacts = 0
            var skippedContacts = 0
            for (peer in contacts) {
                if (peer.publicKeyB64 == main.publicKeyB64 || peer.publicKeyB64 == creatorPub) continue
                val known = repo.peerDao.getPeerByPublicKey(peer.publicKeyB64)
                val binding = repo.getAppSetting("contact_identity_${peer.publicKeyB64}")
                if (known != null && binding != "burnable" && known.relationship != PeerRelationship.NONE) {
                    // Someone this device knows by its personal identity: one row cannot be both.
                    skippedContacts++
                    continue
                }
                if (known == null || binding != "burnable") {
                    repo.peerDao.insertPeer(peer.copy(isOnline = false, pendingNonce = null, pendingEncKey = null))
                }
                repo.putAppSetting("contact_identity_${peer.publicKeyB64}", "burnable")
                restoredContacts++
            }

            var restoredPosts = 0
            for (post in posts) {
                if (repo.postDao.getPostById(post.id) == null) {
                    repo.postDao.insertPost(post)
                    restoredPosts++
                }
            }

            var restoredMedia = 0
            for ((mediaId, staged) in stagedMedia) {
                val type = posts.firstOrNull { mediaIdOf(it) == mediaId }?.mediaType
                if (MediaManager.copyFileToMediaDirectory(staged, type, mediaId) != null) restoredMedia++
            }
            val digests = manifest.optJSONObject("mediaDigests")
            digests?.keys()?.forEach { mediaId -> repo.recordMediaDigest(mediaId, digests.optString(mediaId)) }

            val handle = repo.secondaryIdentityProfile().handle
            Logger.info(TAG, "Creator identity restored",
                "contacts=$restoredContacts | skipped=$skippedContacts | posts=$restoredPosts | media=$restoredMedia")
            CreatorRestoreResult.Restored(
                CreatorArchiveSummary(handle, restoredContacts, posts.size, restoredMedia, manifest.optInt("missingMedia", 0)),
                skippedContacts
            )
        } catch (e: Exception) {
            Logger.error(TAG, "Creator identity restore failed: ${e.message}")
            CreatorRestoreResult.Failed(e.message ?: "Restore failed")
        } finally {
            stageDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ crypto and format

    internal fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int, memoryKiB: Int): SecretKeySpec {
        val params = org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKiB)
            .withParallelism(1)
            .withSalt(salt)
            .build()
        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(32)
        generator.generateBytes(passphrase.toByteArray(Charsets.UTF_8), out, 0, out.size)
        return SecretKeySpec(out, "AES")
    }

    internal fun buildHeader(iterations: Int, memoryKiB: Int, salt: ByteArray, noncePrefix: ByteArray): ByteArray {
        require(salt.size == 16 && noncePrefix.size == 7)
        val out = ByteArrayOutputStream(HEADER_SIZE)
        out.write(MAGIC)
        out.write(VERSION)
        out.write(iterations)
        out.write(intBytes(memoryKiB))
        out.write(salt)
        out.write(noncePrefix)
        return out.toByteArray()
    }

    private fun nonceFor(noncePrefix: ByteArray, chunk: Int, last: Boolean): ByteArray =
        noncePrefix + intBytes(chunk) + byteArrayOf(if (last) 1 else 0)

    /** Encrypts plaintext into length-prefixed, individually authenticated chunks (see the class comment). */
    internal class ChunkedAeadOutputStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val noncePrefix: ByteArray
    ) : OutputStream() {
        private val buffer = ByteArray(CHUNK_SIZE)
        private var filled = 0
        private var chunk = 0
        private var closed = false

        override fun write(b: Int) {
            check(!closed) { "stream closed" }
            if (filled == CHUNK_SIZE) seal(last = false)
            buffer[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (filled == CHUNK_SIZE) seal(last = false)
                val n = minOf(remaining, CHUNK_SIZE - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n
                offset += n
                remaining -= n
            }
        }

        private fun seal(last: Boolean) {
            check(chunk < Int.MAX_VALUE) { "file too large" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BYTES * 8, nonceFor(noncePrefix, chunk, last)))
            cipher.updateAAD(header)
            val sealedChunk = cipher.doFinal(buffer, 0, filled)
            out.write(if (last) 1 else 0)
            out.write(intBytes(sealedChunk.size))
            out.write(sealedChunk)
            chunk++
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            seal(last = true)
            closed = true
            out.flush()
            out.close()
        }
    }

    /** Reads and authenticates the chunks written by [ChunkedAeadOutputStream]. */
    internal class ChunkedAeadInputStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val noncePrefix: ByteArray
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var position = 0
        private var chunk = 0
        private var finished = false

        private fun nextChunk(): Boolean {
            if (finished) return false
            val flag = input.read()
            if (flag == -1) throw IOException("The file ends before its last part")
            if (flag != 0 && flag != 1) throw ChunkAuthenticationException(chunk, null)
            val lengthBytes = ByteArray(4)
            if (!readFully(input, lengthBytes)) throw IOException("The file ends before its last part")
            val length = intAt(lengthBytes, 0)
            if (length < GCM_TAG_BYTES || length > CHUNK_SIZE + GCM_TAG_BYTES) throw ChunkAuthenticationException(chunk, null)
            val sealedChunk = ByteArray(length)
            if (!readFully(input, sealedChunk)) throw IOException("The file ends before its last part")
            val last = flag == 1
            plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BYTES * 8, nonceFor(noncePrefix, chunk, last)))
                cipher.updateAAD(header)
                cipher.doFinal(sealedChunk)
            } catch (e: java.security.GeneralSecurityException) {
                throw ChunkAuthenticationException(chunk, e)
            }
            position = 0
            chunk++
            if (last) {
                finished = true
                if (input.read() != -1) throw ChunkAuthenticationException(chunk, null)
            }
            return true
        }

        override fun read(): Int {
            while (position >= plain.size) {
                if (!nextChunk()) return -1
            }
            return plain[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= plain.size) {
                if (!nextChunk()) return -1
            }
            val n = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, n)
            position += n
            return n
        }

        /** Reads to the end, so the last chunk is authenticated and nothing may follow it. */
        fun drainToEnd() {
            val buf = ByteArray(64 * 1024)
            while (read(buf, 0, buf.size) != -1) { /* discard */ }
        }

        override fun close() = input.close()
    }

    // ------------------------------------------------------------------ rows as JSON (explicit, so R8 renaming cannot break them)

    internal fun peerToJson(p: Peer): JSONObject = JSONObject().apply {
        put("publicKeyB64", p.publicKeyB64)
        put("handle", p.handle)
        put("tripcode", p.tripcode)
        put("onionAddress", p.onionAddress)
        put("encPublicKeyB64", p.encPublicKeyB64)
        put("lastSeenAt", p.lastSeenAt)
        p.authorAvatarB64?.let { put("authorAvatarB64", it) }
        p.customFolder?.let { put("customFolder", it) }
        put("isTemporary", p.isTemporary)
        put("isDiscoverable", p.isDiscoverable)
        put("isCreator", p.isCreator)
        p.fundMeLink?.let { put("fundMeLink", it) }
        p.bio?.let { put("bio", it) }
        put("isFollowing", p.isFollowing)
        put("relationship", p.relationship)
        p.verifiedFingerprint?.let { put("verifiedFingerprint", it) }
    }

    internal fun peerFromJson(o: JSONObject): Peer = Peer(
        publicKeyB64 = o.getString("publicKeyB64"),
        handle = o.getString("handle"),
        tripcode = o.getString("tripcode"),
        onionAddress = o.getString("onionAddress"),
        encPublicKeyB64 = o.optString("encPublicKeyB64", ""),
        lastSeenAt = o.optLong("lastSeenAt", System.currentTimeMillis()),
        authorAvatarB64 = optNullableString(o, "authorAvatarB64"),
        customFolder = optNullableString(o, "customFolder"),
        isTemporary = o.optBoolean("isTemporary", true),
        isDiscoverable = o.optBoolean("isDiscoverable", false),
        isCreator = o.optBoolean("isCreator", false),
        fundMeLink = optNullableString(o, "fundMeLink"),
        bio = optNullableString(o, "bio"),
        isFollowing = o.optBoolean("isFollowing", false),
        relationship = o.optString("relationship", PeerRelationship.NONE),
        verifiedFingerprint = optNullableString(o, "verifiedFingerprint")
    ).withNormalizedTrust()

    internal fun postToJson(p: MeshPost): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("authorPublicKeyB64", p.authorPublicKeyB64)
        put("authorHandle", p.authorHandle)
        put("authorTripcode", p.authorTripcode)
        p.authorAvatarB64?.let { put("authorAvatarB64", it) }
        put("content", p.content)
        put("timestamp", p.timestamp)
        put("signature", p.signature)
        p.mediaUrl?.let { put("mediaUrl", it) }
        p.mediaType?.let { put("mediaType", it) }
        put("gossipCount", p.gossipCount)
        put("privacy", p.privacy)
        p.thumbnailB64?.let { put("thumbnailB64", it) }
        p.clearnetUrl?.let { put("clearnetUrl", it) }
        p.clearnetTitle?.let { put("clearnetTitle", it) }
        p.clearnetThumbnailUrl?.let { put("clearnetThumbnailUrl", it) }
        p.clearnetMediaType?.let { put("clearnetMediaType", it) }
        put("mediaSize", p.mediaSize)
    }

    internal fun postFromJson(o: JSONObject): MeshPost = MeshPost(
        id = o.getString("id"),
        authorPublicKeyB64 = o.getString("authorPublicKeyB64"),
        authorHandle = o.getString("authorHandle"),
        authorTripcode = o.getString("authorTripcode"),
        authorAvatarB64 = optNullableString(o, "authorAvatarB64"),
        content = o.getString("content"),
        timestamp = o.getLong("timestamp"),
        signature = o.getString("signature"),
        mediaUrl = optNullableString(o, "mediaUrl"),
        mediaType = optNullableString(o, "mediaType"),
        gossipCount = o.optInt("gossipCount", 1),
        privacy = o.optString("privacy", "public"),
        thumbnailB64 = optNullableString(o, "thumbnailB64"),
        clearnetUrl = optNullableString(o, "clearnetUrl"),
        clearnetTitle = optNullableString(o, "clearnetTitle"),
        clearnetThumbnailUrl = optNullableString(o, "clearnetThumbnailUrl"),
        clearnetMediaType = optNullableString(o, "clearnetMediaType"),
        mediaSize = o.optLong("mediaSize", 0L)
    )

    // ------------------------------------------------------------------ helpers

    private fun mediaIdOf(post: MeshPost): String? =
        post.mediaUrl?.substringAfterLast("/")?.takeIf { MediaManager.isValidMediaId(it) }

    private fun optNullableString(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.getString(key).takeIf { it.isNotEmpty() } else null

    private fun writeEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            total += n
            if (total > MAX_JSON_BYTES) throw IOException("An entry in the file is too large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun intBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun intAt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or (b[offset + 3].toInt() and 0xFF)

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }
}
