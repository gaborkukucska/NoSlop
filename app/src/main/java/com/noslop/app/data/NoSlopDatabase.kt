// app/src/main/java/com/noslop/app/data/NoSlopDatabase.kt
// FILE: app/src/main/java/com/noslop/app/data/NoSlopDatabase.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Schema Version 14 (with migrations MIGRATION_1_2 through MIGRATION_13_14)
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
        CommentReaction::class,
        MeshVote::class,
        CommentVote::class,
        NotificationItem::class,
        ViewedHistoryItem::class,
        SwipeTracker::class,
        GroupChat::class,
        PendingGroupMessage::class,
        MediaOwner::class
    ],
    version = 19,
    exportSchema = true
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
    abstract fun voteDao(): VoteDao
    abstract fun commentVoteDao(): CommentVoteDao
    abstract fun notificationDao(): NotificationDao
    abstract fun viewedHistoryDao(): ViewedHistoryDao
    abstract fun swipeTrackerDao(): SwipeTrackerDao
    abstract fun groupChatDao(): GroupChatDao
    abstract fun pendingGroupMessageDao(): PendingGroupMessageDao
    abstract fun mediaOwnerDao(): MediaOwnerDao

    companion object {
        @Volatile
        private var INSTANCE: NoSlopDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add clearnetMediaType to carry original clearnet item type through mesh
                database.execSQL("ALTER TABLE meshPost ADD COLUMN clearnetMediaType TEXT")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE mesh_comments ADD COLUMN mediaId TEXT")
                database.execSQL("ALTER TABLE mesh_comments ADD COLUMN mediaType TEXT")
                database.execSQL("DELETE FROM mesh_comments WHERE content LIKE '%noslop-gif://data:image%'")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Discoverable Mode: add contact classification and folder fields to peers
                database.execSQL("ALTER TABLE peers ADD COLUMN customFolder TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE peers ADD COLUMN isTemporary INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE peers ADD COLUMN isDiscoverable INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE peers ADD COLUMN isCreator INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE peers ADD COLUMN fundMeLink TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Discoverability: Add bio field to peers
                database.execSQL("ALTER TABLE peers ADD COLUMN bio TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add mediaSize to mesh_posts
                database.execSQL("ALTER TABLE mesh_posts ADD COLUMN mediaSize INTEGER NOT NULL DEFAULT 0")
            }
        }

        // --- NOSLOP_DELETION_BUDGET_V1 ---
        // Purely additive: an existing row keeps its data and starts with a
        // full deletion budget, so any deletion still pending propagation gets
        // MAX_DELETION_BROADCASTS more attempts after the upgrade rather than
        // being silenced immediately.
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE mesh_posts ADD COLUMN deletionBroadcasts INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE feed_items ADD COLUMN channelCreatedAt INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE feed_sources ADD COLUMN channelCreatedAt INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE peers ADD COLUMN isFollowing INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE TABLE IF NOT EXISTS group_chats (groupId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, adminPublicKeyB64 TEXT NOT NULL, membersJson TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_chats ADD COLUMN description TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE group_chats ADD COLUMN allowMemberInvites INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE group_chats ADD COLUMN allowMemberSelfRemove INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_chats ADD COLUMN avatarB64 TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_chats ADD COLUMN memberHandlesJson TEXT DEFAULT '{}'")
                database.execSQL("DELETE FROM peers WHERE isTrusted = 0 AND (onionAddress IS NULL OR onionAddress = '')")
            }
        }

        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_group_messages (
                        groupId TEXT NOT NULL,
                        memberPub TEXT NOT NULL,
                        msgId TEXT NOT NULL,
                        ciphertext TEXT NOT NULL,
                        nonce TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(groupId, memberPub, msgId)
                    )
                """.trimIndent())

                // P0-2: Re-encrypt existing plaintext group chat messages at rest with AAD binding
                try {
                    val cursor = database.query("SELECT id, ciphertext, chatWithPeerPub FROM chat_messages WHERE (nonce = '' OR nonce IS NULL) AND chatWithPeerPub LIKE '%-%'")
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val plaintext = cursor.getString(1)
                        val groupId = cursor.getString(2) ?: ""
                        if (!plaintext.startsWith("ENC:GCM")) {
                            val (encBody, iv) = com.noslop.app.crypto.GroupMessageCrypto.encrypt(plaintext, groupId = groupId, msgId = id)
                            database.execSQL("UPDATE chat_messages SET ciphertext = ?, nonce = ? WHERE id = ?", arrayOf(encBody, iv, id))
                        }
                    }
                    cursor.close()
                } catch (e: Exception) {
                    com.noslop.app.debug.Logger.warn("DATABASE", "Migration 12->13 re-encryption notice: ${e.message}")
                }
            }
        }

        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // A-1: Retire legacy ENC:GCM: ciphertext by re-encrypting with AAD binding ($groupId|$msgId)
                try {
                    val cursor = database.query(
                        "SELECT id, ciphertext, nonce, chatWithPeerPub FROM chat_messages WHERE ciphertext LIKE 'ENC:GCM:%'"
                    )
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val ciphertext = cursor.getString(1)
                        val nonce = cursor.getString(2) ?: ""
                        val groupId = cursor.getString(3) ?: ""

                        // Idempotent: skip rows already carrying ENC:GCM2:
                        if (!ciphertext.startsWith(com.noslop.app.crypto.GroupMessageCrypto.CIPHERTEXT_PREFIX_V2)) {
                            val plaintext = com.noslop.app.crypto.GroupMessageCrypto.decryptOrNull(ciphertext, nonce)
                            if (plaintext != null) {
                                val (encBody, iv) = com.noslop.app.crypto.GroupMessageCrypto.encrypt(
                                    plaintext,
                                    groupId = groupId,
                                    msgId = id
                                )
                                database.execSQL(
                                    "UPDATE chat_messages SET ciphertext = ?, nonce = ? WHERE id = ?",
                                    arrayOf(encBody, iv, id)
                                )
                            } else {
                                com.noslop.app.debug.Logger.error("DATABASE", "Migration 13->14: Message $id could not be decrypted with group key; skipping re-encryption to preserve original ciphertext")
                            }
                        }
                    }
                    cursor.close()
                } catch (e: Exception) {
                    com.noslop.app.debug.Logger.warn("DATABASE", "Migration 13->14 legacy migration notice: ${e.message}")
                }
            }
        }

        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_chats ADD COLUMN bannedMembersJson TEXT DEFAULT '[]'")
            }
        }

        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_chats ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE peers ADD COLUMN relationship TEXT NOT NULL DEFAULT 'NONE'")
                database.execSQL("ALTER TABLE peers ADD COLUMN pendingNonce TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE peers ADD COLUMN pendingEncKey TEXT DEFAULT NULL")
                database.execSQL("UPDATE peers SET relationship = 'ACCEPTED' WHERE isTrusted = 1")
            }
        }

        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN isLegacy INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'DELIVERED'")
            }
        }

        /**
         * v0.7.0 (round-2 review, D01/D02/D10). The only schema change of this release:
         *  1. peers.verifiedFingerprint (D10 "Mark verified").
         *  2. media_owner index table (D02), back-filled from every existing post, comment and
         *     message attachment so the deny-by-default media ACL knows every file on disk.
         *  3. Trust repair (D01): burnable-identity contacts are always temporary, and
         *     isTrusted is re-derived from relationship. This demotes every peer the old
         *     startup "heal" promoted without consent (relationship stayed INCOMING_PENDING)
         *     and every group admin that acceptGroupInvite made a full friend.
         */
        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE peers ADD COLUMN verifiedFingerprint TEXT DEFAULT NULL")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_owner` (`mediaId` TEXT NOT NULL, `ownerType` TEXT NOT NULL, " +
                    "`ownerId` TEXT NOT NULL, `privacy` TEXT NOT NULL, `authorPub` TEXT NOT NULL, `accessKey` TEXT, " +
                    "`sha256` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`, `ownerType`, `ownerId`))"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_media_owner_mediaId` ON `media_owner` (`mediaId`)")

                // D01 trust repair. 'contact_identity_' is 17 characters, so the peer key starts at 18.
                database.execSQL(
                    "UPDATE peers SET isTemporary = 1 WHERE publicKeyB64 IN " +
                    "(SELECT substr(`key`, 18) FROM app_settings WHERE `key` LIKE 'contact_identity_%' AND `value` = 'burnable')"
                )
                database.execSQL("UPDATE peers SET isTrusted = CASE WHEN relationship = 'ACCEPTED' THEN 1 ELSE 0 END")

                backfillMediaOwners(database)
            }
        }

        /** D02 back-fill, shared by MIGRATION_18_19. Idempotent (INSERT OR IGNORE). */
        internal fun backfillMediaOwners(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            fun insert(mediaId: String, type: String, ownerId: String, privacy: String, author: String) {
                if (!com.noslop.app.mesh.MediaManager.isValidMediaId(mediaId)) return
                database.execSQL(
                    "INSERT OR IGNORE INTO media_owner (mediaId, ownerType, ownerId, privacy, authorPub, accessKey, sha256, createdAt) " +
                    "VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)",
                    arrayOf<Any?>(mediaId, type, ownerId, privacy, author, now)
                )
            }
            database.query("SELECT id, mediaUrl, privacy, authorPublicKeyB64 FROM mesh_posts WHERE mediaUrl IS NOT NULL AND isOrphaned = 0").use { c ->
                while (c.moveToNext()) {
                    val mediaId = MediaOwner.mediaIdFromUrl(c.getString(1)) ?: continue
                    insert(mediaId, MediaOwner.TYPE_POST, c.getString(0), MediaOwner.normalizePrivacy(c.getString(2)), c.getString(3) ?: "")
                }
            }
            database.query(
                "SELECT c.id, c.mediaId, c.authorPublicKeyB64, p.privacy FROM mesh_comments c " +
                "LEFT JOIN mesh_posts p ON p.id = c.postId WHERE c.mediaId IS NOT NULL"
            ).use { c ->
                while (c.moveToNext()) {
                    val mediaId = c.getString(1) ?: continue
                    insert(mediaId, MediaOwner.TYPE_COMMENT, c.getString(0), MediaOwner.normalizePrivacy(c.getString(3)), c.getString(2) ?: "")
                }
            }
            database.query(
                "SELECT m.mediaId, m.chatWithPeerPub, m.senderPub, " +
                "(SELECT COUNT(*) FROM group_chats g WHERE g.groupId = m.chatWithPeerPub) FROM chat_messages m WHERE m.mediaId IS NOT NULL"
            ).use { c ->
                while (c.moveToNext()) {
                    val mediaId = c.getString(0) ?: continue
                    val isGroup = c.getInt(3) > 0
                    insert(
                        mediaId,
                        if (isGroup) MediaOwner.TYPE_GROUP else MediaOwner.TYPE_DM,
                        c.getString(1) ?: continue,
                        if (isGroup) "group" else "private",
                        c.getString(2) ?: ""
                    )
                }
            }
        }

        fun getDatabase(context: Context): NoSlopDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoSlopDatabase::class.java,
                    "mesh.db"
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)
                .build()
                .also { INSTANCE = it }
            }
        }

        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
