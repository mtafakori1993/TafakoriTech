package ir.tafakoritech.adminapplication.ui.projects

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.ProjectItem
import ir.tafakoritech.adminapplication.data.UrlDatabase
import ir.tafakoritech.adminapplication.data.UrlEntry
import ir.tafakoritech.adminapplication.ui.server.ServerDetailScreen
import ir.tafakoritech.adminapplication.ui.server.AddEntryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ProjectTab { Commander, Monitoring }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(project: ProjectItem, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val db = remember { UrlDatabase.get(context) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(ProjectTab.Commander) }
    var entries by remember { mutableStateOf<List<UrlEntry>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<UrlEntry?>(null) }
    var selectedServer by remember { mutableStateOf<UrlEntry?>(null) }

    val projectColor = remember(project.color) {
        runCatching { Color(android.graphics.Color.parseColor(project.color)) }
            .getOrDefault(Color(0xFF6750A4))
    }

    suspend fun reload() {
        entries = withContext(Dispatchers.IO) {
            db.getByProject(project.id)
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(project.name) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal, Modifier.fillMaxWidth()) {
                    Tab(
                        selected = selectedTab == ProjectTab.Commander,
                        onClick = { selectedTab = ProjectTab.Commander },
                        icon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                        text = { Text("Commander") }
                    )
                    Tab(
                        selected = selectedTab == ProjectTab.Monitoring,
                        onClick = { selectedTab = ProjectTab.Monitoring },
                        icon = { Icon(Icons.Rounded.MonitorHeart, contentDescription = null) },
                        text = { Text("Monitoring") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == ProjectTab.Commander) {
                FloatingActionButton(
                    onClick = { showAdd = true },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add server", modifier = Modifier.size(24.dp))
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                ProjectTab.Commander -> CommanderTabContent(
                    entries = entries,
                    onOpen = { selectedServer = it },
                    onEdit = { editingServer = it },
                    onDelete = { entry ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.delete(entry.id)
                                db.clearServerQueue(entry.id)
                                db.clearServerChatMessages(entry.id)
                            }
                            reload()
                        }
                    }
                )
                ProjectTab.Monitoring -> MonitoringTabContent()
            }
        }
    }

    // Add server dialog
    AnimatedVisibility(
        visible = showAdd,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        AddEntryScreen(
            projects = emptyList(),
            existing = null,
            onBack = { showAdd = false },
            onSave = { title, url, token, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        db.insert(title, url, token, project.id)
                    }
                    reload()
                    showAdd = false
                }
            }
        )
    }

    // Edit server dialog
    AnimatedVisibility(
        visible = editingServer != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        editingServer?.let { server ->
            AddEntryScreen(
                projects = emptyList(),
                existing = server,
                onBack = { editingServer = null },
                onSave = { title, url, token, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            db.update(server.id, title, url, token, project.id)
                        }
                        reload()
                        editingServer = null
                    }
                }
            )
        }
    }

    // Server detail screen
    AnimatedVisibility(
        visible = selectedServer != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        selectedServer?.let { server ->
            ServerDetailScreen(server = server, onClose = { selectedServer = null })
        }
    }
}

@Composable
private fun CommanderTabContent(
    entries: List<UrlEntry>,
    onOpen: (UrlEntry) -> Unit,
    onEdit: (UrlEntry) -> Unit,
    onDelete: (UrlEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(4.dp))
                Text("No servers yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap + to add a server",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                ProjectServerCard(
                    entry = entry,
                    onClick = { onOpen(entry) },
                    onEdit = { onEdit(entry) },
                    onDelete = { onDelete(entry) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectServerCard(
    entry: UrlEntry,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    androidx.compose.material3.Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    entry.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "Edit server",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete server",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete server?") },
            text = { Text("Remove \"${entry.title}\" from this project?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MonitoringTabContent() {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.MonitorHeart,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
            Text("Coming soon", style = MaterialTheme.typography.titleLarge)
            Text(
                "Monitoring features are under development",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
