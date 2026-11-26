package com.xinyu.companion.data.settings

import android.content.Context
import com.xinyu.companion.domain.perception.PerceptionSettings
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SharedPreferencesPerceptionSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) : PerceptionSettingsStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    override val settings = state.asStateFlow()

    override fun load(): PerceptionSettings = read()

    override suspend fun save(settings: PerceptionSettings) {
        val clean = settings.copy(
            searxngUrl = settings.searxngUrl.trim().trimEnd('/'),
            preferredCity = settings.preferredCity.trim().take(MAX_CITY_CHARS),
        )
        preferences.edit()
            .putBoolean(KEY_FOREGROUND_APP, clean.foregroundAppEnabled)
            .putBoolean(KEY_COARSE_LOCATION, clean.coarseLocationEnabled)
            .putBoolean(KEY_WEB_SEARCH, clean.webSearchEnabled)
            .putString(KEY_SEARXNG_URL, clean.searxngUrl)
            .putString(KEY_PREFERRED_CITY, clean.preferredCity)
            .apply()
        state.value = clean
    }

    private fun read(): PerceptionSettings = PerceptionSettings(
        foregroundAppEnabled = preferences.getBoolean(KEY_FOREGROUND_APP, false),
        coarseLocationEnabled = preferences.getBoolean(KEY_COARSE_LOCATION, false),
        webSearchEnabled = preferences.getBoolean(KEY_WEB_SEARCH, false),
        searxngUrl = preferences.getString(KEY_SEARXNG_URL, "").orEmpty(),
        preferredCity = preferences.getString(KEY_PREFERRED_CITY, "").orEmpty(),
    )

    private companion object {
        const val PREFERENCES_NAME = "xinyu_perception_settings"
        const val KEY_FOREGROUND_APP = "foreground_app_enabled"
        const val KEY_COARSE_LOCATION = "coarse_location_enabled"
        const val KEY_WEB_SEARCH = "web_search_enabled"
        const val KEY_SEARXNG_URL = "searxng_url"
        const val KEY_PREFERRED_CITY = "preferred_city"
        const val MAX_CITY_CHARS = 80
    }
}
