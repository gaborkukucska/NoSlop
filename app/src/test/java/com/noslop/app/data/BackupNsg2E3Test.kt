package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom

/**
 * C07 / C06 (round E3), end to end through BackupManager: exports are NSG2 and leave no plaintext file
 * behind, a restore streams the archive straight into staging, and a modified, truncated or unfinished
 * archive changes nothing on the device. Older NSG1 and CBC backups still restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupNsg2E3Test {

    private val words = "apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon"
    private lateinit var context: Context
    private lateinit var testGroupKey: javax.crypto.SecretKey

    @Before
    fun setUp() = runBlocking<Unit> {
        context = RuntimeEnvironment.getApplication()
        val kg = javax.crypto.KeyGenerator.getInstance("AES")
        kg.init(256)
        testGroupKey = kg.generateKey()
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = { testGroupKey }
        IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
            .saveIdentity("Alice", CryptoService.generateIdentity("Alice"), words)
    }

    @After
    fun tearDown() {
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = null
        com.noslop.app.mesh.GossipService.resetForTesting()
        com.noslop.app.mesh.MediaManager.resetForTesting()
        NoSlopDatabase.closeInstance()
    }

    private fun export(option: BackupMediaOption = BackupMediaOption.NONE): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(BackupManager.exportData(context, words, out, option))
        return out.toByteArray()
    }

    private fun restore(bytes: ByteArray, allowLegacy: Boolean = false) =
        BackupManager.importBackup(context, words, ByteArrayInputStream(bytes), allowLegacyUnauthenticated = allowLegacy)

    private fun setMarker(value: String) = runBlocking {
        NoSlopDatabase.getDatabase(context).appSettingDao().insertSetting(AppSetting("e3_marker", value))
    }

    private fun marker() = runBlocking { NoSlopDatabase.getDatabase(context).appSettingDao().getSetting("e3_marker") }

    private fun pictures(): File {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir
        return File(base, "NoSlop").apply { mkdirs() }
    }

    private fun assertRefusedAndUnchanged(result: ImportResult, expectedMarker: String) {
        assertTrue("expected a failure, got $result", result is ImportResult.Failed)
        assertTrue("nothing was replaced", (result as ImportResult.Failed).rolledBack)
        assertEquals(expectedMarker, marker())
    }

    @Test
    fun export_isNsg2_andRoundTrips() {
        setMarker("from-archive")
        val archive = export()
        assertEquals("NSG2", String(archive, 0, 4, Charsets.US_ASCII))
        setMarker("changed")

        assertEquals(ImportResult.Success, restore(archive))
        assertEquals("from-archive", marker())
    }

    @Test
    fun export_writesNoPlaintextFiles_andCarriesGroupMessages() = runBlocking<Unit> {
        val db = NoSlopDatabase.getDatabase(context)
        listOf("m1", "m2").forEach { id ->
            val (enc, iv) = com.noslop.app.crypto.GroupMessageCrypto.encrypt("hello $id", "grp-e3", id)
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO chat_messages (id, chatWithPeerPub, senderPub, ciphertext, nonce, timestamp, isRead) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(id, "grp-e3", "sender", enc, iv, System.currentTimeMillis(), 1L)
            )
        }
        val cacheBefore = context.cacheDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet()

        val archive = export(BackupMediaOption.ALL)

        val created = context.cacheDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet() - cacheBefore
        assertTrue("export left files behind: $created", created.isEmpty())
        val text = String(archive, Charsets.ISO_8859_1)
        assertFalse(text.contains("PK\u0003\u0004"))
        assertFalse(text.contains("hello m1"))

        val entries = BackupArchiveTestSupport.entries(BackupArchiveTestSupport.zipOf(archive, words))
        val rows = org.json.JSONArray(String(entries.getValue("group_messages_backup.json"), Charsets.UTF_8))
        assertEquals(2, rows.length())
        assertEquals(setOf("hello m1", "hello m2"), (0 until rows.length()).map { rows.getJSONObject(it).getString("plaintext") }.toSet())
    }

    @Test
    fun noGroupMessages_meansNoGroupMessagesEntry() {
        val entries = BackupArchiveTestSupport.entries(BackupArchiveTestSupport.zipOf(export(), words))
        assertFalse(entries.containsKey("group_messages_backup.json"))
        assertTrue(entries.containsKey("api_keys_backup.json"))
    }

    @Test
    fun aLargeBackupWithMedia_spansSeveralChunks_andRestores() {
        val media = File(pictures(), "e3_large.mine")
        val content = ByteArray(2_600_000).also { SecureRandom().nextBytes(it) }
        media.writeBytes(content)
        val archive = export(BackupMediaOption.ALL)
        assertTrue(BackupArchiveTestSupport.chunkRecordOffsets(archive).size >= 3)
        media.delete()

        assertEquals(ImportResult.Success, restore(archive))
        assertArrayEquals(content, media.readBytes())
        val stage = context.noBackupFilesDir.listFiles { f -> f.name.startsWith("restore_stage_") }.orEmpty()
        assertTrue("staging is removed", stage.isEmpty())
    }

    @Test
    fun aModifiedOrTruncatedArchive_changesNothing() {
        File(pictures(), "e3_large.mine").writeBytes(ByteArray(2_600_000).also { SecureRandom().nextBytes(it) })
        setMarker("from-archive")
        val archive = export(BackupMediaOption.ALL)
        setMarker("kept")
        val offsets = BackupArchiveTestSupport.chunkRecordOffsets(archive)

        val tampered = archive.copyOf().also { it[offsets[1] + 50] = (it[offsets[1] + 50].toInt() xor 1).toByte() }
        val r1 = restore(tampered)
        assertRefusedAndUnchanged(r1, "kept")
        assertEquals("The backup file is damaged or was modified", (r1 as ImportResult.Failed).reason)

        assertRefusedAndUnchanged(restore(archive.copyOf(offsets.last())), "kept")
        assertRefusedAndUnchanged(restore(archive.copyOf(archive.size - 1)), "kept")
        assertRefusedAndUnchanged(restore(archive + byteArrayOf(0)), "kept")

        val newer = restore(archive.copyOf().also { it[4] = 2 })
        assertRefusedAndUnchanged(newer, "kept")
        assertEquals("This backup was made by a newer version of NoSlop", (newer as ImportResult.Failed).reason)
    }

    @Test
    fun anExportThatFailsPartWay_reportsFailure_andNeverRestores() {
        File(pictures(), "e3_large.mine").writeBytes(ByteArray(2_600_000).also { SecureRandom().nextBytes(it) })
        setMarker("kept")
        val limit = BackupArchiveFormat.NSG2_HEADER_SIZE + 5 + CreatorIdentityArchive.CHUNK_SIZE + 16 + 3
        val captured = ByteArrayOutputStream()
        var closed = false
        val failing = object : OutputStream() {
            override fun write(b: Int) {
                if (captured.size() + 1 > limit) throw IOException("disk full")
                captured.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (captured.size() + len > limit) throw IOException("disk full")
                captured.write(b, off, len)
            }

            override fun close() {
                closed = true
            }
        }

        assertFalse(BackupManager.exportData(context, words, failing, BackupMediaOption.ALL))
        assertTrue("the target is closed after a failure", closed)
        assertRefusedAndUnchanged(restore(captured.toByteArray()), "kept")
    }

    @Test
    fun olderBackups_stillRestore() {
        setMarker("from-archive")
        val zip = BackupArchiveTestSupport.zipOf(export(), words)

        setMarker("changed")
        assertEquals("NSG1", ImportResult.Success, restore(BackupArchiveTestSupport.nsg1(zip, words)))
        assertEquals("from-archive", marker())

        setMarker("changed")
        val cbc = BackupArchiveTestSupport.cbc(zip, words)
        assertEquals(ImportResult.ConfirmLegacy, restore(cbc))
        assertEquals("changed", marker())
        assertEquals("CBC after confirmation", ImportResult.Success, restore(cbc, allowLegacy = true))
        assertEquals("from-archive", marker())
    }

    @Test
    fun aTamperedNsg1Backup_changesNothing() {
        setMarker("from-archive")
        val n1 = BackupArchiveTestSupport.nsg1(BackupArchiveTestSupport.zipOf(export(), words), words)
        setMarker("kept")
        val result = restore(n1.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertRefusedAndUnchanged(result, "kept")
        assertEquals("The backup file is damaged or was modified", (result as ImportResult.Failed).reason)
    }

    @Test
    fun hubExportFile_isAnNsg2Archive() {
        // createEncryptedBackupFile is what HubSyncWorker uploads; a good one is an NSG2 archive.
        val file = BackupManager.createEncryptedBackupFile(context, words)
        assertTrue(file != null && file.exists())
        assertEquals("NSG2", String(file!!.readBytes().copyOf(4), Charsets.US_ASCII))
        file.delete()
    }
}
