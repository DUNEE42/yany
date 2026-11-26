package com.xinyu.companion.data.notification

import android.annotation.SuppressLint
import android.content.Context
import com.xinyu.companion.domain.perception.NotificationObservationStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

object NotificationPerceptionRuntime {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var intake: NotificationIntake? = null

    fun get(context: Context): NotificationIntake {
        val current = intake
        if (current != null) return current
        return synchronized(this) {
            intake ?: create(context.applicationContext).also { intake = it }
        }
    }

    fun eventStore(context: Context): NotificationObservationStore =
        get(context).sink

    fun allowlist(context: Context): NotificationAllowlistStore =
        get(context).allowlistStore

    private fun create(context: Context): NotificationIntake {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationRuntimeEntryPoint::class.java,
        )
        return NotificationIntake(
            context = context,
            allowlistStore = entryPoint.allowlistStore(),
            sink = entryPoint.notificationStore(),
            parser = NotificationParser(),
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationRuntimeEntryPoint {
    fun allowlistStore(): NotificationAllowlistStore

    fun notificationStore(): NotificationObservationStore
}
