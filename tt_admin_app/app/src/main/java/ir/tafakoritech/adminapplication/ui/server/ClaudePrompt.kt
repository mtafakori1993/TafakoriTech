package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * An interactive prompt detected on the terminal screen — either a Claude Code
 * select menu (numbered options with a ❯ cursor) or a free-text question that
 * Claude finished its turn with.
 */
internal sealed class ClaudePrompt {
    /** Stable identity so a dismissed prompt isn't re-shown for the same frame. */
    abstract val key: String

    data class Options(
        val question: String,
        val options: List<PromptOption>,
    ) : ClaudePrompt() {
        override val key: String = question + "|" + options.joinToString("|") { "${it.number}:${it.label}" }
    }

    data class Question(val text: String) : ClaudePrompt() {
        override val key: String = "q|$text"
    }
}

internal data class PromptOption(val number: Int, val label: String, val selected: Boolean)

private val BoxChars = setOf(
    '│', '┃', '║', '╭', '╮', '╰', '╯', '┌', '┐', '└', '┘',
    '├', '┤', '─', '━', '═', '╔', '╗', '╚', '╝',
)

private val Bullets = setOf('●', '○', '⏺', '·', '•', '∙')

/** Strips box-drawing borders and surrounding whitespace from a screen row. */
private fun cleanLine(raw: String): String =
    raw.trim().trim { it in BoxChars || it == ' ' }.trim()

/** A row that is only border/box characters (top/bottom edges, separators). */
private fun isBorder(raw: String): Boolean {
    val t = raw.trim()
    return t.isNotEmpty() && t.all { it in BoxChars || it == ' ' }
}

private val OptionLine = Regex("""^(❯\s*)?(\d{1,2})[.)]\s+(.+)$""")

/**
 * Detects an interactive prompt on the rendered screen.
 *
 * Select menus: a consecutive run of `N. label` lines starting at 1 where at
 * least one line carries the ❯ cursor — the cursor is what distinguishes a real
 * menu from a numbered list in Claude's prose.
 *
 * Questions: when Claude is idle (no "esc to interrupt") and the last paragraph
 * above the `>` input box ends with a question mark.
 */
internal fun detectClaudePrompt(screen: String): ClaudePrompt? {
    if (screen.isBlank()) return null
    val lines = screen.lines()
    val cleaned = lines.map { cleanLine(it) }

    detectOptionsMenu(lines, cleaned)?.let { return it }
    return detectQuestion(screen, lines, cleaned)
}

private fun detectOptionsMenu(lines: List<String>, cleaned: List<String>): ClaudePrompt.Options? {
    data class Opt(val line: Int, val number: Int, val label: String, val selected: Boolean)

    val all = cleaned.mapIndexedNotNull { idx, l ->
        OptionLine.matchEntire(l)?.let { m ->
            Opt(idx, m.groupValues[2].toInt(), m.groupValues[3].trim(), m.groupValues[1].isNotEmpty())
        }
    }
    if (all.isEmpty()) return null

    // Split into runs of consecutive numbering on (nearly) adjacent lines, then
    // take the last run that looks like a menu — prompts render near the bottom.
    val runs = mutableListOf<MutableList<Opt>>()
    for (opt in all) {
        val cur = runs.lastOrNull()
        if (cur != null && opt.number == cur.last().number + 1 && opt.line - cur.last().line <= 2) {
            cur.add(opt)
        } else {
            runs.add(mutableListOf(opt))
        }
    }
    val menu = runs.lastOrNull { run ->
        run.size >= 2 && run.first().number == 1 && run.any { it.selected }
    } ?: return null

    // The question is the contiguous block of text just above the first option.
    val question = mutableListOf<String>()
    var i = menu.first().line - 1
    var blanks = 0
    while (i >= 0 && question.size < 4) {
        val l = cleaned[i]
        when {
            l.isBlank() || isBorder(lines[i]) -> {
                if (question.isNotEmpty()) break
                if (++blanks > 2) break
            }
            else -> question.add(0, l)
        }
        i--
    }

    return ClaudePrompt.Options(
        question = question.joinToString(" ").ifBlank { "Choose an option" },
        options = menu.map { PromptOption(it.number, it.label, it.selected) },
    )
}

private fun detectQuestion(screen: String, lines: List<String>, cleaned: List<String>): ClaudePrompt.Question? {
    // While Claude is still working the screen churns; don't nag with modals.
    if (screen.contains("esc to interrupt")) return null

    // The standard Claude Code input box: a row whose content is just ">"
    // (optionally with typed text after it).
    val inputIdx = cleaned.indexOfLast { it == ">" || it.startsWith("> ") }
    if (inputIdx <= 0) return null

    // Walk up to the last paragraph of output above the input box.
    var end = inputIdx - 1
    while (end >= 0 && (cleaned[end].isBlank() || isBorder(lines[end]))) end--
    if (end < 0) return null
    if (!cleaned[end].endsWith("?")) return null

    var start = end
    while (start > 0 && cleaned[start - 1].isNotBlank() && !isBorder(lines[start - 1])) start--

    val text = (start..end)
        .joinToString(" ") { cleaned[it].trimStart { c -> c in Bullets || c == ' ' } }
        .trim()
    if (text.length < 8) return null

    return ClaudePrompt.Question(text)
}

/**
 * Modal for a Claude Code select menu: shows the question and one tappable
 * button per option. Selecting sends the option number straight to the
 * terminal (Claude Code menus react to the digit key).
 */
@Composable
internal fun OptionsPromptModal(
    prompt: ClaudePrompt.Options,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PromptHeader("Claude needs your choice")
                Text(prompt.question, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(2.dp))
                prompt.options.forEach { opt ->
                    val content: @Composable () -> Unit = {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    "${opt.number}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.size(width = 26.dp, height = 22.dp).padding(top = 1.dp)
                                )
                            }
                            Text(
                                opt.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (opt.selected) {
                        FilledTonalButton(
                            onClick = { onSelect(opt.number) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                        ) { content() }
                    } else {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onSelect(opt.number) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                        ) { content() }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Use terminal instead")
                }
            }
        }
    }
}

/**
 * Modal for a free-text question from Claude: shows the question with a big
 * autofocused answer box; Send submits the answer to the terminal.
 */
@Composable
internal fun QuestionPromptModal(
    prompt: ClaudePrompt.Question,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var answer by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        if (answer.isNotBlank()) onAnswer(answer.trim())
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PromptHeader("Claude is asking")
                Text(prompt.text, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    placeholder = { Text("Your answer…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp)
                        .focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Dismiss")
                    }
                    Button(onClick = { submit() }, enabled = answer.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptHeader(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Filled.QuestionAnswer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
