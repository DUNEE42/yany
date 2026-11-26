package com.xinyu.companion.data.reminder

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinyu.companion.domain.reminder.ReminderCoordinator
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRuntimeTest {
    @Test
    fun encryptedStoreSchedulesAndDeliversLocalNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission()
        ReminderNotifications.ensureChannel(context)
        val store = EncryptedReminderStore(context)
        val coordinator = ReminderCoordinator(
            store = store,
            scheduler = AndroidReminderAlarmScheduler(context),
        )
        val triggerAt = System.currentTimeMillis() + Duration.ofSeconds(3).toMillis()
        val rule = coordinator.create("运行时提醒测试", triggerAt).getOrThrow()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        try {
            val delivered = waitUntil(Duration.ofSeconds(15)) {
                notificationManager.activeNotifications.any { it.id == rule.id.hashCode() }
            }

            assertTrue(delivered)
            assertFalse(store.loadAll().any { it.id == rule.id })
        } finally {
            notificationManager.cancel(rule.id.hashCode())
            coordinator.cancel(rule.id)
        }
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private fun waitUntil(timeout: Duration, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }
}
