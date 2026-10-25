package com.noslop.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingGroupMessageDaoTest {

    private lateinit var db: NoSlopDatabase
    private lateinit var dao: PendingGroupMessageDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.pendingGroupMessageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndGetPendingForMember() = runBlocking {
        val msg = PendingGroupMessage(
            groupId = "group-1",
            memberPub = "bob_pub",
            msgId = "msg-101",
            ciphertext = "ENC:GCM2:xyz",
            nonce = "iv101",
            createdAt = 1000L
        )
        dao.insert(msg)

        val pending = dao.getPendingForMember("bob_pub")
        assertEquals(1, pending.size)
        assertEquals("msg-101", pending[0].msgId)
        assertEquals("group-1", pending[0].groupId)
        assertEquals("ENC:GCM2:xyz", pending[0].ciphertext)

        // Non-matching member returns empty
        val other = dao.getPendingForMember("alice_pub")
        assertTrue(other.isEmpty())
    }

    @Test
    fun deleteDeliveredMessage() = runBlocking {
        val msg = PendingGroupMessage(
            groupId = "group-1",
            memberPub = "bob_pub",
            msgId = "msg-101",
            ciphertext = "ENC:GCM2:xyz",
            nonce = "iv101",
            createdAt = 1000L
        )
        dao.insert(msg)
        assertEquals(1, dao.getPendingForMember("bob_pub").size)

        dao.delete("group-1", "bob_pub", "msg-101")
        assertTrue(dao.getPendingForMember("bob_pub").isEmpty())
    }

    @Test
    fun deleteExpiredMessages() = runBlocking {
        val oldMsg = PendingGroupMessage(
            groupId = "group-1",
            memberPub = "bob_pub",
            msgId = "msg-old",
            ciphertext = "ENC:GCM2:old",
            nonce = "ivOld",
            createdAt = 1000L
        )
        val newMsg = PendingGroupMessage(
            groupId = "group-1",
            memberPub = "bob_pub",
            msgId = "msg-new",
            ciphertext = "ENC:GCM2:new",
            nonce = "ivNew",
            createdAt = 5000L
        )
        dao.insert(oldMsg)
        dao.insert(newMsg)

        assertEquals(2, dao.getPendingForMember("bob_pub").size)

        // Cutoff at 3000L deletes oldMsg (1000L) and keeps newMsg (5000L)
        dao.deleteExpired(3000L)

        val remaining = dao.getPendingForMember("bob_pub")
        assertEquals(1, remaining.size)
        assertEquals("msg-new", remaining[0].msgId)
    }
}
