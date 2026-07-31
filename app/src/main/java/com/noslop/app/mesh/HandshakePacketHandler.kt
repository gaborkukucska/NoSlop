// FILE: app/src/main/java/com/noslop/app/mesh/HandshakePacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.*
import android.util.Base64
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import java.util.UUID

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
        val myPubKey = repo.getLocalIdentity()?.publicKeyB64
        if (myPubKey == connPay.fromUserId) {
            return false
        }
        
        val signature = packet.signature ?: return false
        var payloadToVerify = "${connPay.fromUserId}|${connPay.fromUsername}|${connPay.fromHomeNode}|${connPay.timestamp}"
        if (connPay.authorAvatarB64 != null) {
            payloadToVerify += "|${connPay.authorAvatarB64}"
        }
        if (!connPay.bio.isNullOrBlank()) {
            payloadToVerify += "|${connPay.bio}"
        }
        if (!CryptoService.verify(payloadToVerify, signature, connPay.fromUserId)) {
            return false
        }
        
        val existingPeer = peerDao.getPeerByPublicKey(connPay.fromUserId)
        val isTrusted = existingPeer?.isTrusted ?: false
        val isOldPacket = (System.currentTimeMillis() - connPay.timestamp) > 5 * 60 * 1000L
        val isVeryOldPacket = (System.currentTimeMillis() - connPay.timestamp) > 60 * 60 * 1000L // 1 hour

        if (isTrusted) {
            if (!isOldPacket) {
                Logger.info(TAG, "Received recent CONNECTION_REQUEST from already trusted peer ${existingPeer?.handle}. Resending USER_HANDSHAKE.")
                repo.acceptConnectionRequest(existingPeer!!)
            } else {
                Logger.debug(TAG, "Ignored old CONNECTION_REQUEST from already trusted peer.")
            }
            return true
        }

        // Ignore zombie requests from deleted peers if they are older than 1 hour
        if (existingPeer == null && isVeryOldPacket) {
            Logger.debug(TAG, "Ignored ancient CONNECTION_REQUEST from unknown peer.")
            return false
        }

        val isNewRequest = existingPeer == null

        val pubBytes = Base64.decode(connPay.fromUserId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        
        var handleToUse = if (connPay.fromUsername.isNotBlank()) connPay.fromUsername else existingPeer?.handle ?: "Unknown"
        if (handleToUse.endsWith(".$tripcode")) handleToUse = handleToUse.removeSuffix(".$tripcode")
        val encPubToUse = connPay.fromEncryptionPublicKey?.takeIf { it.isNotBlank() } ?: existingPeer?.encPublicKeyB64 ?: ""
        val avatarToUse = connPay.authorAvatarB64 ?: existingPeer?.authorAvatarB64
        
        val burnable = repo.getBurnableIdentity()
        if (burnable != null && packet.targetUserId == burnable.publicKeyB64) {
            db.appSettingDao().insertSetting(com.noslop.app.data.AppSetting("contact_identity_${connPay.fromUserId}", "burnable"))
        }

        val peer = Peer(
            publicKeyB64 = connPay.fromUserId,
            handle = handleToUse,
            tripcode = tripcode,
            onionAddress = connPay.fromHomeNode,
            encPublicKeyB64 = encPubToUse,
            isTrusted = false,
            lastSeenAt = System.currentTimeMillis(),
            authorAvatarB64 = avatarToUse,
            isTemporary = if (burnable != null && packet.targetUserId == burnable.publicKeyB64) true else (existingPeer?.isTemporary ?: false),
            isDiscoverable = existingPeer?.isDiscoverable ?: false,
            isCreator = existingPeer?.isCreator ?: false,
            fundMeLink = existingPeer?.fundMeLink,
            customFolder = existingPeer?.customFolder,
            bio = connPay.bio ?: existingPeer?.bio
        )
        peerDao.insertPeer(peer)
        
        val isLocalCreator = db.appSettingDao().getSetting("is_creator") == "true"
        if (isLocalCreator) {
            val now = System.currentTimeMillis()
            val limits = autoAcceptRateLimits.getOrPut("auto_accept") { mutableListOf() }
            var allowed = false
            synchronized(limits) {
                limits.removeAll { now - it > 3600_000 }
                if (limits.size < 10) {
                    limits.add(now)
                    allowed = true
                }
            }
            
            if (allowed) {
                peerDao.insertPeer(peer.copy(isTrusted = true, isTemporary = true))
                repo.acceptConnectionRequest(peer)
                Logger.info(TAG, "Auto-accepted connection request from ${peer.handle}")
            } else {
                repo.setIncomingRequest(peer)
            }
        } else {
            repo.setIncomingRequest(peer)

            // ONLY NOTIFY ON BRAND NEW REQUESTS
            if (isNewRequest) {
                val notifSettings = repo.notificationSettingsFlow.value
                if (notifSettings.connectionRequests) {
                    val title = "New Connection Request"
                    val msg = "${peer.handle} wants to connect with you."
                    val route = "notifications"
                    
                    notificationDao.insertNotification(
                        NotificationItem(
                            id = "conn_req_${peer.publicKeyB64}",
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
                        deepLinkRoute = route,
                        notificationId = peer.publicKeyB64.hashCode()
                    )
                }
            }
        }

        return true
    }

    suspend fun handleUserHandshake(packet: NetworkPacket): Boolean {
        val handPay = packet.getUserHandshakePayload() ?: return false
        val signature = packet.signature ?: return false
        val myPubKey = repo.getLocalIdentity()?.publicKeyB64
        if (myPubKey == handPay.fromUserId) return false

        var payloadToVerify = "${handPay.fromUserId}|${handPay.fromUsername}|${handPay.fromHomeNode}|${handPay.timestamp}"
        if (handPay.authorAvatarB64 != null) {
            payloadToVerify += "|${handPay.authorAvatarB64}"
        }
        if (!handPay.bio.isNullOrBlank()) {
            payloadToVerify += "|${handPay.bio}"
        }
        if (!CryptoService.verify(payloadToVerify, signature, handPay.fromUserId)) return false

        val peer = peerDao.getPeerByPublicKey(handPay.fromUserId)
        if (peer == null) {
            Logger.warn(TAG, "Received USER_HANDSHAKE from unknown/deleted peer ${handPay.fromUserId}. Ignoring to prevent forced re-connection.")
            return false
        }

        val isOldPacket = (System.currentTimeMillis() - handPay.timestamp) > 5 * 60 * 1000L
        if (peer.isTrusted && isOldPacket) {
            Logger.debug(TAG, "Ignored old USER_HANDSHAKE from already trusted peer ${peer.handle}.")
            return true
        }

        val pubBytes = Base64.decode(handPay.fromUserId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)

        val wasAlreadyTrusted = peer.isTrusted
        var handleToUse = if (handPay.fromUsername.isNotBlank()) handPay.fromUsername else peer.handle
        if (handleToUse.endsWith(".$tripcode")) handleToUse = handleToUse.removeSuffix(".$tripcode")
        
        peerDao.insertPeer(peer.copy(
            handle = handleToUse,
            isTrusted = true,
            lastSeenAt = System.currentTimeMillis(),
            onionAddress = handPay.fromHomeNode,
            encPublicKeyB64 = handPay.fromEncryptionPublicKey?.takeIf { it.isNotBlank() } ?: peer.encPublicKeyB64,
            authorAvatarB64 = handPay.authorAvatarB64 ?: peer.authorAvatarB64,
            bio = handPay.bio ?: peer.bio
        ))

        if (!wasAlreadyTrusted) {
            val notifSettings = repo.notificationSettingsFlow.value
            if (notifSettings.system) {
                val title = "Connection Accepted"
                val msg = "${handPay.fromUsername} accepted your connection request."
                val route = "chat/${handPay.fromUserId}"

                notificationDao.insertNotification(
                    NotificationItem(
                        id = "conn_acc_${handPay.fromUserId}",
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
                    deepLinkRoute = route,
                    notificationId = handPay.fromUserId.hashCode()
                )
            }
            repo.setHandshakeAccepted(peer)
        }
        return true
    }

    suspend fun handleConnectionRejected(packet: NetworkPacket): Boolean {
        val rejectPay = packet.getConnectionRejectedPayload() ?: return false
        val signature = packet.signature ?: return false
        val isOldPacket = (System.currentTimeMillis() - rejectPay.timestamp) > 5 * 60 * 1000L
        if (isOldPacket) return true

        val payloadToVerify = "${rejectPay.fromUserId}|${rejectPay.timestamp}"
        if (!CryptoService.verify(payloadToVerify, signature, rejectPay.fromUserId)) return false

        val peer = peerDao.getPeerByPublicKey(rejectPay.fromUserId)
        if (peer != null && !peer.isTrusted) {
            peerDao.deletePeer(peer)

            val notifSettings = repo.notificationSettingsFlow.value
            if (notifSettings.system) {
                val title = "Connection Declined"
                val msg = "${peer.handle} declined your connection request."
                val route = "notifications"

                notificationDao.insertNotification(
                    NotificationItem(
                        id = "conn_rej_${peer.publicKeyB64}",
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
                    deepLinkRoute = route,
                    notificationId = peer.publicKeyB64.hashCode()
                )
            }
        }
        return true
    }

    suspend fun handleAnnouncePeer(packet: NetworkPacket): Boolean {
        val announcePay = packet.getAnnouncePeerPayload() ?: return false
        val payloadToVerify = "${announcePay.authorId}|${announcePay.timestamp}"
        if (!CryptoService.verify(payloadToVerify, announcePay.signature, announcePay.authorId)) return false
        
        val isOldPacket = (System.currentTimeMillis() - announcePay.timestamp) > 5 * 60 * 1000L
        if (isOldPacket) return true

        val peer = peerDao.getPeerByPublicKey(announcePay.authorId)
        if (peer != null) {
            val wasOffline = !peer.isOnline
            val newOnion = announcePay.onionAddress?.takeIf { it.isNotBlank() } ?: peer.onionAddress
            peerDao.insertPeer(peer.copy(
                isOnline = true,
                lastSeenAt = System.currentTimeMillis(),
                onionAddress = newOnion
            ))
            if (wasOffline && peer.isTrusted) {
                repo.requestInventorySync(peer)
            }
        }
        return true
    }

    suspend fun handleAnnounceDiscoverable(packet: NetworkPacket): Boolean {
        val announcePay = packet.getAnnounceDiscoverablePayload() ?: return false
        val myPubKey = repo.getLocalIdentity()?.publicKeyB64
        val myBurnablePubKey = repo.getBurnableIdentity()?.publicKeyB64
        
        if (myPubKey == announcePay.authorId || myBurnablePubKey == announcePay.authorId) return false // Ignore our own announcements
        
        // Heuristic: If we already have a trusted peer with this exact handle, ignore this announcement.
        val existingPeers = peerDao.getAllPeersList()
        val hasMatchingTrustedPeer = existingPeers.any { it.isTrusted && it.handle == announcePay.handle }
        if (hasMatchingTrustedPeer) {
            com.noslop.app.debug.Logger.info("HANDSHAKE", "Ignoring ANNOUNCE_DISCOVERABLE from ${announcePay.handle} because we already have a trusted peer with this handle.")
            return false
        }
        
        val payloadToVerify = "${announcePay.authorId}:${announcePay.handle}:${announcePay.onionAddress}:${announcePay.encPublicKey}:${announcePay.isCreator}:${announcePay.fundMeLink ?: ""}:${announcePay.authorAvatarB64 ?: ""}:${announcePay.bio ?: ""}:${announcePay.timestamp}"
        if (!CryptoService.verify(payloadToVerify, announcePay.signature, announcePay.authorId)) {
            com.noslop.app.debug.Logger.warn("HANDSHAKE", "Signature mismatch for ANNOUNCE_DISCOVERABLE from ${announcePay.handle}")
            return false
        }
        
        val isOldPacket = (System.currentTimeMillis() - announcePay.timestamp) > 5 * 60 * 1000L
        if (isOldPacket) return true

        val pubBytes = Base64.decode(announcePay.authorId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        
        var handleToUse = announcePay.handle
        if (handleToUse.endsWith(".$tripcode")) handleToUse = handleToUse.removeSuffix(".$tripcode")
        
        val peer = peerDao.getPeerByPublicKey(announcePay.authorId)
        if (peer == null) {
            val newPeer = Peer(
                publicKeyB64 = announcePay.authorId,
                handle = handleToUse,
                tripcode = tripcode,
                onionAddress = announcePay.onionAddress,
                encPublicKeyB64 = announcePay.encPublicKey,
                isTrusted = false,
                isTemporary = true,
                isDiscoverable = true,
                isCreator = announcePay.isCreator,
                fundMeLink = announcePay.fundMeLink,
                authorAvatarB64 = announcePay.authorAvatarB64,
                bio = announcePay.bio,
                isOnline = true,
                lastSeenAt = System.currentTimeMillis()
            )
            peerDao.insertPeer(newPeer)
        } else {
            peerDao.insertPeer(peer.copy(
                handle = handleToUse,
                onionAddress = announcePay.onionAddress,
                isTemporary = peer.isTemporary,
                isDiscoverable = true,
                isCreator = announcePay.isCreator,
                fundMeLink = announcePay.fundMeLink,
                authorAvatarB64 = announcePay.authorAvatarB64 ?: peer.authorAvatarB64,
                bio = announcePay.bio ?: peer.bio,
                isOnline = true,
                lastSeenAt = System.currentTimeMillis()
            ))
        }
        return true
    }

    suspend fun handleSubscribe(packet: NetworkPacket): Boolean {
        val subscribePay = packet.getSubscribePayload() ?: return false
        val payloadToVerify = "${subscribePay.creatorId}|${subscribePay.subscriberId}|${subscribePay.timestamp}"
        return CryptoService.verify(payloadToVerify, subscribePay.signature, subscribePay.subscriberId)
    }

    suspend fun handleIdentityUpdate(packet: NetworkPacket): Boolean {
        val identityPay = packet.getIdentityUpdatePayload() ?: return false
        var payloadToVerify = "${identityPay.userId}|${identityPay.handle}|${identityPay.timestamp}"
        if (identityPay.authorAvatarB64 != null) {
            payloadToVerify += "|${identityPay.authorAvatarB64}"
        }
        if (!identityPay.bio.isNullOrBlank()) {
            payloadToVerify += "|${identityPay.bio}"
        }
        if (!CryptoService.verify(payloadToVerify, identityPay.signature, identityPay.userId)) return false

        val isOldPacket = (System.currentTimeMillis() - identityPay.timestamp) > 5 * 60 * 1000L
        if (isOldPacket) return true

        val peer = peerDao.getPeerByPublicKey(identityPay.userId)
        if (peer != null) {
            val pubBytes = Base64.decode(identityPay.userId, Base64.DEFAULT)
            val tripcode = CryptoService.deriveTripcode(pubBytes)
            
            var handleToUse = if (identityPay.handle.isNotBlank()) identityPay.handle else peer.handle
            if (handleToUse.endsWith(".$tripcode")) handleToUse = handleToUse.removeSuffix(".$tripcode")
            
            peerDao.insertPeer(peer.copy(
                handle = handleToUse,
                lastSeenAt = System.currentTimeMillis(),
                authorAvatarB64 = identityPay.authorAvatarB64 ?: peer.authorAvatarB64,
                bio = identityPay.bio ?: peer.bio
            ))
        }
        return true
    }

    suspend fun handleUserExit(packet: NetworkPacket): Boolean {
        val exitPay = packet.getUserExitPayload() ?: return false
        if (exitPay.userId != packet.senderId) return false

        val isOldPacket = (System.currentTimeMillis() - exitPay.timestamp) > 5 * 60 * 1000L
        if (isOldPacket) return true

        val payloadToVerify = "${exitPay.userId}|${exitPay.timestamp}"
        if (!CryptoService.verify(payloadToVerify, exitPay.signature, exitPay.userId)) return false

        val peer = peerDao.getPeerByPublicKey(exitPay.userId)
        if (peer != null) {
            peerDao.insertPeer(peer.copy(isOnline = false, lastSeenAt = System.currentTimeMillis()))
        }
        return true
    }
}
