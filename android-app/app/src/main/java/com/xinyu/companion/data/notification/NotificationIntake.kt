package com.xinyu.companion.data.notification

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import com.xinyu.companion.domain.perception.NotificationEvent
import com.xinyu.companion.domain.perception.NotificationObservationStore
import com.xinyu.companion.domain.perception.NotificationSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationIntake(
    private val context: Context,
    internal val allowlistStore: NotificationAllowlistStore,
    internal val sink: NotificationObservationStore,
    private val parser: NotificationParser,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val labelCache = ConcurrentHashMap<String, String>()

    fun onPosted(notification: StatusBarNotification) {
        val packageName = notification.packageName ?: return
        if (packageName == context.packageName) return
        val extras = notification.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" ")
        val dedupeKey = notification.key ?: notification.id.toString()
        val now = System.currentTimeMillis()
        scope.launch {
            runCatching {
                process(
                    packageName = packageName,
                    dedupeKey = dedupeKey,
                    title = title,
                    text = text,
                    observedAt = now,
                )
            }
        }
    }

    suspend fun process(
        packageName: String,
        dedupeKey: String,
        title: CharSequence?,
        text: CharSequence?,
        observedAt: Long,
    ) {
        val appLabel = labelCache.getOrPut(packageName) { resolveLabel(packageName) }

        // Source discovery stores metadata only. Title and body are discarded until
        // the user explicitly allows the package.
        sink.recordSource(
            NotificationSource(
                packageName = packageName,
                appLabel = appLabel,
                lastSeenAt = observedAt,
            ),
        )
        if (!allowlistStore.isAllowed(packageName)) return

        val event = parser.parse(
            dedupeKey = dedupeKey,
            sourcePackage = packageName,
            sourceAppLabel = appLabel,
            title = title,
            text = text,
            observedAt = observedAt,
        ) ?: return
        sink.emit(event)
    }

    suspend fun activeEvents(now: Long = System.currentTimeMillis()): List<NotificationEvent> =
        sink.activeEvents(now)

    private fun resolveLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrElse {
        if (it is PackageManager.NameNotFoundException) packageName else packageName
    }
}
