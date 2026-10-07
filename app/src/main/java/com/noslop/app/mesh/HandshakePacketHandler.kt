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
    // --- NOSLOP_DELETION_BUDGET_V1 --- see refreshDeletionBudgetFor()
    private val postDao = db.postDao()
    private val notificationDao = db.notificationDao()
    private val autoAcceptRateLimits = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()

    private suspend fun syncMemberPeers(memberDetails: Map<String, GroupMemberInfo>?) {
        repo.syncMemberPeers(memberDetails)
    }

    suspend fun handleConnectionRequest(packet: NetworkPacket, sendResponse: suspend (NetworkPacket) -> Unit = {}): Boolean {
        val connPay = packet.getConnectionRequestPayload() ?: return false
        val myPubKey = repo.getLocalIdentity()?.publicKeyB64
        val myBurnablePubKey = repo.getBurnableIdentity()?.publicKeyB64
        if (myPubKey == connPay.fromUserId || myBurnablePubKey == connPay.fromUserId) {
            return false
        }

        // C02: Destination check - ensure the packet is addressed to our identity
        val targetUser = connPay.targetUserId ?: packet.targetUserId
        if (targetUser != null && !repo.isLocalUser(targetUser)) {
            Logger.warn(TAG, "Rejected CONNECTION_REQUEST: targetUserId $targetUser does not match local identity")
            return false
        }
        
        val signature = packet.signature ?: connPay.signature ?: return false

        // C02: Verify v2 signature with key binding & nonce if present, else fallback for legacy
        val nonce = connPay.requestNonce ?: ""
        val encPub = connPay.fromEncryptionPublicKey ?: ""
        val targetForVerify = targetUser ?: ""
        val v2Payload = CryptoService.canonicalHandshakePayloadV2(
            fromUserId = connPay.fromUserId,
            fromUsername = connPay.fromUsername,
            fromHomeNode = connPay.fromHomeNode,
            fromEncryptionPublicKey = encPub,
            targetUserId = targetForVerify,
            nonce = nonce,
            timestamp = connPay.timestamp,
            authorAvatarB64 = connPay.authorAvatarB64,
            bio = connPay.bio
        )

        val encodePayload = CryptoService.encodeForSigning(
            connPay.fromUserId, connPay.fromUsername, connPay.fromHomeNode, connPay.timestamp.toString(),
            connPay.authorAvatarB64, connPay.bio.takeIf { !it.isNullOrBlank() }
        )
        var pipePayload = "${connPay.fromUserId}|${connPay.fromUsername}|${connPay.fromHomeNode}|${connPay.timestamp}"
        if (connPay.authorAvatarB64 != null) {
            pipePayload += "|${connPay.authorAvatarB64}"
        }
        if (!connPay.bio.isNullOrBlank()) {
            pipePayload += "|${connPay.bio}"
        }

        val isV2Valid = CryptoService.verify(v2Payload, signature, connPay.fromUserId)
        val isLegacyValid = CryptoService.verify(encodePayload, signature, connPay.fromUserId) ||
            CryptoService.verify(pipePayload, signature, connPay.fromUserId)

        if (!isV2Valid && !isLegacyValid) {
            Logger.warn(TAG, "Rejected CONNECTION_REQUEST: signature verification failed for ${connPay.fromUserId}")
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

        // Drop requests from peers we recently deleted (unless fresh intentional reconnect)
        if (existingPeer == null && GossipService.isPeerRecentlyDeleted(connPay.fromUserId, connPay.timestamp)) {
            Logger.warn(TAG, "Ignored CONNECTION_REQUEST from recently deleted peer: ${connPay.fromUserId}")
            return false
        }

        val isNewRequest = existingPeer == null

        val pubBytes = Base64.decode(connPay.fromUserId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        
        var handleToUse = if (connPay.fromUsername.isNotBlank()) connPay.fromUsername else existingPeer?.handle ?: "Unknown"
        if (handleToUse.endsWith(".$tripcode")) handleToUse = handleToUse.removeSuffix(".$tripcode")
        
        // C02: Only update encryption key if authenticated via v2 signature
        val encPubToUse = if (isV2Valid && !connPay.fromEncryptionPublicKey.isNullOrBlank()) {
            connPay.fromEncryptionPublicKey
        } else {
            existingPeer?.encPublicKeyB64 ?: ""
        }
        val avatarToUse = connPay.authorAvatarB64 ?: existingPeer?.authorAvatarB64
        
        val burnable = repo.getBurnableIdentity()
        val isLocalCreator = db.appSettingDao().getSetting("is_creator_enabled") == "true" ||
                             db.appSettingDao().getSetting("is_creator") == "true"
        if (burnable != null && (packet.targetUserId == burnable.publicKeyB64 || isLocalCreator)) {
            db.appSettingDao().insertSetting(com.noslop.app.data.AppSetting("contact_identity_${connPay.fromUserId}", "burnable"))
        }

        // C01: Sets relationship to INCOMING_PENDING and records request nonce
        val peer = Peer(
            publicKeyB64 = connPay.fromUserId,
            handle = handleToUse,
            tripcode = tripcode,
            onionAddress = connPay.fromHomeNode,
            encPublicKeyB64 = encPubToUse,
            isTrusted = false,
            relationship = "INCOMING_PENDING",
            pendingNonce = connPay.requestNonce,
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
        
        if (isLocalCreator) {
            val now = System.currentTimeMillis()
            val senderLimits = autoAcceptRateLimits.getOrPut(connPay.fromUserId) { mutableListOf() }
            var allowed = false
            synchronized(senderLimits) {
                senderLimits.removeAll { now - it > 3600_000L }
                if (senderLimits.size < 5) {
                    senderLimits.add(now)
                    allowed = true
                }
            }
            
            if (allowed) {
                peerDao.insertPeer(peer.copy(isTrusted = true, isTemporary = true, relationship = "ACCEPTED"))
                repo.acceptConnectionRequest(peer)
                GossipService.flushFirewallBuffer(connPay.fromUserId)
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
                    val title = com.noslop.app.util.LanguageManager.translate("New Connection Request")
                    val msg = com.noslop.app.util.LanguageManager.translate("{author} wants to connect with you.")
                        .replace("{author}", peer.handle)
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
        val signature = packet.signature ?: handPay.signature ?: return false
        val myPubKey = repo.getLocalIdentity()?.publicKeyB64
        val myBurnablePubKey = repo.getBurnableIdentity()?.publicKeyB64
        if (myPubKey == handPay.fromUserId || myBurnablePubKey == handPay.fromUserId) return false

        // C02: Destination check - ensure the packet is addressed to our identity
        val targetUser = handPay.targetUserId ?: packet.targetUserId
        if (targetUser != null && !repo.isLocalUser(targetUser)) {
            Logger.warn(TAG, "Rejected USER_HANDSHAKE: targetUserId $targetUser does not match local identity")
            return false
        }

        val nonce = handPay.inReplyToNonce ?: ""
        val encPub = handPay.fromEncryptionPublicKey ?: ""
        val targetForVerify = targetUser ?: ""
        val v2Payload = CryptoService.canonicalHandshakePayloadV2(
            fromUserId = handPay.fromUserId,
            fromUsername = handPay.fromUsername,
            fromHomeNode = handPay.fromHomeNode,
            fromEncryptionPublicKey = encPub,
            targetUserId = targetForVerify,
            nonce = nonce,
            timestamp = handPay.timestamp,
            authorAvatarB64 = handPay.authorAvatarB64,
            bio = handPay.bio
        )

        val encodePayload = CryptoService.encodeForSigning(
            handPay.fromUserId, handPay.fromUsername, handPay.fromHomeNode, handPay.timestamp.toString(),
            handPay.authorAvatarB64, handPay.bio.takeIf { !it.isNullOrBlank() }
        )
        var pipePayload = "${handPay.fromUserId}|${handPay.fromUsername}|${handPay.fromHomeNode}|${handPay.timestamp}"
        if (handPay.authorAvatarB64 != null) {
            pipePayload += "|${handPay.authorAvatarB64}"
        }
        if (!handPay.bio.isNullOrBlank()) {
            pipePayload += "|${handPay.bio}"
        }

        val isV2Valid = CryptoService.verify(v2Payload, signature, handPay.fromUserId)
        val isLegacyValid = CryptoService.verify(encodePayload, signature, handPay.fromUserId) ||
            CryptoService.verify(pipePayload, signature, handPay.fromUserId)

        if (!isV2Valid && !isLegacyValid) {
            Logger.warn(TAG, "Rejected USER_HANDSHAKE: signature verification failed for ${handPay.fromUserId}")
            return false
        }

        var peer = peerDao.getPeerByPublicKey(handPay.fromUserId)
        if (peer == null) {
            peer = peerDao.getAllPeersList().find {
                it.onionAddress.isNotBlank() && it.onionAddress == handPay.fromHomeNode && it.publicKeyB64 == handPay.fromUserId
            }
        }

        // C01: Reject unsolicited handshakes if we don't have a record
        if (peer == null) {
            val isRecentlyDeleted = GossipService.isPeerRecentlyDeleted(handPay.fromUserId, handPay.timestamp)
            if (isRecentlyDeleted) {
                Logger.warn(TAG, "Received USER_HANDSHAKE from recently deleted peer ${handPay.fromUserId}. Ignoring to prevent forced re-connection.")
                return false
            }
            val pubBytes = Base64.decode(handPay.fromUserId, Base64.DEFAULT)
            val tripcode = CryptoService.deriveTripcode(pubBytes)
            val allGroups = db.groupChatDao().getAllGroupChatsList()
            val isInGroup = allGroups.any { g ->
                g.adminPublicKeyB64.trim() == handPay.fromUserId.trim() ||
                parseMembers(g.membersJson).any { it.trim() == handPay.fromUserId.trim() }
            }
            // If sender is a group member, store their keys for group messaging without making them a 1:1 contact
            if (isInGroup) {
                val fallbackHandle = "Member_${handPay.fromUserId.take(6)}"
                val stubPeer = Peer(
                    publicKeyB64 = handPay.fromUserId,
                    handle = if (handPay.fromUsername.isNotBlank() && handPay.fromUsername != "Member") handPay.fromUsername else fallbackHandle,
                    tripcode = tripcode,
                    onionAddress = handPay.fromHomeNode,
                    encPublicKeyB64 = if (isV2Valid) encPub else "",
                    isTrusted = false,
                    relationship = "NONE",
                    isTemporary = false,
                    lastSeenAt = System.currentTimeMillis()
                )
                peerDao.insertPeer(stubPeer)
                Logger.info(TAG, "Stored keys for group member: ${handPay.fromUserId.take(8)}... (${stubPeer.handle})")
                return true
            } else {
                Logger.warn(TAG, "Rejected unsolicited USER_HANDSHAKE from non-contact ${handPay.fromUserId.take(8)}...")
                return false
            }
        }

        val isOldPacket = (System.currentTimeMillis() - handPay.timestamp) > 5 * 60 * 1000L
        if (peer.isTrusted && isOldPacket) {
            Logger.debug(TAG, "Ignored old USER_HANDSHAKE from already trusted peer ${peer.handle}.")
            return true
        }

        val pubBytes = Base64.decode(handPay.fromUserId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)

        var handleToUse = if (handPay.fromUsername.isNotBlank()) handPay.fromUsername else peer.handle
        if (handleToUse.endsWith(".$tripcode")) handleToUse = handleToUse.removeSuffix(".$tripcode")

        // C01: Relationship state transition enforcement
        val isOutgoingPending = peer.relationship == "OUTGOING_PENDING"
        val isAlreadyAccepted = peer.relationship == "ACCEPTED" || peer.isTrusted

        if (isOutgoingPending) {
            // Verify inReplyToNonce matches the pendingNonce we generated when sending CONNECTION_REQUEST
            val expectedNonce = peer.pendingNonce
            if (expectedNonce.isNullOrBlank() || handPay.inReplyToNonce != expectedNonce) {
                Logger.warn(TAG, "Rejected USER_HANDSHAKE from ${peer.handle}: nonce mismatch (expected=${expectedNonce?.take(8)}, received=${handPay.inReplyToNonce?.take(8)})")
                return false
            }

            // Both nonce and signature matched! Promote to ACCEPTED
            val encKeyToSave = if (isV2Valid && encPub.isNotBlank()) encPub else peer.encPublicKeyB64
            val updated = peer.copy(
                handle = handleToUse,
                isTrusted = true,
                relationship = "ACCEPTED",
                pendingNonce = null,
                pendingEncKey = null,
                lastSeenAt = System.currentTimeMillis(),
                onionAddress = handPay.fromHomeNode,
                encPublicKeyB64 = encKeyToSave,
                authorAvatarB64 = handPay.authorAvatarB64 ?: peer.authorAvatarB64,
                bio = handPay.bio ?: peer.bio
            )
            peerDao.insertPeer(updated)

            GossipService.recordSendSuccess(handPay.fromHomeNode)
            GossipService.flushFirewallBuffer(handPay.fromUserId)
            repo.requestInventorySync(updated)
            repo.shareDiscoverableNodesWith(updated)

            val notifSettings = repo.notificationSettingsFlow.value
            if (notifSettings.system) {
                val title = com.noslop.app.util.LanguageManager.translate("Connection Accepted")
                val msg = com.noslop.app.util.LanguageManager.translate("{author} accepted your connection request.")
                    .replace("{author}", handPay.fromUsername)
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
            repo.setHandshakeAccepted(updated)
            Logger.info(TAG, "Promoted peer ${updated.handle} (${updated.publicKeyB64.take(8)}) to ACCEPTED / trusted")
            return true

        } else if (isAlreadyAccepted) {
            // C02: Key change detection for already accepted peer
            var pendingKeyAlert: String? = peer.pendingEncKey
            var encKey = peer.encPublicKeyB64
            if (isV2Valid && encPub.isNotBlank() && encPub != peer.encPublicKeyB64) {
                Logger.warn(TAG, "Safety key changed for ACCEPTED peer ${peer.handle}! Storing in pendingEncKey for user acknowledgement.")
                pendingKeyAlert = encPub
            }

            peerDao.insertPeer(peer.copy(
                handle = handleToUse,
                lastSeenAt = System.currentTimeMillis(),
                onionAddress = handPay.fromHomeNode,
                authorAvatarB64 = handPay.authorAvatarB64 ?: peer.authorAvatarB64,
                bio = handPay.bio ?: peer.bio,
                pendingEncKey = pendingKeyAlert
            ))
            Logger.info(TAG, "Refreshed profile details for already ACCEPTED peer ${peer.handle}")
            return true

        } else {
            // Any other state (INCOMING_PENDING, NONE, BLOCKED, group stub) cannot become trusted via incoming handshake
            Logger.warn(TAG, "Rejected USER_HANDSHAKE from ${peer.handle}: peer relationship is ${peer.relationship}, not OUTGOING_PENDING")
            return false
        }
    }

    suspend fun handleConnectionRejected(packet: NetworkPacket): Boolean {
        val rejectPay = packet.getConnectionRejectedPayload() ?: return false
        val signature = packet.signature ?: return false
        val isOldPacket = (System.currentTimeMillis() - rejectPay.timestamp) > 5 * 60 * 1000L
        if (isOldPacket) return true

        val encPayload = CryptoService.encodeForSigning(rejectPay.fromUserId, rejectPay.timestamp.toString())
        val pipePayload = "${rejectPay.fromUserId}|${rejectPay.timestamp}"
        val isValid = CryptoService.verify(encPayload, signature, rejectPay.fromUserId) ||
            CryptoService.verify(pipePayload, signature, rejectPay.fromUserId)
        if (!isValid) return false

        val peer = peerDao.getPeerByPublicKey(rejectPay.fromUserId)
        if (peer != null && !peer.isTrusted) {
            peerDao.deletePeer(peer)

            val notifSettings = repo.notificationSettingsFlow.value
            if (notifSettings.system) {
                val title = com.noslop.app.util.LanguageManager.translate("Connection Declined")
                val msg = com.noslop.app.util.LanguageManager.translate("{author} declined your connection request.")
                    .replace("{author}", peer.handle)
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
        val encodePayload = CryptoService.encodeForSigning(announcePay.authorId, announcePay.timestamp.toString())
        val pipePayload = "${announcePay.authorId}|${announcePay.timestamp}"
        if (!CryptoService.verify(encodePayload, announcePay.signature, announcePay.authorId) &&
            !CryptoService.verify(pipePayload, announcePay.signature, announcePay.authorId)) {
            Logger.warn(TAG, "ANNOUNCE_PEER signature verification failed for ${announcePay.authorId}")
            return false
        }
        
        val isOldPacket = (System.currentTimeMillis() - announcePay.timestamp) > 5 * 60 * 1000L
        if (isOldPacket) return true

        val peer = peerDao.getPeerByPublicKey(announcePay.authorId)
        if (peer != null) {
            val wasOffline = !peer.isOnline
            val newOnion = announcePay.onionAddress?.takeIf { it.isNotBlank() } ?: peer.onionAddress
            GossipService.recordSendSuccess(newOnion)
            peerDao.insertPeer(peer.copy(
                isOnline = true,
                lastSeenAt = System.currentTimeMillis(),
                onionAddress = newOnion
            ))
            if (peer.isTrusted) {
                // Instantly flush any pending outbox DMs and request missed messages
                repo.flushOutboxForPeer(peer.publicKeyB64, newOnion)
                repo.requestDmSync(peer)
                if (wasOffline) {
                    repo.requestInventorySync(peer)
                    resendGroupInvitesForPeer(peer.publicKeyB64, newOnion)
                }
            }
        }
        return true
    }

    suspend fun handleAnnounceDiscoverable(packet: NetworkPacket): Boolean {
        val announcePay = packet.getAnnounceDiscoverablePayload() ?: return false
        val myPubKey = repo.getLocalIdentity()?.publicKeyB64
        val myBurnablePubKey = repo.getBurnableIdentity()?.publicKeyB64
        
        if (myPubKey == announcePay.authorId || myBurnablePubKey == announcePay.authorId) return false // Ignore our own announcements
        
        val existingPeers = peerDao.getAllPeersList()
        val hasMatchingTrustedPeer = announcePay.handle.isNotBlank() && announcePay.handle != "Anonymous" &&
            existingPeers.any { it.publicKeyB64 != announcePay.authorId && it.isTrusted && it.handle.equals(announcePay.handle, ignoreCase = true) }
        if (hasMatchingTrustedPeer) {
            Logger.info("HANDSHAKE", "Ignoring ANNOUNCE_DISCOVERABLE from ${announcePay.handle} because we already have a trusted peer with this handle.")
            return false
        }
        
        val s1 = "${announcePay.authorId}:${announcePay.handle}:${announcePay.onionAddress}:${announcePay.encPublicKey}:${announcePay.isCreator}:${announcePay.fundMeLink ?: ""}:${announcePay.authorAvatarB64 ?: ""}:${announcePay.bio ?: ""}:${announcePay.timestamp}"
        val s2 = "${announcePay.authorId}:${announcePay.handle}:${announcePay.onionAddress}:${announcePay.encPublicKey}:${announcePay.isCreator}:${announcePay.fundMeLink ?: ""}:${announcePay.authorAvatarB64 ?: ""}:${announcePay.timestamp}"
        val s3 = "${announcePay.authorId}:${announcePay.handle}:${announcePay.onionAddress}:${announcePay.encPublicKey}:${announcePay.isCreator}:${announcePay.timestamp}"
        val s4 = "${announcePay.authorId}|${announcePay.handle}|${announcePay.onionAddress}|${announcePay.encPublicKey}|${announcePay.isCreator}|${announcePay.timestamp}"
        val sig = announcePay.signature
        val authorId = announcePay.authorId
        val isValid = CryptoService.verify(s1, sig, authorId) ||
            CryptoService.verify(s2, sig, authorId) ||
            CryptoService.verify(s3, sig, authorId) ||
            CryptoService.verify(s4, sig, authorId)
        if (!isValid) {
            Logger.warn("HANDSHAKE", "Signature mismatch for ANNOUNCE_DISCOVERABLE from ${announcePay.handle}")
            return false
        }
        
        val isOldPacket = Math.abs(System.currentTimeMillis() - announcePay.timestamp) > 30 * 60 * 1000L
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
                relationship = "NONE",
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
            db.appSettingDao().insertSetting(AppSetting("disc_packet_${announcePay.authorId}", packet.toJson()))
        } else {
            db.appSettingDao().insertSetting(AppSetting("disc_packet_${announcePay.authorId}", packet.toJson()))
            val cameBackOnline = !peer.isOnline
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
            if (cameBackOnline && peer.isTrusted) {
                refreshDeletionBudgetFor(peer.handle)
                resendGroupInvitesForPeer(announcePay.authorId, announcePay.onionAddress)
            }
            GossipService.recordSendSuccess(announcePay.onionAddress)
        }
        return true
    }

    private val lastInviteResendTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun resendGroupInvitesForPeer(peerPubKey: String, peerOnion: String) {
        val now = System.currentTimeMillis()
        val lastTime = lastInviteResendTimes[peerPubKey] ?: 0L
        if (now - lastTime < 60 * 1000L) {
            return
        }
        lastInviteResendTimes[peerPubKey] = now
        try {
            val myKeys = repo.getLocalIdentity() ?: return
            val burnableKeys = repo.getBurnableIdentity()
            val groups = db.groupChatDao().getAllGroupChatsList()
            for (group in groups) {
                val members = parseMembers(group.membersJson)
                if (members.contains(peerPubKey)) {
                    val isAdmin = group.adminPublicKeyB64 == myKeys.publicKeyB64 || (burnableKeys != null && group.adminPublicKeyB64 == burnableKeys.publicKeyB64)
                    if (!isAdmin) continue
                    val adminKeys = if (burnableKeys != null && group.adminPublicKeyB64 == burnableKeys.publicKeyB64) burnableKeys else myKeys
                    val timestamp = group.createdAt
                    val sortedMembers = members.sorted().joinToString(",")
                    val allMembers = (members + group.adminPublicKeyB64).distinct()
                    val myHandle = repo.getLocalHandle()
                    val memberDetailsMap = allMembers.mapNotNull { pub ->
                        val p = peerDao.getPeerByPublicKey(pub)
                        if (p != null) {
                            pub to GroupMemberInfo(p.handle, p.encPublicKeyB64, p.onionAddress)
                        } else if (pub == adminKeys.publicKeyB64 || pub == myKeys.publicKeyB64) {
                            pub to GroupMemberInfo(myHandle, adminKeys.encPublicKeyB64, adminKeys.onionAddress)
                        } else null
                    }.toMap()
                    val sortedDetails = canonicalMemberDetailsString(memberDetailsMap)
                    val sortedHandles = canonicalMemberHandlesString(group.getMemberHandles())
                    val payloadToSign = canonicalGroupInvitePayload(
                        group.groupId, group.title, group.adminPublicKeyB64, adminKeys.publicKeyB64, timestamp,
                        sortedMembers, group.allowMemberInvites, group.allowMemberSelfRemove,
                        group.description, group.avatarB64, adminKeys.onionAddress, adminKeys.encPublicKeyB64,
                        sortedDetails, sortedHandles
                    )
                    val signature = CryptoService.sign(payloadToSign, adminKeys.privateKeyB64)

                    val invitePayload = GroupInvitePayload(
                        groupId = group.groupId,
                        title = group.title,
                        adminPublicKeyB64 = group.adminPublicKeyB64,
                        members = members,
                        avatarB64 = group.avatarB64,
                        description = group.description,
                        memberHandles = group.getMemberHandles(),
                        memberDetails = memberDetailsMap,
                        allowMemberInvites = group.allowMemberInvites,
                        allowMemberSelfRemove = group.allowMemberSelfRemove,
                        timestamp = timestamp,
                        signature = signature,
                        adminOnion = adminKeys.onionAddress,
                        adminEncPublicKey = adminKeys.encPublicKeyB64
                    )
                    val packet = NetworkPacket(
                        id = java.util.UUID.randomUUID().toString(),
                        senderId = if (group.allowMemberInvites) adminKeys.publicKeyB64 else myKeys.publicKeyB64,
                        targetUserId = peerPubKey,
                        type = "GROUP_INVITE",
                        payload = com.noslop.app.util.Json.gson.toJsonTree(invitePayload)
                    )
                    Logger.info(TAG, "Re-sending GROUP_INVITE for '${group.title}' to connected peer $peerPubKey")
                    repo.dispatchPacket(peerOnion, packet)
                }
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Could not resend group invites: ${e.message}")
        }
    }

    private suspend fun refreshDeletionBudgetFor(peerHandle: String) {
        try {
            val myPubKey = repo.getLocalIdentity()?.publicKeyB64 ?: return
            postDao.resetDeletionBroadcasts(myPubKey)
            Logger.info(TAG, "Peer $peerHandle reconnected — refreshed deletion announce budget")
        } catch (e: Exception) {
            Logger.warn(TAG, "Could not refresh deletion budget: ${e.message}")
        }
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

        val isOldPacket = Math.abs(System.currentTimeMillis() - exitPay.timestamp) > 30 * 60 * 1000L
        if (isOldPacket) return true

        val encPayload = CryptoService.encodeForSigning(exitPay.userId, exitPay.timestamp.toString())
        val pipePayload = "${exitPay.userId}|${exitPay.timestamp}"
        val isValid = CryptoService.verify(encPayload, exitPay.signature, exitPay.userId) ||
            CryptoService.verify(pipePayload, exitPay.signature, exitPay.userId)
        if (!isValid) return false

        if (packet.targetUserId != null && repo.isLocalUser(packet.targetUserId)) {
            Logger.info(TAG, "Received targeted USER_EXIT from ${exitPay.userId.take(12)} — removing peer and purging content")
            repo.deletePeer(exitPay.userId, notifyRemote = false)
            return true
        }

        val peer = peerDao.getPeerByPublicKey(exitPay.userId)
        if (peer != null) {
            peerDao.insertPeer(peer.copy(isOnline = false, lastSeenAt = System.currentTimeMillis()))
        }
        if (repo.isNodeBanned(exitPay.userId)) {
            Logger.info(TAG, "Banned node ${exitPay.userId.take(8)}... exited/shut down — removing from ban list")
            repo.unbanNode(exitPay.userId)
        }
        return true
    }

    suspend fun handlePeerRemoved(packet: NetworkPacket): Boolean {
        val removePay = packet.getPeerRemovedPayload() ?: return false
        val senderId = packet.senderId
        if (removePay.userId != senderId) return false

        val encPayload = CryptoService.encodeForSigning(removePay.userId, removePay.timestamp.toString())
        val pipePayload = "${removePay.userId}|${removePay.timestamp}"
        val sig = removePay.signature
        if (!CryptoService.verify(encPayload, sig, removePay.userId) &&
            !CryptoService.verify(pipePayload, sig, removePay.userId)) {
            Logger.warn(TAG, "PEER_REMOVED signature verification failed for ${removePay.userId}")
            return false
        }

        Logger.info(TAG, "Peer ${removePay.userId.take(12)} removed us — deleting peer and all their content locally")
        if (repo.isNodeBanned(removePay.userId)) {
            repo.unbanNode(removePay.userId)
        }
        repo.deletePeer(removePay.userId, notifyRemote = false)
        return true
    }

    suspend fun handleFollow(packet: NetworkPacket): Boolean {
        val followPay = packet.getFollowPayload() ?: return false
        val signature = followPay.signature
        val encPayload = CryptoService.encodeForSigning(
            followPay.followedPublicKeyB64, followPay.followerPublicKeyB64, followPay.timestamp.toString()
        )
        val pipePayload = "${followPay.followedPublicKeyB64}|${followPay.followerPublicKeyB64}|${followPay.timestamp}"
        val isValid = CryptoService.verify(encPayload, signature, followPay.followerPublicKeyB64) ||
            CryptoService.verify(pipePayload, signature, followPay.followerPublicKeyB64)
        if (!isValid) {
            Logger.warn(TAG, "Invalid signature on FOLLOW packet from ${followPay.followerPublicKeyB64}")
            return false
        }
        val isFollow = followPay.action == "follow"
        peerDao.updateFollowState(followPay.followerPublicKeyB64, isFollow)
        Logger.info(TAG, "Updated follow state for ${followPay.followerPublicKeyB64} to isFollowing=$isFollow")
        return true
    }

    private fun parseMembers(membersJson: String): MutableList<String> = try {
        com.noslop.app.util.Json.gson.fromJson(membersJson, Array<String>::class.java).toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun resolveUpdateSigner(update: GroupUpdatePayload, existing: GroupChat, members: List<String>): String? {
        val candidates = (listOf(existing.adminPublicKeyB64) + members).distinct()
        val sortedAdded = update.addedMembers?.sorted()?.joinToString(",") ?: ""
        val sortedRemoved = update.removedMembers?.sorted()?.joinToString(",") ?: ""
        val sortedBanned = encodeOptBanned(update.bannedMembers)
        val sortedDetails = canonicalMemberDetailsString(update.memberDetails)
        val sortedHandles = canonicalMemberHandlesString(update.memberHandles)
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            // W02: Strictly require canonical 13-field signature binding directory maps, permissions, and bans
            val canonicalPayload = canonicalGroupUpdatePayload(
                update.groupId, update.title, candidate, update.timestamp,
                sortedAdded, sortedRemoved, sortedBanned, update.description, update.avatarB64,
                update.allowMemberInvites, update.allowMemberSelfRemove,
                sortedDetails, sortedHandles
            )
            if (CryptoService.verify(canonicalPayload, update.signature, candidate)) return candidate
        }
        return null
    }

    suspend fun handleGroupInvite(packet: NetworkPacket): Boolean {
        val invite = packet.getGroupInvitePayload() ?: return false

        val existing = db.groupChatDao().getGroupChatById(invite.groupId)
        val candidates = if (existing != null) {
            val existingMembers = parseMembers(existing.membersJson)
            (listOf(existing.adminPublicKeyB64) + existingMembers).distinct()
        } else {
            (listOf(invite.adminPublicKeyB64) + invite.members).distinct()
        }
        val sortedMembers = invite.members.sorted().joinToString(",")
        val sortedDetails = canonicalMemberDetailsString(invite.memberDetails)
        val sortedHandles = canonicalMemberHandlesString(invite.memberHandles)
        var verifiedSigner: String? = null
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            val canonicalPayload = canonicalGroupInvitePayload(
                invite.groupId, invite.title, invite.adminPublicKeyB64, candidate, invite.timestamp,
                sortedMembers, invite.allowMemberInvites, invite.allowMemberSelfRemove,
                invite.description, invite.avatarB64, invite.adminOnion, invite.adminEncPublicKey,
                sortedDetails, sortedHandles
            )
            if (CryptoService.verify(canonicalPayload, invite.signature, candidate)) {
                verifiedSigner = candidate
                break
            }

            val unsignedFieldsEmpty = invite.description.isNullOrEmpty() && invite.avatarB64.isNullOrEmpty() &&
                invite.adminOnion.isNullOrEmpty() && invite.adminEncPublicKey.isNullOrEmpty() &&
                invite.memberDetails.isNullOrEmpty() && invite.memberHandles.isNullOrEmpty()
            if (candidate == invite.adminPublicKeyB64 && unsignedFieldsEmpty) {
                val enc7Field = CryptoService.encodeForSigning(
                    invite.groupId, invite.title, candidate, invite.timestamp.toString(),
                    sortedMembers, invite.allowMemberInvites.toString(), invite.allowMemberSelfRemove.toString()
                )
                if (CryptoService.verify(enc7Field, invite.signature, candidate)) {
                    verifiedSigner = candidate
                    break
                }
            }
        }
        if (verifiedSigner == null) {
            Logger.warn(TAG, "Rejected GROUP_INVITE ${invite.groupId}: signature matches no admin or group member")
            return false
        }

        val myKeys = repo.getLocalIdentity()
        val burnable = repo.getBurnableIdentity()
        val meInGroup = invite.members.any {
            it == myKeys?.publicKeyB64 || (burnable != null && it == burnable.publicKeyB64)
        }
        if (!meInGroup) {
            Logger.warn(TAG, "Rejected GROUP_INVITE ${invite.groupId}: our identity is not in the member list")
            return false
        }

        if (existing != null) {
            if (existing.adminPublicKeyB64 != invite.adminPublicKeyB64) {
                Logger.warn(TAG, "Rejected GROUP_INVITE ${invite.groupId}: admin key does not match the stored group")
                return false
            }
            return true
        }

        val isMyGroup = invite.adminPublicKeyB64 == myKeys?.publicKeyB64 || invite.adminPublicKeyB64 == burnable?.publicKeyB64
        if (isMyGroup) {
            syncMemberPeers(invite.memberDetails)
            val membersJson = com.noslop.app.util.Json.gson.toJson(invite.members)
            val memberHandlesJson = com.noslop.app.util.Json.gson.toJson(invite.memberHandles ?: emptyMap<String, String>())
            val group = GroupChat(
                groupId = invite.groupId,
                title = invite.title,
                adminPublicKeyB64 = invite.adminPublicKeyB64,
                membersJson = membersJson,
                createdAt = invite.timestamp,
                description = invite.description,
                avatarB64 = invite.avatarB64,
                memberHandlesJson = memberHandlesJson
            )
            db.groupChatDao().insertGroupChat(group)
            Logger.info(TAG, "Joined own group chat '${invite.title}' (${invite.groupId})")
            return true
        }

        val jsonPayload = com.noslop.app.util.Json.gson.toJson(invite)
        db.appSettingDao().insertSetting(AppSetting("pending_group_invite_${invite.groupId}", jsonPayload))

        val inviterPeer = peerDao.getPeerByPublicKey(verifiedSigner)
        val inviterName = inviterPeer?.handle ?: invite.memberHandles?.get(verifiedSigner) ?: (if (verifiedSigner == invite.adminPublicKeyB64) "Group Admin" else "A contact")
        val title = com.noslop.app.util.LanguageManager.translate("Group Chat Invite")
        val body = com.noslop.app.util.LanguageManager.translate("{author} invited you to join '{group}'")
            .replace("{author}", inviterName)
            .replace("{group}", invite.title)
        val route = "group_invite/${invite.groupId}"

        notificationDao.insertNotification(
            NotificationItem(
                id = "group_invite_${invite.groupId}",
                type = "GROUP_INVITE",
                title = title,
                body = body,
                targetRoute = route,
                iconType = "group",
                senderPub = invite.adminPublicKeyB64,
                timestamp = invite.timestamp
            )
        )

        com.noslop.app.util.NotificationHelper.showNotification(
            context = repo.context,
            title = title,
            message = body,
            deepLinkRoute = route
        )

        Logger.info(TAG, "Received group invite for '${invite.title}' (${invite.groupId}) - created pending notification")
        return true
    }

    suspend fun handleGroupUpdate(packet: NetworkPacket): Boolean {
        val update = packet.getGroupUpdatePayload() ?: return false
        val existing = db.groupChatDao().getGroupChatById(update.groupId) ?: return false

        val currentMembers = parseMembers(existing.membersJson)

        if (update.timestamp <= existing.revision) {
            Logger.debug(TAG, "Ignoring stale GROUP_UPDATE for ${update.groupId} (update ts ${update.timestamp} <= existing revision ${existing.revision})")
            return true
        }

        val signer = resolveUpdateSigner(update, existing, currentMembers)
        if (signer == null) {
            Logger.warn(TAG, "Rejected GROUP_UPDATE ${update.groupId}: signature matches no current member")
            return false
        }
        val isAdmin = signer == existing.adminPublicKeyB64

        val added = update.addedMembers.orEmpty()
        val removed = update.removedMembers.orEmpty()

        if (!isAdmin) {
            val metadataChanged =
                (update.title != null && update.title != existing.title) ||
                (update.description != null && update.description != existing.description) ||
                (update.avatarB64 != null && update.avatarB64 != existing.avatarB64)
            if (metadataChanged) {
                Logger.warn(TAG, "Rejected GROUP_UPDATE ${update.groupId}: only the admin may change group metadata")
                return false
            }
            if (added.isNotEmpty() && !existing.allowMemberInvites) {
                Logger.warn(TAG, "Rejected GROUP_UPDATE ${update.groupId}: member invites are disabled for this group")
                return false
            }
            if (removed.isNotEmpty()) {
                val selfRemovalOnly = removed.all { it == signer }
                if (!selfRemovalOnly || !existing.allowMemberSelfRemove) {
                    Logger.warn(TAG, "Rejected GROUP_UPDATE ${update.groupId}: a member may only remove themselves")
                    return false
                }
            }
        }
        if (removed.contains(existing.adminPublicKeyB64)) {
            Logger.warn(TAG, "Rejected GROUP_UPDATE ${update.groupId}: the admin cannot be removed")
            return false
        }

        syncMemberPeers(update.memberDetails)
        val bannedSet = if (isAdmin && update.bannedMembers != null) update.bannedMembers.toSet() else existing.getBannedMembers().toSet()
        val filteredAdded = added.filter { it !in bannedSet }
        currentMembers.addAll(filteredAdded)
        currentMembers.removeAll(removed.toSet())
        currentMembers.removeAll(bannedSet)
        val updatedTitle = update.title ?: existing.title

        val handlesMap = existing.getMemberHandles().toMutableMap()
        update.memberHandles?.let { handlesMap.putAll(it) }

        val allowInvites = if (isAdmin && update.allowMemberInvites != null) update.allowMemberInvites else existing.allowMemberInvites
        val allowSelfRemove = if (isAdmin && update.allowMemberSelfRemove != null) update.allowMemberSelfRemove else existing.allowMemberSelfRemove

        val updatedGroup = existing.copy(
            title = updatedTitle,
            description = update.description ?: existing.description,
            avatarB64 = update.avatarB64 ?: existing.avatarB64,
            allowMemberInvites = allowInvites,
            allowMemberSelfRemove = allowSelfRemove,
            membersJson = com.noslop.app.util.Json.gson.toJson(currentMembers.distinct()),
            memberHandlesJson = com.noslop.app.util.Json.gson.toJson(handlesMap),
            bannedMembersJson = if (isAdmin && update.bannedMembers != null) com.noslop.app.util.Json.gson.toJson(bannedSet.toList()) else existing.bannedMembersJson,
            revision = if (isAdmin) maxOf(existing.revision, update.timestamp) else existing.revision
        )

        val myPub = repo.getLocalIdentity()?.publicKeyB64
        val myBurnable = repo.getBurnableIdentity()?.publicKeyB64
        val stillAMember = currentMembers.any { it == myPub || (myBurnable != null && it == myBurnable) }
        if (!stillAMember) {
            val previousMembers = parseMembers(existing.membersJson)
            repo.cleanupOrphanedGroupPeers(previousMembers + existing.adminPublicKeyB64, excludedGroupId = update.groupId)
            db.groupChatDao().deleteGroupChat(update.groupId)
            Logger.info(TAG, "Left group chat ${update.groupId}: we were removed by ${if (isAdmin) "the admin" else "a member"} and cleaned up group peers")
            return true
        }

        if (removed.isNotEmpty()) {
            repo.cleanupOrphanedGroupPeers(removed, excludedGroupId = update.groupId)
        }

        db.groupChatDao().insertGroupChat(updatedGroup)
        Logger.info(TAG, "Updated group chat '${updatedGroup.title}' (${update.groupId}) by ${if (isAdmin) "admin" else "member"}")

        val myLocalPub = repo.getLocalIdentity()?.publicKeyB64
        val myBurnablePub = repo.getBurnableIdentity()?.publicKeyB64
        val isLocalAdmin = existing.adminPublicKeyB64 == myLocalPub || (myBurnablePub != null && existing.adminPublicKeyB64 == myBurnablePub)
        if (isLocalAdmin && !isAdmin && removed.isNotEmpty()) {
            for (memberPub in currentMembers) {
                if (memberPub == myLocalPub || memberPub == myBurnablePub || memberPub == signer) continue
                val p = peerDao.getPeerByPublicKey(memberPub)
                if (p != null && p.onionAddress.isNotBlank()) {
                    val relayPacket = packet.copy(id = java.util.UUID.randomUUID().toString(), targetUserId = memberPub)
                    repo.dispatchPacket(p.onionAddress, relayPacket)
                }
            }
        }
        return true
    }

    suspend fun handleGroupDelete(packet: NetworkPacket): Boolean {
        val del = packet.getGroupDeletePayload() ?: return false
        val existing = db.groupChatDao().getGroupChatById(del.groupId) ?: return false

        if (existing.adminPublicKeyB64 != del.adminPublicKeyB64) {
            Logger.warn(TAG, "Rejected GROUP_DELETE ${del.groupId}: not from the stored admin")
            return false
        }
        val encodePayload = CryptoService.encodeForSigning(del.groupId, "delete", del.adminPublicKeyB64, del.timestamp.toString())
        val pipePayload = "${del.groupId}|delete|${del.adminPublicKeyB64}|${del.timestamp}"
        if (!CryptoService.verify(encodePayload, del.signature, del.adminPublicKeyB64) &&
            !CryptoService.verify(pipePayload, del.signature, del.adminPublicKeyB64)) {
            Logger.warn(TAG, "Rejected GROUP_DELETE ${del.groupId}: bad admin signature")
            return false
        }

        db.groupChatDao().deleteGroupChat(del.groupId)
        val previousMembers = try {
            com.noslop.app.util.Json.gson.fromJson(existing.membersJson, Array<String>::class.java).toList()
        } catch (e: Exception) { emptyList() }
        repo.cleanupOrphanedGroupPeers(previousMembers + existing.adminPublicKeyB64, excludedGroupId = del.groupId)
        Logger.info(TAG, "Deleted group chat (${del.groupId}) via admin delete packet and cleaned up group peers")
        return true
    }

    suspend fun handleGroupQuery(packet: NetworkPacket): Boolean {
        val query = packet.getGroupQueryPayload() ?: return false
        val group = db.groupChatDao().getGroupChatById(query.groupId) ?: return false

        val members = parseMembers(group.membersJson)
        val pDao = peerDao
        val senderPeer = pDao.getPeerByPublicKey(packet.senderId)
        val requesterPeer = pDao.getPeerByPublicKey(query.requesterId)

        val reqId = query.requesterId.trim()
        val sendId = packet.senderId.trim()
        val adminId = group.adminPublicKeyB64.trim()

        val hasPendingInvite = db.appSettingDao().getSetting("pending_group_invite_${query.groupId}") != null
        val requesterInGroup = members.any { it.trim() == reqId || it.trim() == sendId } ||
            adminId == reqId || adminId == sendId ||
            group.allowMemberInvites || hasPendingInvite ||
            (senderPeer != null && (members.any { it.trim() == senderPeer.publicKeyB64.trim() } || adminId == senderPeer.publicKeyB64.trim())) ||
            (requesterPeer != null && (members.any { it.trim() == requesterPeer.publicKeyB64.trim() } || adminId == requesterPeer.publicKeyB64.trim())) ||
            pDao.getAllPeersList().any { p -> 
                val pKey = p.publicKeyB64.trim()
                (members.any { it.trim() == pKey } || adminId == pKey) && 
                (pKey == sendId || pKey == reqId || (senderPeer != null && p.onionAddress.isNotBlank() && p.onionAddress == senderPeer.onionAddress))
            }
        if (!requesterInGroup) {
            Logger.warn(TAG, "Rejected GROUP_QUERY ${query.groupId}: requester ${query.requesterId} is not a group member or admin")
            return false
        }

        val myKeys = repo.getLocalIdentity() ?: return false
        val burnableKeys = repo.getBurnableIdentity()
        val signingKey = if (burnableKeys != null && (group.membersJson.contains(burnableKeys.publicKeyB64) || group.adminPublicKeyB64 == burnableKeys.publicKeyB64)) burnableKeys else myKeys

        val groupJson = com.noslop.app.util.Json.gson.toJson(group)
        val stateTimestamp = maxOf(group.createdAt, group.revision)

        val allMembers = parseMembers(group.membersJson) + group.adminPublicKeyB64
        val memberDetails = allMembers.distinct().mapNotNull { pub ->
            val p = peerDao.getPeerByPublicKey(pub)
            if (p != null) pub to GroupMemberInfo(p.handle, p.encPublicKeyB64, p.onionAddress)
            else if (pub == myKeys.publicKeyB64 || (burnableKeys != null && pub == burnableKeys.publicKeyB64)) {
                pub to GroupMemberInfo(repo.getLocalHandle(), signingKey.encPublicKeyB64, signingKey.onionAddress)
            } else null
        }.toMap()

        val sortedDetails = canonicalMemberDetailsString(memberDetails)
        val payloadToSign = canonicalGroupSyncPayload(group.groupId, groupJson, stateTimestamp, sortedDetails)
        val signature = CryptoService.sign(payloadToSign, signingKey.privateKeyB64)

        val syncPayload = GroupSyncPayload(
            groupChatJson = groupJson,
            memberDetails = memberDetails,
            timestamp = stateTimestamp,
            signature = signature
        )

        val syncPacket = NetworkPacket(
            id = java.util.UUID.randomUUID().toString(),
            hops = 1,
            senderId = signingKey.publicKeyB64,
            targetUserId = packet.senderId,
            type = "GROUP_SYNC",
            payload = com.noslop.app.util.Json.gson.toJsonTree(syncPayload)
        )
        val targetOnion = senderPeer?.onionAddress?.takeIf { it.isNotBlank() }
            ?: requesterPeer?.onionAddress?.takeIf { it.isNotBlank() }
        if (!targetOnion.isNullOrBlank()) {
            repo.dispatchPacket(targetOnion, syncPacket)
        } else {
            GossipService.broadcast(syncPacket)
        }
        Logger.info(TAG, "Responded to GROUP_QUERY for group ${group.title} (${query.groupId}) to ${packet.senderId}")
        return true
    }

    suspend fun handleGroupSync(packet: NetworkPacket): Boolean {
        val sync = packet.getGroupSyncPayload() ?: return false
        val group = try {
            com.noslop.app.util.Json.gson.fromJson(sync.groupChatJson, GroupChat::class.java)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to parse GroupChat from GROUP_SYNC: ${e.message}")
            return false
        } ?: return false

        val sortedDetails = canonicalMemberDetailsString(sync.memberDetails)
        val encCanonical = canonicalGroupSyncPayload(group.groupId, sync.groupChatJson, sync.timestamp, sortedDetails)
        val encLegacy = CryptoService.encodeForSigning(group.groupId, sync.groupChatJson, sync.timestamp.toString())
        val pipePayload = "${group.groupId}|${sync.groupChatJson}|${sync.timestamp}"
        val existing = db.groupChatDao().getGroupChatById(group.groupId)

        val allowedSigners = if (existing != null) {
            val existingMembers = parseMembers(existing.membersJson)
            (listOf(existing.adminPublicKeyB64) + existingMembers).distinct()
        } else {
            listOf(group.adminPublicKeyB64)
        }
        val isValid = allowedSigners.any { cand ->
            CryptoService.verify(encCanonical, sync.signature, cand) ||
            (sync.memberDetails.isNullOrEmpty() && (CryptoService.verify(encLegacy, sync.signature, cand) || CryptoService.verify(pipePayload, sync.signature, cand)))
        }
        if (!isValid) {
            Logger.warn(TAG, "Rejected GROUP_SYNC ${group.groupId}: signature verification failed against stored authority")
            return false
        }

        val myKeys = repo.getLocalIdentity()
        val burnable = repo.getBurnableIdentity()
        val incomingMembersList = parseMembers(group.membersJson)
        val meInGroup = incomingMembersList.any {
            it == myKeys?.publicKeyB64 || (burnable != null && it == burnable.publicKeyB64)
        } || group.adminPublicKeyB64 == myKeys?.publicKeyB64 || (burnable != null && group.adminPublicKeyB64 == burnable.publicKeyB64)

        val signerIsAdmin = if (existing != null) {
            CryptoService.verify(encCanonical, sync.signature, existing.adminPublicKeyB64) ||
            (sync.memberDetails.isNullOrEmpty() && (CryptoService.verify(encLegacy, sync.signature, existing.adminPublicKeyB64) || CryptoService.verify(pipePayload, sync.signature, existing.adminPublicKeyB64)))
        } else {
            CryptoService.verify(encCanonical, sync.signature, group.adminPublicKeyB64) ||
            (sync.memberDetails.isNullOrEmpty() && (CryptoService.verify(encLegacy, sync.signature, group.adminPublicKeyB64) || CryptoService.verify(pipePayload, sync.signature, group.adminPublicKeyB64)))
        }

        if (existing != null && sync.timestamp <= existing.revision) {
            Logger.debug(TAG, "Ignoring stale GROUP_SYNC for ${group.groupId} (sync ts ${sync.timestamp} <= existing revision ${existing.revision})")
            return true
        }

        if (existing != null && signerIsAdmin && !meInGroup) {
            Logger.info(TAG, "Authoritative admin GROUP_SYNC for ${group.groupId} excluded us — applying removal and deleting group locally")
            repo.deleteGroupChat(group.groupId)
            return true
        }

        if (!meInGroup) {
            Logger.warn(TAG, "Rejected GROUP_SYNC ${group.groupId}: our identity is not in the group members list")
            return false
        }

        if (existing == null) {
            val isMyGroup = group.adminPublicKeyB64 == myKeys?.publicKeyB64 || (burnable != null && group.adminPublicKeyB64 == burnable.publicKeyB64)
            val hasPendingInvite = db.appSettingDao().getSetting("pending_group_invite_${group.groupId}") != null
            if (isMyGroup) {
                syncMemberPeers(sync.memberDetails)
                db.groupChatDao().insertGroupChat(group.copy(revision = sync.timestamp))
                Logger.info(TAG, "Received own group chat '${group.title}' (${group.groupId}) via GROUP_SYNC")
                return true
            } else if (hasPendingInvite) {
                syncMemberPeers(sync.memberDetails)
                Logger.info(TAG, "Updated pending invite directory for '${group.title}' via GROUP_SYNC")
                return true
            } else {
                Logger.warn(TAG, "Rejected GROUP_SYNC for unknown group '${group.groupId}': no invite accepted")
                return false
            }
        }

        syncMemberPeers(sync.memberDetails)

        val bannedSet = if (signerIsAdmin) group.getBannedMembers().toSet() else existing.getBannedMembers().toSet()
        val existingMembers = parseMembers(existing.membersJson)
        val incomingFiltered = incomingMembersList.filter { it !in bannedSet }

        val mergedMembers = if (signerIsAdmin) {
            incomingFiltered.distinct()
        } else if (existing.allowMemberInvites) {
            (existingMembers + incomingFiltered).distinct()
        } else {
            existingMembers
        }

        val mergedHandles = existing.getMemberHandles().toMutableMap()
        group.getMemberHandles().forEach { (k, v) -> mergedHandles[k] = v }

        val mergedGroup = if (signerIsAdmin) {
            existing.copy(
                title = group.title,
                description = group.description,
                avatarB64 = group.avatarB64,
                allowMemberInvites = group.allowMemberInvites,
                allowMemberSelfRemove = group.allowMemberSelfRemove,
                membersJson = com.noslop.app.util.Json.gson.toJson(mergedMembers),
                memberHandlesJson = com.noslop.app.util.Json.gson.toJson(mergedHandles),
                bannedMembersJson = com.noslop.app.util.Json.gson.toJson(bannedSet.toList()),
                revision = maxOf(existing.revision, sync.timestamp)
            )
        } else {
            existing.copy(
                membersJson = com.noslop.app.util.Json.gson.toJson(mergedMembers),
                memberHandlesJson = com.noslop.app.util.Json.gson.toJson(mergedHandles),
                revision = existing.revision
            )
        }

        db.groupChatDao().insertGroupChat(mergedGroup)
        Logger.info(TAG, "Synced and merged group chat '${mergedGroup.title}' (${group.groupId}) via GROUP_SYNC (members: ${mergedMembers.size})")
        return true
    }
}
