package com.xinyu.companion.data.notification

import com.xinyu.companion.domain.perception.NotificationEvent
import com.xinyu.companion.domain.perception.NotificationEventKind
import com.xinyu.companion.domain.perception.NotificationSensitivity
import com.xinyu.companion.domain.perception.UntrustedTextPolicy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class NotificationParser(
    private val sanitizer: NotificationTextSanitizer = NotificationTextSanitizer,
) {
    fun parse(
        dedupeKey: String,
        sourcePackage: String,
        sourceAppLabel: String,
        title: CharSequence?,
        text: CharSequence?,
        observedAt: Long,
    ): NotificationEvent? {
        val sanitized = sanitizer.sanitize(title, text)
        val combined = "${sanitized.title} ${sanitized.text}".trim()
        if (combined.isBlank()) return null
        if (UntrustedTextPolicy.containsInstructionLikeText(combined)) return null

        val kind = classify(combined)
        // We intentionally do not persist generic notification prose. Allowing an
        // app is not blanket consent for every private notification from that app.
        if (kind == NotificationEventKind.GENERIC) return null
        val confidence = when (kind) {
            NotificationEventKind.PARCEL_READY -> {
                if (PARCEL_ACTION.containsMatchIn(combined)) 0.92 else 0.78
            }

            NotificationEventKind.COURSE_UPDATE -> 0.85
            NotificationEventKind.CALENDAR_UPDATE -> 0.8
            NotificationEventKind.GENERIC -> 0.55
        }
        val summary = when (kind) {
            NotificationEventKind.PARCEL_READY -> buildParcelSummary(
                sourceAppLabel = sourceAppLabel,
                text = combined,
            )

            else -> combined.take(SUMMARY_LIMIT)
        }.trim()
        if (summary.isBlank()) return null

        val eventId = stableId(
            "$sourcePackage|$dedupeKey|${sanitized.title}|${sanitized.text}",
        )
        return NotificationEvent(
            id = eventId,
            dedupeKey = "$sourcePackage|$dedupeKey",
            sourcePackage = sourcePackage,
            sourceAppLabel = sourceAppLabel,
            kind = kind,
            title = sanitized.title.take(TITLE_LIMIT),
            summary = summary,
            observedAt = observedAt,
            expiresAt = observedAt + retentionMillis(kind),
            confidence = confidence,
            sensitivity = sanitized.sensitivity,
        )
    }

    private fun classify(text: String): NotificationEventKind = when {
        PARCEL_WORD.containsMatchIn(text) -> NotificationEventKind.PARCEL_READY
        COURSE_WORD.containsMatchIn(text) -> NotificationEventKind.COURSE_UPDATE
        CALENDAR_WORD.containsMatchIn(text) -> NotificationEventKind.CALENDAR_UPDATE
        else -> NotificationEventKind.GENERIC
    }

    private fun buildParcelSummary(sourceAppLabel: String, text: String): String {
        val status = when {
            text.contains("已签收") -> "有包裹显示已签收"
            PARCEL_ACTION.containsMatchIn(text) -> "有快递或包裹到达，等待领取"
            else -> "收到一条快递或包裹动态"
        }
        return if (sourceAppLabel.isBlank()) status else "$status（$sourceAppLabel）"
    }

    private fun retentionMillis(kind: NotificationEventKind): Long = when (kind) {
        NotificationEventKind.PARCEL_READY -> TimeUnit.HOURS.toMillis(72)
        NotificationEventKind.COURSE_UPDATE -> TimeUnit.HOURS.toMillis(48)
        NotificationEventKind.CALENDAR_UPDATE -> TimeUnit.HOURS.toMillis(48)
        NotificationEventKind.GENERIC -> TimeUnit.HOURS.toMillis(24)
    }

    private fun stableId(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
            .ifBlank { UUID.randomUUID().toString() }

    private companion object {
        val PARCEL_WORD = Regex("""(?:快递|包裹|取件|驿站|丰巢|菜鸟)""")
        val PARCEL_ACTION = Regex("""(?:已到|到达|待取|领取|取件|送达|签收)""")
        val COURSE_WORD = Regex("""(?:课程|上课|下课|教室|课表|课堂)""")
        val CALENDAR_WORD = Regex("""(?:日程|会议|预约|提醒|待办)""")
        const val TITLE_LIMIT = 120
        const val SUMMARY_LIMIT = 240
    }
}
