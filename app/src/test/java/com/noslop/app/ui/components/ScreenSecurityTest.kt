// FILE: app/src/test/java/com/noslop/app/ui/components/ScreenSecurityTest.kt
package com.noslop.app.ui.components

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** NOSLOP_SCREEN_SECURITY_V1 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSecurityTest {

    @Before
    fun setUp() = ScreenSecurity.resetForTest()

    @After
    fun tearDown() = ScreenSecurity.resetForTest()

    @Test
    fun messagesAreHiddenByDefault() {
        assertTrue(ScreenSecurity.hideMessages(RuntimeEnvironment.getApplication()).value)
    }

    @Test
    fun theChoiceIsKept() {
        val context = RuntimeEnvironment.getApplication()
        ScreenSecurity.setHideMessages(context, false)
        ScreenSecurity.resetForTest()
        assertFalse("read back from the preferences", ScreenSecurity.hideMessages(context).value)
        ScreenSecurity.setHideMessages(context, true)
    }
}
