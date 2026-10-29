package ir.tafakoritech.adminapplication.ui.marketplace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ir.tafakoritech.adminapplication.api.CommanderClient
import ir.tafakoritech.adminapplication.api.ExecRequest
import ir.tafakoritech.adminapplication.data.Plugin
import ir.tafakoritech.adminapplication.data.PluginCommand
import ir.tafakoritech.adminapplication.data.PluginLibrary
import ir.tafakoritech.adminapplication.data.UrlDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CommandRunState(
    val running: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
)

private val CommandRunState.combinedOutput: String
    get() = buildString {
        if (stdout.isNotBlank()) append(stdout.trimEnd())
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append(stderr.trimEnd())
        }
        if (error != null) {
            if (isNotEmpty()) append("\n")
            append("Error: $error")
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(
    pluginId: String,
    serverId: Long,
    client: CommanderClient,
    onBack: () -> Unit,
) {
    val plugin = PluginLibrary.getPlugin(pluginId) ?: return
    val context = LocalContext.current
    val db = remember { UrlDatabase.get(context) }
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf("") }
    var showPathDialog by remember { mutableStateOf(false) }
    val commandStates = remember { mutableStateMapOf<String, CommandRunState>() }
    var maximizedCmdId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        path = withContext(Dispatchers.IO) { db.getPluginPath(serverId) ?: "" }
    }

    fun runCommand(cmd: PluginCommand) {
        if (path.isBlank()) { showPathDialog = true; return }
        commandStates[cmd.id] = CommandRunState(running = true)
        scope.launch {
            runCatching {
                client.exec(ExecRequest(command = cmd.command, path = path))
            }.onSuccess { resp ->
                commandStates[cmd.id] = CommandRunState(
                    stdout = resp.stdout,
                    stderr = resp.stderr,
                    exitCode = resp.exitCode,
                )
            }.onFailure { e ->
                commandStates[cmd.id] = CommandRunState(error = e.message ?: "failed")
            }
        }
    }

    if (showPathDialog) {
        PathEditDialog(
            initial = path,
            onConfirm = { newPath ->
                path = newPath
                scope.launch { withContext(Dispatchers.IO) { db.setPluginPath(serverId, newPath) } }
                showPathDialog = false
            },
            onDismiss = { showPathDialog = false },
        )
    }

    maximizedCmdId?.let { cmdId ->
        val cmd = plugin.commands.find { it.id == cmdId }
        val state = commandStates[cmdId]
        if (cmd != null && state != null) {
            MaximizedOutputDialog(
                commandName = cmd.name,
                state = state,
                onDismiss = { maximizedCmdId = null },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(plugin.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { PluginHeader(plugin) }

            item { PathRow(path = path, onEdit = { showPathDialog = true }) }

            item {
                Text("Commands (${plugin.commands.size})", style = MaterialTheme.typography.titleMedium)
            }

            items(plugin.commands, key = { it.id }) { cmd ->
                CommandCard(
                    name = cmd.name,
                    description = cmd.description,
                    command = cmd.command,
                    runState = commandStates[cmd.id],
                    onRun = { runCommand(cmd) },
                    onMaximize = { maximizedCmdId = cmd.id },
                )
            }

            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun PathRow(path: String, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Working directory",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    path.ifBlank { "Tap edit to set a path" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (path.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Edit, contentDescription = "Edit path", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PathEditDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Working directory") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Absolute path") },
                placeholder = { Text("/home/user/project") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CommandCard(
    name: String,
    description: String,
    command: String,
    runState: CommandRunState?,
    onRun: () -> Unit,
    onMaximize: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(10.dp)
            ) {
                Text(
                    command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.size(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(command)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onRun,
                    enabled = runState?.running != true,
                    modifier = Modifier.weight(1f)
                ) {
                    if (runState?.running == true) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text("Run", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (runState != null && !runState.running) {
                Spacer(Modifier.size(10.dp))
                MiniTerminalOutput(state = runState, onMaximize = onMaximize)
            }
        }
    }
}

@Composable
private fun MiniTerminalOutput(state: CommandRunState, onMaximize: () -> Unit) {
    val scrollState = rememberScrollState()
    val output = state.combinedOutput

    LaunchedEffect(output) { scrollState.animateScrollTo(scrollState.maxValue) }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp, max = 160.dp)
                .background(Color(0xFF1A1A2E), MaterialTheme.shapes.small)
        ) {
            Text(
                text = output.ifBlank { "(no output)" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Color(0xFFDDDDDD),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(10.dp)
                    .padding(end = 32.dp)
            )
            IconButton(
                onClick = onMaximize,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(34.dp)
            ) {
                Icon(
                    Icons.Rounded.Fullscreen,
                    contentDescription = "Maximize",
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        val exitCode = state.exitCode
        if (exitCode != null) {
            val ok = exitCode == 0
            Box(
                modifier = Modifier
                    .background(
                        if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                        MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    if (ok) "exit 0 ✓" else "exit $exitCode ✗",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaximizedOutputDialog(
    commandName: String,
    state: CommandRunState,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0D0D1A)
        ) {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            commandName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
                )
                val scrollState = rememberScrollState()
                val output = state.combinedOutput
                LaunchedEffect(output) { scrollState.animateScrollTo(scrollState.maxValue) }
                Text(
                    text = output.ifBlank { "(no output)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFFDDDDDD),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp)
                )
                state.exitCode?.let { exitCode ->
                    val ok = exitCode == 0
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A2E))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    MaterialTheme.shapes.small
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (ok) "exit 0 ✓" else "exit $exitCode ✗",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginHeader(plugin: Plugin) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                PluginLibrary.getIcon(plugin.category),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.size(16.dp))
        Text(plugin.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(4.dp))
        Text(
            plugin.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Card(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        plugin.commands.size.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Commands",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Card(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        plugin.version,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
