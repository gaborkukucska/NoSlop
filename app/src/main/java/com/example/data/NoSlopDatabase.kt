// FILE: app/src/main/java/com/example/data/NoSlopDatabase.kt
package com.example.data

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
        AppSetting::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NoSlopDatabase : RoomDatabase() {

    abstract fun feedDao(): FeedDao
    abstract fun peerDao(): PeerDao
    abstract fun postDao(): PostDao
    abstract fun messageDao(): MessageDao
    abstract fun appSettingDao(): AppSettingDao

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
                .fallbackToDestructiveMigration() // Facilitate updates during prototyping
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
