// FILE: app/src/test/java/com/noslop/app/data/OnionAddressUpdateTest.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.mesh.GossipService
import com.noslop.app.mesh.NetworkPacket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * NoSlopRepository.updateOnionAddress runs on every app start. It used to broadcast an untargeted
 * USER_HANDSHAKE across the mesh each time, which every receiver rejects. Real repository and Room
 * database; outgoing broadcasts are read from the Hub push hook, which GossipService.broadcast calls
 * first when a Hub is linked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnionAddressUpdateTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private val alice = CryptoService.generateIdentity("alice")
    private val pushed = java.util.Collections.synchronizedList(mutableListOf<NetworkPacket>())

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        repo.putAppSetting("hub_deployment_status", "deployed")
        GossipService.pushPacketToHub = { packet -> pushed.add(packet); true }
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        GossipService.resetForTesting()
        db.close()
    }

    @Test
    fun startupCallWithTheCurrentAddress_sendsNoHandshake_andStillPublishesTheIdentity() = runBlocking<Unit> {
        repo.updateOnionAddress(alice.onionAddress)
        withTimeout(2_000) { repo.identityUpdateFlow.first() }
        assertTrue("no USER_HANDSHAKE broadcast", pushed.none { it.type == "USER_HANDSHAKE" })
        assertEquals(alice.onionAddress, repo.getLocalIdentity()?.onionAddress)
    }

    @Test
    fun aChangedAddress_isStored_withoutAHandshakeBroadcast() = runBlocking<Unit> {
        // The Hub "heal" path passes the Hub's onion. Peers learn addresses from ANNOUNCE_PEER.
        val hubOnion = CryptoService.generateIdentity("hub").onionAddress
        repo.updateOnionAddress(hubOnion)
        assertEquals(hubOnion, repo.getAppSetting("local_onion"))
        assertTrue("no USER_HANDSHAKE broadcast", pushed.none { it.type == "USER_HANDSHAKE" })
    }

    @Test
    fun aBlankAddress_isNotStored() = runBlocking<Unit> {
        // The ViewModel passes "" when there is no identity yet.
        repo.updateOnionAddress("")
        assertEquals(alice.onionAddress, repo.getAppSetting("local_onion"))
    }
}
