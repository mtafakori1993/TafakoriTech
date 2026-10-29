package ir.tafakoritech.adminapplication.ui.persona

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.*
import ir.tafakoritech.adminapplication.data.AuthTokenStore
import kotlinx.coroutines.launch

private enum class PersonaTab { Users, Roles, Teams, Permissions }

private val statusColors = mapOf(
    "active"    to Color(0xFF4CAF50),
    "inactive"  to Color(0xFF9E9E9E),
    "suspended" to Color(0xFFF44336),
)
private val userStatuses = listOf("active", "inactive", "suspended")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val token = remember { AuthTokenStore(context).apiKey.orEmpty() }
    var selectedTab by remember { mutableStateOf(PersonaTab.Users) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Persona IAM") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    PersonaTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.name) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                PersonaTab.Users       -> UsersTab(token)
                PersonaTab.Roles       -> RolesTab(token)
                PersonaTab.Teams       -> TeamsTab(token)
                PersonaTab.Permissions -> PermissionsTab(token)
            }
        }
    }
}

// ── Users tab ─────────────────────────────────────────────────────────────────

@Composable
private fun UsersTab(token: String) {
    val scope = rememberCoroutineScope()
    val users = remember { mutableStateListOf<PersonaUser>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<PersonaUser?>(null) }
    var deleteTarget by remember { mutableStateOf<PersonaUser?>(null) }

    fun load(reset: Boolean = true) {
        scope.launch {
            loading = true; error = null
            runCatching { PersonaClient.listUsers(token, if (reset) null else nextCursor) }
                .onSuccess { resp ->
                    if (reset) users.clear()
                    users.addAll(resp.items)
                    nextCursor = resp.nextCursor
                    hasMore = resp.nextCursor != null
                }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Box(Modifier.fillMaxSize()) {
        when {
            loading && users.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null && users.isEmpty() -> ErrorBox(error!!) { load() }
            users.isEmpty() -> EmptyBox(Icons.Rounded.Person, "No users", "Tap + to create")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(users, key = { it.id }) { user ->
                    UserCard(user,
                        onEdit = { editTarget = user },
                        onDelete = { deleteTarget = user },
                    )
                }
                if (hasMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) {
                            TextButton(onClick = { load(false) }) { Text("Load more") }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) { Icon(Icons.Rounded.Add, null) }
    }

    if (showCreate) {
        UserFormDialog(
            initial = null,
            onDismiss = { showCreate = false },
            onConfirm = { firstName, lastName, email, phone, status ->
                scope.launch {
                    runCatching {
                        PersonaClient.createUser(
                            CreateUserRequest(
                                firstName = firstName,
                                lastName = lastName,
                                email = email,
                                phone = phone.trim().ifBlank { null },
                                status = status,
                            ), token
                        )
                    }.onSuccess { showCreate = false; load() }
                }
            },
        )
    }
    editTarget?.let { u ->
        UserFormDialog(
            initial = u,
            onDismiss = { editTarget = null },
            onConfirm = { firstName, lastName, _, phone, status ->
                scope.launch {
                    runCatching {
                        PersonaClient.updateUser(
                            u.id,
                            UpdateUserRequest(
                                firstName = firstName,
                                lastName = lastName,
                                phone = phone.trim().ifBlank { null },
                                status = status,
                            ), token
                        )
                    }.onSuccess { editTarget = null; load() }
                }
            },
        )
    }
    deleteTarget?.let { u ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete user?") },
            text = { Text("\"${u.fullName}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { PersonaClient.deleteUser(u.id, token) }
                        deleteTarget = null; load()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserCard(user: PersonaUser, onEdit: () -> Unit, onDelete: () -> Unit) {
    val color = statusColors[user.status] ?: MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(color.copy(alpha = 0.12f), CircleShape), Alignment.Center) {
                Text(user.firstName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.fullName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(user.status.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = color)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserFormDialog(
    initial: PersonaUser?,
    onDismiss: () -> Unit,
    onConfirm: (firstName: String, lastName: String, email: String, phone: String, status: String) -> Unit,
) {
    var firstName by remember { mutableStateOf(initial?.firstName ?: "") }
    var lastName by remember { mutableStateOf(initial?.lastName ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var status by remember { mutableStateOf(initial?.status ?: "active") }
    var statusExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New user" else "Edit user") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(firstName, { firstName = it }, label = { Text("First name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(lastName, { lastName = it }, label = { Text("Last name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (initial == null) {
                    OutlinedTextField(email, { email = it }, label = { Text("Email *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                ExposedDropdownMenuBox(statusExpanded, { statusExpanded = it }) {
                    OutlinedTextField(
                        value = status.replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(statusExpanded, { statusExpanded = false }) {
                        userStatuses.forEach { s ->
                            DropdownMenuItem(text = { Text(s.replaceFirstChar { it.uppercase() }) },
                                onClick = { status = s; statusExpanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = firstName.isNotBlank() && lastName.isNotBlank() && (initial != null || email.isNotBlank()),
                onClick = { onConfirm(firstName.trim(), lastName.trim(), email.trim(), phone.trim(), status) },
            ) { Text(if (initial == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Roles tab ─────────────────────────────────────────────────────────────────

@Composable
private fun RolesTab(token: String) {
    val scope = rememberCoroutineScope()
    val roles = remember { mutableStateListOf<PersonaRole>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PersonaRole?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            runCatching { PersonaClient.listRoles(token) }
                .onSuccess { resp -> roles.clear(); roles.addAll(resp.items) }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    SimpleListTab(
        items = roles,
        loading = loading,
        error = error,
        onRetry = { load() },
        nameOf = { it.name },
        subtitleOf = { it.description.ifBlank { it.slug } },
        onDelete = { deleteTarget = it },
        onFab = { showCreate = true },
        emptyIcon = Icons.Rounded.Shield,
        emptyTitle = "No roles",
    )

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New role") },
            text = {
                var name by remember { mutableStateOf("") }
                var slug by remember { mutableStateOf("") }
                var desc by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(slug, { slug = it }, label = { Text("Slug *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                }
            },
            confirmButton = { TextButton(onClick = { showCreate = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { r ->
        ConfirmDeleteDialog("role", r.name,
            onConfirm = { scope.launch { runCatching { PersonaClient.deleteRole(r.id, token) }; deleteTarget = null; load() } },
            onDismiss = { deleteTarget = null },
        )
    }
}

// ── Teams tab ─────────────────────────────────────────────────────────────────

@Composable
private fun TeamsTab(token: String) {
    val scope = rememberCoroutineScope()
    val teams = remember { mutableStateListOf<PersonaTeam>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PersonaTeam?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            runCatching { PersonaClient.listTeams(token) }
                .onSuccess { resp -> teams.clear(); teams.addAll(resp.items) }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    SimpleListTab(
        items = teams,
        loading = loading,
        error = error,
        onRetry = { load() },
        nameOf = { it.name },
        subtitleOf = { it.description },
        onDelete = { deleteTarget = it },
        onFab = { showCreate = true },
        emptyIcon = Icons.Rounded.Group,
        emptyTitle = "No teams",
    )

    if (showCreate) {
        NameDescDialog("New team", onDismiss = { showCreate = false }) { name, desc ->
            scope.launch {
                runCatching { PersonaClient.createTeam(CreateTeamRequest(name, desc), token) }
                    .onSuccess { showCreate = false; load() }
            }
        }
    }
    deleteTarget?.let { t ->
        ConfirmDeleteDialog("team", t.name,
            onConfirm = { scope.launch { runCatching { PersonaClient.deleteTeam(t.id, token) }; deleteTarget = null; load() } },
            onDismiss = { deleteTarget = null },
        )
    }
}

// ── Permissions tab ───────────────────────────────────────────────────────────

@Composable
private fun PermissionsTab(token: String) {
    val scope = rememberCoroutineScope()
    val permissions = remember { mutableStateListOf<PersonaPermission>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PersonaPermission?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            runCatching { PersonaClient.listPermissions(token) }
                .onSuccess { resp -> permissions.clear(); permissions.addAll(resp.items) }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    SimpleListTab(
        items = permissions,
        loading = loading,
        error = error,
        onRetry = { load() },
        nameOf = { it.name },
        subtitleOf = { "${it.resource}:${it.action}" },
        onDelete = { deleteTarget = it },
        onFab = { showCreate = true },
        emptyIcon = Icons.Rounded.Lock,
        emptyTitle = "No permissions",
    )

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var slug by remember { mutableStateOf("") }
        var resource by remember { mutableStateOf("") }
        var action by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New permission") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(slug, { slug = it }, label = { Text("Slug *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(resource, { resource = it }, label = { Text("Resource *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(action, { action = it }, label = { Text("Action *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && slug.isNotBlank() && resource.isNotBlank() && action.isNotBlank(),
                    onClick = {
                        scope.launch {
                            runCatching { PersonaClient.createPermission(CreatePermissionRequest(name.trim(), slug.trim(), resource.trim(), action.trim()), token) }
                                .onSuccess { showCreate = false; load() }
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { p ->
        ConfirmDeleteDialog("permission", p.name,
            onConfirm = { scope.launch { runCatching { PersonaClient.deletePermission(p.id, token) }; deleteTarget = null; load() } },
            onDismiss = { deleteTarget = null },
        )
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
private fun <T : Any> SimpleListTab(
    items: List<T>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    nameOf: (T) -> String,
    subtitleOf: (T) -> String,
    onDelete: (T) -> Unit,
    onFab: () -> Unit,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            loading && items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null && items.isEmpty() -> ErrorBox(error, onRetry)
            items.isEmpty() -> EmptyBox(emptyIcon, emptyTitle, "Tap + to create")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(nameOf(item), style = MaterialTheme.typography.titleSmall)
                                val sub = subtitleOf(item)
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onDelete(item) }) {
                                Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onFab,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) { Icon(Icons.Rounded.Add, null) }
    }
}

@Composable
private fun NameDescDialog(title: String, onDismiss: () -> Unit, onConfirm: suspend (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { scope.launch { onConfirm(name.trim(), desc.trim()) } }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDeleteDialog(type: String, name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $type?") },
        text = { Text("\"$name\" will be permanently removed.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Failed to load", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyBox(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
