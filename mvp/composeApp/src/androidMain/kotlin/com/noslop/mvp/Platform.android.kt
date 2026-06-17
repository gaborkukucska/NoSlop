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
    private val bc = org.bouncycastle.jce.provider.BouncyCastleProvider()
    private var fallbackKey: ByteArray? = null

    private fun prefs(): SharedPreferences? {
        if (!AndroidAppContext.isSet) return null
        val ctx = AndroidAppContext.context
        val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual fun loadOrCreatePublicKey(): ByteArray {
        val p = prefs() ?: return fallbackKey ?: genKeyPairPublic().also { fallbackKey = it }
        p.getString(KEY_PUB, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }

        val kp = KeyPairGenerator.getInstance("Ed25519", bc)
            .apply { initialize(255, SecureRandom()) }.generateKeyPair()
        p.edit()
            .putString(KEY_PUB, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString(KEY_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .apply()
        return kp.public.encoded // X.509 (44 bytes); IdentityDerivation strips the header
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

actual fun httpClientEngineFactory(): HttpClient = HttpClient(OkHttp)
