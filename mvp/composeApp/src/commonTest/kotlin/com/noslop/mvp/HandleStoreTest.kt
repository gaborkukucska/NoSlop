package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals

/** The handle persists across load/save (NSUserDefaults on iOS; SharedPreferences/fallback on Android). */
class HandleStoreTest {
    @Test
    fun handle_roundTrips() {
        HandleStore.save("alice")
        assertEquals("alice", HandleStore.load())
    }
}
