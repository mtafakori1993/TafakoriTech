package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.CommanderClient
import ir.tafakoritech.adminapplication.api.SessionDetail
import ir.tafakoritech.adminapplication.api.TaskStatus
import ir.tafakoritech.adminapplication.data.UrlDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Turn(val command: String, val response: String, val isError: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionScreen(
    client: CommanderClient,
    serverId: Long,
    sessionId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { UrlDatabase.get(context) }
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<SessionDetail?>(null) }
    val turns = remember { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Load session info and persisted history on first open.
    LaunchedEffect(sessionId) {
        runCatching { client.getSession(sessionId) }.onSuccess { session = it }
        val rows = withContext(Dispatchers.IO) { db.getChatMessages(serverId, sessionId) }
        turns.addAll(rows.map { Turn(it.command, it.response, it.isError) })
    }

    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    fun addAndSave(turn: Turn) {
        turns += turn
        scope.launch {
            withContext(Dispatchers.IO) {
                db.insertChatMessage(serverId, sessionId, turn.command, turn.response, turn.isError)
            }
        }
    }

    fun send() {
        val command = input.trim()
        if (command.isBlank() || sending) return
        input = ""
        sending = true
        scope.launch {
            runCatching { client.runCommand(sessionId, command) }
                .onSuccess { resp ->
                    when {
                        // /clear resets context on the server — wipe local history too.
                        command == "/clear" && resp.kind == "ack" -> {
                            turns.clear()
                            withContext(Dispatchers.IO) { db.clearChatMessages(serverId, sessionId) }
                        }
                        resp.kind == "task" && resp.task != null -> {
                            val taskId = resp.task.id
                            runCatching {
                                var detail = client.getTask(taskId)
                                while (detail.status == TaskStatus.RUNNING) {
                                    delay(1000)
                                    detail = client.getTask(taskId)
                                }
                                detail
                            }.onSuccess { detail ->
                                val text = detail.report.ifBlank { detail.error ?: "[no response]" }
                                addAndSave(Turn(command, text, isError = detail.status == TaskStatus.FAILED))
                            }.onFailure { e ->
                                addAndSave(Turn(command, e.message ?: "failed", isError = true))
                            }
                        }
                        else -> addAndSave(Turn(command, resp.message ?: "[${resp.kind}]"))
                    }
                    runCatching { client.getSession(sessionId) }.onSuccess { session = it }
                }
                .onFailure { e ->
                    addAndSave(Turn(command, e.message ?: "failed", isError = true))
                }
            sending = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Session")
                        session?.let {
                            Text(
                                it.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (turns.isEmpty()) {
                EmptyState(
                    "Send a message",
                    "Type a prompt or a slash command like /model or /clear",
                    Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    turnItems(turns)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message or /command") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )
                Spacer(Modifier.size(8.dp))
                IconButton(onClick = { send() }, enabled = input.isNotBlank() && !sending) {
                    if (sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

private fun LazyListScope.turnItems(turns: List<Turn>) {
    turns.forEachIndexed { index, turn ->
        item(key = index) { TurnCard(turn) }
    }
}

@Composable
private fun TurnCard(turn: Turn) {
    Column {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    turn.command,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        if (turn.isError) {
            Text(
                turn.response,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp)
            )
        } else {
            MarkdownText(
                text = turn.response,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
