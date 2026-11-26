package com.xinyu.companion.domain.settings

import com.xinyu.companion.domain.CompanionProfile
import kotlinx.coroutines.flow.StateFlow

interface CompanionProfileStore {
    val profile: StateFlow<CompanionProfile>

    fun load(): CompanionProfile

    fun save(profile: CompanionProfile): CompanionProfile
}
