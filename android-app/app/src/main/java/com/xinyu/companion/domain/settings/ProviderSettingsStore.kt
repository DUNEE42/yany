package com.xinyu.companion.domain.settings

import com.xinyu.companion.domain.provider.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ProviderSettingsStore {
    val configured: StateFlow<Boolean>
        get() = MutableStateFlow(load().isConfigured)

    fun load(): ProviderConfig

    fun save(config: ProviderConfig)

    fun clearApiKey()
}
