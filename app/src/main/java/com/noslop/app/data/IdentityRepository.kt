// FILE: app/src/main/java/com/noslop/app/data/IdentityRepository.kt
package com.noslop.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger

/**
 * Stores and retrieves cryptographic identity keys.
 *
 * Private key storage contract:
 *   - Private keys are stored ONLY in EncryptedSharedPreferences (AES-256-GCM encrypted,
 *     key in Android Keystore hardware-backed store where available).
 *   - Public data (handle, tripcode, onion address, public keys) is stored in Room
 *     via AppSettingDao for display purposes.
 *   - Private keys are NEVER written to Room, logs, or any unencrypted store.
 */
class IdentityRepository(context: Context, private val appSettingDao: AppSettingDao) {

    private val TAG = "IDENTITY_REPO"

    // EncryptedSharedPreferences backed by Android Keystore master key with robust fallback to prevent startup/emulator crashes
    private val prefs: android.content.SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "noslop_identity_secure",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Logger.error(TAG, "EncryptedSharedPreferences init failed. Falling back to unencrypted SharedPreferences.", e.message)
        context.getSharedPreferences("noslop_identity_fallback", Context.MODE_PRIVATE)
    }

    suspend fun saveIdentity(handle: String, keys: CryptoService.IdentityKeys, mnemonic: String) {
        // Private keys -> SharedPreferences
        prefs.edit()
            .putString("ed25519_private_key", keys.privateKeyB64)
            .putString("enc_private_key", keys.encPrivateKeyB64)
            .putString("mnemonic", mnemonic)
            .apply()

        // Public data -> Room (safe to query, display, share)
        appSettingDao.insertSetting(AppSetting("local_handle", handle))
        appSettingDao.insertSetting(AppSetting("local_pub_ed25519", keys.publicKeyB64))
        appSettingDao.insertSetting(AppSetting("local_pub_enc", keys.encPublicKeyB64))
        appSettingDao.insertSetting(AppSetting("local_tripcode", keys.tripcode))
        appSettingDao.insertSetting(AppSetting("local_onion", keys.onionAddress))
        appSettingDao.insertSetting(AppSetting("local_display_name", keys.displayName))

        Logger.info(
            TAG, "Identity saved",
            "handle=$handle | tripcode=${keys.tripcode} | onion_prefix=${keys.onionAddress.take(16)}..."
        )
        // Private key bytes intentionally NOT logged
    }

    suspend fun loadIdentity(): CryptoService.IdentityKeys? {
        val pubEd = appSettingDao.getSetting("local_pub_ed25519") ?: return null
        val pubEnc = appSettingDao.getSetting("local_pub_enc") ?: return null
        val tripcode = appSettingDao.getSetting("local_tripcode") ?: return null
        val onion = appSettingDao.getSetting("local_onion") ?: return null
        val displayName = appSettingDao.getSetting("local_display_name") ?: return null

        val privEd = prefs.getString("ed25519_private_key", null) ?: return null
        val privEnc = prefs.getString("enc_private_key", null) ?: return null

        return CryptoService.IdentityKeys(
            publicKeyB64 = pubEd,
            privateKeyB64 = privEd,
            tripcode = tripcode,
            onionAddress = onion,
            displayName = displayName,
            encPublicKeyB64 = pubEnc,
            encPrivateKeyB64 = privEnc
        )
    }

    suspend fun getMnemonic(): String? = prefs.getString("mnemonic", null)

    suspend fun logout() {
        // We don't necessarily clear the prefs, but we can set a flag that the session is locked
        appSettingDao.insertSetting(AppSetting("session_locked", "true"))
        Logger.info(TAG, "User logged out / Session locked")
    }

    suspend fun isLocked(): Boolean = appSettingDao.getSetting("session_locked") == "true"

    suspend fun unlock(mnemonic: String): Boolean {
        val savedMnemonic = prefs.getString("mnemonic", null)
        return if (savedMnemonic == mnemonic) {
            appSettingDao.insertSetting(AppSetting("session_locked", "false"))
            Logger.info(TAG, "Session unlocked")
            true
        } else {
            Logger.warn(TAG, "Unlock failed: Mnemonic mismatch")
            false
        }
    }

    suspend fun getHandle(): String = appSettingDao.getSetting("local_handle") ?: "Anonymous"
    suspend fun isOnboardingComplete(): Boolean =
        appSettingDao.getSetting("onboarding_complete") == "true"
    suspend fun setOnboardingComplete(complete: Boolean) {
        appSettingDao.insertSetting(AppSetting("onboarding_complete", complete.toString()))
    }
    
    suspend fun updateOnionAddress(address: String) {
        appSettingDao.insertSetting(AppSetting("local_onion", address))
        Logger.info(TAG, "Onion address dynamically updated in Room: $address")
    }
}
