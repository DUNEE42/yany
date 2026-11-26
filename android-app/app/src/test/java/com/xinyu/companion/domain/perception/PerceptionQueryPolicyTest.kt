package com.xinyu.companion.domain.perception

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PerceptionQueryPolicyTest {
    @Test
    fun `recognizes weather and searchable freshness questions`() {
        assertThat(PerceptionQueryPolicy.wantsWeather("苏州今天天气怎么样")).isTrue()
        assertThat(PerceptionQueryPolicy.wantsWebSearch("帮我查一下今天有什么新闻")).isTrue()
        assertThat(PerceptionQueryPolicy.wantsWebSearch("陪我聊聊今天的心情")).isFalse()
    }

    @Test
    fun `does not send likely secrets to search`() {
        val query = "查一下银行卡 6222021234567890 的交易"

        assertThat(PerceptionQueryPolicy.wantsWebSearch(query)).isFalse()
        assertThat(PerceptionQueryPolicy.searchQuery(query)).isNull()
    }

    @Test
    fun `strips the search command and blocks private narrative`() {
        assertThat(PerceptionQueryPolicy.searchQuery("帮我查一下苏州明天的天气"))
            .isEqualTo("苏州明天的天气")
        assertThat(
            PerceptionQueryPolicy.searchQuery(
                "查一下我和同事吵架以后怎么放松",
            ),
        ).isNull()
    }

    @Test
    fun `suggests likely private proper nouns for explicit confirmation`() {
        assertThat(PerceptionQueryPolicy.suggestedSearchQuery("查一下张伟的失信记录"))
            .isEqualTo("张伟的失信记录")
        assertThat(PerceptionQueryPolicy.hasConfirmableSearchIntent("学校附近有什么好吃的"))
            .isFalse()
    }

    @Test
    fun `confirmed query still rejects hard secrets`() {
        assertThat(
            PerceptionQueryPolicy.normalizeConfirmedSearchQuery(
                "银行卡 6222021234567890",
            ),
        ).isNull()
        assertThat(PerceptionQueryPolicy.normalizeConfirmedSearchQuery(" 苏州天气 "))
            .isEqualTo("苏州天气")
    }

    @Test
    fun `recognizes notification and foreground app intents`() {
        assertThat(PerceptionQueryPolicy.wantsNotifications("我下课了，快递到了吗")).isTrue()
        assertThat(PerceptionQueryPolicy.wantsForegroundApp("看到我在用什么应用吗")).isTrue()
    }
}
