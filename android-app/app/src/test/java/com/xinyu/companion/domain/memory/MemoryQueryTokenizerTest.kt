package com.xinyu.companion.domain.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MemoryQueryTokenizerTest {
    @Test
    fun `long Chinese question yields useful retrieval terms`() {
        val terms = MemoryQueryTokenizer.terms("你还记得杭州的面试吗")

        assertThat(terms).containsAtLeast("杭州", "面试")
        assertThat(terms).doesNotContain("你还记得杭州的面试吗")
    }

    @Test
    fun `mixed Chinese and API names keep searchable pieces`() {
        val terms = MemoryQueryTokenizer.terms("我的 DeepSeek API 设置")

        assertThat(terms).containsAtLeast("deepseek", "api")
    }

    @Test
    fun `short topic with a sentence particle remains searchable`() {
        assertThat(MemoryQueryTokenizer.terms("茶呢")).contains("茶")
    }
}
