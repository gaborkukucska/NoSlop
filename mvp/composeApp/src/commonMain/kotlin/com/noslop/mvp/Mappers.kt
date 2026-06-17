package com.noslop.mvp

import com.noslop.mvp.db.MeshPost

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
