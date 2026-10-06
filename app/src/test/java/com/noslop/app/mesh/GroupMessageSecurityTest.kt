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
    fun groupUpdate_canonicalSigning_roundTrips() {
        val timestamp = 1700000000L
        val updatePayloadToSign = CryptoService.encodeForSigning(
            groupId, "Updated Title", admin.publicKeyB64, timestamp.toString(),
            alice.publicKeyB64, "", "", "New Desc", "avatarB64", "true", "true"
        )
        val sig = CryptoService.sign(updatePayloadToSign, admin.privateKeyB64)
        assertTrue(CryptoService.verify(updatePayloadToSign, sig, admin.publicKeyB64))
    }

    @Test
    fun groupLeave_canonicalSigning_verified() {
        val timestamp = 1700000000L
        val leavePayloadToSign = CryptoService.encodeForSigning(
            groupId, "", alice.publicKeyB64, timestamp.toString(),
            "", alice.publicKeyB64, "", "", "", "", ""
        )
        val sig = CryptoService.sign(leavePayloadToSign, alice.privateKeyB64)
        assertTrue(CryptoService.verify(leavePayloadToSign, sig, alice.publicKeyB64))
    }

    @Test
    fun groupInvite_canonicalSigning_verified() {
        val members = listOf(admin.publicKeyB64, alice.publicKeyB64).sorted()
        val s = canonicalGroupInvitePayload(
            groupId, "Test Group", admin.publicKeyB64, admin.publicKeyB64, 1000L,
            members.joinToString(","), true, true, null, null, null, null
        )
        val sig = CryptoService.sign(s, admin.privateKeyB64)
        assertTrue(CryptoService.verify(s, sig, admin.publicKeyB64))
    }

    @Test
    fun groupInvite_withTamperedMembershipOrPermissions_failsVerification() {
        val members = listOf(admin.publicKeyB64, alice.publicKeyB64).sorted()
        val originalPayload = canonicalGroupInvitePayload(
            groupId, "Test Group", admin.publicKeyB64, admin.publicKeyB64, 1000L,
            members.joinToString(","), true, true, null, null, null, null
        )
        val sig = CryptoService.sign(originalPayload, admin.privateKeyB64)

        // Altering members list invalidates signature (S02)
        val tamperedMembers = listOf(admin.publicKeyB64, alice.publicKeyB64, mallory.publicKeyB64).sorted()
        val tamperedPayload1 = canonicalGroupInvitePayload(
            groupId, "Test Group", admin.publicKeyB64, admin.publicKeyB64, 1000L,
            tamperedMembers.joinToString(","), true, true, null, null, null, null
        )
        assertFalse(CryptoService.verify(tamperedPayload1, sig, admin.publicKeyB64))

        // Altering permission invalidates signature (S02)
        val tamperedPayload2 = canonicalGroupInvitePayload(
            groupId, "Test Group", admin.publicKeyB64, admin.publicKeyB64, 1000L,
            members.joinToString(","), false, true, null, null, null, null
        )
        assertFalse(CryptoService.verify(tamperedPayload2, sig, admin.publicKeyB64))

        // Altering admin contact keys invalidates signature (S07)
        val tamperedPayload3 = canonicalGroupInvitePayload(
            groupId, "Test Group", admin.publicKeyB64, admin.publicKeyB64, 1000L,
            members.joinToString(","), true, true, null, null, "attacker.onion", null
        )
        assertFalse(CryptoService.verify(tamperedPayload3, sig, admin.publicKeyB64))
    }

    @Test
    fun groupUpdate_withAlteredBannedMembers_failsVerification() {
        val timestamp = 1700000000L
        val originalBannedStr = encodeOptBanned(null)
        val payloadToSign = canonicalGroupUpdatePayload(
            groupId, "Updated Title", admin.publicKeyB64, timestamp,
            alice.publicKeyB64, "", originalBannedStr, "New Desc", "avatarB64", true, true
        )
        val sig = CryptoService.sign(payloadToSign, admin.privateKeyB64)

        // W03: Altering banned list from absent to non-empty fails verification
        val tamperedBannedStr = encodeOptBanned(listOf(mallory.publicKeyB64))
        val tamperedPayload = canonicalGroupUpdatePayload(
            groupId, "Updated Title", admin.publicKeyB64, timestamp,
            alice.publicKeyB64, "", tamperedBannedStr, "New Desc", "avatarB64", true, true
        )
        assertFalse(CryptoService.verify(tamperedPayload, sig, admin.publicKeyB64))

        // W03: Altering banned list from absent to cleared empty list also fails verification
        val clearedBannedStr = encodeOptBanned(emptyList())
        val clearedPayload = canonicalGroupUpdatePayload(
            groupId, "Updated Title", admin.publicKeyB64, timestamp,
            alice.publicKeyB64, "", clearedBannedStr, "New Desc", "avatarB64", true, true
        )
        assertFalse(CryptoService.verify(clearedPayload, sig, admin.publicKeyB64))
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

    @Test
    fun groupInvite_withAlteredMemberDetails_failsVerification() {
        val members = listOf(admin.publicKeyB64, alice.publicKeyB64).sorted()
        val originalDetails = mapOf(
            alice.publicKeyB64 to GroupMemberInfo(handle = "Alice", encPublicKey = alice.encPublicKeyB64, onionAddress = "alice.onion")
        )
        val sortedDetails = canonicalMemberDetailsString(originalDetails)
        val payloadToSign = canonicalGroupInvitePayload(
            groupId, "Test Group", admin.publicKeyB64, admin.publicKeyB64, 1000L,
            members.joinToString(","), true, true, null, null, null, null,
            sortedDetails, ""
        )
        val sig = CryptoService.sign(payloadToSign, admin.privateKeyB64)

        // U03: Mallory modifies Alice's encryption public key in directory
        val tamperedDetails = mapOf(
            alice.publicKeyB64 to GroupMemberInfo(handle = "Alice", encPublicKey = mallory.encPublicKeyB64, onionAddress = "alice.onion")
        )
        val tamperedDetailsStr = canonicalMemberDetailsString(tamperedDetails)
        val tamperedPayload = canonicalGroupInvitePayload(
            groupId, "Test Group", admin.publicKeyB64, admin.publicKeyB64, 1000L,
            members.joinToString(","), true, true, null, null, null, null,
            tamperedDetailsStr, ""
        )
        assertFalse("Tampered member encryption keys must fail verification", CryptoService.verify(tamperedPayload, sig, admin.publicKeyB64))
    }

    @Test
    fun groupInvite_7fieldLegacyWithUnsignedFields_isRejected() {
        // U03: 7-field signature only covered members and basic permissions
        val members = listOf(admin.publicKeyB64, alice.publicKeyB64).sorted()
        val enc7Field = CryptoService.encodeForSigning(
            groupId, "Test Group", admin.publicKeyB64, "1000",
            members.joinToString(","), "true", "true"
        )
        val sig = CryptoService.sign(enc7Field, admin.privateKeyB64)

        // Verifies against 7-field
        assertTrue(CryptoService.verify(enc7Field, sig, admin.publicKeyB64))

        // But fails canonical 12-field verification when attacker injects admin onion / keys
        val canonicalWithInjectedFields = canonicalGroupInvitePayload(
            groupId, "Test Group", admin.publicKeyB64, admin.publicKeyB64, 1000L,
            members.joinToString(","), true, true, "Fake Description", null, "attacker.onion", "attacker-enc-pub"
        )
        assertFalse(CryptoService.verify(canonicalWithInjectedFields, sig, admin.publicKeyB64))
    }

    @Test
    fun canonicalDirectoryStrings_injectiveEncoding_preventsDelimiterInjection() {
        // V03: Test that delimiter characters in handles cannot trigger map structural collisions
        val pubA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val pubB = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

        val map1 = mapOf(
            pubA to GroupMemberInfo(handle = "x;${pubB}:E2:O2:y", encPublicKey = "E1", onionAddress = "O1")
        )
        val map2 = mapOf(
            pubA to GroupMemberInfo(handle = "x", encPublicKey = "E1", onionAddress = "O1"),
            pubB to GroupMemberInfo(handle = "y", encPublicKey = "E2", onionAddress = "O2")
        )

        val s1 = canonicalMemberDetailsString(map1)
        val s2 = canonicalMemberDetailsString(map2)
        assertNotEquals("Injective directory encoding must never collide on delimiter characters (V03)", s1, s2)
    }

    @Test
    fun groupUpdate_withAlteredMemberDetails_failsVerification() {
        val details = mapOf(
            alice.publicKeyB64 to GroupMemberInfo(handle = "Alice", encPublicKey = alice.encPublicKeyB64, onionAddress = "alice.onion")
        )
        val sortedDetails = canonicalMemberDetailsString(details)
        val bannedStr = encodeOptBanned(null)
        val payloadToSign = canonicalGroupUpdatePayload(
            groupId, "Updated Title", admin.publicKeyB64, 1000L,
            "", "", bannedStr, "Desc", "avatar", true, true,
            sortedDetails, ""
        )
        val sig = CryptoService.sign(payloadToSign, admin.privateKeyB64)

        // V03: Mallory tampers with Alice's encryption key in GROUP_UPDATE directory
        val tamperedDetails = mapOf(
            alice.publicKeyB64 to GroupMemberInfo(handle = "Alice", encPublicKey = mallory.encPublicKeyB64, onionAddress = "alice.onion")
        )
        val tamperedDetailsStr = canonicalMemberDetailsString(tamperedDetails)
        val tamperedPayload = canonicalGroupUpdatePayload(
            groupId, "Updated Title", admin.publicKeyB64, 1000L,
            "", "", bannedStr, "Desc", "avatar", true, true,
            tamperedDetailsStr, ""
        )
        assertFalse("Tampered member details in GROUP_UPDATE must fail signature verification (V03)", CryptoService.verify(tamperedPayload, sig, admin.publicKeyB64))
    }

    @Test
    fun groupSync_withAlteredMemberDetails_failsVerification() {
        val details = mapOf(
            alice.publicKeyB64 to GroupMemberInfo(handle = "Alice", encPublicKey = alice.encPublicKeyB64, onionAddress = "alice.onion")
        )
        val sortedDetails = canonicalMemberDetailsString(details)
        val groupJson = gson.toJson(mapOf("groupId" to groupId))
        val payloadToSign = canonicalGroupSyncPayload(groupId, groupJson, 1000L, sortedDetails)
        val sig = CryptoService.sign(payloadToSign, admin.privateKeyB64)

        // V03: Mallory tampers with member details in GROUP_SYNC
        val tamperedDetails = mapOf(
            alice.publicKeyB64 to GroupMemberInfo(handle = "Alice", encPublicKey = mallory.encPublicKeyB64, onionAddress = "alice.onion")
        )
        val tamperedDetailsStr = canonicalMemberDetailsString(tamperedDetails)
        val tamperedPayload = canonicalGroupSyncPayload(groupId, groupJson, 1000L, tamperedDetailsStr)
        assertFalse("Tampered member details in GROUP_SYNC must fail signature verification (V03)", CryptoService.verify(tamperedPayload, sig, admin.publicKeyB64))
    }
}
