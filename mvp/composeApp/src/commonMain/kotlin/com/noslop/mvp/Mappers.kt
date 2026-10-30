package com.noslop.mvp

import com.noslop.mvp.db.MeshPost

/**
 * Map a persisted [MeshPost] row back to a wire [PostPayload] — used to re-show the mesh feed on launch
 * (the store was previously write-only; posts vanished on restart). Media metadata is only partially
 * persisted (type + thumbnail, no id/size), which is enough to render the thumbnail card; the full-media
 * download can't restart from a stored row until we persist the media id/size too.
 */
fun MeshPost.toPostPayload(): PostPayload = PostPayload(
    id = id,
    authorId = authorPublicKeyB64,
    authorName = authorHandle,
    authorPublicKey = authorPublicKeyB64,
    authorAvatarB64 = authorAvatarB64,
    content = content,
    timestamp = timestamp,
    privacy = privacy,
    signature = signature.ifBlank { null },
    mediaMetadata = thumbnailB64?.let {
        MediaMetadata(id = "", type = mediaType ?: "image", mimeType = "", size = 0, chunkCount = 0, thumbnailB64 = it)
    },
    clearnetUrl = clearnetUrl,
    clearnetTitle = clearnetTitle,
    clearnetThumbnailUrl = clearnetThumbnailUrl,
)

/** Map a wire [PostPayload] to the persisted [MeshPost] row. Used by any node (HUB or app) that stores posts. */
fun PostPayload.toMeshPost(): MeshPost = MeshPost(
    id = id,
    authorPublicKeyB64 = authorPublicKey,
    authorHandle = authorName,
    authorTripcode = "",
    authorAvatarB64 = authorAvatarB64,
    content = content,
    timestamp = timestamp,
    signature = signature ?: "",
    mediaUrl = null,
    mediaType = mediaMetadata?.type,
    gossipCount = 1,
    privacy = privacy,
    thumbnailB64 = mediaMetadata?.thumbnailB64,
    clearnetUrl = clearnetUrl,
    clearnetTitle = clearnetTitle,
    clearnetThumbnailUrl = clearnetThumbnailUrl,
    isOrphaned = false,
)
