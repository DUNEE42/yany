package com.xinyu.companion.data.notification

import com.xinyu.companion.domain.perception.NotificationEvent
import com.xinyu.companion.domain.perception.NotificationObservationStore
import com.xinyu.companion.domain.perception.NotificationSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class InMemoryNotificationEventSink @Inject constructor() : NotificationObservationStore {
    private val mutableEvents = MutableStateFlow<List<NotificationEvent>>(emptyList())
    private val mutableSources = MutableStateFlow<List<NotificationSource>>(emptyList())
    private val lock = Any()

    val events: StateFlow<List<NotificationEvent>> = mutableEvents.asStateFlow()
    val sources: StateFlow<List<NotificationSource>> = mutableSources.asStateFlow()

    override suspend fun emit(event: NotificationEvent) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            mutableEvents.value = buildList {
                add(event)
                addAll(
                    mutableEvents.value.filter {
                        it.dedupeKey != event.dedupeKey && it.expiresAt > now
                    },
                )
            }.sortedByDescending(NotificationEvent::observedAt)
                .take(MAX_EVENTS)
        }
    }

    override fun observeActiveEvents(): StateFlow<List<NotificationEvent>> = events

    override fun observeSources(): StateFlow<List<NotificationSource>> = sources

    override suspend fun activeEvents(now: Long, limit: Int): List<NotificationEvent> =
        events.value.filter { it.expiresAt > now }.take(limit)

    override suspend fun recordSource(source: NotificationSource) {
        synchronized(lock) {
            mutableSources.value = buildList {
                add(source)
                addAll(mutableSources.value.filter { it.packageName != source.packageName })
            }.sortedByDescending(NotificationSource::lastSeenAt)
                .take(MAX_SOURCES)
        }
    }

    override suspend fun clearEvents() {
        synchronized(lock) {
            mutableEvents.value = emptyList()
        }
    }

    override suspend fun clearSources() {
        synchronized(lock) {
            mutableSources.value = emptyList()
        }
    }

    override suspend fun pruneExpired(now: Long) {
        synchronized(lock) {
            mutableEvents.value = mutableEvents.value.filter { it.expiresAt > now }
        }
    }

    override suspend fun deleteEvent(id: String) {
        synchronized(lock) {
            mutableEvents.value = mutableEvents.value.filterNot { it.id == id }
        }
    }

    private companion object {
        const val MAX_EVENTS = 100
        const val MAX_SOURCES = 80
    }
}
