package com.xinyu.companion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "perception_events",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["expiresAt"]),
        Index(value = ["observedAt"]),
    ],
)
data class PerceptionEventEntity(
    @PrimaryKey val id: String,
    val dedupeKey: String,
    val sourcePackage: String,
    val sourceAppLabel: String,
    val kind: String,
    val title: String,
    val summary: String,
    val observedAt: Long,
    val expiresAt: Long,
    val confidence: Double,
    val sensitivity: String,
)

@Entity(tableName = "notification_sources")
data class NotificationSourceEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val lastSeenAt: Long,
)
