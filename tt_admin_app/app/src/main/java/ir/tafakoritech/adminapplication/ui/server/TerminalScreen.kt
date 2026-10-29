package ir.tafakoritech.adminapplication.ui.server

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import ir.tafakoritech.adminapplication.api.CommanderClient
import ir.tafakoritech.adminapplication.api.TerminalInfo
import ir.tafakoritech.adminapplication.api.TerminalStreamEvent
import ir.tafakoritech.adminapplication.data.CommandQueueItem
import ir.tafakoritech.adminapplication.data.UrlDatabase
import ir.tafakoritech.adminapplication.data.UrlEntry
import ir.tafakoritech.adminapplication.service.QueueRunnerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalScreen(
    server: UrlEntry,
    client: CommanderClient,
    terminalId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { UrlDatabase.get(context) }
    val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }

    var term by remember { mutableStateOf<TerminalInfo?>(null) }
    var screen by remember { mutableStateOf("") }
    var ansi by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val timeline = remember { TerminalTimeline() }

    var terminalFontSize by rememberSaveable { mutableStateOf(12f) }

    var handledPromptKey by rememberSaveable { mutableStateOf<String?>(null) }
    val claudePrompt = remember(screen) { detectClaudePrompt(screen) }

    var queue by remember { mutableStateOf<List<CommandQueueItem>>(emptyList()) }
    var showSendModal by remember { mutableStateOf(false) }
    var showAddModal by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CommandQueueItem?>(null) }
    var backgrounding by remember { mutableStateOf(false) }

    var ctrlArmed by remember { mutableStateOf(false) }
    var shiftArmed by remember { mutableStateOf(false) }

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    fun nowTime() = timeFmt.format(Date())

    suspend fun loadQueue() {
        queue = withContext(Dispatchers.IO) { db.queuedCommands(server.id, terminalId) }
    }

    fun enqueue(vararg texts: String) = scope.launch {
        withContext(Dispatchers.IO) { texts.forEach { db.enqueueCommand(server.id, terminalId, it) } }
        loadQueue()
    }

    fun refresh() = scope.launch {
        runCatching { client.getTerminal(terminalId) }.onSuccess { info ->
            term = info
            if (info.screenText.isNotBlank()) screen = info.screenText
            if (!info.ansi.isNullOrBlank()) ansi = info.ansi
        }
    }

    LaunchedEffect(terminalId) { refresh(); loadQueue() }

    // Feed every SSE frame into the timeline.
    LaunchedEffect(ansi) { timeline.onScreenUpdate(ansi) }

    DisposableEffect(terminalId) {
        val source = client.streamTerminal(terminalId, object : CommanderClient.SseListener {
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
        scope.launch { runCatching { client.terminalInput(terminalId, seq, submit = false) } }

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
        scope.launch { runCatching { client.terminalInput(terminalId, withMods, submit = submit) } }
    }

    fun sendNow(text: String) {
        timeline.onUserSent(SentCommand(text, nowTime()))
        scope.launch { runCatching { client.terminalInput(terminalId, text, submit = true) } }
    }

    fun runNext() = scope.launch {
        val item = withContext(Dispatchers.IO) { db.popCommand(server.id, terminalId) }
        if (item != null) {
            timeline.onUserSent(SentCommand(item.text, nowTime()))
            runCatching { client.terminalInput(terminalId, item.text, submit = true) }
            loadQueue()
        }
    }

    fun addCommand(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (queue.isEmpty()) enqueue(t) else pendingCommand = t
    }

    fun startBackground() {
        backgrounding = true
        QueueRunnerService.start(context, server.id, server.baseUrl, server.token, terminalId)
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startBackground() }

    fun runInBackground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startBackground()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Terminal")
                        term?.let {
                            Text(
                                it.path,
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
                    val running = term?.isRunning == true
                    if (running) {
                        IconButton(onClick = {
                            scope.launch { runCatching { client.terminalOff(terminalId) }; refresh() }
                        }) { Icon(Icons.Filled.Stop, contentDescription = "Turn off") }
                    } else {
                        IconButton(onClick = {
                            scope.launch { runCatching { client.terminalOn(terminalId) }; refresh() }
                        }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Turn on") }
                    }
                    IconButton(onClick = {
                        scope.launch { runCatching { client.deleteTerminal(terminalId) }; onBack() }
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
                .imePadding()
                .padding(horizontal = 0.dp, vertical = 0.dp),
        ) {
            // ── Chat bubble area ──────────────────────────────────────────
            TerminalChatView(
                timeline = timeline,
                listState = listState,
                fontSize = terminalFontSize,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Pinch-to-zoom changes font size; two-finger gesture won't
                    // conflict with LazyColumn's single-finger scroll.
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            terminalFontSize = (terminalFontSize * zoom).coerceIn(7f, 28f)
                        }
                    },
            )

            // ── Controls ─────────────────────────────────────────────────
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                KeyBar(
                    ctrlArmed = ctrlArmed,
                    shiftArmed = shiftArmed,
                    onToggleCtrl = { ctrlArmed = !ctrlArmed },
                    onToggleShift = { shiftArmed = !shiftArmed },
                    onRaw = { sendRaw(it) },
                )

                Spacer(Modifier.size(8.dp))

                QueuePanel(
                    queue = queue,
                    backgrounding = backgrounding,
                    onRunNext = { runNext() },
                    onCommitPush = { sendNow("commit and push") },
                    onStartBackground = { runInBackground() },
                    onStopBackground = {
                        backgrounding = false
                        QueueRunnerService.stop(context)
                    },
                    onEdit = { editing = it },
                    onDelete = { item ->
                        scope.launch {
                            withContext(Dispatchers.IO) { db.deleteCommand(item.id) }
                            loadQueue()
                        }
                    },
                )

                Spacer(Modifier.size(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showAddModal = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Add to queue")
                    }
                    Button(
                        onClick = { showSendModal = true },
                        modifier = Modifier.weight(1f),
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
    }

    // Claude interactive prompt modals
    claudePrompt?.takeIf { it.key != handledPromptKey }?.let { prompt ->
        when (prompt) {
            is ClaudePrompt.Options -> OptionsPromptModal(
                prompt = prompt,
                onSelect = { number -> handledPromptKey = prompt.key; sendRaw(number.toString()) },
                onDismiss = { handledPromptKey = prompt.key },
            )
            is ClaudePrompt.Question -> QuestionPromptModal(
                prompt = prompt,
                onAnswer = { answer -> handledPromptKey = prompt.key; sendNow(answer) },
                onDismiss = { handledPromptKey = prompt.key },
            )
        }
    }

    if (showSendModal) {
        CommandInputModal(
            title = "Send to terminal",
            submitLabel = "Send",
            ctrlArmed = ctrlArmed,
            shiftArmed = shiftArmed,
            onSubmit = { sendText(it) },
            onDismiss = { showSendModal = false },
        )
    }

    if (showAddModal) {
        CommandInputModal(
            title = "Add command to queue",
            submitLabel = "Add",
            ctrlArmed = false,
            shiftArmed = false,
            onSubmit = { addCommand(it) },
            onDismiss = { showAddModal = false },
        )
    }

    pendingCommand?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingCommand = null },
            title = { Text("Related to previous?") },
            text = { Text("Is \"$text\" related to the previous command? If not, a /clear will be queued before it.") },
            confirmButton = {
                TextButton(onClick = { enqueue(text); pendingCommand = null }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { enqueue("/clear", text); pendingCommand = null }) {
                    Text("No — clear first")
                }
            },
        )
    }

    editing?.let { item ->
        var editText by remember(item.id) { mutableStateOf(item.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit command") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = editText.trim()
                    if (t.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.updateCommand(item.id, t) }
                            loadQueue()
                        }
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }
}

/** Classic phosphor-green terminal text (kept for plain-text fallback). */
internal val TerminalGreen = Color(0xFF6BFF6B)

/**
 * Horizontally scrollable row of terminal keys missing from the soft keyboard.
 */
@Composable
internal fun KeyBar(
    ctrlArmed: Boolean,
    shiftArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleShift: () -> Unit,
    onRaw: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyButton("Esc")    { onRaw("") }
        KeyButton("Ctrl+C") { onRaw(Char(3).toString()) }
        KeyButton("Ctrl",  active = ctrlArmed,  onClick = onToggleCtrl)
        KeyButton("Shift", active = shiftArmed, onClick = onToggleShift)
        KeyButton("Tab")   { onRaw("\t") }
        KeyButton("⇧Tab")  { onRaw("[Z") }
        KeyButton("↑")     { onRaw("[A") }
        KeyButton("↓")     { onRaw("[B") }
        KeyButton("←")     { onRaw("[D") }
        KeyButton("→")     { onRaw("[C") }
        KeyButton("Enter") { onRaw("\r") }
    }
}

@Composable
private fun KeyButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    if (active) {
        Button(onClick = onClick, contentPadding = padding) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = padding) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun CommandInputModal(
    title: String,
    submitLabel: String,
    ctrlArmed: Boolean,
    shiftArmed: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        if (text.isNotBlank()) onSubmit(text)
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (ctrlArmed || shiftArmed) {
                    val mods = listOfNotNull(
                        if (ctrlArmed) "Ctrl" else null,
                        if (shiftArmed) "Shift" else null,
                    ).joinToString("+")
                    Text(
                        "$mods will be applied",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Type here…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(onClick = { submit() }, modifier = Modifier.weight(1f)) {
                        Text(submitLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(
    queue: List<CommandQueueItem>,
    backgrounding: Boolean,
    onRunNext: () -> Unit,
    onCommitPush: () -> Unit,
    onStartBackground: () -> Unit,
    onStopBackground: () -> Unit,
    onEdit: (CommandQueueItem) -> Unit,
    onDelete: (CommandQueueItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Queue (${queue.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onCommitPush,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Commit & push")
            }
        }

        if (queue.isNotEmpty()) {
            val listScroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(listScroll),
            ) {
                queue.forEachIndexed { index, item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            item.text,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.text.trim() == "/clear")
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { onEdit(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRunNext,
                enabled = queue.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Run next")
            }
            if (backgrounding) {
                Button(onClick = onStopBackground, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = onStartBackground,
                    enabled = queue.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Run in background")
                }
            }
        }
    }
}
