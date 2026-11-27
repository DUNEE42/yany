package com.xinyu.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as CommonMarkText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

internal sealed interface MarkdownBlock {
    data class HeadingBlock(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock
    data class ParagraphBlock(val inlines: List<MarkdownInline>) : MarkdownBlock
    data class BulletListBlock(val items: List<List<MarkdownInline>>) : MarkdownBlock
    data class OrderedListBlock(val startNumber: Int, val items: List<List<MarkdownInline>>) : MarkdownBlock
    data class QuoteBlock(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data object DividerBlock : MarkdownBlock
}

internal sealed interface MarkdownInline {
    data class Plain(val text: String) : MarkdownInline
    data class Bold(val children: List<MarkdownInline>) : MarkdownInline
    data class Italic(val children: List<MarkdownInline>) : MarkdownInline
    data class Strike(val children: List<MarkdownInline>) : MarkdownInline
    data class InlineCode(val code: String) : MarkdownInline
    data class SafeLink(val url: String, val children: List<MarkdownInline>) : MarkdownInline
    data object LineBreak : MarkdownInline
}

internal object MarkdownBlockParser {
    private val parser: Parser = Parser.builder()
        .extensions(listOf(StrikethroughExtension.create()))
        .build()

    private val rawHttpUrl = Regex("""https?://[^\s<>()\[\]]+""")

    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.isBlank()) return emptyList()
        val document = parser.parse(markdown)
        return collectBlocks(document)
    }

    fun sanitizeLinkUrl(raw: String): String? {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    private fun collectBlocks(parent: Node): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var node = parent.firstChild
        while (node != null) {
            when (node) {
                is Heading -> blocks += MarkdownBlock.HeadingBlock(
                    level = node.level.coerceIn(1, 6),
                    inlines = collectInlines(node),
                )
                is Paragraph -> blocks += MarkdownBlock.ParagraphBlock(
                    inlines = collectInlines(node),
                )
                is BulletList -> blocks += MarkdownBlock.BulletListBlock(
                    items = collectListItems(node),
                )
                is OrderedList -> blocks += MarkdownBlock.OrderedListBlock(
                    startNumber = (node.markerStartNumber ?: 1).coerceAtLeast(1),
                    items = collectListItems(node),
                )
                is BlockQuote -> blocks += MarkdownBlock.QuoteBlock(
                    blocks = collectBlocks(node),
                )
                is FencedCodeBlock -> blocks += MarkdownBlock.CodeBlock(
                    language = node.info?.trim()?.takeIf(String::isNotBlank),
                    code = node.literal.orEmpty().trimEnd('\n'),
                )
                is IndentedCodeBlock -> blocks += MarkdownBlock.CodeBlock(
                    language = null,
                    code = node.literal.orEmpty().trimEnd('\n'),
                )
                is ThematicBreak -> blocks += MarkdownBlock.DividerBlock
                else -> {
                    val nested = collectInlines(node)
                    if (nested.isNotEmpty()) {
                        blocks += MarkdownBlock.ParagraphBlock(nested)
                    }
                }
            }
            node = node.next
        }
        return blocks
    }

    private fun collectListItems(listNode: Node): List<List<MarkdownInline>> {
        val items = mutableListOf<List<MarkdownInline>>()
        var child = listNode.firstChild
        while (child != null) {
            if (child is ListItem) {
                val itemInlines = mutableListOf<MarkdownInline>()
                var block = child.firstChild
                while (block != null) {
                    if (itemInlines.isNotEmpty()) {
                        itemInlines += MarkdownInline.LineBreak
                    }
                    itemInlines += collectInlines(block)
                    block = block.next
                }
                items += itemInlines
            }
            child = child.next
        }
        return items
    }

