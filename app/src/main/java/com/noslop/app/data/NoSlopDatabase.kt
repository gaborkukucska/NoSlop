// FILE: app/src/main/java/com/noslop/app/data/NoSlopDatabase.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// FIX: Bumped from 17 → 18 for ChatReaction and CommentReaction entities.
//
// Two crashes were caused by a stale on-device database:
//
// 1. FOREIGN KEY constraint failed (SQLiteConstraintException) in
//    CommentDao.insertComment and FeedDao.insertItems — the device's existing
//    SQLite DB had FK constraints baked in from a prior schema version that no
//    longer matches the current Room entities (which declare no @ForeignKey
//    annotations). This caused FK violations when inserting comments/reactions
//    whose postId/sourceId hadn't yet been inserted in the same session.
//
// 2. IllegalStateException: "Room cannot verify the data integrity. Looks like
//    you've changed schema but forgot to update the version number." — Room
//    detected a hash mismatch between the compiled schema and the on-device DB
//    (expected f7ece6e379d08b101ccb11ca0f90b8b3, found 550a3f0104d58e1fa7df95f9cde61faa).
//    fallbackToDestructiveMigration() only triggers on a VERSION change, not
//    a hash-only change. Without a version bump it crashes instead of wiping.
//
// Incrementing the version number causes Room to invoke fallbackToDestructiveMigration,
// which drops and recreates all tables cleanly, resolving both issues.
@Database(
    entities = [
        FeedSource::class,
        FeedItem::class,
        Peer::class,
        MeshPost::class,
        ChatMessage::class,
        AppSetting::class,
        MeshComment::class,
        MeshReaction::class,
        ChatReaction::class,
        CommentReaction::class
    ],
    version = 19,
    exportSchema = false
)
abstract class NoSlopDatabase : RoomDatabase() {

    abstract fun feedDao(): FeedDao
    abstract fun peerDao(): PeerDao
    abstract fun postDao(): PostDao
    abstract fun messageDao(): MessageDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun commentDao(): CommentDao
    abstract fun reactionDao(): ReactionDao
    abstract fun chatReactionDao(): ChatReactionDao
    abstract fun commentReactionDao(): CommentReactionDao

    companion object {
        @Volatile
        private var INSTANCE: NoSlopDatabase? = null

        fun getDatabase(context: Context): NoSlopDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoSlopDatabase::class.java,
                    "noslop_app_database"
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration() // Facilitate updates during prototyping
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
