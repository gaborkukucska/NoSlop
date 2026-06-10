// FILE: app/src/main/java/com/noslop/app/data/NoSlopDatabase.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FeedSource::class,
        FeedItem::class,
        Peer::class,
        MeshPost::class,
        ChatMessage::class,
        AppSetting::class,
        MeshComment::class,
        MeshReaction::class
    ],
    version = 15,
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
