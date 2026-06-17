package com.noslop.mvp

/** The local NoSlop identity for display. */
data class Identity(
    val handle: String,
    val tripcode: String,
    val onionAddress: String,
    val publicKeyHex: String,
    /** True if the underlying keypair is a real Ed25519 key persisted in secure storage. */
    val isRealKeypair: Boolean,
)

/**
 * Platform seam for the **persistent** local Ed25519 identity.
 *
 * The keypair is generated once and kept in platform secure storage (iOS Keychain via CryptoKit;
 * Android Keystore-backed EncryptedSharedPreferences), so the identity is stable across app launches.
 * [loadOrCreatePublicKey] returns the persisted 32-byte public key, creating + storing a keypair on
 * first use; [reset] discards it so the next load creates a fresh one.
 */
expect object IdentityKeyStore {
    fun loadOrCreatePublicKey(): ByteArray
    val isRealKeypair: Boolean
    fun reset()
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun identityFrom(handle: String, rawPub: ByteArray) = Identity(
    handle = handle,
    tripcode = IdentityDerivation.deriveTripcode(rawPub),
    onionAddress = IdentityDerivation.deriveOnionAddress(rawPub),
    publicKeyHex = rawPub.toHex(),
    isRealKeypair = IdentityKeyStore.isRealKeypair,
)

/** Load the persisted identity (generating + storing one on first run). Stable across launches. */
fun loadIdentity(handle: String): Identity =
    identityFrom(handle, IdentityKeyStore.loadOrCreatePublicKey())

/** Discard the stored identity and create a fresh one. */
fun regenerateIdentity(handle: String): Identity {
    IdentityKeyStore.reset()
    return identityFrom(handle, IdentityKeyStore.loadOrCreatePublicKey())
}
