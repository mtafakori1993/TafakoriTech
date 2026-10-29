package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.tafakoritech.adminapplication.ui.theme.BrightGreen

// ── Colours ──────────────────────────────────────────────────────────────────

private val ChatBackground  = Color(0xFF06101F)
private val IncomingBubble  = Color(0xFF132030)
private val IncomingUpdated = Color(0xFF1C3554)   // brief flash on live update
private val OutgoingBubble  = Color(0xFF0C3A1C)
private val OutgoingBorder  = Color(0x330DBC79)
private val TimestampColor  = Color(0x72_86EFAC)
private val EmptyHintColor  = Color(0xFF4A6080)

// ── Bubble shapes (Android "tail" style) ─────────────────────────────────────

/** First bubble in a received block — pointed top-left corner = "incoming tail". */
private val IncomingFirstShape = RoundedCornerShape(
    topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp,
)
/** Continuation bubbles in the same block — all corners equal, no tail. */
private val IncomingMidShape = RoundedCornerShape(16.dp)

/** Sent bubble — pointed top-right corner = "outgoing tail". */
private val OutgoingShape = RoundedCornerShape(
    topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp,
)

private val ANSI_ESCAPE_RE = Regex("""\x1b\[[0-9;?]*[a-zA-Z]|\x1b\][^\x07]*\x07|\x1b[()][AB0]""")
private fun String.stripAnsi(): String = ANSI_ESCAPE_RE.replace(this, "")

// ── Public data ──────────────────────────────────────────────────────────────

/** One command the user sent, stored with a display timestamp. */
data class SentCommand(val text: String, val time: String)

/**
 * Manages the chat timeline: a sequence of [Entry] objects that mix
 * user-sent commands with live terminal screen lines.
 *
 * Call [onUserSent] each time the user sends a command.
 * Call [onScreenUpdate] on every SSE event with the new `ansi` text.
 */
@Stable
class TerminalTimeline {
    sealed class Entry {
        /** A command the user sent. */
        data class Sent(val cmd: SentCommand, val id: Long) : Entry()

        /**
         * A "live" block of terminal output. [ansiLines] is replaced in-place
         * on every screen update until the next user send creates a new block.
         */
        data class Terminal(val ansiLines: List<String>, val id: Long) : Entry()
    }

    private val _entries = mutableStateListOf<Entry>()
    val entries: List<Entry> get() = _entries

    private var nextId = 0L
    private var awaitingFreshBlock = false

    fun onUserSent(cmd: SentCommand) {
        _entries.add(Entry.Sent(cmd, nextId++))
        awaitingFreshBlock = true
    }

    fun onScreenUpdate(ansi: String) {
        if (ansi.isBlank()) return
        val lines = ansi.lines().filter { it.stripAnsi().isNotBlank() }
        if (lines.isEmpty()) return
        val last = _entries.lastOrNull()
        if (last is Entry.Terminal && !awaitingFreshBlock) {
            // Update the live block in-place.
            _entries[_entries.lastIndex] = last.copy(ansiLines = lines)
        } else {
            awaitingFreshBlock = false
            _entries.add(Entry.Terminal(lines, nextId++))
        }
    }

    fun clear() {
        _entries.clear()
        awaitingFreshBlock = false
    }
}

// ── Main composable ───────────────────────────────────────────────────────────

/**
 * A LazyColumn that renders the [timeline] as Android-style chat bubbles:
 *   • User commands  → right-aligned green bubbles with timestamp + checkmark
 *   • Terminal lines → left-aligned dark bubbles that update live
 *
 * Pinch-to-zoom is handled by the caller; [fontSize] is passed in.
 */
