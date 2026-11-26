package com.xinyu.companion.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.xinyu.companion.domain.reminder.ReminderRuleStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject
    lateinit var store: ReminderRuleStore

    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
        when (intent.action) {
            ACTION_FIRE -> {
                val rule = runCatching {
                    store.loadAll().firstOrNull { it.id == ruleId }
                }.getOrNull() ?: return
                if (!ReminderNotifications.enabled(context)) return
                ReminderNotifications.show(context, rule.id, rule.message)
                runCatching { store.remove(rule.id) }
            }

            ACTION_DISMISS -> {
                NotificationManagerCompat.from(context).cancel(ruleId.hashCode())
                runCatching { store.remove(ruleId) }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.xinyu.companion.reminder.FIRE"
        const val ACTION_DISMISS = "com.xinyu.companion.reminder.DISMISS"
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_MESSAGE = "message"

        fun ruleUri(id: String) = android.net.Uri.parse("yanyu://reminder/$id")
    }
}
