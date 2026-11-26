package com.xinyu.companion.domain.perception

import kotlinx.coroutines.flow.StateFlow

interface NotificationSourcePolicy {
    fun isAllowed(packageName: String): Boolean
}

interface NotificationSourceSettings : NotificationSourcePolicy {
    val allowedPackages: StateFlow<Set<String>>

    fun setAllowed(packageName: String, allowed: Boolean)
}
