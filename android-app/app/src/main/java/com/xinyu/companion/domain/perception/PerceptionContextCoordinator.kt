package com.xinyu.companion.domain.perception

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerceptionContextCoordinator @Inject constructor(
    private val settingsStore: PerceptionSettingsStore,
    private val notificationStore: NotificationObservationStore,
    private val notificationSourceSettings: NotificationSourceSettings,
    private val accessChecker: PerceptionAccessChecker,
    private val foregroundAppProvider: ForegroundAppProvider,
    private val networkLocationProvider: CoarseNetworkLocationProvider,
    private val weatherProvider: WeatherProvider,
    private val searchProvider: WebSearchProvider,
) : PerceptionContextProvider {
    override suspend fun build(
        query: String,
        approvedSearchQuery: String?,
    ): PerceptionPromptContext {
        val settings = settingsStore.load()
        val now = System.currentTimeMillis()
        notificationStore.pruneExpired(now)
        val notes = mutableListOf<String>()
        val wantsWeather = PerceptionQueryPolicy.wantsWeather(query)
        val wantsSearch = PerceptionQueryPolicy.wantsWebSearch(query)
        val confirmedSearchQuery = approvedSearchQuery
            ?.let(PerceptionQueryPolicy::normalizeConfirmedSearchQuery)

        val notifications = if (
            accessChecker.status().notificationAccess &&
            PerceptionQueryPolicy.wantsNotifications(query)
        ) {
            selectRelevantNotifications(
                query = query,
                events = notificationStore
                    .activeEvents(now, limit = NOTIFICATION_SCAN_LIMIT)
                    .filter { notificationSourceSettings.isAllowed(it.sourcePackage) },
            )
        } else {
            emptyList()
        }

        val foregroundApp = if (
            settings.foregroundAppEnabled &&
            PerceptionQueryPolicy.wantsForegroundApp(query)
        ) {
            foregroundAppProvider.current()
        } else {
            null
        }

        val networkLocation = if (
            settings.coarseLocationEnabled &&
            wantsWeather &&
            settings.preferredCity.isBlank()
        ) {
            networkLocationProvider.current().also {
                if (it == null) notes += "没有取得可用的网络估计位置"
            }
        } else {
            null
        }

        val city = settings.preferredCity.ifBlank {
            networkLocation?.city.orEmpty()
        }
        val weather = if (wantsWeather && city.isNotBlank()) {
            weatherProvider.current(city).fold(
                onSuccess = { it },
                onFailure = {
                    notes += "天气服务暂时不可用"
                    null
                },
            )
        } else {
            if (wantsWeather) {
                notes += "没有城市信息，无法查询实时天气"
            }
            null
        }

        val shouldSearch = wantsSearch || (wantsWeather && weather == null)
        val search = if (
            shouldSearch &&
            settings.searchConfigured &&
            weather == null &&
            confirmedSearchQuery != null
        ) {
            searchProvider.search(
                baseUrl = settings.searxngUrl,
                query = confirmedSearchQuery,
                maxResults = MAX_SEARCH_RESULTS,
            ).fold(
                onSuccess = {
                    if (it.results.isEmpty()) {
                        notes += "没有找到可用的搜索结果，不要声称已经核实"
                        null
                    } else {
                        it
                    }
                },
                onFailure = {
                    notes += "联网搜索失败，不要声称已经查到实时信息"
                    null
                },
            )
        } else {
            if (shouldSearch && !settings.searchConfigured) {
                notes += "联网搜索未启用，不要声称已经查询实时信息"
            }
            null
        }
        val needsExternalSearch =
            settings.searchConfigured &&
                (wantsSearch || (wantsWeather && weather == null))
        if (needsExternalSearch && confirmedSearchQuery == null && notes.none { it.contains("搜索") }) {
            notes += "用户本轮未确认搜索词，未向外部搜索服务发送任何查询"
        }

        return PerceptionPromptContext(
            currentTimeLabel = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            notifications = notifications,
            foregroundApp = foregroundApp,
            networkLocation = networkLocation,
            weather = weather,
            search = search,
            notes = notes,
        )
    }

    private fun selectRelevantNotifications(
        query: String,
        events: List<NotificationEvent>,
    ): List<NotificationEvent> {
        val queryTerms = MemoryLikeText.terms(query)
        return events
            .asSequence()
            .map { event ->
                val text = "${event.title} ${event.summary}".lowercase()
                val relevance = when {
                    event.kind == NotificationEventKind.PARCEL_READY &&
                        Regex("(?:快递|包裹|取件|驿站|丰巢)").containsMatchIn(query) -> 2

                    event.kind == NotificationEventKind.PARCEL_READY &&
                        Regex("(?:下课|吃饭|午饭|晚饭|饿了|提醒)").containsMatchIn(query) -> 1

                    event.kind == NotificationEventKind.COURSE_UPDATE &&
                        Regex("(?:上课|下课|课程|课表|教室)").containsMatchIn(query) -> 2

                    event.kind == NotificationEventKind.CALENDAR_UPDATE &&
                        Regex("(?:日程|提醒|安排|会议|开会|待办)").containsMatchIn(query) -> 2

                    queryTerms.any { it.length >= 2 && text.contains(it) } -> 1
                    else -> 0
                }
                event to relevance
            }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<NotificationEvent, Int>> { it.second }
                    .thenByDescending { it.first.observedAt },
            )
            .take(MAX_NOTIFICATION_RESULTS)
            .map { it.first }
            .toList()
    }

    private object MemoryLikeText {
        fun terms(query: String): List<String> =
            query.lowercase()
                .split(Regex("""[\s，。！？、,.!?;；:：()（）\[\]【】]+"""))
                .flatMap { chunk ->
                    if (chunk.length <= 3) listOf(chunk) else chunk.windowed(2)
                }
                .filter { it.isNotBlank() }
                .distinct()
    }

    private companion object {
        const val NOTIFICATION_SCAN_LIMIT = 20
        const val MAX_NOTIFICATION_RESULTS = 3
        const val MAX_SEARCH_RESULTS = 5
    }
}
