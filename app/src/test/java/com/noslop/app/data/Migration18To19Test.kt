// FILE: app/src/test/java/com/noslop/app/data/Migration18To19Test.kt
package com.noslop.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs on the JVM (Robolectric), so it is part of `testGithubDebugUnitTest`.
 *
 * Builds a real version-18 database from the exported schema (app/schemas/.../18.json), seeds it
 * with the states the round-2 review describes, then opens it through Room with MIGRATION_18_19.
 * Room validates every table against the v19 entities after migrating, so a migration that does
 * not produce exactly the v19 schema fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration18To19Test {

    private lateinit var context: Context
    private val dbName = "migration-18-19-test.db"
    private var room: NoSlopDatabase? = null

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(dbName)
    }

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/schemas/com.noslop.app.data.NoSlopDatabase/18.json").exists()) return dir
            if (File(dir, "schemas/com.noslop.app.data.NoSlopDatabase/18.json").exists()) return File(dir, "..").canonicalFile
            dir = dir.parentFile
        }
        error("Could not locate app/schemas/com.noslop.app.data.NoSlopDatabase/18.json")
    }

    private fun createVersion18(): SQLiteDatabase {
        val schema = JsonParser.parseString(
            File(findProjectRoot(), "app/schemas/com.noslop.app.data.NoSlopDatabase/18.json").readText()
        ).asJsonObject.getAsJsonObject("database")
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in schema.getAsJsonArray("entities")) {
            val e = entity.asJsonObject
            val table = e.get("tableName").asString
            db.execSQL(e.get("createSql").asString.replace("\${TABLE_NAME}", table))
            e.getAsJsonArray("indices")?.forEach { idx ->
                db.execSQL(idx.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
            }
        }
        for (q in schema.getAsJsonArray("setupQueries")) db.execSQL(q.asString)
        db.version = 18
        return db
    }

    private fun SQLiteDatabase.peer(pub: String, isTrusted: Int, relationship: String, isTemporary: Int = 0) {
        insertOrThrow("peers", null, ContentValues().apply {
            put("publicKeyB64", pub); put("handle", pub); put("tripcode", "t"); put("onionAddress", "$pub.onion")
            put("encPublicKeyB64", ""); put("isTrusted", isTrusted); put("isOnline", 0); put("lastSeenAt", 1L)
            put("isTemporary", isTemporary); put("isDiscoverable", 0); put("isCreator", 0); put("isFollowing", 0)
            put("relationship", relationship)
        })
    }

    private fun SQLiteDatabase.post(id: String, mediaUrl: String?, privacy: String, orphaned: Int = 0) {
        insertOrThrow("mesh_posts", null, ContentValues().apply {
            put("id", id); put("authorPublicKeyB64", "alice"); put("authorHandle", "alice"); put("authorTripcode", "t")
            put("content", "c"); put("timestamp", 1L); put("signature", "s"); put("mediaUrl", mediaUrl)
            put("gossipCount", 1); put("privacy", privacy); put("isOrphaned", orphaned); put("mediaSize", 0L)
            put("deletionBroadcasts", 0)
        })
    }

    @Test
    fun migrate18To19_repairsTrust_andBackfillsMediaOwners() = runBlocking {
        createVersion18().apply {
            peer("friend", 1, "ACCEPTED")
            peer("healVictim", 1, "INCOMING_PENDING")       // promoted by the removed startup heal
            peer("groupAdmin", 1, "NONE")                  // made trusted by the old acceptGroupInvite
            peer("follower", 1, "ACCEPTED", isTemporary = 0) // burnable contact wrongly non-temporary
            peer("stranger", 0, "NONE")
            insertOrThrow("app_settings", null, ContentValues().apply { put("key", "contact_identity_follower"); put("value", "burnable") })

            post("p-pub", "noslop://a.onion/pub_1.jpg", "public")
            post("p-fr", "noslop://a.onion/fr_1.jpg", "friends")
            post("p-del", "noslop://a.onion/del_1.jpg", "public", orphaned = 1)
            post("p-bad", "noslop://a.onion/.hidden", "public")

            insertOrThrow("group_chats", null, ContentValues().apply {
                put("groupId", "g-1"); put("title", "g"); put("adminPublicKeyB64", "groupAdmin"); put("membersJson", "[]")
                put("createdAt", 1L); put("allowMemberInvites", 1); put("allowMemberSelfRemove", 1); put("revision", 0L)
            })
            insertOrThrow("chat_messages", null, ContentValues().apply {
                put("id", "m-dm"); put("chatWithPeerPub", "friend"); put("senderPub", "friend"); put("ciphertext", "c")
                put("nonce", "n"); put("timestamp", 1L); put("isRead", 0); put("mediaId", "dm_1.jpg")
                put("isLegacy", 0); put("deliveryStatus", "DELIVERED")
            })
            insertOrThrow("chat_messages", null, ContentValues().apply {
                put("id", "m-grp"); put("chatWithPeerPub", "g-1"); put("senderPub", "groupAdmin"); put("ciphertext", "c")
                put("nonce", "n"); put("timestamp", 1L); put("isRead", 0); put("mediaId", "grp_1.jpg")
                put("isLegacy", 0); put("deliveryStatus", "DELIVERED")
            })
            insertOrThrow("mesh_comments", null, ContentValues().apply {
                put("id", "c-1"); put("postId", "p-fr"); put("authorPublicKeyB64", "bob"); put("authorHandle", "bob")
                put("content", "x"); put("timestamp", 1L); put("signature", "s"); put("mediaId", "gif_1.gif")
            })
            close()
        }

        room = Room.databaseBuilder(context, NoSlopDatabase::class.java, dbName)
            .addMigrations(NoSlopDatabase.MIGRATION_18_19)
            .allowMainThreadQueries()
            .build()
        val db = room!!
        val peers = db.peerDao()

        assertTrue(peers.getPeerByPublicKey("friend")!!.isTrusted)
        assertFalse("heal victim must be demoted", peers.getPeerByPublicKey("healVictim")!!.isTrusted)
        assertEquals("relationship itself is untouched", "INCOMING_PENDING", peers.getPeerByPublicKey("healVictim")!!.relationship)
        assertFalse("group admin is not a friend", peers.getPeerByPublicKey("groupAdmin")!!.isTrusted)
        val follower = peers.getPeerByPublicKey("follower")!!
        assertTrue("burnable contacts are always temporary", follower.isTemporary)
        assertFalse(follower.isFriend)
        assertNull(follower.verifiedFingerprint)
        assertFalse(peers.getPeerByPublicKey("stranger")!!.isTrusted)

        val owners = db.mediaOwnerDao()
        assertEquals("public", owners.getOwners("pub_1.jpg").single().privacy)
        assertEquals("friends", owners.getOwners("fr_1.jpg").single().privacy)
        assertTrue("orphaned posts own nothing", owners.getOwners("del_1.jpg").isEmpty())
        assertEquals(MediaOwner.TYPE_DM, owners.getOwners("dm_1.jpg").single().ownerType)
        assertEquals(MediaOwner.TYPE_GROUP, owners.getOwners("grp_1.jpg").single().ownerType)
        val commentOwner = owners.getOwners("gif_1.gif").single()
        assertEquals(MediaOwner.TYPE_COMMENT, commentOwner.ownerType)
        assertEquals("comment media inherits the friends-only parent", "friends", commentOwner.privacy)
        assertEquals("invalid media ids are never indexed", 5, owners.count())
    }
}
