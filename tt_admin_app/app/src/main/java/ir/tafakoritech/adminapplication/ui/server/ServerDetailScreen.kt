package ir.tafakoritech.adminapplication.ui.server

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import ir.tafakoritech.adminapplication.api.AuthStatus
import ir.tafakoritech.adminapplication.api.CommanderClient
import ir.tafakoritech.adminapplication.data.Plugin
import ir.tafakoritech.adminapplication.data.PluginLibrary
import ir.tafakoritech.adminapplication.data.UrlDatabase
import ir.tafakoritech.adminapplication.data.UrlEntry
import ir.tafakoritech.adminapplication.ui.marketplace.MarketplaceScreen
import ir.tafakoritech.adminapplication.ui.marketplace.PluginDetailScreen

private sealed interface Route {
    data object Home : Route
    data object TasksView : Route
    data object SessionsView : Route
    data object TerminalsView : Route
    data object ShellsView : Route
    data object PluginsView : Route
    data class TaskDetail(val id: String) : Route
    data class SessionDetail(val id: String) : Route
    data class TerminalDetail(val id: String) : Route
    data class ShellDetail(val id: String) : Route
    data object Marketplace : Route
    data class PluginDetailView(val pluginId: String) : Route
}

/**
 * Full-screen dashboard for a single Commander server. Hosts the auth/health
 * header and the Tasks / Sessions / Terminals tabs, and routes into the
 * per-item live views.
 */
@Composable
fun ServerDetailScreen(server: UrlEntry, onClose: () -> Unit) {
    val client = remember(server.id) { CommanderClient(server.baseUrl, server.token) }
    var route by remember { mutableStateOf<Route>(Route.Home) }

    BackHandler(enabled = route != Route.Home) { route = Route.Home }

    when (val r = route) {
        Route.Home -> ServerHome(
            server = server,
            client = client,
            onClose = onClose,
            onOpenTasks = { route = Route.TasksView },
            onOpenSessions = { route = Route.SessionsView },
            onOpenTerminals = { route = Route.TerminalsView },
            onOpenShells = { route = Route.ShellsView },
            onOpenPlugins = { route = Route.PluginsView },
        )
        Route.TasksView -> TasksListScreen(client, { route = Route.TaskDetail(it) }, { route = Route.Home })
        Route.SessionsView -> SessionsListScreen(client, { route = Route.SessionDetail(it) }, { route = Route.Home })
        Route.TerminalsView -> TerminalsListScreen(client, { route = Route.TerminalDetail(it) }, { route = Route.Home })
        Route.ShellsView -> ShellsListScreen(client, { route = Route.ShellDetail(it) }, { route = Route.Home })
        Route.PluginsView -> PluginsListScreen(
            server = server,
            client = client,
            onOpenPlugin = { route = Route.PluginDetailView(it) },
            onOpenMarketplace = { route = Route.Marketplace },
            onBack = { route = Route.Home },
        )
        is Route.TaskDetail -> TaskScreen(client, r.id) { route = Route.TasksView }
        is Route.SessionDetail -> SessionScreen(client, server.id, r.id) { route = Route.SessionsView }
        is Route.TerminalDetail -> TerminalScreen(server, client, r.id) { route = Route.TerminalsView }
        is Route.ShellDetail -> ShellScreen(client, r.id) { route = Route.ShellsView }
        Route.Marketplace -> MarketplaceScreenWrapper(
            server = server,
            client = client,
            onPluginClick = { route = Route.PluginDetailView(it) },
            onBack = { route = Route.PluginsView }
        )
        is Route.PluginDetailView -> PluginDetailScreenWrapper(
            server = server,
            client = client,
            pluginId = r.pluginId,
            onBack = { route = Route.Marketplace }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerHome(
    server: UrlEntry,
    client: CommanderClient,
    onClose: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenTerminals: () -> Unit,
    onOpenShells: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    var refreshKey by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            server.baseUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AuthHeader(client, refreshKey)
            }

            item {
                Text("Features", style = MaterialTheme.typography.titleMedium)
            }

            item {
                DashboardGrid(
                    onOpenTasks = onOpenTasks,
                    onOpenSessions = onOpenSessions,
                    onOpenTerminals = onOpenTerminals,
                    onOpenShells = onOpenShells,
                    onOpenPlugins = onOpenPlugins
                )
            }
        }
    }
}

