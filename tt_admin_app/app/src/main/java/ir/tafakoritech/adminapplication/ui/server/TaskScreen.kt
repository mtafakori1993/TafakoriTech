package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.CommanderClient
import ir.tafakoritech.adminapplication.api.TaskDetail
import ir.tafakoritech.adminapplication.api.TaskStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskScreen(client: CommanderClient, taskId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<TaskDetail?>(null) }
    var liveLog by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    fun refresh() = scope.launch {
        runCatching { client.getTask(taskId) }
            .onSuccess { detail = it }
            .onFailure { error = it.message }
    }

    LaunchedEffect(taskId) { refresh() }

    DisposableEffect(taskId) {
        val source = client.streamTask(taskId, object : CommanderClient.SseListener {
            override fun onData(data: String) {
                liveLog += data + "\n"
            }
            override fun onClosed() { refresh() }
        })
        onDispose { source.cancel() }
    }

    LaunchedEffect(liveLog) { scroll.animateScrollTo(scroll.maxValue) }

    val running = detail?.status == TaskStatus.RUNNING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (running) {
                        IconButton(onClick = { scope.launch { runCatching { client.stopTask(taskId) }; refresh() } }) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            detail?.let { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { StatusChip(d.status) }
                        Text(d.prompt, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            d.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        d.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if (d.needsLogin) ErrorBanner("Server needs `claude auth login`")
                    }
                }
                Spacer(Modifier.size(12.dp))
            }
            if (error != null && detail == null) ErrorBanner(error!!)

            val body = liveLog.ifBlank { detail?.report.orEmpty() }
            Card(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(scroll)) {
                    ConsoleText(body)
                }
            }
        }
    }
}
