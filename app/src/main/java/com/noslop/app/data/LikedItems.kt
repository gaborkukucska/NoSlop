// FILE: app/src/main/java/com/noslop/app/data/LikedItems.kt
package com.noslop.app.data

/**
 * --- NOSLOP_LIKED_LIST_V1 ---
 * What puts an item in the Liked list: a positive reaction (or a mesh upvote) by one of this device's
 * own identities. Saved is a separate list; a like also saves the item, a save is not a like.
 */
val POSITIVE_REACTIONS: Set<String> = setOf(
    "like", "upvote", "laugh", "fire", "wow", "celebrate", "insightful", "clap", "gem"
)

/** A reaction or a vote on a post, as Liked reads it. */
data class LikeMark(val postId: String, val authorKey: String, val type: String, val timestamp: Long)

/** The latest time each post was liked by one of [ownKeys]. Other people's marks and other types do not count. */
fun likedAtByPost(marks: List<LikeMark>, ownKeys: Set<String>): Map<String, Long> {
    val likedAt = HashMap<String, Long>()
    for (mark in marks) {
        if (mark.authorKey !in ownKeys || mark.type !in POSITIVE_REACTIONS) continue
        val previous = likedAt[mark.postId]
        if (previous == null || mark.timestamp > previous) likedAt[mark.postId] = mark.timestamp
    }
    return likedAt
}

/** The next page of the Liked list: liked, matching the search, not on screen yet, newest like first. */
fun <T> nextLikedPage(
    candidates: List<T>,
    likedAt: (T) -> Long?,
    isShown: (T) -> Boolean,
    matches: (T) -> Boolean,
    limit: Int
): List<T> = candidates
    .mapNotNull { candidate -> likedAt(candidate)?.let { candidate to it } }
    .filter { (candidate, _) -> !isShown(candidate) && matches(candidate) }
    .sortedByDescending { it.second }
    .take(limit)
    .map { it.first }
