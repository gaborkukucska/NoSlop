// FILE: app/src/main/java/com/noslop/app/mesh/SyncPayloads.kt
package com.noslop.app.mesh

import com.noslop.app.data.MeshComment
import com.noslop.app.data.MeshPost
import com.noslop.app.data.MeshReaction
import com.noslop.app.data.NoSlopRepository

/**
 * NOSLOP_HUB_CREATOR_HOSTING_V1 — how a stored post, comment or reaction goes out in a SYNC_RESPONSE.
 *
 * Moved verbatim out of [SyncPacketHandler] so the content the phone uploads to its Home HUB
 * (NoSlopRepository.syncCreatorContentToHub) is exactly what the phone itself would serve: the Hub
 * passes these on to the creator's followers unchanged, and they verify the creator's signatures.
 */
internal suspend fun MeshComment.toSyncCommentData(repo: NoSlopRepository): CommentSyncData = CommentSyncData(
    id = id,
    postId = postId,
    authorId = authorPublicKeyB64,
    authorName = authorHandle,
    authorAvatarB64 = authorAvatarB64,
    content = content,
    timestamp = timestamp,
    signature = signature,
    parentCommentId = parentCommentId,
    mediaId = mediaId,
    mediaType = mediaType,
    // R2: carry the media descriptor and its signed digest so a comment with a GIF/image verifies
    // (C17 v2 signature) and its media can be fetched after a sync.
    mediaMetadata = mediaId?.let { mid ->
        MediaMetadata(
            id = mid,
            type = mediaType ?: "image",
            mimeType = "application/octet-stream",
            size = 0,
            chunkCount = 0,
            ownerId = authorPublicKeyB64,
            sha256 = repo.mediaDigestFor(mid)
        )
    }
)

internal fun MeshReaction.toSyncReactionData(): ReactionSyncData = ReactionSyncData(
    id = id,
    postId = postId,
    authorId = authorPublicKeyB64,
    reactionType = reactionType,
    timestamp = timestamp,
    signature = signature
)

internal suspend fun MeshPost.toSyncPostPayload(repo: NoSlopRepository): PostPayload {
    val rawMediaId = mediaUrl?.substringAfterLast("/")
    return PostPayload(
        id = id,
        authorId = authorPublicKeyB64,
        authorName = authorHandle,
        authorPublicKey = authorPublicKeyB64,
        authorAvatarB64 = authorAvatarB64,
        originNode = null,
        content = content,
        timestamp = timestamp,
        privacy = privacy,
        signature = signature,
        mediaId = rawMediaId,
        mediaMetadata = if (rawMediaId != null) MediaMetadata(
            id = rawMediaId,
            type = mediaType ?: "image",
            mimeType = "application/octet-stream",
            size = mediaSize,
            chunkCount = 0,
            thumbnailB64 = thumbnailB64,
            // R2: the signed media digest; without it a C17 v2 signature cannot be checked.
            sha256 = repo.mediaDigestFor(rawMediaId)
        ) else null,
        clearnetUrl = clearnetUrl,
        clearnetTitle = clearnetTitle,
        clearnetThumbnailUrl = clearnetThumbnailUrl,
        clearnetMediaType = clearnetMediaType
    )
}
