package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.CommanderClient
import ir.tafakoritech.adminapplication.api.TerminalInfo
import ir.tafakoritech.adminapplication.api.TerminalStreamEvent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live view of a free shell (any command in a PTY). Same chat-bubble output,
 * key bar, pinch-to-zoom and modal input as the Claude terminal, without the
 * Claude-specific queue and prompt detection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellScreen(
    client: CommanderClient,
    shellId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }

    var shell by remember { mutableStateOf<TerminalInfo?>(null) }
    var screen by remember { mutableStateOf("") }
    var ansi by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val timeline = remember { TerminalTimeline() }

    var showSendModal by remember { mutableStateOf(false) }
    var terminalFontSize by rememberSaveable { mutableStateOf(12f) }

    var ctrlArmed by remember { mutableStateOf(false) }
    var shiftArmed by remember { mutableStateOf(false) }

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    fun nowTime() = timeFmt.format(Date())

    fun refresh() = scope.launch {
        runCatching { client.getShell(shellId) }.onSuccess { info ->
            shell = info
            if (info.screenText.isNotBlank()) screen = info.screenText
            if (!info.ansi.isNullOrBlank()) ansi = info.ansi
        }
    }

    LaunchedEffect(shellId) { refresh() }

    // Feed every SSE frame into the timeline.
    LaunchedEffect(ansi) { timeline.onScreenUpdate(ansi) }

    DisposableEffect(shellId) {
        val source = client.streamShell(shellId, object : CommanderClient.SseListener {
            override fun onData(data: String) {
                val event = runCatching { json.decodeFromString<TerminalStreamEvent>(data) }.getOrNull()
                event?.ansi?.let { if (it.isNotBlank()) ansi = it }
                when {
                    event?.screen != null -> screen = event.screen
                    event?.chunk != null -> screen += event.chunk
                    else -> screen += data
                }
            }
        })
        onDispose { source.cancel() }
    }

    fun sendRaw(seq: String) =
        scope.launch { runCatching { client.shellInput(shellId, seq, submit = false) } }

    fun applyModifiers(text: String): String {
        if (!ctrlArmed && !shiftArmed) return text
        val out = buildString {
            for (c in text) {
                val ch = if (shiftArmed) c.uppercaseChar() else c
                append(if (ctrlArmed) (ch.uppercaseChar().code and 0x1f).toChar() else ch)
            }
        }
        ctrlArmed = false; shiftArmed = false
        return out
    }

    fun sendText(raw: String) {
        val data = raw.trim()
        if (data.isBlank()) return
        val withMods = applyModifiers(data)
        val submit = withMods == data
        if (submit) timeline.onUserSent(SentCommand(data, nowTime()))
        scope.launch { runCatching { client.shellInput(shellId, withMods, submit = submit) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Shell")
                        shell?.let {
                            Text(
                                listOfNotNull(
                                    it.commandDisplay,
                                    it.path.ifBlank { null },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val running = shell?.isRunning == true
                    if (running) {
                        IconButton(onClick = {
                            scope.launch { runCatching { client.shellOff(shellId) }; refresh() }
                        }) { Icon(Icons.Filled.Stop, contentDescription = "Turn off") }
                    } else {
                        IconButton(onClick = {
                            scope.launch { runCatching { client.shellOn(shellId) }; refresh() }
                        }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Turn on") }
                    }
                    IconButton(onClick = {
                        scope.launch { runCatching { client.deleteShell(shellId) }; onBack() }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            // ── Chat bubble area ──────────────────────────────────────────
            TerminalChatView(
                timeline = timeline,
                listState = listState,
                fontSize = terminalFontSize,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            terminalFontSize = (terminalFontSize * zoom).coerceIn(7f, 28f)
                        }
                    },
            )

            // ── Controls ─────────────────────────────────────────────────
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyBar(
                    ctrlArmed = ctrlArmed,
                    shiftArmed = shiftArmed,
                    onToggleCtrl = { ctrlArmed = !ctrlArmed },
                    onToggleShift = { shiftArmed = !shiftArmed },
                    onRaw = { sendRaw(it) },
                )

                Button(
                    onClick = { showSendModal = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardReturn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("Send command")
                }
            }
        }
    }

    if (showSendModal) {
        CommandInputModal(
            title = "Send to shell",
            submitLabel = "Send",
            ctrlArmed = ctrlArmed,
            shiftArmed = shiftArmed,
            onSubmit = { sendText(it) },
            onDismiss = { showSendModal = false },
        )
    }
}
