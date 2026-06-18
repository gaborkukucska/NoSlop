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

    suspend fun handleConnectionRequest(packet: NetworkPacket): Boolean {
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
        val peer = Peer(
            publicKeyB64 = connPay.fromUserId,
            handle = connPay.fromUsername.split(".")[0],
            tripcode = tripcode,
            onionAddress = connPay.fromHomeNode,
            encPublicKeyB64 = connPay.fromEncryptionPublicKey ?: "",
            isTrusted = false,
            lastSeenAt = System.currentTimeMillis(),
            authorAvatarB64 = connPay.authorAvatarB64
        )
        peerDao.insertPeer(peer)
        repo.setIncomingRequest(peer)

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
            peerDao.insertPeer(peer.copy(
                isTrusted = true,
                lastSeenAt = System.currentTimeMillis(),
                authorAvatarB64 = handPay.authorAvatarB64 ?: peer.authorAvatarB64
            ))
        } else {
            val pubBytes = Base64.decode(handPay.fromUserId, Base64.DEFAULT)
            val tripcode = CryptoService.deriveTripcode(pubBytes)
            val newPeer = Peer(
                publicKeyB64 = handPay.fromUserId,
                handle = handPay.fromUsername.split(".")[0],
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
        val title = "Connection Accepted"
        val msg = "${handPay.fromUsername.split(".")[0]} accepted your connection request."
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
            peerDao.insertPeer(peer.copy(isOnline = true, lastSeenAt = System.currentTimeMillis()))
            Logger.debug(TAG, "ANNOUNCE_PEER received: ${peer.handle} is online")
        }
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
            peerDao.insertPeer(peer.copy(
                handle = identityPay.handle,
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
