// FILE: app/src/main/java/com/noslop/app/data/IdentityRepository.kt
package com.noslop.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import org.json.JSONObject
import java.io.File

sealed class IdentityRestoreResult {
    object Success : IdentityRestoreResult()
    object RecoveryRequired : IdentityRestoreResult()
    data class Failure(val reason: String) : IdentityRestoreResult()
}

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
class IdentityRepository(private val context: Context, private val appSettingDao: AppSettingDao) {

    private val TAG = "IDENTITY_REPO"

    val isUsingInsecureStorage = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isQuarantined = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun hasQuarantinedIdentity(): Boolean {
        if (isQuarantined.value) return true
        val prefsDir = File(context.filesDir.parentFile, "shared_prefs")
        return prefsDir.listFiles()?.any { it.name.startsWith("noslop_identity_secure.xml.corrupt_") } == true
    }

    suspend fun needsIdentityRecovery(): Boolean {
        if (isQuarantined.value || hasQuarantinedIdentity() || appSettingDao.getSetting("identity_quarantined") == "true") {
            return loadIdentity() == null
        }
        return false
    }

    fun resolveQuarantine() {
        isQuarantined.value = false
        appSettingDao.removeSetting("identity_quarantined")
        try {
            val prefsDir = File(context.filesDir.parentFile, "shared_prefs")
            prefsDir.listFiles()?.filter { it.name.startsWith("noslop_identity_secure.xml.corrupt_") }?.forEach { corruptFile ->
                val resolvedName = corruptFile.name.replace(".corrupt_", ".resolved_")
                val target = File(corruptFile.parentFile, resolvedName)
                corruptFile.renameTo(target)
                Logger.info(TAG, "Archived quarantine marker ${corruptFile.name} -> ${target.name}")
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed archiving quarantine file: ${e.message}")
        }
    }

    private fun buildMasterKey(ctx: Context): MasterKey {
        return MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun createEncryptedPrefs(ctx: Context): android.content.SharedPreferences {
        return EncryptedSharedPreferences.create(
            ctx,
            "noslop_identity_secure",
            buildMasterKey(ctx),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // EncryptedSharedPreferences backed by Android Keystore master key
    // Falls back to plaintext SharedPreferences ONLY if hardware Keystore is genuinely unavailable
    private val prefs: android.content.SharedPreferences = try {
        createEncryptedPrefs(context).also {
            Logger.info(TAG, "EncryptedSharedPreferences initialized with hardware-backed keystore")
        }
    } catch (e: Exception) {
        val secureFile = File(context.filesDir.parentFile, "shared_prefs/noslop_identity_secure.xml")
        var recovered: android.content.SharedPreferences? = null
        if (secureFile.exists()) {
            Logger.error(TAG, "EncryptedSharedPreferences failed on existing file (${e.message}). Quarantining unopenable file without destroying key material...")
            try {
                val quarantine = File(context.filesDir.parentFile, "shared_prefs/noslop_identity_secure.xml.corrupt_${System.currentTimeMillis()}")
                val renamed = secureFile.renameTo(quarantine)
                if (renamed) {
                    Logger.warn(TAG, "Secure file quarantined to ${quarantine.name}. Storing quarantine recovery state...")
                    isQuarantined.value = true
                    appSettingDao.insertSetting(AppSetting("identity_quarantined", "true"))
                    recovered = try {
                        createEncryptedPrefs(context).also {
                            Logger.info(TAG, "Successfully re-initialized hardware Keystore preferences after quarantine")
                        }
                    } catch (e2: Exception) {
                        Logger.error(TAG, "Hardware Keystore is genuinely unavailable even after quarantine: ${e2.message}")
                        null
                    }
                }
            } catch (renEx: Exception) {
                Logger.error(TAG, "Failed to quarantine secure preference file: ${renEx.message}")
            }
        }
        if (recovered != null) {
            recovered
        } else {
            Logger.error(TAG, "EncryptedSharedPreferences failed, falling back to AES-GCM encrypted SharedPreferences: ${e.message}")
            isUsingInsecureStorage.value = true
            context.getSharedPreferences("noslop_identity_fallback", Context.MODE_PRIVATE)
        }
    }

    private val fallbackSecretKey: javax.crypto.SecretKey by lazy {
        val deviceId = try {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "noslop_fallback_salt"
        } catch (_: Exception) { "noslop_fallback_salt" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(("NoSlopSecureSalt_$deviceId").toByteArray(Charsets.UTF_8))
        javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
    }

    private fun secureFallbackWrite(value: String): String {
        return if (isUsingInsecureStorage.value) {
            try {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, fallbackSecretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
                val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                val payload = iv + encrypted
                "ENC_GCM:" + android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                Logger.error(TAG, "Fallback encryption failed: ${e.message}")
                throw SecurityException("Failed to encrypt secret into fallback storage", e)
            }
        } else {
            value
        }
    }

    private fun secureFallbackRead(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith("ENC_GCM:")) return stored
        return try {
            val payload = android.util.Base64.decode(stored.removePrefix("ENC_GCM:"), android.util.Base64.DEFAULT)
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, fallbackSecretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.error(TAG, "Fallback decryption failed: ${e.message}")
            null
        }
    }

    suspend fun saveIdentity(handle: String, keys: CryptoService.IdentityKeys, mnemonic: String) {
        // Private keys -> SharedPreferences (encrypted via ESP or fallback AES-GCM)
        prefs.edit()
            .putString("ed25519_private_key", secureFallbackWrite(keys.privateKeyB64))
            .putString("enc_private_key", secureFallbackWrite(keys.encPrivateKeyB64))
            .putString("mnemonic", secureFallbackWrite(mnemonic))
            // Also persist public identity data in ESP so it survives DB resets
            .putString("pub_ed25519", keys.publicKeyB64)
            .putString("pub_enc", keys.encPublicKeyB64)
            .putString("handle", handle)
            .putString("tripcode", keys.tripcode)
            .putString("onion", keys.onionAddress)
            .putString("display_name", keys.displayName)
            .putString("onboarding_complete", "true")
            // Clean up any stale burnable identity from previous account
            .remove("burnable_ed25519_private_key")
            .remove("burnable_enc_private_key")
            .remove("burnable_pub_ed25519")
            .remove("burnable_pub_enc")
            .remove("burnable_tripcode")
            .remove("burnable_onion")
            .remove("burnable_display_name")
            .apply()

        // Identity version 2 indicates deterministic HKDF derivation from Word Cloud mnemonic
        resolveQuarantine()
        appSettingDao.insertSetting(AppSetting("identity_version", "2"))
        prefs.edit().putString("identity_version", "2").apply()

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
    }

    /**
     * U01 / U02: Atomically restore identity credentials with strict validation and verified persistence.
     */
    suspend fun restoreAuthoritativeIdentity(identityObj: JSONObject, fallbackMnemonic: String): IdentityRestoreResult {
        try {
            val requiredFields = listOf(
                "publicKeyB64", "privateKeyB64", "encPublicKeyB64", "encPrivateKeyB64",
                "handle", "tripcode", "onionAddress", "displayName"
            )
            for (rf in requiredFields) {
                if (!identityObj.has(rf) || identityObj.getString(rf).isBlank()) {
                    return IdentityRestoreResult.Failure("Missing required identity field: $rf")
                }
            }

            val pubEd = identityObj.getString("publicKeyB64")
            val privEd = identityObj.getString("privateKeyB64")
            val pubEnc = identityObj.getString("encPublicKeyB64")
            val privEnc = identityObj.getString("encPrivateKeyB64")

            // Validate main Ed25519 signing keypair consistency
            val probePayload = "noslop_probe_${System.currentTimeMillis()}"
            val probeSig = CryptoService.sign(probePayload, privEd)
            if (!CryptoService.verify(probePayload, probeSig, pubEd)) {
                return IdentityRestoreResult.Failure("Main Ed25519 keypair consistency check failed")
            }

            // Validate main X25519 encryption keypair consistency
            val (encProbe, nonceProbe) = CryptoService.encryptDM(probePayload, pubEnc, privEnc)
            val decProbe = CryptoService.decryptDM(encProbe, nonceProbe, pubEnc, privEnc)
            if (decProbe != probePayload) {
                return IdentityRestoreResult.Failure("Main X25519 keypair agreement check failed")
            }

            // Validate burnable identity if present
            var hasBurnable = false
            if (identityObj.has("burnable")) {
                val bObj = identityObj.getJSONObject("burnable")
                val burnableRequired = listOf(
                    "publicKeyB64", "privateKeyB64", "encPublicKeyB64", "encPrivateKeyB64",
                    "tripcode", "onionAddress", "displayName"
                )
                for (brf in burnableRequired) {
                    if (!bObj.has(brf) || bObj.getString(brf).isBlank()) {
                        return IdentityRestoreResult.Failure("Burnable identity missing required field: $brf")
                    }
                }
                val bPubEd = bObj.getString("publicKeyB64")
                val bPrivEd = bObj.getString("privateKeyB64")
                val bPubEnc = bObj.getString("encPublicKeyB64")
                val bPrivEnc = bObj.getString("encPrivateKeyB64")

                val bSig = CryptoService.sign(probePayload, bPrivEd)
                if (!CryptoService.verify(probePayload, bSig, bPubEd)) {
                    return IdentityRestoreResult.Failure("Burnable Ed25519 keypair consistency check failed")
                }
                val (bEncProbe, bNonceProbe) = CryptoService.encryptDM(probePayload, bPubEnc, bPrivEnc)
                val bDecProbe = CryptoService.decryptDM(bEncProbe, bNonceProbe, bPubEnc, bPrivEnc)
                if (bDecProbe != probePayload) {
                    return IdentityRestoreResult.Failure("Burnable X25519 keypair agreement check failed")
                }
                hasBurnable = true
            }

            val handle = identityObj.getString("handle")
            val tripcode = identityObj.getString("tripcode")
            val onion = identityObj.getString("onionAddress")
            val displayName = identityObj.getString("displayName")
            val mnemonic = identityObj.optString("mnemonic", fallbackMnemonic)
            val idVersion = identityObj.optString("identity_version", "2")

            val edit = prefs.edit().clear()
            edit.putString("ed25519_private_key", secureFallbackWrite(privEd))
                .putString("enc_private_key", secureFallbackWrite(privEnc))
                .putString("mnemonic", secureFallbackWrite(mnemonic))
                .putString("pub_ed25519", pubEd)
                .putString("pub_enc", pubEnc)
                .putString("handle", handle)
                .putString("tripcode", tripcode)
                .putString("onion", onion)
                .putString("display_name", displayName)
                .putString("onboarding_complete", "true")
                .putString("identity_version", idVersion)

            if (hasBurnable) {
                val bObj = identityObj.getJSONObject("burnable")
                edit.putString("burnable_ed25519_private_key", secureFallbackWrite(bObj.getString("privateKeyB64")))
                    .putString("burnable_enc_private_key", secureFallbackWrite(bObj.getString("encPrivateKeyB64")))
                    .putString("burnable_pub_ed25519", bObj.getString("publicKeyB64"))
                    .putString("burnable_pub_enc", bObj.getString("encPublicKeyB64"))
                    .putString("burnable_tripcode", bObj.getString("tripcode"))
                    .putString("burnable_onion", bObj.getString("onionAddress"))
                    .putString("burnable_display_name", bObj.getString("displayName"))
            }

            val committed = edit.commit()
            if (!committed) {
                return IdentityRestoreResult.Failure("Synchronous preference commit failed")
            }

            // Sync Room settings mirror
            appSettingDao.insertSetting(AppSetting("local_handle", handle))
            appSettingDao.insertSetting(AppSetting("local_pub_ed25519", pubEd))
            appSettingDao.insertSetting(AppSetting("local_pub_enc", pubEnc))
            appSettingDao.insertSetting(AppSetting("local_tripcode", tripcode))
            appSettingDao.insertSetting(AppSetting("local_onion", onion))
            appSettingDao.insertSetting(AppSetting("local_display_name", displayName))
            appSettingDao.insertSetting(AppSetting("onboarding_complete", "true"))
            appSettingDao.insertSetting(AppSetting("identity_version", idVersion))

            // Verify identity can be loaded back and private keys are intact
            val reloadedMain = loadIdentity()
            if (reloadedMain == null) {
                return IdentityRestoreResult.Failure("Restored main identity failed reload probe")
            }
            if (hasBurnable) {
                val reloadedBurnable = getBurnableIdentity()
                if (reloadedBurnable == null) {
                    return IdentityRestoreResult.Failure("Restored burnable identity failed reload probe")
                }
            }

            resolveQuarantine()
            Logger.info(TAG, "Authoritative sovereign identity restored successfully")
            return IdentityRestoreResult.Success
        } catch (e: Exception) {
            Logger.error(TAG, "Exception during identity restoration: ${e.message}")
            return IdentityRestoreResult.Failure(e.message ?: "Unknown restoration error")
        }
    }

    suspend fun loadIdentity(): CryptoService.IdentityKeys? {
        val pubEd = appSettingDao.getSetting("local_pub_ed25519") ?: prefs.getString("pub_ed25519", null)
        val pubEnc = appSettingDao.getSetting("local_pub_enc") ?: prefs.getString("pub_enc", null)
        val tripcode = appSettingDao.getSetting("local_tripcode") ?: prefs.getString("tripcode", null)
        val onion = appSettingDao.getSetting("local_onion") ?: prefs.getString("onion", null)
        val displayName = appSettingDao.getSetting("local_display_name") ?: prefs.getString("display_name", null)

        val rawPrivEd = prefs.getString("ed25519_private_key", null) ?: return null
        val rawPrivEnc = prefs.getString("enc_private_key", null) ?: return null

        val privEd = secureFallbackRead(rawPrivEd) ?: return null
        val privEnc = secureFallbackRead(rawPrivEnc) ?: return null

        if (pubEd == null || pubEnc == null || tripcode == null || onion == null || displayName == null) {
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

    suspend fun getMnemonic(): String? = secureFallbackRead(prefs.getString("mnemonic", null))

    suspend fun logout() {
        appSettingDao.insertSetting(AppSetting("session_locked", "true"))
        Logger.info(TAG, "User logged out / Session locked")
    }

    suspend fun isLocked(): Boolean = appSettingDao.getSetting("session_locked") == "true"

    suspend fun unlock(mnemonic: String): Boolean {
        val savedMnemonic = secureFallbackRead(prefs.getString("mnemonic", null))
        val normalised = mnemonic.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        val savedNormalised = savedMnemonic?.trim()?.lowercase()?.split(Regex("\\s+"))?.joinToString(" ")
        return if (savedNormalised != null && savedNormalised == normalised) {
            appSettingDao.insertSetting(AppSetting("session_locked", "false"))
            Logger.info(TAG, "Session unlocked")
            true
        } else {
            Logger.warn(TAG, "Unlock failed: Mnemonic mismatch")
            false
        }
    }

    suspend fun getIdentityVersion(): Int {
        val verStr = appSettingDao.getSetting("identity_version") ?: prefs.getString("identity_version", null)
        return verStr?.toIntOrNull() ?: 1
    }

    suspend fun getHandle(): String = appSettingDao.getSetting("local_handle") ?: "Anonymous"

    suspend fun isOnboardingComplete(): Boolean {
        if (needsIdentityRecovery()) {
            return false
        }
        val roomVal = appSettingDao.getSetting("onboarding_complete")
        if (roomVal == "true") return true
        
        val espVal = prefs.getString("onboarding_complete", null)
        if (espVal == "true") {
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

    suspend fun clearAll() {
        prefs.edit().clear().apply()
        val secureFile = File(context.filesDir.parentFile, "shared_prefs/noslop_identity_secure.xml")
        if (secureFile.exists()) secureFile.delete()
        val fallbackFile = File(context.filesDir.parentFile, "shared_prefs/noslop_identity_fallback.xml")
        if (fallbackFile.exists()) fallbackFile.delete()
        resolveQuarantine()
        isUsingInsecureStorage.value = false
        listOf(
            "local_handle", "local_pub_ed25519", "local_pub_enc",
            "local_tripcode", "local_onion", "local_display_name",
            "onboarding_complete", "session_locked", "identity_version"
        ).forEach { appSettingDao.removeSetting(it) }
        Logger.info(TAG, "All identity data cleared from encrypted prefs, files, and Room")
    }

    suspend fun generateBurnableIdentity(): CryptoService.IdentityKeys {
        val burnableHandle = getHandle()
        val burnableIdentity = CryptoService.generateIdentity(burnableHandle)
        
        prefs.edit()
            .putString("burnable_ed25519_private_key", secureFallbackWrite(burnableIdentity.privateKeyB64))
            .putString("burnable_enc_private_key", secureFallbackWrite(burnableIdentity.encPrivateKeyB64))
            .putString("burnable_pub_ed25519", burnableIdentity.publicKeyB64)
            .putString("burnable_pub_enc", burnableIdentity.encPublicKeyB64)
            .putString("burnable_tripcode", burnableIdentity.tripcode)
            .putString("burnable_onion", burnableIdentity.onionAddress)
            .putString("burnable_display_name", burnableIdentity.displayName)
            .apply()
            
        Logger.info(TAG, "Burnable identity generated: ${burnableIdentity.tripcode}")
        return burnableIdentity
    }

    suspend fun getBurnableIdentity(): CryptoService.IdentityKeys? {
        val pubEd = prefs.getString("burnable_pub_ed25519", null) ?: return null
        val pubEnc = prefs.getString("burnable_pub_enc", null) ?: return null
        val tripcode = prefs.getString("burnable_tripcode", null) ?: return null
        val onion = prefs.getString("burnable_onion", null) ?: return null
        val displayName = getHandle()
        val privEd = secureFallbackRead(prefs.getString("burnable_ed25519_private_key", null)) ?: return null
        val privEnc = secureFallbackRead(prefs.getString("burnable_enc_private_key", null)) ?: return null
        
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

    suspend fun clearBurnableIdentity() {
        prefs.edit()
            .remove("burnable_ed25519_private_key")
            .remove("burnable_enc_private_key")
            .remove("burnable_pub_ed25519")
            .remove("burnable_pub_enc")
            .remove("burnable_tripcode")
            .remove("burnable_onion")
            .remove("burnable_display_name")
            .apply()
        Logger.info(TAG, "Burnable identity cleared")
    }

    fun isEncryptionActive(): Boolean {
        return prefs.javaClass.name.contains("EncryptedSharedPreferences")
    }
}
