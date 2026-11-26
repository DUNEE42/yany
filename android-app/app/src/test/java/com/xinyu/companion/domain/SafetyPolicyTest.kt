package com.xinyu.companion.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafetyPolicyTest {
    private val policy = SafetyPolicy()

    @Test
    fun `detects crisis language`() {
        val result = policy.assess("我真的不想活了")

        assertThat(result.level).isEqualTo("crisis")
        assertThat(result.action).isEqualTo("show_support_resources")
        assertThat(result.instruction).contains("安全")
    }

    @Test
    fun `detects paraphrased self harm intent`() {
        val result = policy.assess("我觉得自己快撑不住了，想消失")

        assertThat(result.level).isEqualTo("distress")
        assertThat(result.action).isEqualTo("support_first")
        assertThat(result.instruction).contains("安全")
    }

    @Test
    fun `does not treat a negated phrase as intent`() {
        val result = policy.assess("我没有想自杀，只是最近压力很大")

        assertThat(result.level).isEqualTo("normal")
    }

    @Test
    fun `detects intent inside a softer sentence`() {
        val result = policy.assess("最近真的很累，我有点想死")

        assertThat(result.level).isEqualTo("crisis")
    }

    @Test
    fun `does not confuse fear of death with intent`() {
        val result = policy.assess("我不想死，我只是不知道该怎么办")

        assertThat(result.level).isEqualTo("normal")
    }

    @Test
    fun `keeps normal conversation normal`() {
        val result = policy.assess("今天天气怎么样？")

        assertThat(result.level).isEqualTo("normal")
        assertThat(result.action).isEqualTo("none")
        assertThat(result.instruction).isEmpty()
    }
}
