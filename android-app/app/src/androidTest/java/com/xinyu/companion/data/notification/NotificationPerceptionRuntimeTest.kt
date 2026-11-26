package com.xinyu.companion.data.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPerceptionRuntimeTest {
    @Test
    fun hiltEntryPointProvidesPersistentNotificationStore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = NotificationPerceptionRuntime.eventStore(context)
        val allowlist = NotificationAllowlistStore(context)
        val packageName = "com.example.runtime.test"

        try {
            store.clearEvents()
            store.clearSources()
            allowlist.setAllowed(packageName, true)
            NotificationPerceptionRuntime.get(context).process(
                packageName = packageName,
                dedupeKey = "runtime-test",
                title = "包裹已到",
                text = "包裹已到驿站，取件码 123456",
                observedAt = System.currentTimeMillis(),
            )

            val event = store.activeEvents(System.currentTimeMillis()).single()
            assertEquals(packageName, event.sourcePackage)
            assertFalse(event.summary.contains("123456"))
        } finally {
            allowlist.setAllowed(packageName, false)
            store.clearEvents()
            store.clearSources()
            store.pruneExpired(System.currentTimeMillis())
        }
    }
}
