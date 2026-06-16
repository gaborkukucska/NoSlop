package com.noslop.mvp

/** A generated NoSlop identity for display. */
data class Identity(
    val handle: String,
    val tripcode: String,
    val onionAddress: String,
    val publicKeyHex: String,
    /** True if the underlying keypair is a real Ed25519 key; false for the iOS MVP demo key. */
    val isRealKeypair: Boolean,
)

/**
 * Platform seam for keypair material. Returns the 32-byte raw Ed25519 public key used to derive the
 * identity. Android supplies a real BouncyCastle keypair; iOS currently supplies a secure-random demo
 * key (real CryptoKit Curve25519 is the documented fast-follow — see IOS_MVP_PLAN.md).
 */
expect object KeyProvider {
    fun generateRawEd25519PublicKey(): ByteArray
    val producesRealKeypair: Boolean
}

/** Generate an identity: platform keypair → portable tripcode/onion derivation. */
fun generateIdentity(handle: String): Identity {
    val rawPub = KeyProvider.generateRawEd25519PublicKey()
    val tripcode = IdentityDerivation.deriveTripcode(rawPub)
    return Identity(
        handle = handle,
        tripcode = tripcode,
        onionAddress = IdentityDerivation.deriveOnionAddress(rawPub),
        publicKeyHex = rawPub.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        isRealKeypair = KeyProvider.producesRealKeypair,
    )
}
