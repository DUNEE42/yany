package com.xinyu.companion.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.xinyu.companion.domain.reminder.ReminderAlarmScheduler
import com.xinyu.companion.domain.reminder.ReminderRule

class AndroidReminderAlarmScheduler(
    private val context: Context,
) : ReminderAlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(rule: ReminderRule) {
        val pendingIntent = firePendingIntent(rule)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            rule.triggerAtMillis,
            pendingIntent,
        )
    }

    override fun cancel(id: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            data = ReminderReceiver.ruleUri(id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(id),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun firePendingIntent(rule: ReminderRule): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            data = ReminderReceiver.ruleUri(rule.id)
            putExtra(ReminderReceiver.EXTRA_RULE_ID, rule.id)
            putExtra(ReminderReceiver.EXTRA_MESSAGE, rule.message)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(rule.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(id: String): Int = id.hashCode()
}
