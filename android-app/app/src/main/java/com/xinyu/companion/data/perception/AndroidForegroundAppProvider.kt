package com.xinyu.companion.data.perception

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.xinyu.companion.domain.perception.ForegroundAppProvider
import com.xinyu.companion.domain.perception.ForegroundAppSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AndroidForegroundAppProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ForegroundAppProvider {
    override suspend fun current(): ForegroundAppSnapshot? = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) return@withContext null
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return@withContext null
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - LOOKBACK_MS, now)
        val usageEvent = UsageEvents.Event()
        var packageName: String? = null
        var observedAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(usageEvent)
            val isForeground = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    usageEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED

                else -> usageEvent.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val isBackground = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    usageEvent.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                        usageEvent.eventType == UsageEvents.Event.ACTIVITY_STOPPED

                else -> usageEvent.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            }
            if (isForeground && usageEvent.packageName != context.packageName) {
                packageName = usageEvent.packageName
                observedAt = usageEvent.timeStamp
            } else if (isBackground && usageEvent.packageName == packageName) {
                packageName = null
                observedAt = usageEvent.timeStamp
            }
        }
        packageName?.takeIf { now - observedAt <= FRESHNESS_MS }?.let {
            ForegroundAppSnapshot(
                packageName = it,
                appLabel = resolveLabel(it),
                observedAt = observedAt,
            )
        }
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun resolveLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val LOOKBACK_MS = 10L * 60L * 1_000L
        const val FRESHNESS_MS = 2L * 60L * 1_000L
    }
}
