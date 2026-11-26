package com.xinyu.companion.data.notification

import android.content.Context
import com.xinyu.companion.domain.perception.NotificationSourceSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class NotificationAllowlistStore @Inject constructor(
    @ApplicationContext context: Context,
) : NotificationSourceSettings {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableAllowedPackages = MutableStateFlow(loadAllowedPackages())

    override val allowedPackages: StateFlow<Set<String>> = mutableAllowedPackages.asStateFlow()

    override fun isAllowed(packageName: String): Boolean =
        preferences.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())
            .orEmpty()
            .contains(packageName)

    override fun setAllowed(packageName: String, allowed: Boolean) {
        val clean = packageName.trim()
        if (clean.isBlank()) return
        val updated = mutableAllowedPackages.value.toMutableSet().apply {
            if (allowed) add(clean) else remove(clean)
        }
        preferences.edit().putStringSet(KEY_ALLOWED_PACKAGES, updated).apply()
        mutableAllowedPackages.value = updated
    }

    private fun loadAllowedPackages(): Set<String> =
        preferences.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()

    private companion object {
        const val PREFERENCES_NAME = "xinyu_notification_perception"
        const val KEY_ALLOWED_PACKAGES = "allowed_packages"
    }
}
