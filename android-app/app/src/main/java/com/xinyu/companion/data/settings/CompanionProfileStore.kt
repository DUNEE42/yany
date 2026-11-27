package com.xinyu.companion.data.settings

import android.content.Context
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.settings.CompanionProfileStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedPreferencesCompanionProfileStore(context: Context) : CompanionProfileStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableProfile = MutableStateFlow(readProfile())

    override val profile: StateFlow<CompanionProfile> = mutableProfile.asStateFlow()

    @Synchronized
    override fun load(): CompanionProfile {
        val current = readProfile()
        mutableProfile.value = current
        return current
    }

    @Synchronized
    override fun save(profile: CompanionProfile): CompanionProfile {
        val normalized = normalize(profile)
        preferences.edit()
            .putString(KEY_NAME, normalized.name)
            .apply {
                if (normalized.avatarPath.isNullOrBlank()) {
                    remove(KEY_AVATAR_PATH)
                } else {
                    putString(KEY_AVATAR_PATH, normalized.avatarPath)
                }
            }
            .apply()
        mutableProfile.value = normalized
        return normalized
    }

    private fun readProfile(): CompanionProfile {
        val rawName = preferences.getString(KEY_NAME, null)
        val rawAvatarPath = preferences.getString(KEY_AVATAR_PATH, null)
            ?.takeIf { path ->
                path.isNotBlank() && File(path).let { it.exists() && it.isFile && it.length() > 0 }
            }
        return normalize(
            CompanionProfile(
                name = rawName ?: CompanionProfile.DEFAULT_NAME,
                avatarPath = rawAvatarPath,
            ),
        )
    }

    private fun normalize(profile: CompanionProfile): CompanionProfile {
        val cleanName = profile.name
            .trim()
            .take(CompanionProfile.MAX_NAME_LENGTH)
            .ifBlank { CompanionProfile.DEFAULT_NAME }
        val cleanAvatarPath = profile.avatarPath?.trim()?.takeIf(String::isNotBlank)
        return CompanionProfile(
            id = CompanionProfile.MAIN_COMPANION_ID,
            name = cleanName,
            avatarPath = cleanAvatarPath,
            persona = profile.persona,
            speakingStyle = profile.speakingStyle,
            boundaries = profile.boundaries,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "xinyu_companion_profile"
        const val KEY_NAME = "companion_name"
        const val KEY_AVATAR_PATH = "companion_avatar_path"
    }
}
