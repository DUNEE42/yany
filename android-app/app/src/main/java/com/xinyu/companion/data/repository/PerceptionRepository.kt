package com.xinyu.companion.data.repository

import com.xinyu.companion.data.local.AppDatabase
import com.xinyu.companion.data.local.NotificationSourceEntity
import com.xinyu.companion.data.local.PerceptionEventEntity
import com.xinyu.companion.domain.perception.NotificationEvent
import com.xinyu.companion.domain.perception.NotificationEventKind
import com.xinyu.companion.domain.perception.NotificationObservationStore
import com.xinyu.companion.domain.perception.NotificationSensitivity
import com.xinyu.companion.domain.perception.NotificationSource
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class PerceptionRepository @Inject constructor(
    private val databaseProvider: Provider<AppDatabase>,
) : NotificationObservationStore {
    private val database by lazy { databaseProvider.get() }
    private val dao by lazy { database.perceptionDao() }

    override suspend fun emit(event: NotificationEvent) {
        val now = System.currentTimeMillis()
        dao.deleteExpiredEvents(now)
        dao.upsertEvent(event.toEntity())
    }

    override fun observeActiveEvents(): Flow<List<NotificationEvent>> =
        dao.observeActiveEvents(System.currentTimeMillis()).map { rows ->
            rows.map(PerceptionEventEntity::toDomain)
        }

    override fun observeSources(): Flow<List<NotificationSource>> =
        dao.observeSources(System.currentTimeMillis() - SOURCE_RETENTION_MS).map { rows ->
            rows.map(NotificationSourceEntity::toDomain)
        }

    override suspend fun activeEvents(
        now: Long,
        limit: Int,
    ): List<NotificationEvent> =
        dao.getActiveEvents(now, limit).map(PerceptionEventEntity::toDomain)

    override suspend fun recordSource(source: NotificationSource) {
        dao.upsertSource(
            NotificationSourceEntity(
                packageName = source.packageName,
                appLabel = source.appLabel,
                lastSeenAt = source.lastSeenAt,
            ),
        )
    }

    override suspend fun deleteEvent(id: String) {
        dao.deleteEvent(id)
    }

    override suspend fun clearEvents() {
        dao.deleteAllEvents()
    }

    override suspend fun clearSources() {
        dao.deleteAllSources()
    }

    override suspend fun pruneExpired(now: Long) {
        dao.deleteExpiredEvents(now)
        dao.deleteSourcesBefore(now - SOURCE_RETENTION_MS)
    }

    private companion object {
        const val SOURCE_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}

private fun NotificationEvent.toEntity() = PerceptionEventEntity(
    id = id,
    dedupeKey = dedupeKey,
    sourcePackage = sourcePackage,
    sourceAppLabel = sourceAppLabel,
    kind = kind.name,
    title = title,
    summary = summary,
    observedAt = observedAt,
    expiresAt = expiresAt,
    confidence = confidence,
    sensitivity = sensitivity.name,
)

private fun PerceptionEventEntity.toDomain() = NotificationEvent(
    id = id,
    dedupeKey = dedupeKey,
    sourcePackage = sourcePackage,
    sourceAppLabel = sourceAppLabel,
    kind = runCatching { NotificationEventKind.valueOf(kind) }
        .getOrDefault(NotificationEventKind.GENERIC),
    title = title,
    summary = summary,
    observedAt = observedAt,
    expiresAt = expiresAt,
    confidence = confidence,
    sensitivity = runCatching { NotificationSensitivity.valueOf(sensitivity) }
        .getOrDefault(NotificationSensitivity.NORMAL),
)

private fun NotificationSourceEntity.toDomain() = NotificationSource(
    packageName = packageName,
    appLabel = appLabel,
    lastSeenAt = lastSeenAt,
)
