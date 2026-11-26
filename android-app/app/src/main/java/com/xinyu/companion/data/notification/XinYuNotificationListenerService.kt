package com.xinyu.companion.data.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class XinYuNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            activeNotifications?.forEach(::onNotificationPosted)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        NotificationPerceptionRuntime.get(applicationContext).onPosted(sbn)
    }
}
