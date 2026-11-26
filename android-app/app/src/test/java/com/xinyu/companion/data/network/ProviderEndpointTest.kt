package com.xinyu.companion.data.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderEndpointTest {
    @Test
    fun `builds chat completions url from base`() {
        assertThat(chatCompletionsUrl("https://api.deepseek.com/"))
            .isEqualTo("https://api.deepseek.com/chat/completions")
    }

    @Test
    fun `does not duplicate chat completions path`() {
        assertThat(chatCompletionsUrl("https://api.deepseek.com/chat/completions"))
            .isEqualTo("https://api.deepseek.com/chat/completions")
    }
}
