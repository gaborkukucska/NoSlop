package com.noslop.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * NOSLOP_DB_ENCRYPTION_V1: the wrapped database key file (docs/TECHNICAL_REFERENCE.md §16.51, "The key").
 * An in-memory AES key stands in for the Android Keystore, which the JVM doesn't have.
 */
class DbKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun wrapKey(): SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    private fun load(dir: File, mayCreate: Boolean, wrap: SecretKey) =
        DbKeyStore.load(dir, mayCreate, { wrap }, retryDelayMs = 0L)

    private fun keyOf(result: DbKeyStore.Result): ByteArray {
        assertTrue("expected a key, got $result", result is DbKeyStore.Result.Ready)
        return (result as DbKeyStore.Result.Ready).key
    }

    @Test
    fun firstLoad_makesA32ByteKey_andASecondLoadReturnsTheSameKey() {
        val dir = tmp.newFolder()
        val wrap = wrapKey()
        val first = keyOf(load(dir, mayCreate = true, wrap = wrap))
        assertEquals(32, first.size)
        assertTrue(File(dir, DbKeyStore.KEY_FILE).exists())
        assertFalse("temp file must not be left behind", File(dir, DbKeyStore.KEY_FILE + ".tmp").exists())

        val second = keyOf(load(dir, mayCreate = false, wrap = wrap))
        assertArrayEquals(first, second)
    }

    @Test
    fun keyFile_doesNotContainTheKeyItself() {
        val dir = tmp.newFolder()
        val key = keyOf(load(dir, mayCreate = true, wrap = wrapKey()))
        val onDisk = File(dir, DbKeyStore.KEY_FILE).readBytes()
        assertEquals(5 + 12 + 32 + 16, onDisk.size)
        for (start in 0..(onDisk.size - key.size)) {
            assertFalse(
                "raw key found in the key file at offset $start",
                onDisk.copyOfRange(start, start + key.size).contentEquals(key)
            )
        }
    }

    @Test
    fun twoDevices_getDifferentKeys() {
        val a = keyOf(load(tmp.newFolder(), mayCreate = true, wrap = wrapKey()))
        val b = keyOf(load(tmp.newFolder(), mayCreate = true, wrap = wrapKey()))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun noKeyFile_andMayNotCreate_isUnavailable_andNothingIsWritten() {
        val dir = tmp.newFolder()
        val result = load(dir, mayCreate = false, wrap = wrapKey())
        assertTrue(result is DbKeyStore.Result.Unavailable)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun wrongWrappingKey_andMayNotCreate_isUnavailable_andTheFileIsUntouched() {
        // The Keystore key was lost while an encrypted database exists: report it, change nothing.
        val dir = tmp.newFolder()
        keyOf(load(dir, mayCreate = true, wrap = wrapKey()))
        val before = File(dir, DbKeyStore.KEY_FILE).readBytes()

        val result = load(dir, mayCreate = false, wrap = wrapKey())
        assertTrue(result is DbKeyStore.Result.Unavailable)
        assertArrayEquals(before, File(dir, DbKeyStore.KEY_FILE).readBytes())
        assertFalse(File(dir, DbKeyStore.KEY_FILE + DbKeyStore.UNREADABLE_SUFFIX).exists())
    }

    @Test
    fun wrongWrappingKey_andMayCreate_keepsTheOldFileAside_andMakesANewKey() {
        val dir = tmp.newFolder()
        val oldKey = keyOf(load(dir, mayCreate = true, wrap = wrapKey()))
        val oldFile = File(dir, DbKeyStore.KEY_FILE).readBytes()

        val newWrap = wrapKey()
        val newKey = keyOf(load(dir, mayCreate = true, wrap = newWrap))
        assertFalse(oldKey.contentEquals(newKey))
        assertArrayEquals(oldFile, File(dir, DbKeyStore.KEY_FILE + DbKeyStore.UNREADABLE_SUFFIX).readBytes())
        assertArrayEquals(newKey, keyOf(load(dir, mayCreate = false, wrap = newWrap)))
    }

    @Test
    fun tamperedKeyFile_isRefused() {
        val dir = tmp.newFolder()
        val wrap = wrapKey()
        keyOf(load(dir, mayCreate = true, wrap = wrap))
        val file = File(dir, DbKeyStore.KEY_FILE)
        val good = file.readBytes()

        // One flipped bit in the ciphertext, in the IV, and in the header.
        for (offset in listOf(good.size - 1, 6, 0)) {
            val bad = good.copyOf().also { it[offset] = (it[offset].toInt() xor 0x01).toByte() }
            file.writeBytes(bad)
            assertTrue(
                "a key file changed at byte $offset must be refused",
                load(dir, mayCreate = false, wrap = wrap) is DbKeyStore.Result.Unavailable
            )
        }
        // Cut short.
        file.writeBytes(good.copyOf(good.size - 1))
        assertTrue(load(dir, mayCreate = false, wrap = wrap) is DbKeyStore.Result.Unavailable)

        // The untouched file still works.
        file.writeBytes(good)
        assertTrue(load(dir, mayCreate = false, wrap = wrap) is DbKeyStore.Result.Ready)
    }

    @Test
    fun keystoreFailure_isUnavailable_notACrash_andLeavesNoKeyFile() {
        val dir = tmp.newFolder()
        val result = DbKeyStore.load(dir, true, { throw java.security.KeyStoreException("AndroidKeyStore not found") }, 0L)
        assertTrue(result is DbKeyStore.Result.Unavailable)
        assertFalse(File(dir, DbKeyStore.KEY_FILE).exists())
        assertFalse(File(dir, DbKeyStore.KEY_FILE + ".tmp").exists())
        // The reason is safe to show: it names the error, nothing else.
        assertTrue((result as DbKeyStore.Result.Unavailable).why.contains("KeyStoreException"))
    }

    @Test
    fun aKeystoreHiccup_onAStoredKey_isRetried() {
        val dir = tmp.newFolder()
        val wrap = wrapKey()
        val key = keyOf(load(dir, mayCreate = true, wrap = wrap))

        var calls = 0
        val flaky: () -> SecretKey = {
            calls++
            if (calls < 3) throw java.security.KeyStoreException("busy") else wrap
        }
        val result = DbKeyStore.load(dir, false, flaky, 0L)
        assertArrayEquals(key, keyOf(result))
        assertEquals(3, calls)
    }

    @Test
    fun wipe_zeroesTheArray() {
        val key = ByteArray(32) { 7 }
        DbKeyStore.wipe(key)
        assertArrayEquals(ByteArray(32), key)
        DbKeyStore.wipe(null)
        assertNotEquals(0, DbEncryptionPolicy.KEY_BYTES)
    }
}
