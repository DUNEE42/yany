package com.xinyu.companion.domain.perception

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PerceptionContextCoordinatorTest {
    @Test
    fun `manual city takes priority without requesting public ip`() = runTest {
        val network = FakeNetworkLocationProvider(error = AssertionError("must not be called"))
        val weather = FakeWeatherProvider()
        val coordinator = coordinator(
            settings = PerceptionSettings(
                coarseLocationEnabled = true,
                preferredCity = "苏州",
            ),
            network = network,
            weather = weather,
        )

        val context = coordinator.build("苏州今天天气怎么样")

        assertThat(network.calls).isEqualTo(0)
        assertThat(weather.requestedCity).isEqualTo("苏州")
        assertThat(context.weather).isNotNull()
    }

    @Test
    fun `search runs only when configured and weather cannot answer`() = runTest {
        val search = FakeSearchProvider()
        val coordinator = coordinator(
            settings = PerceptionSettings(
                webSearchEnabled = true,
                searxngUrl = "https://search.example",
            ),
            weather = FakeWeatherProvider(failure = WeatherException("down")),
            search = search,
        )

        val context = coordinator.build(
            query = "查一下今天的新闻",
            approvedSearchQuery = "今天的新闻",
        )

        assertThat(search.calls).isEqualTo(1)
        assertThat(search.lastQuery).isEqualTo("今天的新闻")
        assertThat(context.search!!.results).hasSize(1)
    }

    @Test
    fun `search does not run without a per-turn confirmation`() = runTest {
        val search = FakeSearchProvider()
        val context = coordinator(
            settings = PerceptionSettings(
                webSearchEnabled = true,
                searxngUrl = "https://search.example",
            ),
            search = search,
        ).build("查一下张伟的失信记录")

        assertThat(search.calls).isEqualTo(0)
        assertThat(context.search).isNull()
        assertThat(context.notes.joinToString()).contains("未确认搜索词")
    }

    @Test
    fun `confirmed private entity query is sent exactly as edited`() = runTest {
        val search = FakeSearchProvider()
        coordinator(
            settings = PerceptionSettings(
                webSearchEnabled = true,
                searxngUrl = "https://search.example",
            ),
            search = search,
        ).build(
            query = "查一下张伟的失信记录",
            approvedSearchQuery = "失信被执行人 公开信息",
        )

        assertThat(search.calls).isEqualTo(1)
        assertThat(search.lastQuery).isEqualTo("失信被执行人 公开信息")
    }

    @Test
    fun `only relevant allowlisted notification is included`() = runTest {
        val notifications = FakeNotificationStore(
            events = listOf(
                event("parcel", NotificationEventKind.PARCEL_READY, "有包裹到达，等待领取"),
                event("meeting", NotificationEventKind.CALENDAR_UPDATE, "下午开组会"),
            ),
        )
        val context = coordinator(
            notifications = notifications,
            settings = PerceptionSettings(),
        ).build("我下课去吃饭，快递到了吗")

        assertThat(context.notifications.map { it.id }).containsExactly("parcel")
    }

    @Test
    fun `foreground app is read only when enabled and explicitly requested`() = runTest {
        val foreground = FakeForegroundProvider()
        val coordinator = coordinator(
            settings = PerceptionSettings(foregroundAppEnabled = true),
            foreground = foreground,
        )

        assertThat(coordinator.build("今天有点累").foregroundApp).isNull()
        assertThat(foreground.calls).isEqualTo(0)

        val context = coordinator.build("看到我在用什么应用吗")
        assertThat(foreground.calls).isEqualTo(1)
        assertThat(context.foregroundApp!!.appLabel).isEqualTo("阅读")
    }

    @Test
    fun `revoked notification access blocks stored events`() = runTest {
        val context = coordinator(
            notifications = FakeNotificationStore(
                events = listOf(
                    event("parcel", NotificationEventKind.PARCEL_READY, "有包裹到达"),
                ),
            ),
            access = PerceptionAccessStatus(
                notificationAccess = false,
                usageAccess = true,
            ),
        ).build("快递到了吗")

        assertThat(context.notifications).isEmpty()
    }

    @Test
    fun `disabled notification source blocks old events`() = runTest {
        val context = coordinator(
            notifications = FakeNotificationStore(
                events = listOf(
                    event("parcel", NotificationEventKind.PARCEL_READY, "有包裹到达"),
                ),
            ),
            notificationSources = FakeNotificationSourceSettings(emptySet()),
        ).build("快递到了吗")

        assertThat(context.notifications).isEmpty()
    }

    @Test
    fun `private narrative is stripped before search provider`() = runTest {
        val search = FakeSearchProvider()
        coordinator(
            settings = PerceptionSettings(
                webSearchEnabled = true,
                searxngUrl = "https://search.example",
            ),
            search = search,
        ).build(
            query = "我今天和同事吵架了，帮我查一下怎么放松",
            approvedSearchQuery = "怎么放松",
        )

        assertThat(search.calls).isEqualTo(1)
        assertThat(search.lastQuery).isEqualTo("怎么放松")
    }

    @Test
    fun `empty search result does not claim success`() = runTest {
        val context = coordinator(
            settings = PerceptionSettings(
                webSearchEnabled = true,
                searxngUrl = "https://search.example",
            ),
            search = FakeSearchProvider(empty = true),
        ).build(
            query = "查一下今天的新闻",
            approvedSearchQuery = "今天的新闻",
        )

        assertThat(context.search).isNull()
        assertThat(context.notes.joinToString()).contains("没有找到可用的搜索结果")
    }

    private fun coordinator(
        settings: PerceptionSettings = PerceptionSettings(),
        notifications: FakeNotificationStore = FakeNotificationStore(),
        notificationSources: NotificationSourceSettings = FakeNotificationSourceSettings(),
        access: PerceptionAccessStatus = PerceptionAccessStatus(true, true),
        foreground: FakeForegroundProvider = FakeForegroundProvider(),
        network: FakeNetworkLocationProvider = FakeNetworkLocationProvider(),
        weather: FakeWeatherProvider = FakeWeatherProvider(),
        search: FakeSearchProvider = FakeSearchProvider(),
    ) = PerceptionContextCoordinator(
        settingsStore = FakePerceptionSettingsStore(settings),
        notificationStore = notifications,
        notificationSourceSettings = notificationSources,
        accessChecker = FakeAccessChecker(access),
        foregroundAppProvider = foreground,
        networkLocationProvider = network,
        weatherProvider = weather,
        searchProvider = search,
    )

    private fun event(id: String, kind: NotificationEventKind, summary: String) =
        NotificationEvent(
            id = id,
            dedupeKey = id,
            sourcePackage = "com.example",
            sourceAppLabel = "示例应用",
            kind = kind,
            title = "",
            summary = summary,
            observedAt = 1_000,
            expiresAt = Long.MAX_VALUE,
            confidence = 0.9,
        )
}

