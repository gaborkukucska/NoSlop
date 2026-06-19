package com.noslop.mvp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.noslop.mvp.db.MeshDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * JVM (desktop HUB) actuals for the shared platform seams — the same BouncyCastle crypto the Android target
 * uses (so a HUB verifies signatures and relays DMs identically to a phone), plus plain-file persistence for
 * identity/handle and a JDBC SQLite database. This is what makes the always-on HUB (ADR-002) a real process.
 */
private val bc = BouncyCastleProvider()
private val hubDir = File(System.getProperty("user.home"), ".noslop-hub").apply { mkdirs() }

@OptIn(ExperimentalEncodingApi::class)
actual object IdentityKeyStore {
    private val file = File(hubDir, "identity")
    actual fun loadOrCreatePublicKey(): ByteArray {
        if (file.exists()) file.readLines().firstOrNull()?.let { return Base64.decode(it) }
        val kp = KeyPairGenerator.getInstance("Ed25519", bc)
            .apply { initialize(255, SecureRandom()) }.generateKeyPair()
        file.writeText(Base64.encode(kp.public.encoded) + "\n" + Base64.encode(kp.private.encoded) + "\n")
        return kp.public.encoded
    }
    actual val isRealKeypair: Boolean get() = true
    actual fun reset() { file.delete() }
}

actual object HandleStore {
    private val file = File(hubDir, "handle")
    actual fun load(): String = if (file.exists()) file.readText().trim().ifBlank { "hub" } else "hub"
    actual fun save(handle: String) { file.writeText(handle) }
}

/** JVM Ed25519 signer via BouncyCastle JCA (raw bytes; base64/UTF-8 stay in commonMain). Mirrors androidMain. */
actual object Signer {
    actual val isAvailable: Boolean = true

    actual fun signRaw(payload: ByteArray, pkcs8PrivateKey: ByteArray): ByteArray = try {
        val priv = KeyFactory.getInstance("Ed25519", bc).generatePrivate(PKCS8EncodedKeySpec(pkcs8PrivateKey))
        Signature.getInstance("Ed25519", bc).run { initSign(priv); update(payload); sign() }
    } catch (e: Exception) { ByteArray(0) }

    actual fun verifyRaw(payload: ByteArray, signature: ByteArray, x509PublicKey: ByteArray): Boolean = try {
        val pub = KeyFactory.getInstance("Ed25519", bc).generatePublic(X509EncodedKeySpec(x509PublicKey))
        Signature.getInstance("Ed25519", bc).run { initVerify(pub); update(payload); verify(signature) }
    } catch (e: Exception) { false }
}

/** JVM DM crypto via BouncyCastle: X25519 + ChaCha20-Poly1305 (IETF). Mirrors androidMain. */
actual object DmCrypto {
    actual val isAvailable: Boolean = true

    actual fun x25519SharedSecret(myPrivPkcs8: ByteArray, theirPubX509: ByteArray): ByteArray = try {
        val kf = KeyFactory.getInstance("X25519", bc)
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(myPrivPkcs8))
        val pub = kf.generatePublic(X509EncodedKeySpec(theirPubX509))
        KeyAgreement.getInstance("X25519", bc).run { init(priv); doPhase(pub, true); generateSecret() }
    } catch (e: Exception) { ByteArray(0) }

    actual fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray = try {
        cipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)
    } catch (e: Exception) { ByteArray(0) }

    actual fun chachaOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = try {
        cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(ciphertextAndTag)
    } catch (e: Exception) { null }

    actual fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("ChaCha20-Poly1305", bc).apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        }
}

/** JVM SQLite via the JDBC driver; creates the schema on first run. */
actual object DbDriverFactory {
    actual val isAvailable: Boolean = true
    actual fun create(): SqlDriver {
        val dbFile = File(hubDir, "mesh.db")
        val fresh = !dbFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (fresh) MeshDatabase.Schema.create(driver)
        return driver
    }
}

actual fun httpClientEngineFactory(): HttpClient = HttpClient(OkHttp)

actual fun nowMillis(): Long = System.currentTimeMillis()
actual fun randomId(): String = java.util.UUID.randomUUID().toString()

/** The desktop HUB hosts the onion (via TorProcess) rather than dialing one — no client Tor needed here. */
actual object TorService {
    actual val isAvailable: Boolean = false
    actual fun start() {}
    actual fun socksPort(): Int = 0
    actual fun bootstrapProgress(): Int = 0
    actual fun status(): String = "unavailable"
}

/** The desktop HUB *shows* the QR; it doesn't scan one. */
actual object QrScanner {
    actual val isAvailable: Boolean = false
    actual fun scan(onResult: (String?) -> Unit) { onResult(null) }
}
