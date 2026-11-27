package com.xinyu.companion.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `parses headings lists quotes code blocks and inline formatting`() {
        val markdown = """
            ## 今天安排

            这里有 **加粗**、*斜体*、~~删除~~ 和 `inline`。

            - 第一项
            - 第二项

            > 记得喝水

            ```kotlin
            val ok = true
            ```
        """.trimIndent()

        val blocks = MarkdownBlockParser.parse(markdown)

        assertThat(blocks.filterIsInstance<MarkdownBlock.HeadingBlock>()).hasSize(1)
        assertThat(blocks.filterIsInstance<MarkdownBlock.ParagraphBlock>()).hasSize(1)
        assertThat(blocks.filterIsInstance<MarkdownBlock.BulletListBlock>()).hasSize(1)
        assertThat(blocks.filterIsInstance<MarkdownBlock.QuoteBlock>()).hasSize(1)
        val codeBlock = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertThat(codeBlock.language).isEqualTo("kotlin")
        assertThat(codeBlock.code).isEqualTo("val ok = true")
    }

    @Test
    fun `allows http and https links but rejects dangerous url schemes`() {
        assertThat(MarkdownBlockParser.sanitizeLinkUrl("https://example.com/a"))
            .isEqualTo("https://example.com/a")
        assertThat(MarkdownBlockParser.sanitizeLinkUrl("http://example.com"))
            .isEqualTo("http://example.com")
        assertThat(MarkdownBlockParser.sanitizeLinkUrl("javascript:alert(1)")).isNull()
        assertThat(MarkdownBlockParser.sanitizeLinkUrl("intent://scan/#Intent;end")).isNull()
        assertThat(MarkdownBlockParser.sanitizeLinkUrl("file:///etc/passwd")).isNull()

        val blocks = MarkdownBlockParser.parse("[click](javascript:alert(1)) and https://example.com/ref")
        val paragraph = blocks.single() as MarkdownBlock.ParagraphBlock
        val links = paragraph.inlines.filterIsInstance<MarkdownInline.SafeLink>()
        assertThat(links).hasSize(1)
        assertThat(links.single().url).isEqualTo("https://example.com/ref")
    }
}
