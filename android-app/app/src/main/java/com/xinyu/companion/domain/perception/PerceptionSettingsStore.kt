package com.xinyu.companion.domain.perception

import kotlinx.coroutines.flow.Flow

interface PerceptionSettingsStore {
    val settings: Flow<PerceptionSettings>

    fun load(): PerceptionSettings

    suspend fun save(settings: PerceptionSettings)
}
