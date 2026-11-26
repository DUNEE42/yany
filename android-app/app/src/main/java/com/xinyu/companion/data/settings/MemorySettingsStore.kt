package com.xinyu.companion.data.settings

import android.content.Context
import com.xinyu.companion.domain.memory.MemorySettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SharedPreferencesMemorySettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) : MemorySettingsStore {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val enabled = MutableStateFlow(
        preferences.getBoolean(KEY_AUTO_MEMORY, true),
    )

    override val autoMemoryEnabled: Flow<Boolean> = enabled.asStateFlow()

    override suspend fun setAutoMemoryEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_MEMORY, enabled).apply()
        this.enabled.value = enabled
    }

    private companion object {
        const val PREFERENCES_NAME = "xinyu_memory_settings"
        const val KEY_AUTO_MEMORY = "auto_memory_enabled"
    }
}

