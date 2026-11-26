package com.xinyu.companion.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        ConversationSummaryEntity::class,
        MemoryItemEntity::class,
        MemorySourceEntity::class,
        MemoryJobEntity::class,
        PerceptionEventEntity::class,
        NotificationSourceEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun companionDao(): CompanionDao
    abstract fun memoryDao(): MemoryDao
    abstract fun perceptionDao(): PerceptionDao

    companion object {
        /**
         * Version 1 wrote the user and assistant rows with the same sequence.
         * The assistant REPLACE then erased every stored user turn, so the
         * corrupted history has no reliable user side to recover.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DELETE FROM messages")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN replyToMessageId TEXT",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_conversations_companionId_updatedAt` " +
                        "ON `conversations` (`companionId`, `updatedAt`)",
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`sequence` INTEGER NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`clientMessageId` TEXT, " +
                        "`replyToMessageId` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                database.execSQL(
                    "INSERT INTO `messages_new` (" +
                        "`id`, `conversationId`, `role`, `sequence`, `content`, " +
                        "`status`, `clientMessageId`, `replyToMessageId`, `createdAt`, `updatedAt`) " +
                        "SELECT " +
                        "`id`, `conversationId`, `role`, `sequence`, `content`, " +
                        "`status`, `clientMessageId`, `replyToMessageId`, `createdAt`, `updatedAt` " +
                        "FROM `messages`",
                )
                database.execSQL("DROP TABLE `messages`")
                database.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_messages_conversationId_sequence` " +
                        "ON `messages` (`conversationId`, `sequence`)",
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_clientMessageId` " +
                        "ON `messages` (`clientMessageId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_memories_companionId_userConfirmed_importance_createdAt` " +
                        "ON `memories` (`companionId`, `userConfirmed`, `importance`, `createdAt`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_summaries` (
                      `id` TEXT NOT NULL,
                      `conversationId` TEXT NOT NULL,
                      `startSequence` INTEGER NOT NULL,
                      `endSequence` INTEGER NOT NULL,
                      `periodStart` INTEGER NOT NULL,
                      `periodEnd` INTEGER NOT NULL,
                      `content` TEXT NOT NULL,
                      `contentHash` TEXT NOT NULL,
                      `status` TEXT NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`id`),
                      FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_conversation_summaries_conversationId_endSequence` " +
                        "ON `conversation_summaries` (`conversationId`, `endSequence`)",
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_conversation_summaries_conversationId_contentHash` " +
                        "ON `conversation_summaries` (`conversationId`, `contentHash`)",
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_items` (
                      `id` TEXT NOT NULL,
                      `companionId` TEXT NOT NULL,
                      `kind` TEXT NOT NULL,
                      `scope` TEXT NOT NULL,
                      `subject` TEXT NOT NULL,
                      `predicate` TEXT NOT NULL,
                      `objectText` TEXT NOT NULL,
                      `statement` TEXT NOT NULL,
                      `normalizedKey` TEXT NOT NULL,
                      `sourceKind` TEXT NOT NULL,
                      `confirmationState` TEXT NOT NULL,
                      `status` TEXT NOT NULL,
                      `confidence` REAL NOT NULL,
                      `importance` INTEGER NOT NULL,
                      `sensitivity` TEXT NOT NULL,
                      `observedAt` INTEGER NOT NULL,
                      `validFrom` INTEGER,
                      `validTo` INTEGER,
                      `supersededById` TEXT,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      `lastAccessedAt` INTEGER,
                      PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_memory_items_companionId_status_kind` " +
                        "ON `memory_items` (`companionId`, `status`, `kind`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_identity` " +
                        "ON `memory_items` (`scope`, `subject`, `predicate`, `status`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_validity` " +
                        "ON `memory_items` (`validFrom`, `validTo`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_normalizedKey` " +
                        "ON `memory_items` (`normalizedKey`)",
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_sources` (
                      `id` TEXT NOT NULL,
                      `memoryId` TEXT NOT NULL,
                      `messageId` TEXT,
                      `summaryId` TEXT,
                      `evidenceHash` TEXT NOT NULL,
                      PRIMARY KEY(`id`),
                      FOREIGN KEY(`memoryId`) REFERENCES `memory_items`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                      FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL,
                      FOREIGN KEY(`summaryId`) REFERENCES `conversation_summaries`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_sources_memoryId` " +
                        "ON `memory_sources` (`memoryId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_sources_messageId` " +
                        "ON `memory_sources` (`messageId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_sources_summaryId` " +
                        "ON `memory_sources` (`summaryId`)",
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_sources_identity` " +
                        "ON `memory_sources` (`memoryId`, `messageId`, `summaryId`)",
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_jobs` (
                      `id` TEXT NOT NULL,
                      `conversationId` TEXT NOT NULL,
                      `companionId` TEXT NOT NULL,
                      `fromSequence` INTEGER NOT NULL,
                      `toSequence` INTEGER NOT NULL,
                      `status` TEXT NOT NULL,
                      `attempts` INTEGER NOT NULL,
                      `lastError` TEXT,
                      `scheduledAt` INTEGER NOT NULL,
                      `startedAt` INTEGER,
                      `completedAt` INTEGER,
                      PRIMARY KEY(`id`),
                      FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_memory_jobs_conversationId_status` " +
                        "ON `memory_jobs` (`conversationId`, `status`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_jobs_status_scheduledAt` " +
                        "ON `memory_jobs` (`status`, `scheduledAt`)",
                )

                database.execSQL(
                    """
                    INSERT INTO `memory_items` (
                      `id`, `companionId`, `kind`, `scope`, `subject`, `predicate`,
                      `objectText`, `statement`, `normalizedKey`, `sourceKind`,
                      `confirmationState`, `status`, `confidence`, `importance`,
                      `sensitivity`, `observedAt`, `validFrom`, `validTo`,
                      `supersededById`, `createdAt`, `updatedAt`, `lastAccessedAt`
                    )
                    SELECT
                      `id`,
                      `companionId`,
                      CASE UPPER(`type`)
                        WHEN 'PREFERENCE' THEN 'PREFERENCE'
                        WHEN 'BOUNDARY' THEN 'BOUNDARY'
                        WHEN 'PLAN' THEN 'PLAN'
                        WHEN 'EVENT' THEN 'EVENT'
                        WHEN 'RELATIONSHIP_PREFERENCE' THEN 'RELATIONSHIP_PREFERENCE'
                        ELSE 'FACT'
                      END,
                      'USER',
                      'user',
                      'mentions',
                      `content`,
                      `content`,
                      'legacy:' || `id`,
                      'MANUAL',
                      CASE WHEN `userConfirmed` = 1 THEN 'CONFIRMED' ELSE 'UNCONFIRMED' END,
                      CASE WHEN `userConfirmed` = 1 THEN 'ACTIVE' ELSE 'CANDIDATE' END,
                      1.0,
                      `importance`,
                      'NORMAL',
                      `createdAt`,
                      `createdAt`,
                      NULL,
                      NULL,
                      `createdAt`,
                      `createdAt`,
                      `createdAt`
                    FROM `memories`
                    """.trimIndent(),
                )

                database.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `memory_items_fts` " +
                        "USING fts4(`memoryId`, `statement`, `subject`, `predicate`, `objectText`)",
                )
                database.execSQL(
                    """
                    INSERT OR REPLACE INTO `memory_items_fts` (
                      `memoryId`, `statement`, `subject`, `predicate`, `objectText`
                    )
                    SELECT
                      `id`, `statement`, `subject`, `predicate`, `objectText`
                    FROM `memory_items`
                    WHERE `status` != 'DELETED'
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `perception_events` (
                      `id` TEXT NOT NULL,
                      `dedupeKey` TEXT NOT NULL,
                      `sourcePackage` TEXT NOT NULL,
                      `sourceAppLabel` TEXT NOT NULL,
                      `kind` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `summary` TEXT NOT NULL,
                      `observedAt` INTEGER NOT NULL,
                      `expiresAt` INTEGER NOT NULL,
                      `confidence` REAL NOT NULL,
                      `sensitivity` TEXT NOT NULL,
                      PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_perception_events_dedupeKey` " +
                        "ON `perception_events` (`dedupeKey`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_perception_events_expiresAt` " +
                        "ON `perception_events` (`expiresAt`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_perception_events_observedAt` " +
                        "ON `perception_events` (`observedAt`)",
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notification_sources` (
                      `packageName` TEXT NOT NULL,
                      `appLabel` TEXT NOT NULL,
                      `lastSeenAt` INTEGER NOT NULL,
                      PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}

