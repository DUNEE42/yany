package com.xinyu.companion.domain.settings

import com.xinyu.companion.domain.ConversationStyle

interface ChatStyleStore {
    fun load(): ConversationStyle

    fun save(style: ConversationStyle)
}
