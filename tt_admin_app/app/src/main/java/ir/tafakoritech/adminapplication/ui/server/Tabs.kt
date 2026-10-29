package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.CommanderClient
import ir.tafakoritech.adminapplication.api.CreateSessionRequest
import ir.tafakoritech.adminapplication.api.CreateShellRequest
import ir.tafakoritech.adminapplication.api.CreateTaskRequest
import ir.tafakoritech.adminapplication.api.CreateTerminalRequest
import kotlinx.coroutines.launch

// ---- Tasks ----------------------------------------------------------------

@Composable
internal fun TasksTab(client: CommanderClient, refreshKey: Int, onOpen: (String) -> Unit) {
    ListTab(
        refreshKey = refreshKey,
        load = { client.listTasks() },
        emptyTitle = "No tasks yet",
        emptySubtitle = "Tap + to run a one-off Claude task",
        key = { it.id },
    ) { task ->
        RowCard(onClick = { onOpen(task.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.prompt,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                StatusChip(task.status)
            }
            Spacer(Modifier.size(6.dp))
            Text(
                task.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun CreateTaskDialog(
    client: CommanderClient,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    FormDialog(
        title = "New task",
        confirmEnabled = prompt.isNotBlank() && path.isNotBlank(),
        onDismiss = onDismiss,
        submit = {
            client.createTask(
                CreateTaskRequest(
                    prompt = prompt.trim(),
                    path = path.trim(),
                    model = model.trim().ifBlank { null },
                )
            ).id
        },
        onCreated = onCreated,
    ) {
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        PathField(path) { path = it }
        ModelField(model) { model = it }
    }
}

// ---- Sessions -------------------------------------------------------------

@Composable
internal fun SessionsTab(client: CommanderClient, refreshKey: Int, onOpen: (String) -> Unit) {
    ListTab(
        refreshKey = refreshKey,
        load = { client.listSessions() },
        emptyTitle = "No sessions yet",
        emptySubtitle = "Tap + to start a stateful conversation",
        key = { it.id },
    ) { session ->
        RowCard(onClick = { onOpen(session.id) }) {
            Text(
                session.path,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "${session.taskIds.size} message(s)" +
                    (session.model?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun CreateSessionDialog(
    client: CommanderClient,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    FormDialog(
        title = "New session",
        confirmEnabled = path.isNotBlank(),
        onDismiss = onDismiss,
        submit = {
            client.createSession(
                CreateSessionRequest(path = path.trim(), model = model.trim().ifBlank { null })
            ).id
        },
        onCreated = onCreated,
    ) {
        PathField(path) { path = it }
        ModelField(model) { model = it }
    }
}

// ---- Terminals ------------------------------------------------------------

@Composable
internal fun TerminalsTab(client: CommanderClient, refreshKey: Int, onOpen: (String) -> Unit) {
    ListTab(
        refreshKey = refreshKey,
        load = { client.listTerminals() },
        emptyTitle = "No terminals yet",
        emptySubtitle = "Tap + to launch an interactive Claude PTY",
        key = { it.id },
    ) { term ->
        RowCard(onClick = { onOpen(term.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    term.path.ifBlank { term.id },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                Pill(
                    if (term.isRunning) "on" else "off",
                    if (term.isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun CreateTerminalDialog(
    client: CommanderClient,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    FormDialog(
        title = "New terminal",
        confirmEnabled = path.isNotBlank(),
        onDismiss = onDismiss,
        submit = {
            client.createTerminal(
                CreateTerminalRequest(path = path.trim(), model = model.trim().ifBlank { null })
            ).id
        },
        onCreated = onCreated,
    ) {
        PathField(path) { path = it }
        ModelField(model) { model = it }
    }
}

// ---- Shells (free terminals) ------------------------------------------------

@Composable
internal fun ShellsTab(client: CommanderClient, refreshKey: Int, onOpen: (String) -> Unit) {
    ListTab(
        refreshKey = refreshKey,
        load = { client.listShells() },
        emptyTitle = "No shells yet",
        emptySubtitle = "Tap + to open a free terminal on the server",
        key = { it.id },
    ) { shell ->
        RowCard(onClick = { onOpen(shell.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shell.commandDisplay,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                Pill(
                    if (shell.isRunning) "on" else "off",
                    if (shell.isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (shell.path.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    shell.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun CreateShellDialog(
    client: CommanderClient,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    FormDialog(
        title = "New shell",
        confirmEnabled = path.isNotBlank(),
        onDismiss = onDismiss,
        submit = {
            val cmd = command.trim()
            client.createShell(
                CreateShellRequest(
                    path = path.trim(),
                    command = if (cmd.isBlank()) null else cmd.split(" ")
                )
            ).id
        },
        onCreated = onCreated,
    ) {
        PathField(path) { path = it }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("Command (optional)") },
            placeholder = { Text("server \$SHELL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---- List Screen Wrappers -------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksListScreen(client: CommanderClient, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "New task")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TasksTab(client, refreshKey, onOpen)
        }
    }

    if (showCreate) {
        CreateTaskDialog(
            client = client,
            onDismiss = { showCreate = false },
            onCreated = { id -> showCreate = false; onOpen(id) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsListScreen(client: CommanderClient, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat / Sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "New session")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SessionsTab(client, refreshKey, onOpen)
        }
    }

    if (showCreate) {
        CreateSessionDialog(
            client = client,
            onDismiss = { showCreate = false },
            onCreated = { id -> showCreate = false; onOpen(id) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalsListScreen(client: CommanderClient, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "New terminal")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TerminalsTab(client, refreshKey, onOpen)
        }
    }

    if (showCreate) {
        CreateTerminalDialog(
            client = client,
            onDismiss = { showCreate = false },
            onCreated = { id -> showCreate = false; onOpen(id) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellsListScreen(client: CommanderClient, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shells") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "New shell")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ShellsTab(client, refreshKey, onOpen)
        }
    }

    if (showCreate) {
        CreateShellDialog(
            client = client,
            onDismiss = { showCreate = false },
            onCreated = { id -> showCreate = false; onOpen(id) }
        )
    }
}

// ---- Shared form pieces ---------------------------------------------------

@Composable
private fun PathField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Working directory (absolute path)") },
        placeholder = { Text("/home/user/project") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ModelField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Model (optional)") },
        placeholder = { Text("server default") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * A modal form that runs [submit] on a background coroutine when confirmed,
 * surfacing any error inline and forwarding the created id to [onCreated].
 */
@Composable
private fun FormDialog(
    title: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    submit: suspend () -> String,
    onCreated: (String) -> Unit,
    fields: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fields()
                if (error != null) ErrorBanner(error!!)
            }
        },
        confirmButton = {
            TextButton(
                enabled = confirmEnabled && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { submit() }
                            .onSuccess { onCreated(it) }
                            .onFailure { error = it.message; busy = false }
                    }
                }
            ) { Text(if (busy) "Creating…" else "Create") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
