package com.xinyu.companion.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xinyu.companion.domain.reminder.ReminderCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ReminderBootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var coordinator: ReminderCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching { coordinator.reschedulePending() }
        }
    }
}
