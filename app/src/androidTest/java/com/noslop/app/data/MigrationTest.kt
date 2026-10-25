// FILE: app/src/androidTest/java/com/noslop/app/data/MigrationTest.kt
package com.noslop.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noslop.app.crypto.GroupMessageCrypto
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NoSlopDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate13To14_reEncryptsLegacyGroupMessagesWithAad() {
        val kg = javax.crypto.KeyGenerator.getInstance("AES")
        kg.init(256)
        val testKey = kg.generateKey()
        GroupMessageCrypto.testKeyProviderOverride = { testKey }

        try {
            // Create database at version 13
            var db = helper.createDatabase(TEST_DB, 13)

            val msgId = "msg-legacy-123"
            val groupId = "group-uuid-456"
            val originalPlaintext = "Confidential migration message"

            // Encrypt with legacy format (no AAD, ENC:GCM:)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, testKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(originalPlaintext.toByteArray(Charsets.UTF_8))
            val legacyCiphertext = GroupMessageCrypto.LEGACY_CIPHERTEXT_PREFIX +
                android.util.Base64.encodeToString(cipherBytes, android.util.Base64.NO_WRAP)
            val ivB64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)

            // Seed database with legacy message row
            val values = ContentValues().apply {
                put("id", msgId)
                put("chatWithPeerPub", groupId)
                put("senderPub", "alice_pub")
                put("ciphertext", legacyCiphertext)
                put("nonce", ivB64)
                put("timestamp", 1700000000L)
                put("isRead", 0)
            }
            db.insert("chat_messages", SQLiteDatabase.CONFLICT_REPLACE, values)
            db.close()

            // Run migration to version 14
            db = helper.runMigrationsAndValidate(TEST_DB, 14, true, NoSlopDatabase.MIGRATION_13_14)

            // Verify row was re-encrypted with V2 prefix and can be decrypted with AAD
            val cursor = db.query(
                "SELECT id, ciphertext, nonce, chatWithPeerPub FROM chat_messages WHERE id = ?",
                arrayOf(msgId)
            )
            assertTrue(cursor.moveToFirst())
            val updatedCiphertext = cursor.getString(1)
            val updatedNonce = cursor.getString(2)
            val updatedGroupId = cursor.getString(3)
            cursor.close()

            assertTrue("Expected V2 prefix but got: $updatedCiphertext",
                updatedCiphertext.startsWith(GroupMessageCrypto.CIPHERTEXT_PREFIX_V2))
            assertNotEquals("Nonce should have been refreshed", ivB64, updatedNonce)

            // Decrypt with AAD binding ($groupId|$msgId)
            val decrypted = GroupMessageCrypto.decrypt(
                updatedCiphertext,
                updatedNonce,
                groupId = updatedGroupId,
                msgId = msgId
            )
            assertEquals(originalPlaintext, decrypted)

            // Tampered AAD must fail decryption
            val tamperedDecrypted = GroupMessageCrypto.decrypt(
                updatedCiphertext,
                updatedNonce,
                groupId = "wrong-group",
                msgId = msgId
            )
            assertEquals("Decryption with tampered AAD must fail and return ciphertext",
                updatedCiphertext, tamperedDecrypted)

        } finally {
            GroupMessageCrypto.testKeyProviderOverride = null
        }
    }

    @Test
    fun migrate16To17_addsRelationshipAndNonces() {
        // Create database at version 16
        var db = helper.createDatabase(TEST_DB, 16)

        // Seed peer rows: one trusted contact, one untrusted contact
        val trustedPeer = ContentValues().apply {
            put("publicKeyB64", "trusted_pub_key_123")
            put("handle", "Alice")
            put("tripcode", "alice1")
            put("onionAddress", "alice.onion")
            put("encPublicKeyB64", "alice_enc_pub")
            put("isTrusted", 1)
            put("isOnline", 0)
            put("lastSeenAt", 1700000000L)
            put("isTemporary", 0)
            put("isDiscoverable", 0)
            put("isCreator", 0)
            put("isFollowing", 0)
        }
        val untrustedPeer = ContentValues().apply {
            put("publicKeyB64", "untrusted_pub_key_456")
            put("handle", "Bob")
            put("tripcode", "bob123")
            put("onionAddress", "bob.onion")
            put("encPublicKeyB64", "bob_enc_pub")
            put("isTrusted", 0)
            put("isOnline", 0)
            put("lastSeenAt", 1700000000L)
            put("isTemporary", 0)
            put("isDiscoverable", 0)
            put("isCreator", 0)
            put("isFollowing", 0)
        }
        db.insert("peers", SQLiteDatabase.CONFLICT_REPLACE, trustedPeer)
        db.insert("peers", SQLiteDatabase.CONFLICT_REPLACE, untrustedPeer)
        db.close()

        // Run migration 16 -> 17
        db = helper.runMigrationsAndValidate(TEST_DB, 17, true, NoSlopDatabase.MIGRATION_16_17)

        // Verify trusted peer has relationship = 'ACCEPTED'
        val cursorTrusted = db.query(
            "SELECT relationship, pendingNonce, pendingEncKey FROM peers WHERE publicKeyB64 = ?",
            arrayOf("trusted_pub_key_123")
        )
        assertTrue(cursorTrusted.moveToFirst())
        assertEquals("ACCEPTED", cursorTrusted.getString(0))
        assertNull(cursorTrusted.getString(1))
        assertNull(cursorTrusted.getString(2))
        cursorTrusted.close()

        // Verify untrusted peer has relationship = 'NONE'
        val cursorUntrusted = db.query(
            "SELECT relationship, pendingNonce, pendingEncKey FROM peers WHERE publicKeyB64 = ?",
            arrayOf("untrusted_pub_key_456")
        )
        assertTrue(cursorUntrusted.moveToFirst())
        assertEquals("NONE", cursorUntrusted.getString(0))
        assertNull(cursorUntrusted.getString(1))
        assertNull(cursorUntrusted.getString(2))
        cursorUntrusted.close()
    }
}