@Composable
internal fun TerminalChatView(
    timeline: TerminalTimeline,
    listState: LazyListState = rememberLazyListState(),
    fontSize: Float = 12f,
    modifier: Modifier = Modifier,
) {
    val entries = timeline.entries

    // Auto-scroll to the bottom whenever entries change or the last terminal
    // block's line count changes. We use instant scrollToItem (not animated)
    // to avoid jitter during high-frequency live output.
    val lastTerminalSize = (entries.lastOrNull() as? TerminalTimeline.Entry.Terminal)?.ansiLines?.size ?: 0
    LaunchedEffect(entries.size, lastTerminalSize) {
        if (entries.isNotEmpty()) {
            // Int.MAX_VALUE is clamped by LazyListState to the last real item.
            listState.scrollToItem(Int.MAX_VALUE)
        }
    }

    // After the terminal output stabilises (no line-count change for 1.5 s),
    // scroll back to the user's last sent bubble so the full answer is visible.
    LaunchedEffect(lastTerminalSize) {
        if (lastTerminalSize == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        if (entries.lastOrNull() !is TerminalTimeline.Entry.Terminal) return@LaunchedEffect
        val lastSentIdx = entries.indexOfLast { it is TerminalTimeline.Entry.Sent }
        if (lastSentIdx < 0) return@LaunchedEffect
        // Count LazyColumn items that precede the last Sent entry.
        var lazyIdx = 0
        for (i in 0 until lastSentIdx) {
            lazyIdx += when (val e = entries[i]) {
                is TerminalTimeline.Entry.Sent -> 1
                is TerminalTimeline.Entry.Terminal -> e.ansiLines.size
            }
        }
        listState.animateScrollToItem(lazyIdx)
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(ChatBackground),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (entries.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Waiting for terminal output…",
                        color = EmptyHintColor,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            return@LazyColumn
        }

        entries.forEachIndexed { entryIndex, entry ->
            when (entry) {
                is TerminalTimeline.Entry.Sent -> {
                    item(key = "sent_${entry.id}") {
                        SentBubble(entry.cmd, fontSize = fontSize.sp)
                    }
                }

                is TerminalTimeline.Entry.Terminal -> {
                    val lines = entry.ansiLines
                    itemsIndexed(
                        items = lines,
                        key = { lineIdx, _ -> "term_${entry.id}_$lineIdx" },
                    ) { lineIdx, line ->
                        IncomingBubbleLine(
                            ansiLine = line,
                            showTail = lineIdx == 0,
                            fontSize = fontSize.sp,
                        )
                    }
                }
            }
        }
    }
}

// ── Bubble composables ────────────────────────────────────────────────────────

@Composable
private fun IncomingBubbleLine(
    ansiLine: String,
    showTail: Boolean,
    fontSize: TextUnit,
) {
    // Track changes to flash the background briefly when content updates live.
    var isFlashing by remember { mutableStateOf(false) }
    var prevLine by remember { mutableStateOf(ansiLine) }
    if (ansiLine != prevLine) {
        prevLine = ansiLine
        isFlashing = true
    }
    LaunchedEffect(isFlashing) {
        if (isFlashing) {
            kotlinx.coroutines.delay(32)
            isFlashing = false
        }
    }

    val bg by animateColorAsState(
        targetValue = if (isFlashing) IncomingUpdated else IncomingBubble,
        animationSpec = tween(durationMillis = if (isFlashing) 0 else 600),
        label = "bubbleBg",
    )

    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val shape = if (showTail) IncomingFirstShape else IncomingMidShape
        Box(
            Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val annotated = remember(ansiLine) { parseAnsi(ansiLine) }
            Text(
                text = annotated,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                color = AnsiDefaultFg,
                lineHeight = fontSize * 1.5f,
            )
        }
    }
}

@Composable
private fun SentBubble(cmd: SentCommand, fontSize: TextUnit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.78f)
                .clip(OutgoingShape)
                .background(OutgoingBubble)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = cmd.text,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                color = BrightGreen,
                lineHeight = fontSize * 1.5f,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${cmd.time} ✓",
                fontSize = 10.sp,
                color = TimestampColor,
            )
        }
    }
}
