package com.xinyu.companion.domain.settings

import com.xinyu.companion.domain.provider.ProviderConfig

interface ProviderSettingsStore {
    fun load(): ProviderConfig

    fun save(config: ProviderConfig)

    fun clearApiKey()
}
