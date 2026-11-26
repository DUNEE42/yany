package com.xinyu.companion.domain.perception

import kotlinx.coroutines.flow.Flow

data class ForegroundAppSnapshot(
    val packageName: String,
    val appLabel: String,
    val observedAt: Long,
)

data class CoarseNetworkLocation(
    val networkType: String,
    val city: String?,
    val region: String?,
    val country: String?,
    val confidence: Double,
    val estimatedAt: Long,
    val source: String = "public_ip",
)

data class PerceptionPromptContext(
    val currentTimeLabel: String,
    val notifications: List<NotificationEvent> = emptyList(),
    val foregroundApp: ForegroundAppSnapshot? = null,
    val networkLocation: CoarseNetworkLocation? = null,
    val weather: WeatherSnapshot? = null,
    val search: WebSearchResponse? = null,
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = notifications.isEmpty() &&
            foregroundApp == null &&
            networkLocation == null &&
            weather == null &&
            search == null
}

interface PerceptionContextProvider {
    suspend fun build(
        query: String,
        approvedSearchQuery: String? = null,
    ): PerceptionPromptContext
}

interface NotificationObservationStore : NotificationEventSink {
    fun observeActiveEvents(): Flow<List<NotificationEvent>>

    fun observeSources(): Flow<List<NotificationSource>>

    suspend fun activeEvents(now: Long, limit: Int = 20): List<NotificationEvent>

    suspend fun recordSource(source: NotificationSource)

    suspend fun deleteEvent(id: String)

    suspend fun clearEvents()

    suspend fun clearSources()

    suspend fun pruneExpired(now: Long)
}

interface ForegroundAppProvider {
    suspend fun current(): ForegroundAppSnapshot?
}

interface CoarseNetworkLocationProvider {
    suspend fun current(): CoarseNetworkLocation?
}

data class PerceptionSettings(
    val foregroundAppEnabled: Boolean = false,
    val coarseLocationEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val searxngUrl: String = "",
    val preferredCity: String = "",
) {
    val searchConfigured: Boolean
        get() = webSearchEnabled && searxngUrl.isNotBlank()
}