    private fun collectInlines(parent: Node): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        var node = parent.firstChild
        while (node != null) {
            when (node) {
                is CommonMarkText -> result += splitAutoLinks(node.literal.orEmpty())
                is StrongEmphasis -> result += MarkdownInline.Bold(collectInlines(node))
                is Emphasis -> result += MarkdownInline.Italic(collectInlines(node))
                is Strikethrough -> result += MarkdownInline.Strike(collectInlines(node))
                is Code -> result += MarkdownInline.InlineCode(node.literal.orEmpty())
                is Link -> {
                    val safeUrl = sanitizeLinkUrl(node.destination.orEmpty())
                    val children = collectInlines(node)
                    if (safeUrl != null) {
                        result += MarkdownInline.SafeLink(
                            url = safeUrl,
                            children = children.ifEmpty { listOf(MarkdownInline.Plain(safeUrl)) },
                        )
                    } else {
                        result += children
                    }
                }
                is SoftLineBreak, is HardLineBreak -> result += MarkdownInline.LineBreak
                else -> result += collectInlines(node)
            }
            node = node.next
        }
        return result
    }

    private fun splitAutoLinks(raw: String): List<MarkdownInline> {
        if (!raw.contains("http://") && !raw.contains("https://")) {
            return if (raw.isEmpty()) emptyList() else listOf(MarkdownInline.Plain(raw))
        }
        val items = mutableListOf<MarkdownInline>()
        var cursor = 0
        rawHttpUrl.findAll(raw).forEach { match ->
            if (match.range.first > cursor) {
                items += MarkdownInline.Plain(raw.substring(cursor, match.range.first))
            }
            val cleaned = match.value.trimEnd('.', ',', ';', ')', ']', '。', '，', '；')
            val trailing = match.value.removePrefix(cleaned)
            val safe = sanitizeLinkUrl(cleaned)
            if (safe != null) {
                items += MarkdownInline.SafeLink(
                    url = safe,
                    children = listOf(MarkdownInline.Plain(cleaned)),
                )
            } else {
                items += MarkdownInline.Plain(cleaned)
            }
            if (trailing.isNotEmpty()) {
                items += MarkdownInline.Plain(trailing)
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            items += MarkdownInline.Plain(raw.substring(cursor))
        }
        return items
    }
}

@Composable
fun MarkdownMessageText(
    text: String,
    isUserMessage: Boolean,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { MarkdownBlockParser.parse(text) }
    val colorScheme = MaterialTheme.colorScheme
    val codeBg = if (isUserMessage) {
        colorScheme.onPrimary.copy(alpha = 0.16f)
    } else {
        colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val linkColor = if (isUserMessage) {
        colorScheme.onPrimary
    } else {
        colorScheme.primary
    }
    val quoteBarColor = if (isUserMessage) {
        colorScheme.onPrimary.copy(alpha = 0.45f)
    } else {
        colorScheme.primary.copy(alpha = 0.55f)
    }

    if (blocks.isEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            MarkdownBlockView(
                block = block,
                codeBg = codeBg,
                linkColor = linkColor,
                quoteBarColor = quoteBarColor,
            )
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    codeBg: Color,
    linkColor: Color,
    quoteBarColor: Color,
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge
    when (block) {
        is MarkdownBlock.HeadingBlock -> {
            val headingStyle = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                text = block.inlines.toAnnotatedString(codeBg, linkColor),
                style = headingStyle,
            )
        }

        is MarkdownBlock.ParagraphBlock -> {
            Text(
                text = block.inlines.toAnnotatedString(codeBg, linkColor),
                style = bodyStyle,
            )
        }

        is MarkdownBlock.BulletListBlock -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "•",
                            style = bodyStyle,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = item.toAnnotatedString(codeBg, linkColor),
                            style = bodyStyle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        is MarkdownBlock.OrderedListBlock -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${block.startNumber + index}.",
                            style = bodyStyle,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = item.toAnnotatedString(codeBg, linkColor),
                            style = bodyStyle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        is MarkdownBlock.QuoteBlock -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(quoteBarColor),
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    block.blocks.forEach { child ->
                        MarkdownBlockView(
                            block = child,
                            codeBg = codeBg,
                            linkColor = linkColor,
                            quoteBarColor = quoteBarColor,
                        )
                    }
                }
            }
        }

        is MarkdownBlock.CodeBlock -> {
            Surface(
                color = codeBg,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (!block.language.isNullOrBlank()) {
                        Text(
                            text = block.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Text(
                        text = block.code,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        ),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }

        MarkdownBlock.DividerBlock -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }
    }
}

private fun List<MarkdownInline>.toAnnotatedString(
    codeBg: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    appendInlines(this@toAnnotatedString, codeBg, linkColor)
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    codeBg: Color,
    linkColor: Color,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Plain -> append(inline.text)
            MarkdownInline.LineBreak -> append('\n')
            is MarkdownInline.Bold -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                appendInlines(inline.children, codeBg, linkColor)
                pop()
            }
            is MarkdownInline.Italic -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInlines(inline.children, codeBg, linkColor)
                pop()
            }
            is MarkdownInline.Strike -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInlines(inline.children, codeBg, linkColor)
                pop()
            }
            is MarkdownInline.InlineCode -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        background = codeBg,
                    ),
                )
                append(" ${inline.code} ")
                pop()
            }
            is MarkdownInline.SafeLink -> {
                pushLink(
                    LinkAnnotation.Url(
                        url = inline.url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                )
                appendInlines(inline.children, codeBg, linkColor)
                pop()
            }
        }
    }
}
