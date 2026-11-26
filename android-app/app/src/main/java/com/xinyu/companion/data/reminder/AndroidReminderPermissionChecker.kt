package com.xinyu.companion.data.reminder

import android.content.Context
import com.xinyu.companion.domain.reminder.ReminderPermissionChecker

class AndroidReminderPermissionChecker(
    private val context: Context,
) : ReminderPermissionChecker {
    override fun areNotificationsEnabled(): Boolean = ReminderNotifications.enabled(context)
}
