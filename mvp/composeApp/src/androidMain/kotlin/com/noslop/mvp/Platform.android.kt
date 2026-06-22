package com.noslop.mvp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyPairGenerator
import java.security.SecureRandom

/** App context holder, set by [MainActivity] so the keystore can reach EncryptedSharedPreferences. */
object AndroidAppContext {
    lateinit var context: Context
    val isSet: Boolean get() = ::context.isInitialized
}

/**
 * Android persistent identity store: a real BouncyCastle Ed25519 keypair whose private key is kept in
 * Android Keystore-backed [EncryptedSharedPreferences], so the same identity is returned every launch.
 * Falls back to a process-stable in-memory key only if no app context is wired (shouldn't happen in app).
 */
actual object IdentityKeyStore {
    private const val PREFS = "noslop_identity"
    private const val KEY_PUB = "ed25519_pub_x509_b64"
    private const val KEY_PRIV = "ed25519_priv_pkcs8_b64"
    private const val KEY_ENC_PUB = "x25519_pub_x509_b64"
    private const val KEY_ENC_PRIV = "x25519_priv_pkcs8_b64"
    private val bc = org.bouncycastle.jce.provider.BouncyCastleProvider()
    private var fallbackKey: ByteArray? = null

    private fun prefs(): SharedPreferences? {
        if (!AndroidAppContext.isSet) return null
        val ctx = AndroidAppContext.context
        return try {
            val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Keystore key was lost or Auto-Backup restored the prefs without the hardware key.
            // Clear the corrupted file to prevent a permanent crash loop.
            try {
                ctx.deleteSharedPreferences(PREFS)
            } catch (ignored: Exception) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            }
            val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    actual fun loadOrCreatePublicKey(): ByteArray {
        val p = prefs() ?: return fallbackKey ?: genKeyPairPublic().also { fallbackKey = it }
        p.getString(KEY_PUB, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }

        val kp = KeyPairGenerator.getInstance("Ed25519", bc)
            .apply { initialize(255, SecureRandom()) }.generateKeyPair()
            
        val x25519Kp = KeyPairGenerator.getInstance("X25519", bc)
            .apply { initialize(255, SecureRandom()) }.generateKeyPair()
            
        p.edit()
            .putString(KEY_PUB, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString(KEY_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(KEY_ENC_PUB, Base64.encodeToString(x25519Kp.public.encoded, Base64.NO_WRAP))
            .putString(KEY_ENC_PRIV, Base64.encodeToString(x25519Kp.private.encoded, Base64.NO_WRAP))
            .apply()
        return kp.public.encoded // X.509 (44 bytes); IdentityDerivation strips the header
    }

    actual fun getPrivateKey(): ByteArray? {
        val p = prefs() ?: return null
        return p.getString(KEY_PRIV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    actual fun getEncPublicKey(): ByteArray? {
        val p = prefs() ?: return null
        return p.getString(KEY_ENC_PUB, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    actual fun getEncPrivateKey(): ByteArray? {
        val p = prefs() ?: return null
        return p.getString(KEY_ENC_PRIV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    actual val isRealKeypair: Boolean get() = AndroidAppContext.isSet

    actual fun reset() {
        prefs()?.edit()?.clear()?.apply()
        fallbackKey = null
    }

    private fun genKeyPairPublic(): ByteArray =
        KeyPairGenerator.getInstance("Ed25519", bc)
            .apply { initialize(255, SecureRandom()) }.generateKeyPair().public.encoded
}

/** Handle persisted in app-private SharedPreferences (in-memory fallback when no context, e.g. tests). */
actual object HandleStore {
    private const val PREFS = "noslop_handle"
    private const val KEY = "handle"
    private var fallback = "anon"
    private fun sp() =
        if (AndroidAppContext.isSet) AndroidAppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) else null
    actual fun load(): String = sp()?.getString(KEY, null) ?: fallback
    actual fun save(handle: String) {
        val s = sp()
        if (s != null) s.edit().putString(KEY, handle).apply() else fallback = handle
    }
}

/** Android Ed25519 signer via BouncyCastle JCA (raw bytes; base64/UTF-8 handled in commonMain). */
actual object Signer {
    private val bc = org.bouncycastle.jce.provider.BouncyCastleProvider()
    actual val isAvailable: Boolean = true

    actual fun signRaw(payload: ByteArray, pkcs8PrivateKey: ByteArray): ByteArray = try {
        val priv = java.security.KeyFactory.getInstance("Ed25519", bc)
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8PrivateKey))
        java.security.Signature.getInstance("Ed25519", bc).run {
            initSign(priv); update(payload); sign()
        }
    } catch (e: Exception) { ByteArray(0) }

    actual fun verifyRaw(payload: ByteArray, signature: ByteArray, x509PublicKey: ByteArray): Boolean = try {
        val pub = java.security.KeyFactory.getInstance("Ed25519", bc)
            .generatePublic(java.security.spec.X509EncodedKeySpec(x509PublicKey))
        java.security.Signature.getInstance("Ed25519", bc).run {
            initVerify(pub); update(payload); verify(signature)
        }
    } catch (e: Exception) { false }
}

/**
 * Android DM crypto via BouncyCastle: X25519 key agreement + ChaCha20-Poly1305 (IETF, 12-byte nonce).
 * Raw bytes only — SHA3 / base64 / UTF-8 are handled in commonMain ([DmCrypto] / [encryptDM]).
 */
actual object DmCrypto {
    private val bc = org.bouncycastle.jce.provider.BouncyCastleProvider()
    actual val isAvailable: Boolean = true

    actual fun x25519SharedSecret(myPrivPkcs8: ByteArray, theirPubX509: ByteArray): ByteArray = try {
        val kf = java.security.KeyFactory.getInstance("X25519", bc)
        val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(myPrivPkcs8))
        val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(theirPubX509))
        javax.crypto.KeyAgreement.getInstance("X25519", bc).run {
            init(priv); doPhase(pub, true); generateSecret()
        }
    } catch (e: Exception) { ByteArray(0) }

    actual fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray = try {
        cipher(javax.crypto.Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext) // out = ciphertext || 16-byte tag
    } catch (e: Exception) { ByteArray(0) }

    actual fun chachaOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = try {
        cipher(javax.crypto.Cipher.DECRYPT_MODE, key, nonce).doFinal(ciphertextAndTag)
    } catch (e: Exception) { null }

    actual fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): javax.crypto.Cipher =
        javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", bc).apply {
            init(mode, javax.crypto.spec.SecretKeySpec(key, "ChaCha20"), javax.crypto.spec.IvParameterSpec(nonce))
        }
}

/** Android SQLite via SQLDelight's AndroidSqliteDriver, using the app context holder set by MainActivity. */
actual object DbDriverFactory {
    actual val isAvailable: Boolean get() = AndroidAppContext.isSet
    actual fun create(): app.cash.sqldelight.db.SqlDriver =
        app.cash.sqldelight.driver.android.AndroidSqliteDriver(
            com.noslop.mvp.db.MeshDatabase.Schema, AndroidAppContext.context, "mesh.db",
        )
}

actual fun httpClientEngineFactory(): HttpClient = HttpClient(OkHttp)

actual fun nowMillis(): Long = System.currentTimeMillis()
actual fun randomId(): String = java.util.UUID.randomUUID().toString()

/** No embedded Tor on Android in the MVP (Orbot/system Tor is a later option). */
actual object TorService {
    actual val isAvailable: Boolean = false
    actual fun start() {}
    actual fun socksPort(): Int = 0
    actual fun bootstrapProgress(): Int = 0
    actual fun status(): String = "unavailable"
}

/** No in-app QR scanner on Android yet (CameraX/ML Kit is a later option); manual host/port still works. */
actual object QrScanner {
    actual val isAvailable: Boolean = false
    actual fun scan(onResult: (String?) -> Unit) { onResult(null) }
}
