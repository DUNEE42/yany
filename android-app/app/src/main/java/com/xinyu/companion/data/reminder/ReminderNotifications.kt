package com.xinyu.companion.data.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xinyu.companion.MainActivity
import com.xinyu.companion.R

object ReminderNotifications {
    const val CHANNEL_ID = "yanyu_reminders"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "本地提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "你在言语里设置的定时提醒"
        }
        manager.createNotificationChannel(channel)
    }

    fun enabled(context: Context): Boolean {
        ensureChannel(context)
        val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelEnabled = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
            ?.importance != NotificationManager.IMPORTANCE_NONE
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return appEnabled && channelEnabled && permissionGranted
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, ruleId: String, message: String) {
        if (!enabled(context)) return
        val openApp = PendingIntent.getActivity(
            context,
            ruleId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            (ruleId.hashCode() * 31),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_DISMISS
                data = ReminderReceiver.ruleUri(ruleId)
                putExtra(ReminderReceiver.EXTRA_RULE_ID, ruleId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val companionName = com.xinyu.companion.data.settings
            .SharedPreferencesCompanionProfileStore(context)
            .load()
            .displayName
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle("${companionName}提醒你")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, "知道了", dismiss)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(ruleId.hashCode(), notification)
        }
    }
}
