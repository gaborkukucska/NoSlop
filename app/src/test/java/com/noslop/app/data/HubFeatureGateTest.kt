// FILE: app/src/test/java/com/noslop/app/data/HubFeatureGateTest.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Room
import com.noslop.app.HubFeature
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * NOSLOP_HUB_BUILD_SWITCH_V1: a build without the HUB reads no Hub link, so every Hub path stays idle,
 * and the stored link survives for a later build that has the HUB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubFeatureGateTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private val before = HubFeature.enabled

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
    }

    @After
    fun tearDown() {
        HubFeature.enabled = before
        repo.stopPresenceHeartbeat()
        db.close()
    }

    @Test
    fun withoutTheHub_theStoredLinkReadsAsNone() = runBlocking<Unit> {
        HubFeature.enabled = false
        repo.putAppSetting(HubFeature.STATUS_KEY, "Active at 192.168.1.20")
        assertNull(repo.getAppSetting(HubFeature.STATUS_KEY))
    }

    @Test
    fun withTheHub_theStoredLinkIsRead() = runBlocking<Unit> {
        HubFeature.enabled = true
        repo.putAppSetting(HubFeature.STATUS_KEY, "Active at 192.168.1.20")
        assertEquals("Active at 192.168.1.20", repo.getAppSetting(HubFeature.STATUS_KEY))
    }

    @Test
    fun theLinkIsKeptForALaterHubBuild() = runBlocking<Unit> {
        HubFeature.enabled = false
        repo.putAppSetting(HubFeature.STATUS_KEY, "Active at 192.168.1.20")
        HubFeature.enabled = true
        assertEquals("Active at 192.168.1.20", repo.getAppSetting(HubFeature.STATUS_KEY))
    }

    @Test
    fun otherSettingsAreNotAffected() = runBlocking<Unit> {
        HubFeature.enabled = false
        repo.putAppSetting("foreground_service_enabled", "true")
        assertEquals("true", repo.getAppSetting("foreground_service_enabled"))
    }
}
