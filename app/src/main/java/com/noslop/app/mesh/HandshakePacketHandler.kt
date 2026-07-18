// FILE: app/src/main/java/com/noslop/app/mesh/HandshakePacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.*
import android.util.Base64
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import java.util.UUID

/**
 * Handles incoming HANDSHAKE mesh packets (handleConnectionRequest, handleUserHandshake, handleConnectionRejected, handleAnnouncePeer, handleIdentityUpdate, handleUserExit).
 *
 * Extracted from the monolithic MeshPacketHandler (Phase 0, Stage 0.3) into one handler per packet
 * domain behind a dispatcher. Constructed with (repo, db) like the original; method bodies are a
 * verbatim move (ADR-004). The dispatcher routes by packet type; this class owns the per-type logic.
 */
class HandshakePacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"
    private val peerDao = db.peerDao()
    private val notificationDao = db.notificationDao()
    private val autoAcceptRateLimits = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()

    suspend fun handleConnectionRequest(packet: NetworkPacket, sendResponse: suspend (NetworkPacket) -> Unit = {}): Boolean {
        val connPay = packet.getConnectionRequestPayload() ?: return false
        
        val signature = packet.signature
        if (signature == null) {
            Logger.warn(TAG, "Rejected CONNECTION_REQUEST: Missing signature")
            return false
        }
        var payloadToVerify = "${connPay.fromUserId}|${connPay.fromUsername}|${connPay.fromHomeNode}|${connPay.timestamp}"
        if (connPay.authorAvatarB64 != null) {
            payloadToVerify += "|${connPay.authorAvatarB64}"
        }
        val isValid = CryptoService.verify(payloadToVerify, signature, connPay.fromUserId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected CONNECTION_REQUEST: Signature verification failed")
            return false
        }
        
        val pubBytes = Base64.decode(connPay.fromUserId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        
        val existingPeer = peerDao.getPeerByPublicKey(connPay.fromUserId)
        val isTrusted = existingPeer?.isTrusted ?: false
        val handleToUse = if (connPay.fromUsername.isNotBlank()) connPay.fromUsername else existingPeer?.handle ?: "Unknown"
        val encPubToUse = connPay.fromEncryptionPublicKey?.takeIf { it.isNotBlank() } ?: existingPeer?.encPublicKeyB64 ?: ""
        val avatarToUse = connPay.authorAvatarB64 ?: existingPeer?.authorAvatarB64
        
        val peer = Peer(
            publicKeyB64 = connPay.fromUserId,
            handle = handleToUse,
            tripcode = tripcode,
            onionAddress = connPay.fromHomeNode,
            encPublicKeyB64 = encPubToUse,
            isTrusted = isTrusted, // Prevent duplicate requests from downgrading trust!
            lastSeenAt = System.currentTimeMillis(),
            authorAvatarB64 = avatarToUse
        )
        peerDao.insertPeer(peer)
        
        if (!isTrusted) {
            val isLocalCreator = db.appSettingDao().getSetting("is_creator") == "true"
            if (isLocalCreator) {
                // Rate limit check
                val now = System.currentTimeMillis()
                val limits = autoAcceptRateLimits.getOrPut("auto_accept") { mutableListOf() }
                var allowed = false
                synchronized(limits) {
                    limits.removeAll { now - it > 3600_000 } // 1 hour window
                    if (limits.size < 10) {
                        limits.add(now)
                        allowed = true
                    }
                }
                
                if (allowed) {
                    // Auto-accept connection for creators, restrict permissions by marking as temporary
                    peerDao.insertPeer(peer.copy(isTrusted = true, isTemporary = true))
                    repo.setHandshakeAccepted(peer)
                    Logger.info(TAG, "Auto-accepted connection request from ${peer.handle} (Creator Mode)")
                } else {
                    Logger.warn(TAG, "Auto-accept rate limit exceeded. Falling back to manual request.")
                    repo.setIncomingRequest(peer)
                }
            } else {
                repo.setIncomingRequest(peer)

                val notifSettings = repo.notificationSettingsFlow.value
                if (notifSettings.connectionRequests) {
                    val title = "New Connection Request"
                    val msg = "${peer.handle} wants to connect with you."
                    val route = "notifications"
                    
                    notificationDao.insertNotification(
                        NotificationItem(
                            id = UUID.randomUUID().toString(),
                            type = "CONNECTION_REQUEST",
                            title = title,
                            body = msg,
                            targetRoute = route,
                            iconType = "handshake",
                            senderPub = peer.publicKeyB64
                        )
                    )

                    com.noslop.app.util.NotificationHelper.showNotification(
                        context = repo.context,
                        title = title,
                        message = msg,
                        deepLinkRoute = route
                    )
                }
            }
        }

        return true
    }

    suspend fun handleUserHandshake(packet: NetworkPacket): Boolean {
        val handPay = packet.getUserHandshakePayload() ?: return false
        
        val signature = packet.signature
        if (signature == null) {
            Logger.warn(TAG, "Rejected USER_HANDSHAKE: Missing signature")
            return false
        }
        var payloadToVerify = "${handPay.fromUserId}|${handPay.fromUsername}|${handPay.fromHomeNode}|${handPay.timestamp}"
        if (handPay.authorAvatarB64 != null) {
            payloadToVerify += "|${handPay.authorAvatarB64}"
        }
        val isValid = CryptoService.verify(payloadToVerify, signature, handPay.fromUserId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected USER_HANDSHAKE: Signature verification failed")
            return false
        }

        val peer = peerDao.getPeerByPublicKey(handPay.fromUserId)
        if (peer != null) {
            val handleToUse = if (handPay.fromUsername.isNotBlank()) handPay.fromUsername else peer.handle
            peerDao.insertPeer(peer.copy(
                handle = handleToUse,
                isTrusted = true,
                lastSeenAt = System.currentTimeMillis(),
                onionAddress = handPay.fromHomeNode,
                encPublicKeyB64 = handPay.fromEncryptionPublicKey?.takeIf { it.isNotBlank() } ?: peer.encPublicKeyB64,
                authorAvatarB64 = handPay.authorAvatarB64 ?: peer.authorAvatarB64
            ))
        } else {
            val handleToUse = if (handPay.fromUsername.isNotBlank()) handPay.fromUsername else "Unknown"
            val pubBytes = Base64.decode(handPay.fromUserId, Base64.DEFAULT)
            val tripcode = CryptoService.deriveTripcode(pubBytes)
            val newPeer = Peer(
                publicKeyB64 = handPay.fromUserId,
                handle = handleToUse,
                tripcode = tripcode,
                onionAddress = handPay.fromHomeNode,
                encPublicKeyB64 = handPay.fromEncryptionPublicKey ?: "",
                isTrusted = true,
                lastSeenAt = System.currentTimeMillis(),
                authorAvatarB64 = handPay.authorAvatarB64
            )
            peerDao.insertPeer(newPeer)
        }

        // Generate the accepted notification
        val notifSettings = repo.notificationSettingsFlow.value
        if (notifSettings.system) {
            val title = "Connection Accepted"
        val msg = "${handPay.fromUsername} accepted your connection request."
        val route = "chat/${handPay.fromUserId}"

        notificationDao.insertNotification(
            NotificationItem(
                id = UUID.randomUUID().toString(),
                type = "SYSTEM",
                title = title,
                body = msg,
                targetRoute = route,
                iconType = "handshake",
                senderPub = handPay.fromUserId
            )
        )

        com.noslop.app.util.NotificationHelper.showNotification(
                context = repo.context,
                title = title,
                message = msg,
                deepLinkRoute = route
            )
        }

        // Signal the UI that our outgoing request was accepted
        val acceptedPeer = peerDao.getPeerByPublicKey(handPay.fromUserId)
        if (acceptedPeer != null) {
            repo.setHandshakeAccepted(acceptedPeer)
        }

        return true
    }

    suspend fun handleConnectionRejected(packet: NetworkPacket): Boolean {
        val rejectPay = packet.getConnectionRejectedPayload() ?: return false
        val signature = packet.signature
        if (signature == null) {
            Logger.warn(TAG, "Rejected CONNECTION_REJECTED: Missing signature")
            return false
        }

        val payloadToVerify = "${rejectPay.fromUserId}|${rejectPay.timestamp}"
        if (!CryptoService.verify(payloadToVerify, signature, rejectPay.fromUserId)) {
            Logger.warn(TAG, "Rejected CONNECTION_REJECTED: Signature verification failed")
            return false
        }

        val peer = peerDao.getPeerByPublicKey(rejectPay.fromUserId)
        if (peer != null) {
            // Remove the pending untrusted peer since they rejected us
            peerDao.deletePeer(peer)

            val notifSettings = repo.notificationSettingsFlow.value
            if (notifSettings.system) {
                val title = "Connection Declined"
            val msg = "${peer.handle} declined your connection request."
            val route = "notifications"

            notificationDao.insertNotification(
                NotificationItem(
                    id = UUID.randomUUID().toString(),
                    type = "SYSTEM",
                    title = title,
                    body = msg,
                    targetRoute = route,
                    iconType = "handshake_declined",
                    senderPub = peer.publicKeyB64
                )
            )

            com.noslop.app.util.NotificationHelper.showNotification(
                    context = repo.context,
                    title = title,
                    message = msg,
                    deepLinkRoute = route
                )
            }
        }
        return true
    }

    suspend fun handleAnnouncePeer(packet: NetworkPacket): Boolean {
        val announcePay = packet.getAnnouncePeerPayload() ?: return false
        val payloadToVerify = "${announcePay.authorId}|${announcePay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, announcePay.signature, announcePay.authorId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected ANNOUNCE_PEER: Signature verification failed")
            return false
        }
        
        val peer = peerDao.getPeerByPublicKey(announcePay.authorId)
        if (peer != null) {
            val wasOffline = !peer.isOnline
            // Update onion address if the peer is broadcasting a new one (e.g. after deploying a Hub)
            val newOnion = announcePay.onionAddress?.takeIf { it.isNotBlank() } ?: peer.onionAddress
            val onionChanged = newOnion != peer.onionAddress && newOnion.isNotBlank()
            if (onionChanged) {
                Logger.info(TAG, "ANNOUNCE_PEER: ${peer.handle} onion address updated: ${peer.onionAddress.take(12)}... → ${newOnion.take(12)}...")
            }
            peerDao.insertPeer(peer.copy(
                isOnline = true,
                lastSeenAt = System.currentTimeMillis(),
                onionAddress = newOnion
            ))
            Logger.debug(TAG, "ANNOUNCE_PEER received: ${peer.handle} is online")

            // Catch-up sync: when a trusted peer transitions from offline → online,
            // request their inventory so we receive any posts/comments/reactions we missed.
            if (wasOffline && peer.isTrusted) {
                Logger.info(TAG, "Trusted peer ${peer.handle} came online — requesting inventory sync")
                repo.requestInventorySync(peer)
            }
        }
        return true
    }

    suspend fun handleAnnounceDiscoverable(packet: NetworkPacket): Boolean {
        val announcePay = packet.getAnnounceDiscoverablePayload() ?: return false
        val payloadToVerify = "${announcePay.authorId}|${announcePay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, announcePay.signature, announcePay.authorId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected ANNOUNCE_DISCOVERABLE: Signature verification failed")
            return false
        }
        
        val pubBytes = Base64.decode(announcePay.authorId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        
        val peer = peerDao.getPeerByPublicKey(announcePay.authorId)
        if (peer == null) {
            // Add as temporary discoverable node
            val newPeer = Peer(
                publicKeyB64 = announcePay.authorId,
                handle = announcePay.handle,
                tripcode = tripcode,
                onionAddress = announcePay.onionAddress,
                encPublicKeyB64 = announcePay.encPublicKey,
                isTrusted = false,
                isTemporary = true,
                isDiscoverable = true,
                isCreator = announcePay.isCreator,
                fundMeLink = announcePay.fundMeLink,
                isOnline = true,
                lastSeenAt = System.currentTimeMillis()
            )
            peerDao.insertPeer(newPeer)
            Logger.debug(TAG, "ANNOUNCE_DISCOVERABLE received: Discovered node ${announcePay.handle}")
        } else {
            // Update existing
            peerDao.insertPeer(peer.copy(
                handle = announcePay.handle,
                onionAddress = announcePay.onionAddress,
                isTemporary = if (peer.isTrusted) false else true,
                isDiscoverable = true,
                isCreator = announcePay.isCreator,
                fundMeLink = announcePay.fundMeLink,
                isOnline = true,
                lastSeenAt = System.currentTimeMillis()
            ))
        }
        return true
    }

    suspend fun handleSubscribe(packet: NetworkPacket): Boolean {
        val subscribePay = packet.getSubscribePayload() ?: return false
        val payloadToVerify = "${subscribePay.creatorId}|${subscribePay.subscriberId}|${subscribePay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, subscribePay.signature, subscribePay.subscriberId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected SUBSCRIBE: Signature verification failed")
            return false
        }

        // Just acknowledge and optionally log it. In the future this can alter proxy limits.
        Logger.info(TAG, "Received SUBSCRIBE from ${subscribePay.subscriberId}")
        return true
    }

    suspend fun handleIdentityUpdate(packet: NetworkPacket): Boolean {
        val identityPay = packet.getIdentityUpdatePayload() ?: return false
        var payloadToVerify = "${identityPay.userId}|${identityPay.handle}|${identityPay.timestamp}"
        if (identityPay.authorAvatarB64 != null) {
            payloadToVerify += "|${identityPay.authorAvatarB64}"
        }
        val isValid = CryptoService.verify(payloadToVerify, identityPay.signature, identityPay.userId)
        if (!isValid) return false

        val peer = peerDao.getPeerByPublicKey(identityPay.userId)
        if (peer != null) {
            val handleToUse = if (identityPay.handle.isNotBlank()) identityPay.handle else peer.handle
            peerDao.insertPeer(peer.copy(
                handle = handleToUse,
                lastSeenAt = System.currentTimeMillis(),
                authorAvatarB64 = identityPay.authorAvatarB64 ?: peer.authorAvatarB64
            ))
            Logger.debug(TAG, "IDENTITY_UPDATE applied for ${identityPay.userId}")
        }
        return true
    }

    suspend fun handleUserExit(packet: NetworkPacket): Boolean {
        val exitPay = packet.getUserExitPayload() ?: return false

        if (exitPay.userId != packet.senderId) {
            Logger.warn(TAG, "Rejected USER_EXIT: userId does not match packet sender")
            return false
        }

        val payloadToVerify = "${exitPay.userId}|${exitPay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, exitPay.signature, exitPay.userId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected USER_EXIT: Signature verification failed")
            return false
        }

        val peer = peerDao.getPeerByPublicKey(exitPay.userId)
        if (peer != null) {
            peerDao.insertPeer(peer.copy(isOnline = false, lastSeenAt = System.currentTimeMillis()))
            Logger.debug(TAG, "USER_EXIT processed for ${exitPay.userId}")
        }
        return true
    }
}
