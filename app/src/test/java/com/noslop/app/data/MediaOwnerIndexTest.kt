// FILE: app/src/test/java/com/noslop/app/data/MediaOwnerIndexTest.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * D02: the media_owner index is maintained by the DAOs themselves, so every write path
 * (local compose, inbound POST/EDIT/DELETE, sync, DMs, group messages, comments) registers
 * attachments without the caller having to remember. Real Room, in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaOwnerIndexTest {

    private lateinit var db: NoSlopDatabase

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun post(id: String, mediaId: String?, privacy: String, author: String = "alice") = MeshPost(
        id = id, authorPublicKeyB64 = author, authorHandle = "h", authorTripcode = "t", content = "c",
        timestamp = 1000L, signature = "s", privacy = privacy,
        mediaUrl = mediaId?.let { "noslop://abc.onion/$it" }
    )

    @Test
    fun post_insertEditAndDelete_keepIndexInStep() = runBlocking {
        val owners = db.mediaOwnerDao()
        db.postDao().insertPost(post("p1", "img_1.jpg", "friends"))
        assertEquals(listOf("friends"), owners.getOwners("img_1.jpg").map { it.privacy })
        assertEquals(MediaOwner.TYPE_POST, owners.getOwners("img_1.jpg").single().ownerType)

        // Privacy change through the safe edit path.
        db.postDao().editPostSafely(
            id = "p1", authorId = "alice", newContent = "c2", newTimestamp = 2000L, newSignature = "s2",
            authorAvatarB64 = null, mediaUrl = "noslop://abc.onion/img_1.jpg", mediaType = "image",
            thumbnailB64 = null, mediaSize = 1L, privacy = "public", clearnetUrl = null
        )
        assertEquals(listOf("public"), owners.getOwners("img_1.jpg").map { it.privacy })

        // Attachment swapped: the old media id must no longer be owned by this post.
        db.postDao().editPostSafely(
            id = "p1", authorId = "alice", newContent = "c3", newTimestamp = 3000L, newSignature = "s3",
            authorAvatarB64 = null, mediaUrl = "noslop://abc.onion/img_2.jpg", mediaType = "image",
            thumbnailB64 = null, mediaSize = 1L, privacy = "public", clearnetUrl = null
        )
        assertTrue(owners.getOwners("img_1.jpg").isEmpty())
        assertEquals(1, owners.getOwners("img_2.jpg").size)

        db.postDao().markPostOrphaned("p1")
        assertTrue("a deleted post owns no media", owners.getOwners("img_2.jpg").isEmpty())
    }

    @Test
    fun secretsAttachedOnce_neverOverwritten() = runBlocking {
        val owners = db.mediaOwnerDao()
        db.postDao().insertPost(post("p2", "vid_1.mp4", "friends"))
        owners.attachSecrets("vid_1.mp4", MediaOwner.TYPE_POST, "p2", "key-A", "hash-A")
        owners.attachSecrets("vid_1.mp4", MediaOwner.TYPE_POST, "p2", "key-B", "hash-B")
        val row = owners.getOwners("vid_1.mp4").single()
        assertEquals("key-A", row.accessKey)
        assertEquals("hash-A", row.sha256)

        // Re-inserting the post (e.g. a later sync copy) keeps the stored secrets.
        db.postDao().insertPost(post("p2", "vid_1.mp4", "friends"))
        assertEquals("key-A", owners.getOwners("vid_1.mp4").single().accessKey)
    }

    @Test
    fun unknownPrivacy_isTreatedAsFriendsOnly() = runBlocking {
        db.postDao().insertPost(post("p3", "x_1.bin", "weird-value"))
        assertEquals("friends", db.mediaOwnerDao().getOwners("x_1.bin").single().privacy)
    }

    @Test
    fun directAndGroupMessages_registerTheirAttachments() = runBlocking {
        db.groupChatDao().insertGroupChat(
            GroupChat(groupId = "g-1", title = "g", adminPublicKeyB64 = "admin", membersJson = "[]", createdAt = 1L)
        )
        db.messageDao().insertMessage(
            ChatMessage(id = "m1", chatWithPeerPub = "bob", senderPub = "bob", ciphertext = "c", nonce = "n", mediaId = "dm_1.jpg")
        )
        db.messageDao().insertMessage(
            ChatMessage(id = "m2", chatWithPeerPub = "g-1", senderPub = "carol", ciphertext = "c", nonce = "n", mediaId = "grp_1.jpg")
        )
        val dmOwner = db.mediaOwnerDao().getOwners("dm_1.jpg").single()
        assertEquals(MediaOwner.TYPE_DM, dmOwner.ownerType)
        assertEquals("bob", dmOwner.ownerId)
        assertEquals("private", dmOwner.privacy)

        val groupOwner = db.mediaOwnerDao().getOwners("grp_1.jpg").single()
        assertEquals(MediaOwner.TYPE_GROUP, groupOwner.ownerType)
        assertEquals("g-1", groupOwner.ownerId)
        assertEquals("group", groupOwner.privacy)
    }

    @Test
    fun commentMedia_inheritsParentPostVisibility() = runBlocking {
        db.postDao().insertPost(post("pp", null, "public"))
        db.postDao().insertPost(post("pf", null, "friends"))
        db.commentDao().insertComment(MeshComment("c1", "pp", "bob", "bob", content = "x", timestamp = 1L, signature = "s", mediaId = "gif_pub.gif"))
        db.commentDao().insertComment(MeshComment("c2", "pf", "bob", "bob", content = "x", timestamp = 1L, signature = "s", mediaId = "gif_fr.gif"))
        db.commentDao().insertComment(MeshComment("c3", "missing", "bob", "bob", content = "x", timestamp = 1L, signature = "s", mediaId = "gif_unk.gif"))

        assertEquals("public", db.mediaOwnerDao().getOwners("gif_pub.gif").single().privacy)
        assertEquals("friends", db.mediaOwnerDao().getOwners("gif_fr.gif").single().privacy)
        assertEquals("unknown parent post -> deny by default", "friends", db.mediaOwnerDao().getOwners("gif_unk.gif").single().privacy)

        db.commentDao().markCommentDeleted("c1")
        assertTrue(db.mediaOwnerDao().getOwners("gif_pub.gif").isEmpty())
    }
}