@Composable
private fun DashboardGrid(
    onOpenTasks: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenTerminals: () -> Unit,
    onOpenShells: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            DashboardCard(
                title = "Chat",
                description = "Conversations",
                icon = Icons.Rounded.ChatBubble,
                onClick = onOpenSessions,
                modifier = Modifier.weight(1f)
            )
            DashboardCard(
                title = "Terminals",
                description = "Claude PTY",
                icon = Icons.Rounded.Code,
                onClick = onOpenTerminals,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            DashboardCard(
                title = "Shells",
                description = "Free shells",
                icon = Icons.Rounded.DeveloperMode,
                onClick = onOpenShells,
                modifier = Modifier.weight(1f)
            )
            DashboardCard(
                title = "Plugins",
                description = "Marketplace",
                icon = Icons.Rounded.Extension,
                onClick = onOpenPlugins,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 120.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Health dot + Claude login status, refreshed whenever [refreshKey] changes. */
@Composable
private fun AuthHeader(client: CommanderClient, refreshKey: Int) {
    var healthy by remember { mutableStateOf<Boolean?>(null) }
    var auth by remember { mutableStateOf<AuthStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        error = null
        healthy = runCatching { client.health() }.getOrElse { false }
        runCatching { client.authStatus() }
            .onSuccess { auth = it }
            .onFailure { error = it.message }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusDot(ok = healthy == true)
        Column(Modifier.weight(1f)) {
            Text(
                text = when (healthy) {
                    null -> "Checking server…"
                    true -> "Server online"
                    false -> "Server unreachable"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            val authText = when {
                auth == null -> null
                auth!!.loggedIn -> "Claude: logged in"
                else -> auth!!.hint ?: "Claude: run `claude auth login`"
            }
            if (authText != null) {
                Text(
                    authText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (auth != null) Pill(if (auth!!.loggedIn) "auth ✓" else "no auth", MaterialTheme.colorScheme.primary)
    }
    if (error != null) {
        ErrorBanner(error!!, Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * Generic loader + list scaffold shared by the three tabs. Loads [load] whenever
 * [refreshKey] changes and renders each item with [item].
 */
@Composable
internal fun <T> ListTab(
    refreshKey: Int,
    load: suspend () -> List<T>,
    emptyTitle: String,
    emptySubtitle: String,
    key: (T) -> Any,
    item: @Composable (T) -> Unit,
) {
    var items by remember { mutableStateOf<List<T>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        runCatching { load() }
            .onSuccess { items = it }
            .onFailure { error = it.message }
        loading = false
    }

    when {
        loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        error != null && items.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
            ErrorBanner(error!!)
        }
        items.isEmpty() -> EmptyState(emptyTitle, emptySubtitle)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (error != null) item { ErrorBanner(error!!) }
            items(items, key = key) { item(it) }
        }
    }
}

/** A tappable row card used throughout the tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RowCard(onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

// ---- Plugins list screen --------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsListScreen(
    server: UrlEntry,
    client: CommanderClient,
    onOpenPlugin: (String) -> Unit,
    onOpenMarketplace: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { UrlDatabase.get(context) }
    var installedPluginIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        installedPluginIds = db.getInstalledPlugins(server.id)
    }

    val plugins = remember(installedPluginIds) {
        installedPluginIds.mapNotNull { PluginLibrary.getPlugin(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Plugins") },
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
            ExtendedFloatingActionButton(
                onClick = onOpenMarketplace,
                icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                text = { Text("Marketplace") }
            )
        }
    ) { padding ->
        if (plugins.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Rounded.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Text("No plugins installed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Browse the marketplace to add plugins",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(plugins, key = { it.id }) { plugin ->
                    RowCard(onClick = { onOpenPlugin(plugin.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    PluginLibrary.getIcon(plugin.category),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    plugin.name,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "${plugin.commands.size} commands · ${plugin.category}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketplaceScreenWrapper(
    server: UrlEntry,
    client: CommanderClient,
    onPluginClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { UrlDatabase.get(context) }
    var installedPlugins by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        installedPlugins = db.getInstalledPlugins(server.id)
    }

    MarketplaceScreen(
        serverId = server.id,
        installedPluginIds = installedPlugins,
        onInstall = { pluginId ->
            db.installPlugin(server.id, pluginId)
            refreshKey++
        },
        onUninstall = { pluginId ->
            db.uninstallPlugin(server.id, pluginId)
            refreshKey++
        },
        onPluginClick = onPluginClick,
        onBack = onBack
    )
}

@Composable
private fun PluginDetailScreenWrapper(
    server: UrlEntry,
    client: CommanderClient,
    pluginId: String,
    onBack: () -> Unit,
) {
    PluginDetailScreen(
        pluginId = pluginId,
        serverId = server.id,
        client = client,
        onBack = onBack,
    )
}

