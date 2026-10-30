// FILE: app/src/main/java/com/noslop/app/data/EngagementOrdering.kt
package com.noslop.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P1-B: last-writer-wins ordering for reactions and votes (post reactions, post votes, comment votes,
 * comment reactions, chat reactions). Receiver side only; nothing on the wire changes.
 *
 * Each engagement is one row keyed by (target, author, type). "add" REPLACEd the row and "remove"
 * deleted it, so a replayed, still validly signed old "add" brought back an engagement that had been
 * taken back. Now every add/remove is applied only if its signed timestamp is strictly newer than the
 * newest one seen for that key:
 *  - an active engagement's newest timestamp is the stored row's own `timestamp`;
 *  - a removed one's is kept as a small tombstone in `app_settings` (the store the post tombstones
 *    already use), so no schema change or migration is needed. A newer "add" clears the tombstone,
 *    which keeps at most one tombstone per currently-removed engagement.
 *
 * Read-check-write runs under one process-wide lock, so two packets for the same key cannot interleave.
 */
class EngagementOrdering(private val settings: AppSettingDao) {

    /**
     * Applies a received add/remove for engagement [id] of [kind] signed at [timestamp]. [currentRowTimestamp]
     * reads the stored row's timestamp (null when absent); [write] performs the insert or delete.
     * Returns false (and writes nothing) when the event is not newer than what is already known.
     */
    suspend fun applyRemote(
        kind: String,
        id: String,
        timestamp: Long,
        isRemove: Boolean,
        currentRowTimestamp: suspend () -> Long?,
        write: suspend () -> Unit
    ): Boolean = lock.withLock {
        val key = tombstoneKey(kind, id)
        val removedAt = settings.getSetting(key)?.toLongOrNull()
        val newest = listOfNotNull(removedAt, currentRowTimestamp()).maxOrNull()
        if (newest != null && timestamp <= newest) return@withLock false
        write()
        record(key, timestamp, isRemove, removedAt)
        true
    }

    /**
     * The local user's own add/remove: always applied (it is the latest intent by definition), but the
     * tombstone is kept in step so an echo of an older add of ours cannot resurrect it either.
     */
    suspend fun applyLocal(kind: String, id: String, timestamp: Long, isRemove: Boolean, write: suspend () -> Unit) = lock.withLock {
        val key = tombstoneKey(kind, id)
        write()
        record(key, timestamp, isRemove, settings.getSetting(key)?.toLongOrNull())
    }

    private suspend fun record(key: String, timestamp: Long, isRemove: Boolean, removedAt: Long?) {
        if (isRemove) {
            settings.insertSetting(AppSetting(key, timestamp.toString()))
        } else if (removedAt != null) {
            settings.removeSetting(key)
        }
    }

    companion object {
        const val KIND_REACTION = "reaction"
        const val KIND_VOTE = "vote"
        const val KIND_COMMENT_VOTE = "comment_vote"
        const val KIND_COMMENT_REACTION = "comment_reaction"
        const val KIND_CHAT_REACTION = "chat_reaction"

        private val lock = Mutex()

        fun tombstoneKey(kind: String, id: String) = "engagement_removed_${kind}_$id"
    }
}
