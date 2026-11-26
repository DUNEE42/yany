package com.xinyu.companion.domain.perception

data class PerceptionAccessStatus(
    val notificationAccess: Boolean,
    val usageAccess: Boolean,
)

interface PerceptionAccessChecker {
    fun status(): PerceptionAccessStatus
}
