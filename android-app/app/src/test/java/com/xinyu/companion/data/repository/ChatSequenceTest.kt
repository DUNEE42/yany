package com.xinyu.companion.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatSequenceTest {
    @Test
    fun `user and assistant get distinct consecutive sequences`() {
        assertThat(nextTurnSequences(7L)).isEqualTo(7L to 8L)
    }
}
