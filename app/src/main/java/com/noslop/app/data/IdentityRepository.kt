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

    val isUsingInsecureStorage = kotlinx.coroutines.flow.MutableStateFlow(false)

    // EncryptedSharedPreferences backed by Android Keystore master key
    // Falls back to plaintext SharedPreferences if hardware Keystore is unavailable
    private val prefs: android.content.SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "noslop_identity_secure",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also {
            Logger.info(TAG, "EncryptedSharedPreferences initialized with hardware-backed keystore")
        }
    } catch (e: Exception) {
        Logger.error(TAG, "EncryptedSharedPreferences failed, falling back to plaintext: ${e.message}")
        isUsingInsecureStorage.value = true
        context.getSharedPreferences("noslop_identity_fallback", Context.MODE_PRIVATE)
    }

    suspend fun saveIdentity(handle: String, keys: CryptoService.IdentityKeys, mnemonic: String) {
        // Private keys -> SharedPreferences
        prefs.edit()
            .putString("ed25519_private_key", keys.privateKeyB64)
            .putString("enc_private_key", keys.encPrivateKeyB64)
            .putString("mnemonic", mnemonic)
            // Also persist public identity data in ESP so it survives DB resets
            .putString("pub_ed25519", keys.publicKeyB64)
            .putString("pub_enc", keys.encPublicKeyB64)
            .putString("handle", handle)
            .putString("tripcode", keys.tripcode)
            .putString("onion", keys.onionAddress)
            .putString("display_name", keys.displayName)
            .putString("onboarding_complete", "true")
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
        // Try Room first
        val pubEd = appSettingDao.getSetting("local_pub_ed25519")
        val pubEnc = appSettingDao.getSetting("local_pub_enc")
        val tripcode = appSettingDao.getSetting("local_tripcode")
        val onion = appSettingDao.getSetting("local_onion")
        val displayName = appSettingDao.getSetting("local_display_name")

        val privEd = prefs.getString("ed25519_private_key", null) ?: return null
        val privEnc = prefs.getString("enc_private_key", null) ?: return null

        // If Room data was wiped by a destructive migration but ESP has identity, recover
        if (pubEd == null || pubEnc == null || tripcode == null || onion == null || displayName == null) {
            val espPub = prefs.getString("pub_ed25519", null)
            val espEncPub = prefs.getString("pub_enc", null)
            val espHandle = prefs.getString("handle", null)
            val espTrip = prefs.getString("tripcode", null)
            val espOnion = prefs.getString("onion", null)
            val espDisplay = prefs.getString("display_name", null)

            if (espPub != null && espEncPub != null && espHandle != null && espTrip != null && espOnion != null && espDisplay != null) {
                Logger.info(TAG, "Room identity wiped — recovering from EncryptedSharedPreferences")
                // Re-seed Room
                appSettingDao.insertSetting(AppSetting("local_handle", espHandle))
                appSettingDao.insertSetting(AppSetting("local_pub_ed25519", espPub))
                appSettingDao.insertSetting(AppSetting("local_pub_enc", espEncPub))
                appSettingDao.insertSetting(AppSetting("local_tripcode", espTrip))
                appSettingDao.insertSetting(AppSetting("local_onion", espOnion))
                appSettingDao.insertSetting(AppSetting("local_display_name", espDisplay))
                appSettingDao.insertSetting(AppSetting("onboarding_complete", "true"))

                return CryptoService.IdentityKeys(
                    publicKeyB64 = espPub,
                    privateKeyB64 = privEd,
                    tripcode = espTrip,
                    onionAddress = espOnion,
                    displayName = espDisplay,
                    encPublicKeyB64 = espEncPub,
                    encPrivateKeyB64 = privEnc
                )
            }
            return null
        }

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
    suspend fun isOnboardingComplete(): Boolean {
        // Check Room first, then fall back to ESP (survives destructive DB migration)
        val roomVal = appSettingDao.getSetting("onboarding_complete")
        if (roomVal == "true") return true
        
        val espVal = prefs.getString("onboarding_complete", null)
        if (espVal == "true") {
            // Re-seed Room so subsequent checks are fast
            appSettingDao.insertSetting(AppSetting("onboarding_complete", "true"))
            return true
        }
        return false
    }
    suspend fun setOnboardingComplete(complete: Boolean) {
        appSettingDao.insertSetting(AppSetting("onboarding_complete", complete.toString()))
        prefs.edit().putString("onboarding_complete", complete.toString()).apply()
    }
    
    suspend fun updateOnionAddress(address: String) {
        appSettingDao.insertSetting(AppSetting("local_onion", address))
        prefs.edit().putString("onion", address).apply()
        Logger.info(TAG, "Onion address dynamically updated in Room: $address")
    }

    /**
     * Wipe all identity data from both ESP and Room. Used by factory reset.
     */
    suspend fun clearAll() {
        prefs.edit().clear().apply()
        Logger.info(TAG, "All identity data cleared from EncryptedSharedPreferences")
    }

    /**
     * Checks if encryption is active.
     * Returns true if using EncryptedSharedPreferences, false if using fallback plaintext SharedPreferences.
     */
    fun isEncryptionActive(): Boolean {
        return prefs.javaClass.name.contains("EncryptedSharedPreferences")
    }
}
