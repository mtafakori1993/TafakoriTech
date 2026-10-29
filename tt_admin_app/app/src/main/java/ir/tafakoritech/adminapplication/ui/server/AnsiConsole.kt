package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

/** Default foreground for cells the TUI didn't colour. */
internal val AnsiDefaultFg = Color(0xFFD4D4D4)

/** Standard 16-colour terminal palette (VS Code dark-ish), indexed by SGR code. */
private val AnsiPalette: Map<Int, Color> = mapOf(
    30 to Color(0xFF000000), 31 to Color(0xFFCD3131), 32 to Color(0xFF0DBC79),
    33 to Color(0xFFE5E510), 34 to Color(0xFF2472C8), 35 to Color(0xFFBC3FBC),
    36 to Color(0xFF11A8CD), 37 to Color(0xFFE5E5E5),
    90 to Color(0xFF666666), 91 to Color(0xFFF14C4C), 92 to Color(0xFF23D18B),
    93 to Color(0xFFF5F543), 94 to Color(0xFF3B8EEA), 95 to Color(0xFFD670D6),
    96 to Color(0xFF29B8DB), 97 to Color(0xFFFFFFFF),
)

/**
 * Renders text containing ANSI/SGR colour escapes (as produced by the server's
 * `ansi` field) with their real colours, in a monospace font.
 */
@Composable
internal fun AnsiConsole(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val annotated = remember(text) { parseAnsi(text) }
    val base = MaterialTheme.typography.bodySmall
    val style = if (fontSize != TextUnit.Unspecified)
        base.copy(fontSize = fontSize, lineHeight = fontSize * 1.35f)
    else base
    Text(
        text = annotated,
        style = style,
        fontFamily = FontFamily.Monospace,
        color = AnsiDefaultFg,
        modifier = modifier,
    )
}

/** Parses a subset of SGR sequences (colours, bold, reverse) into spans. */
internal fun parseAnsi(input: String): AnnotatedString {
    if (input.isBlank()) return AnnotatedString("Waiting for output…")

    return buildAnnotatedString {
        var fg: Color? = null
        var bg: Color? = null
        var bold = false
        var reverse = false
        val esc = ''
        val run = StringBuilder()

        fun flush() {
            if (run.isEmpty()) return
            val realFg = (if (reverse) bg ?: Color.Black else fg) ?: AnsiDefaultFg
            val realBg = if (reverse) fg ?: AnsiDefaultFg else bg
            withStyle(
                SpanStyle(
                    color = realFg,
                    background = realBg ?: Color.Unspecified,
                    fontWeight = if (bold) FontWeight.Bold else null,
                )
            ) { append(run.toString()) }
            run.clear()
        }

        fun applySgr(params: String) {
            val codes = params.split(';').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
            var i = 0
            while (i < codes.size) {
                when (val code = codes[i]) {
                    0 -> { fg = null; bg = null; bold = false; reverse = false }
                    1 -> bold = true
                    7 -> reverse = true
                    22 -> bold = false
                    27 -> reverse = false
                    39 -> fg = null
                    49 -> bg = null
                    in 30..37, in 90..97 -> fg = AnsiPalette[code]
                    in 40..47 -> bg = AnsiPalette[code - 10]
                    in 100..107 -> bg = AnsiPalette[code - 10]
                    38, 48 -> {
                        // 38;2;r;g;b (truecolor) or 38;5;n (256-colour).
                        if (i + 1 < codes.size && codes[i + 1] == 2 && i + 4 < codes.size) {
                            val c = Color(codes[i + 2], codes[i + 3], codes[i + 4])
                            if (code == 38) fg = c else bg = c
                            i += 4
                        } else if (i + 2 < codes.size && codes[i + 1] == 5) {
                            val c = xterm256(codes[i + 2])
                            if (code == 38) fg = c else bg = c
                            i += 2
                        }
                    }
                }
                i++
            }
        }

        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == esc && i + 1 < input.length && input[i + 1] == '[') {
                val m = input.indexOf('m', startIndex = i + 2)
                // Only handle SGR ("...m"); skip other CSI sequences defensively.
                val nextCsiEnd = input.indexOfFirst(i + 2) { it in '@'..'~' }
                if (m != -1 && m == nextCsiEnd) {
                    flush()
                    applySgr(input.substring(i + 2, m))
                    i = m + 1
                    continue
                } else if (nextCsiEnd != -1) {
                    i = nextCsiEnd + 1
                    continue
                }
            }
            run.append(c)
            i++
        }
        flush()
    }
}

private inline fun String.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
    var i = start
    while (i < length) {
        if (predicate(this[i])) return i
        i++
    }
    return -1
}

/** Approximates an xterm 256-colour index as an RGB colour. */
private fun xterm256(n: Int): Color = when {
    n < 16 -> AnsiPalette[if (n < 8) 30 + n else 90 + (n - 8)] ?: AnsiDefaultFg
    n in 16..231 -> {
        val v = n - 16
        val r = v / 36; val g = (v % 36) / 6; val b = v % 6
        fun ch(x: Int) = if (x == 0) 0 else x * 40 + 55
        Color(ch(r), ch(g), ch(b))
    }
    else -> {
        val gray = (n - 232) * 10 + 8
        Color(gray, gray, gray)
    }
}
