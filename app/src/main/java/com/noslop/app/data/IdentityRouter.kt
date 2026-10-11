// FILE: app/src/main/java/com/noslop/app/data/IdentityRouter.kt
package com.noslop.app.data

import com.noslop.app.crypto.CryptoService

/**
 * One of our two identities, with the profile that goes with it. The personal identity carries the
 * personal handle, bio and avatar; the creator (burnable) identity carries the creator profile
 * (see [CREATOR_PROFILE_KEY]) and never the personal one.
 *
 * D10c: on a node that is not a creator the burnable identity only follows creators; it goes by the
 * personal handle there (see [secondaryIdentityHandle]) and still never carries the personal bio or
 * avatar.
 */
data class RoutedIdentity(
    val keys: CryptoService.IdentityKeys,
    val handle: String,
    /** Null when blank. */
    val bio: String?,
    /** Null when blank. */
    val avatarB64: String?,
    val isCreator: Boolean
) {
    val publicKeyB64: String get() = keys.publicKeyB64
    val onionAddress: String get() = keys.onionAddress
}

/**
 * Review D04: the single place that decides which of our identities, and which profile, a packet
 * for a given peer is built from. Before, every send path chose for itself (by `contact_identity_*`,
 * by `Peer.isTemporary`, by creator mode, or not at all), and the creator identity went out with the
 * personal handle, bio and avatar, so creator contacts could link it to the personal identity.
 *
 * The rule ([knowsUsAsCreator]): a peer knows us by our creator identity when the contact is bound to
 * it (`contact_identity_<peer>` = burnable), or, before the user has decided, when the peer addressed
 * it (`requested_identity_<peer>`). A peer with neither setting that is a temporary (creator-side) row
 * and not an accepted contact (a discoverable node, a stranger) is also treated as a creator contact.
 * Everyone else knows our personal identity.
 */
class IdentityRouter(
    private val db: NoSlopDatabase,
    private val getLocalIdentity: suspend () -> CryptoService.IdentityKeys?,
    private val getBurnableIdentity: suspend () -> CryptoService.IdentityKeys?,
    private val getLocalHandle: suspend () -> String,
    private val getUserProfile: suspend () -> UserProfile,
    private val getCreatorProfile: suspend () -> UserProfile
) {
    /** The personal identity with the personal profile. */
    suspend fun main(): RoutedIdentity? {
        val keys = getLocalIdentity() ?: return null
        val profile = getUserProfile()
        return RoutedIdentity(
            keys = keys,
            handle = getLocalHandle(),
            bio = profile.bio.takeIf { it.isNotBlank() },
            avatarB64 = profile.avatarB64?.takeIf { it.isNotBlank() },
            isCreator = false
        )
    }

    /** The creator (burnable) identity with the creator profile, or null when there is none. */
    suspend fun creator(): RoutedIdentity? {
        val keys = getBurnableIdentity() ?: return null
        val profile = getCreatorProfile()
        return RoutedIdentity(
            keys = keys,
            handle = secondaryIdentityHandle(
                isCreatorNode = db.appSettingDao().getSetting(CREATOR_MODE_KEY) == "true",
                creatorProfile = profile,
                personalHandle = getLocalHandle()
            ),
            bio = profile.bio.takeIf { it.isNotBlank() },
            avatarB64 = profile.avatarB64?.takeIf { it.isNotBlank() },
            isCreator = true
        )
    }

    /** The identity (with its profile) that owns [publicKeyB64], or null when it isn't one of ours. */
    suspend fun forKey(publicKeyB64: String?): RoutedIdentity? {
        if (publicKeyB64.isNullOrBlank()) return null
        val main = main()
        if (main != null && main.publicKeyB64 == publicKeyB64) return main
        val creator = creator()
        if (creator != null && creator.publicKeyB64 == publicKeyB64) return creator
        return null
    }

    /** Whether [peerPub] knows us by our creator identity (see the class comment). */
    suspend fun knowsUsAsCreator(peerPub: String, peer: Peer? = null): Boolean {
        val settings = db.appSettingDao()
        val setting = settings.getSetting("contact_identity_$peerPub")
            ?: settings.getSetting("requested_identity_$peerPub")
        if (setting != null) return setting == "burnable"
        val row = peer ?: db.peerDao().getPeerByPublicKey(peerPub) ?: return false
        return row.isTemporary && row.relationship != PeerRelationship.ACCEPTED
    }

    /**
     * The identity to use towards [peerPub]. Null when the peer knows us as a creator and the creator
     * identity no longer exists (creator mode turned off): nothing is sent then, rather than falling
     * back to the personal identity, which that peer must never see.
     */
    suspend fun forPeer(peerPub: String, peer: Peer? = null): RoutedIdentity? =
        if (knowsUsAsCreator(peerPub, peer)) creator() else main()

    /**
     * The identity to comment on a post by [authorPub] with: the creator identity on our own creator
     * posts and on posts by peers who know us as a creator, otherwise the personal identity.
     */
    suspend fun forPostAuthor(authorPub: String?): RoutedIdentity? {
        if (authorPub.isNullOrBlank()) return main()
        forKey(authorPub)?.let { return it }
        return if (knowsUsAsCreator(authorPub)) creator() ?: main() else main()
    }
}
