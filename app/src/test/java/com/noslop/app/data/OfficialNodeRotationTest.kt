// FILE: app/src/test/java/com/noslop/app/data/OfficialNodeRotationTest.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The official node's identity changed. The old key is refused, on new and existing installs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfficialNodeRotationTest {

    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository

    private val oldKey = NoSlopRepository.RETIRED_OFFICIAL_KEYS.single()
    private val newKey = NoSlopRepository.OFFICIAL_CREATOR_PUBKEY

    @Before
    fun setup() = runBlocking<Unit> {
        com.noslop.app.mesh.GossipService.resetForTesting()
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", CryptoService.generateIdentity("alice"), "mnemonic test word cloud phrase")
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        com.noslop.app.mesh.GossipService.resetForTesting()
        db.close()
    }

    @Test
    fun theNewOnionAddressBelongsToTheNewKey() {
        assertTrue(CryptoService.isOnionOf(newKey, NoSlopRepository.OFFICIAL_CREATOR_ONION))
        assertFalse(newKey in NoSlopRepository.RETIRED_OFFICIAL_KEYS)
    }

    @Test
    fun anExistingInstallLosesTheOldNodeAndGetsTheNewOne() = runBlocking<Unit> {
        db.peerDao().insertPeer(
            Peer(publicKeyB64 = oldKey, handle = "NoSlop", tripcode = "t", onionAddress = "old.onion", isDiscoverable = true, isCreator = true)
        )
        repo.runStartupPeerMaintenance()
        repo.ensureDefaultDiscoverableNode()

        assertNull(db.peerDao().getPeerByPublicKey(oldKey))
        val seeded = db.peerDao().getPeerByPublicKey(newKey)
        assertNotNull(seeded)
        assertEquals(NoSlopRepository.OFFICIAL_CREATOR_ONION, seeded!!.onionAddress)
        assertEquals(NoSlopRepository.OFFICIAL_CREATOR_ENC_PUBKEY, seeded.encPublicKeyB64)
        assertTrue(seeded.isDiscoverable)
        assertFalse("seeded, not trusted", seeded.isTrusted)
    }

    @Test
    fun theOldKeyIsRefused_butIsNotOnTheUsersBannedList() = runBlocking<Unit> {
        assertTrue(repo.isNodeBanned(oldKey))
        assertFalse(repo.isNodeBanned(newKey))
        assertTrue(repo.getBannedNodes().none { it.publicKeyB64 == oldKey })
    }
}
