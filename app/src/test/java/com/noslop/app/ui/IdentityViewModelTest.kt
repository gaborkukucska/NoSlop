package com.noslop.app.ui

import com.noslop.app.NoSlopApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoSlopApp::class)
class IdentityViewModelTest {

    private lateinit var identityViewModel: IdentityViewModel

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication() as NoSlopApp
        identityViewModel = IdentityViewModel(app)
    }

    @Test
    fun identityViewModel_generatesMnemonic_andExposesState() {
        assertNull(identityViewModel.mnemonic.value)
        identityViewModel.generateMnemonic()
        val m = identityViewModel.mnemonic.value
        assertNotNull(m)
        val words = m!!.split(" ")
        assertEquals(12, words.size)
    }

    @Test
    fun identityViewModel_backupPromptReason_lifecycle() {
        assertNull(identityViewModel.backupPromptReason.value)
        identityViewModel.triggerBackupPrompt(NoSlopViewModel.BackupPromptReason.CREATOR_MODE_CHANGED)
        assertEquals(NoSlopViewModel.BackupPromptReason.CREATOR_MODE_CHANGED, identityViewModel.backupPromptReason.value)
        identityViewModel.dismissBackupPrompt()
        assertNull(identityViewModel.backupPromptReason.value)
    }
}
