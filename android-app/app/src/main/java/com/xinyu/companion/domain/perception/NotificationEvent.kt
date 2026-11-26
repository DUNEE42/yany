package com.xinyu.companion.domain.perception

enum class NotificationEventKind {
    PARCEL_READY,
    COURSE_UPDATE,
    CALENDAR_UPDATE,
    GENERIC,
}

data class NotificationEvent(
    val id: String,
    val dedupeKey: String,
    val sourcePackage: String,
    val sourceAppLabel: String,
    val kind: NotificationEventKind,
    val title: String,
    val summary: String,
    val observedAt: Long,
    val expiresAt: Long,
    val confidence: Double,
    val sensitivity: NotificationSensitivity = NotificationSensitivity.NORMAL,
)

data class NotificationSource(
    val packageName: String,
    val appLabel: String,
    val lastSeenAt: Long,
)

enum class NotificationSensitivity {
    NORMAL,
    SENSITIVE,
}

data class SanitizedNotificationContent(
    val title: String,
    val text: String,
    val sensitivity: NotificationSensitivity,
)

interface NotificationEventSink {
    suspend fun emit(event: NotificationEvent)
}
