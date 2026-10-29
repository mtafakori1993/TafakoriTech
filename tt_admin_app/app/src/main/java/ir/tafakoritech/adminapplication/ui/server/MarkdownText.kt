package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Block model ───────────────────────────────────────────────────────────────

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeFence(val language: String?, val code: String) : MdBlock()
    data class Paragraph(val content: AnnotatedString) : MdBlock()
    data class BulletList(val items: List<AnnotatedString>) : MdBlock()
    data class NumberedList(val items: List<AnnotatedString>) : MdBlock()
    data class BlockQuote(val content: AnnotatedString) : MdBlock()
    data object Rule : MdBlock()
}

// ── Public entry point ────────────────────────────────────────────────────────

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingView(block)
                is MdBlock.CodeFence -> CodeFenceView(block)
                is MdBlock.Paragraph -> Text(
                    text = block.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is MdBlock.BulletList -> BulletListView(block)
                is MdBlock.NumberedList -> NumberedListView(block)
                is MdBlock.BlockQuote -> BlockQuoteView(block)
                MdBlock.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

// ── Block renderers ───────────────────────────────────────────────────────────

@Composable
private fun HeadingView(block: MdBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        4 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = block.text,
        style = style,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = if (block.level <= 2) Modifier.padding(top = 4.dp, bottom = 2.dp) else Modifier,
    )
}

@Composable
private fun CodeFenceView(block: MdBlock.CodeFence) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E2E))
    ) {
        if (!block.language.isNullOrBlank()) {
            Text(
                text = block.language,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF888888),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 0.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = block.code.trimEnd('\n'),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
                color = Color(0xFFCDD6F4),
            )
        }
    }
}

@Composable
private fun BulletListView(block: MdBlock.BulletList) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEach { item ->
            Row {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun NumberedListView(block: MdBlock.NumberedList) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(26.dp),
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun BlockQuoteView(block: MdBlock.BlockQuote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = block.content,
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ── Block parser ──────────────────────────────────────────────────────────────

private fun parseBlocks(text: String): List<MdBlock> {
    val lines = text.lines()
    val result = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            line.isBlank() -> i++

            // Fenced code block
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines += lines[i]
                    i++
                }
                if (i < lines.size) i++ // consume closing ```
                result += MdBlock.CodeFence(lang, codeLines.joinToString("\n"))
            }

            // ATX heading
            trimmed.startsWith("#") -> {
                var level = 0
                for (ch in trimmed) { if (ch == '#') level++ else break }
                val headingText = trimmed.drop(level).trimStart()
                result += MdBlock.Heading(level.coerceIn(1, 6), headingText)
                i++
            }

            // Horizontal rule
            trimmed.matches(Regex("[-*_]{3,}\\s*")) -> {
                result += MdBlock.Rule
                i++
            }

            // Block quote
            trimmed.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                result += MdBlock.BlockQuote(parseInline(quoteLines.joinToString("\n")))
            }

            // Unordered list
            trimmed.matches(Regex("[-*+]\\s.+")) -> {
                val items = mutableListOf<AnnotatedString>()
                while (i < lines.size && lines[i].trimStart().matches(Regex("[-*+]\\s.+"))) {
                    items += parseInline(lines[i].trimStart().replaceFirst(Regex("[-*+]\\s"), ""))
                    i++
                }
                result += MdBlock.BulletList(items)
            }

            // Ordered list
            trimmed.matches(Regex("\\d+[.)][\\s]+.+")) -> {
                val items = mutableListOf<AnnotatedString>()
                while (i < lines.size && lines[i].trimStart().matches(Regex("\\d+[.)][\\s]+.+"))) {
                    items += parseInline(lines[i].trimStart().replaceFirst(Regex("\\d+[.)][\\s]+"), ""))
                    i++
                }
                result += MdBlock.NumberedList(items)
            }

            // Paragraph (everything else, continued until a blank line or block starter)
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (l.isBlank() || t.startsWith("#") || t.startsWith("```") ||
                        t.startsWith(">") || t.matches(Regex("[-*+]\\s.+")) ||
                        t.matches(Regex("\\d+[.)][\\s]+.+")) ||
                        t.matches(Regex("[-*_]{3,}\\s*"))) break
                    paraLines += l
                    i++
                }
                if (paraLines.isNotEmpty()) {
                    result += MdBlock.Paragraph(parseInline(paraLines.joinToString("\n")))
                }
            }
        }
    }

    return result
}

// ── Inline parser ─────────────────────────────────────────────────────────────

private val inlineCodeBg = Color(0x22888888)

private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            // **bold**
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop(); i = end + 2
                } else { append(text[i]); i++ }
            }
            // __bold__
            text.startsWith("__", i) -> {
                val end = text.indexOf("__", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop(); i = end + 2
                } else { append(text[i]); i++ }
            }
            // *italic*
            text[i] == '*' && !text.startsWith("**", i) -> {
                val end = findClosingChar(text, i + 1, '*')
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop(); i = end + 1
                } else { append(text[i]); i++ }
            }
            // _italic_
            text[i] == '_' && !text.startsWith("__", i) -> {
                val end = findClosingChar(text, i + 1, '_')
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop(); i = end + 1
                } else { append(text[i]); i++ }
            }
            // ~~strikethrough~~
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(text.substring(i + 2, end))
                    pop(); i = end + 2
                } else { append(text[i]); i++ }
            }
            // `inline code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = inlineCodeBg))
                    append(text.substring(i + 1, end))
                    pop(); i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

private fun findClosingChar(text: String, from: Int, ch: Char): Int {
    for (j in from until text.length) {
        if (text[j] == ch && (j == 0 || text[j - 1] != '\\')) return j
    }
    return -1
}