private class FakeNotificationSourceSettings(
    allowed: Set<String> = setOf("com.example"),
) : NotificationSourceSettings {
    override val allowedPackages = MutableStateFlow(allowed)

    override fun isAllowed(packageName: String): Boolean =
        allowedPackages.value.contains(packageName)

    override fun setAllowed(packageName: String, allowed: Boolean) {
        allowedPackages.value = allowedPackages.value.toMutableSet().apply {
            if (allowed) add(packageName) else remove(packageName)
        }
    }
}

private class FakeAccessChecker(
    private val status: PerceptionAccessStatus,
) : PerceptionAccessChecker {
    override fun status(): PerceptionAccessStatus = status
}

private class FakePerceptionSettingsStore(
    private val value: PerceptionSettings,
) : PerceptionSettingsStore {
    override val settings: Flow<PerceptionSettings> = MutableStateFlow(value)

    override fun load(): PerceptionSettings = value

    override suspend fun save(settings: PerceptionSettings) = Unit
}

private class FakeNotificationStore(
    private val events: List<NotificationEvent> = emptyList(),
) : NotificationObservationStore {
    private val eventState = MutableStateFlow(events)
    private val sourceState = MutableStateFlow<List<NotificationSource>>(emptyList())

    override suspend fun emit(event: NotificationEvent) = Unit

    override fun observeActiveEvents(): Flow<List<NotificationEvent>> = eventState

    override fun observeSources(): Flow<List<NotificationSource>> = sourceState

    override suspend fun activeEvents(now: Long, limit: Int): List<NotificationEvent> =
        events.filter { it.expiresAt > now }.take(limit)

    override suspend fun recordSource(source: NotificationSource) = Unit

    override suspend fun deleteEvent(id: String) = Unit

    override suspend fun clearEvents() = Unit

    override suspend fun clearSources() = Unit

    override suspend fun pruneExpired(now: Long) = Unit
}

private class FakeForegroundProvider : ForegroundAppProvider {
    var calls = 0

    override suspend fun current(): ForegroundAppSnapshot {
        calls += 1
        return ForegroundAppSnapshot(
            packageName = "com.example.reader",
            appLabel = "阅读",
            observedAt = 1,
        )
    }
}

private class FakeNetworkLocationProvider(
    private val error: Throwable? = null,
) : CoarseNetworkLocationProvider {
    var calls = 0

    override suspend fun current(): CoarseNetworkLocation? {
        calls += 1
        error?.let { throw it }
        return CoarseNetworkLocation(
            networkType = "wifi",
            city = "苏州",
            region = "江苏",
            country = "中国",
            confidence = 0.45,
            estimatedAt = 1,
        )
    }
}

private class FakeWeatherProvider(
    private val failure: Throwable? = null,
) : WeatherProvider {
    var requestedCity: String? = null

    override suspend fun current(city: String): Result<WeatherSnapshot> {
        requestedCity = city
        failure?.let { return Result.failure(it) }
        return Result.success(
            WeatherSnapshot(
                city = city,
                region = "江苏",
                country = "中国",
                condition = "晴",
                temperatureCelsius = 18.0,
                apparentTemperatureCelsius = 17.0,
                humidityPercent = 60,
                precipitationMm = 0.0,
                windSpeedKph = 8.0,
                observedAt = "2026-11-25T12:00:00+08:00",
            ),
        )
    }
}

private class FakeSearchProvider(
    private val empty: Boolean = false,
) : WebSearchProvider {
    var calls = 0
    var lastQuery: String? = null

    override suspend fun search(
        baseUrl: String,
        query: String,
        maxResults: Int,
    ): Result<WebSearchResponse> {
        calls += 1
        lastQuery = query
        return Result.success(
            WebSearchResponse(
                query = query,
                results = if (empty) {
                    emptyList()
                } else {
                    listOf(
                        WebSearchResult(
                            title = "测试结果",
                            url = "https://example.com/result",
                            snippet = "摘要",
                        ),
                    )
                },
                searchedAt = 1,
            ),
        )
    }
}
