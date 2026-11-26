package com.xinyu.companion.data.perception

import android.content.Context
import com.xinyu.companion.data.notification.NotificationAccess
import com.xinyu.companion.domain.perception.PerceptionAccessChecker
import com.xinyu.companion.domain.perception.PerceptionAccessStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidPerceptionAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundProvider: AndroidForegroundAppProvider,
) : PerceptionAccessChecker {
    override fun status(): PerceptionAccessStatus = PerceptionAccessStatus(
        notificationAccess = NotificationAccess.isEnabled(context),
        usageAccess = foregroundProvider.hasUsageAccess(),
    )
}
