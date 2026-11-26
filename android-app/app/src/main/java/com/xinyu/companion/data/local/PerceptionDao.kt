package com.xinyu.companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PerceptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: PerceptionEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: NotificationSourceEntity)

    @Query(
        """
        SELECT * FROM perception_events
        WHERE expiresAt > :now
        ORDER BY observedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getActiveEvents(now: Long, limit: Int): List<PerceptionEventEntity>

    @Query(
        """
        SELECT * FROM perception_events
        WHERE expiresAt > :now
        ORDER BY observedAt DESC
        LIMIT 100
        """,
    )
    fun observeActiveEvents(now: Long): Flow<List<PerceptionEventEntity>>

    @Query(
        """
        SELECT * FROM notification_sources
        WHERE lastSeenAt >= :since
        ORDER BY lastSeenAt DESC
        LIMIT 80
        """,
    )
    fun observeSources(since: Long): Flow<List<NotificationSourceEntity>>

    @Query("DELETE FROM perception_events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    @Query("DELETE FROM perception_events WHERE expiresAt <= :now")
    suspend fun deleteExpiredEvents(now: Long)

    @Query("DELETE FROM perception_events")
    suspend fun deleteAllEvents()

    @Query("DELETE FROM notification_sources")
    suspend fun deleteAllSources()

    @Query("DELETE FROM notification_sources WHERE lastSeenAt < :cutoff")
    suspend fun deleteSourcesBefore(cutoff: Long)
}
