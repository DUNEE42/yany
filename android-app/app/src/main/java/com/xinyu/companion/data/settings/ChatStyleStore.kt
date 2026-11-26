package com.xinyu.companion.data.settings

import android.content.Context
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.ReplyLength
import com.xinyu.companion.domain.settings.ChatStyleStore
import kotlin.math.roundToInt

class SharedPreferencesChatStyleStore(context: Context) : ChatStyleStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): ConversationStyle {
        val temperature = (preferences
            .getFloat(KEY_TEMPERATURE, DEFAULT.temperature.toFloat())
            .toDouble()
            .coerceIn(
                ConversationStyle.MIN_TEMPERATURE,
                ConversationStyle.MAX_TEMPERATURE,
            ) * 100).roundToInt() / 100.0
        val closeness = preferences
            .getInt(KEY_CLOSENESS, DEFAULT.closeness)
            .coerceIn(0, 100)
        val replyLength = runCatching {
            ReplyLength.valueOf(
                preferences.getString(KEY_REPLY_LENGTH, DEFAULT.replyLength.name)
                    ?: DEFAULT.replyLength.name,
            )
        }.getOrDefault(DEFAULT.replyLength)

        return ConversationStyle(
            temperature = temperature,
            closeness = closeness,
            replyLength = replyLength,
        )
    }

    override fun save(style: ConversationStyle) {
        preferences.edit()
            .putFloat(KEY_TEMPERATURE, style.temperature.toFloat())
            .putInt(KEY_CLOSENESS, style.closeness)
            .putString(KEY_REPLY_LENGTH, style.replyLength.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "xinyu_chat_style"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_CLOSENESS = "closeness"
        const val KEY_REPLY_LENGTH = "reply_length"
        val DEFAULT = ConversationStyle()
    }
}
