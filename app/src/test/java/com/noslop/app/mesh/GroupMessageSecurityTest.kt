package com.noslop.app.mesh

import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.FakeGroupChatDao
import com.noslop.app.data.FakeMessageDao
import com.noslop.app.data.FakePendingGroupMessageDao
import com.noslop.app.data.GroupChat
import com.noslop.app.data.PendingGroupMessage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupMessageSecurityTest {

    private val gson = Gson()
    private val admin = CryptoService.generateIdentity("admin")
    private val alice = CryptoService.generateIdentity("alice")
    private val mallory = CryptoService.generateIdentity("mallory")

    private val fakeGroupDao = FakeGroupChatDao()
    private val fakePendingDao = FakePendingGroupMessageDao()
    private val fakeMessageDao = FakeMessageDao()

    private val groupId = "test-group-123"

    @Before
    fun setUp() {
        val group = GroupChat(
            groupId = groupId,
            title = "Test Group",
            adminPublicKeyB64 = admin.publicKeyB64,
            membersJson = gson.toJson(listOf(admin.publicKeyB64, alice.publicKeyB64)),
            createdAt = 1000L
        )
        kotlinx.coroutines.runBlocking {
            fakeGroupDao.insertGroupChat(group)
        }
    }

    @Test
    fun groupMessage_signatureVerification_rejectsTamperedPayload() {
        val signString = CryptoService.encodeForSigning(groupId, "msg-1", "Original Text", "1700000000", alice.publicKeyB64)
        val sig = CryptoService.sign(signString, alice.privateKeyB64)

        // Valid signature
        assertTrue(CryptoService.verify(signString, sig, alice.publicKeyB64))

        // Tampered text fails verification
        val tamperedSignString = CryptoService.encodeForSigning(groupId, "msg-1", "Forged Text", "1700000000", alice.publicKeyB64)
        assertFalse(CryptoService.verify(tamperedSignString, sig, alice.publicKeyB64))

        // Re-attributed author fails verification
        assertFalse(CryptoService.verify(signString, sig, mallory.publicKeyB64))
    }

    @Test
    fun groupMessageGate_acceptsValidMemberWithValidSignature() {
        val payload = GroupMessagePayload(
            id = "msg-valid",
            groupId = groupId,
            senderHandle = "alice",
            content = "Hello group",
            timestamp = 1700000000L,
            privacy = "friends",
            signature = "valid_sig"
        )
        val group = kotlinx.coroutines.runBlocking { fakeGroupDao.getGroupChatById(groupId) }
        val verdict = GroupMessageGate.evaluate(payload, group, alice.publicKeyB64, signatureValid = true)
        assertTrue("Expected Accept but got $verdict", verdict is GroupMessageGate.Verdict.Accept)
    }

    @Test
    fun groupMessageGate_rejectsUnknownGroup() {
        val payload = GroupMessagePayload(
            id = "msg-1",
            groupId = "unknown-group",
            senderHandle = "alice",
            content = "Hello",
            timestamp = 1700000000L,
            privacy = "friends",
            signature = "valid_sig"
        )
        val verdict = GroupMessageGate.evaluate(payload, null, alice.publicKeyB64, signatureValid = true)
        assertTrue(verdict is GroupMessageGate.Verdict.Reject)
        assertTrue((verdict as GroupMessageGate.Verdict.Reject).reason.contains("unknown group"))
    }

    @Test
    fun groupMessageGate_rejectsNonMemberSender() {
        val payload = GroupMessagePayload(
            id = "msg-1",
            groupId = groupId,
            senderHandle = "mallory",
            content = "Hello from intruder",
            timestamp = 1700000000L,
            privacy = "friends",
            signature = "valid_sig"
        )
        val group = kotlinx.coroutines.runBlocking { fakeGroupDao.getGroupChatById(groupId) }
        val verdict = GroupMessageGate.evaluate(payload, group, mallory.publicKeyB64, signatureValid = true)
        assertTrue(verdict is GroupMessageGate.Verdict.Reject)
        assertTrue((verdict as GroupMessageGate.Verdict.Reject).reason.contains("not a member"))
    }

    @Test
    fun groupMessageGate_rejectsMissingSignature() {
        val payload = GroupMessagePayload(
            id = "msg-1",
            groupId = groupId,
            senderHandle = "alice",
            content = "Hello",
            timestamp = 1700000000L,
            privacy = "friends",
            signature = ""
        )
        val group = kotlinx.coroutines.runBlocking { fakeGroupDao.getGroupChatById(groupId) }
        val verdict = GroupMessageGate.evaluate(payload, group, alice.publicKeyB64, signatureValid = false)
        assertTrue(verdict is GroupMessageGate.Verdict.Reject)
        assertTrue((verdict as GroupMessageGate.Verdict.Reject).reason.contains("missing cryptographic signature"))
    }

    @Test
    fun groupMessageGate_rejectsInvalidSignature() {
        val payload = GroupMessagePayload(
            id = "msg-1",
            groupId = groupId,
            senderHandle = "alice",
            content = "Hello",
            timestamp = 1700000000L,
            privacy = "friends",
            signature = "forged_sig"
        )
        val group = kotlinx.coroutines.runBlocking { fakeGroupDao.getGroupChatById(groupId) }
        val verdict = GroupMessageGate.evaluate(payload, group, alice.publicKeyB64, signatureValid = false)
        assertTrue(verdict is GroupMessageGate.Verdict.Reject)
        assertTrue((verdict as GroupMessageGate.Verdict.Reject).reason.contains("signature verification failed"))
    }

    @Test
    fun storeAndForward_queue_enqueue_flush_delete_cycle() = kotlinx.coroutines.runBlocking {
        val pendingMsg = PendingGroupMessage(
            groupId = groupId,
            memberPub = alice.publicKeyB64,
            msgId = "msg-999",
            ciphertext = "ENC:GCM2:test",
            nonce = "iv123",
            createdAt = System.currentTimeMillis()
        )

        // 1. Enqueue
        fakePendingDao.insert(pendingMsg)
        val retrieved = fakePendingDao.getPendingForMember(alice.publicKeyB64)
        assertEquals(1, retrieved.size)
        assertEquals("msg-999", retrieved[0].msgId)

        // 2. Deliver & delete
        fakePendingDao.delete(groupId, alice.publicKeyB64, "msg-999")
        val afterDelete = fakePendingDao.getPendingForMember(alice.publicKeyB64)
        assertTrue(afterDelete.isEmpty())

        // 3. Expiration TTL sweep
        val expiredMsg = PendingGroupMessage(
            groupId = groupId,
            memberPub = alice.publicKeyB64,
            msgId = "msg-old",
            ciphertext = "ENC:GCM2:old",
            nonce = "ivOld",
            createdAt = 1000L
        )
        fakePendingDao.insert(expiredMsg)
        assertEquals(1, fakePendingDao.getPendingForMember(alice.publicKeyB64).size)

        val cutoff = 5000L
        fakePendingDao.deleteExpired(cutoff)
        assertTrue(fakePendingDao.getPendingForMember(alice.publicKeyB64).isEmpty())
    }
}
