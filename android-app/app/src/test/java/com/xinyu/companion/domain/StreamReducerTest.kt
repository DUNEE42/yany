package com.xinyu.companion.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamReducerTest {
    @Test
    fun `accumulates deltas and preserves safety signal`() {
        val state = StreamSnapshot()
            .reduce(GenerationEvent.Delta("你"))
            .reduce(GenerationEvent.Delta("好。"))
            .reduce(GenerationEvent.Safety("reminder", "show_pause_hint"))

        assertThat(state.text).isEqualTo("你好。")
        assertThat(state.safetyLevel).isEqualTo("reminder")
        assertThat(state.completed).isFalse()
    }

    @Test
    fun `failure completes stream with a visible error`() {
        val state = StreamSnapshot()
            .reduce(GenerationEvent.Delta("部分回复"))
            .reduce(GenerationEvent.Failed("network", "连接中断", true))

        assertThat(state.text).isEqualTo("部分回复")
        assertThat(state.completed).isTrue()
        assertThat(state.error).isEqualTo("连接中断")
    }
}

