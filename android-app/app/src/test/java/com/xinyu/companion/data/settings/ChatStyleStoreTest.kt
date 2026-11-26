package com.xinyu.companion.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.ReplyLength
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatStyleStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `saved style round trips`() {
        val store = SharedPreferencesChatStyleStore(context)
        val style = ConversationStyle(
            temperature = 1.05,
            closeness = 80,
            replyLength = ReplyLength.DETAILED,
        )

        store.save(style)

        assertThat(store.load()).isEqualTo(style)
    }
}
