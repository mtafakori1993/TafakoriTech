package ir.tafakoritech.adminapplication.ui.projects

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.ProjectApiClient
import ir.tafakoritech.adminapplication.api.ProjectItem
import ir.tafakoritech.adminapplication.data.AuthTokenStore
import ir.tafakoritech.adminapplication.data.CachedProject
import ir.tafakoritech.adminapplication.data.UrlDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Entry point ───────────────────────────────────────────────────────────────

@Composable
fun ProjectsScreen(onBack: () -> Unit) {
    ProjectListScreen(onBack = onBack)
}

// ── Project list ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectListScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val db = remember { UrlDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val store = remember { AuthTokenStore(context) }
    val client = remember {
        ProjectApiClient(store.gatewayUrl, store.apiKey)
    }

    val projects = remember { mutableStateListOf<ProjectItem>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ProjectItem?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectItem?>(null) }
    var selectedProject by remember { mutableStateOf<ProjectItem?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching { client.listProjects() }
                .onSuccess { list ->
                    projects.clear()
                    projects.addAll(list)
                    withContext(Dispatchers.IO) {
                        db.cacheProjects(list.map { CachedProject(it.id, it.name, it.color) })
                    }
                }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "New project")
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Failed to load projects", style = MaterialTheme.typography.titleMedium)
                    Text(error!!, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload() }) { Text("Retry") }
                }
                projects.isEmpty() -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.FolderOpen, null,
                        Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    Text("No projects yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap + to create your first project",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { selectedProject = project },
                            onEdit = { editTarget = project },
                            onDelete = { deleteTarget = project },
                        )
                    }
                }
            }
        }
    }

    // Create dialog
    if (showCreate) {
        ProjectFormDialog(
            initial = null,
            onDismiss = { showCreate = false },
            onConfirm = { name, description, color ->
                showCreate = false
                scope.launch {
                    runCatching { client.createProject(name, description, color) }
                        .onSuccess { reload() }
                        .onFailure { e -> error = e.message }
                }
            },
        )
    }

    // Edit dialog
    editTarget?.let { target ->
        ProjectFormDialog(
            initial = target,
            onDismiss = { editTarget = null },
            onConfirm = { name, description, color ->
                editTarget = null
                scope.launch {
                    runCatching { client.updateProject(target.id, name, description, color) }
                        .onSuccess { reload() }
                        .onFailure { e -> error = e.message }
                }
            },
        )
    }

    // Delete confirmation
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project?") },
            text = { Text("\"${target.name}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        runCatching { client.deleteProject(target.id) }
                            .onSuccess { reload() }
                            .onFailure { e -> error = e.message }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Project detail screen
    AnimatedVisibility(
        visible = selectedProject != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        selectedProject?.let { project ->
            ProjectDetailScreen(project = project, onBack = { selectedProject = null })
        }
    }
}

// ── Project card ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCard(
    project: ProjectItem,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = remember(project.color) {
        runCatching { Color(android.graphics.Color.parseColor(project.color)) }
            .getOrDefault(Color(0xFF6750A4))
    }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.FolderOpen, null,
                    tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                if (project.description.isNotBlank()) {
                    Spacer(Modifier.size(2.dp))
                    Text(project.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2)
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.EditNote, contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── Create / edit dialog ──────────────────────────────────────────────────────

private val projectColors = listOf(
    "#6750A4", "#E91E63", "#F44336", "#FF9800",
    "#4CAF50", "#2196F3", "#00BCD4", "#607D8B",
)

@Composable
private fun ProjectFormDialog(
    initial: ProjectItem?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, color: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: projectColors.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New project" else "Edit project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                Text("Color", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(projectColors) { hex ->
                        val parsed = remember(hex) {
                            runCatching { Color(android.graphics.Color.parseColor(hex)) }
                                .getOrDefault(Color(0xFF6750A4))
                        }
                        val selected = color == hex
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parsed)
                                .then(
                                    if (selected) Modifier.border(2.dp,
                                        MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { color = hex },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(Icons.Rounded.Check, null,
                                    Modifier.size(16.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim(), color) },
                enabled = name.isNotBlank(),
            ) { Text(if (initial == null) "Create" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
