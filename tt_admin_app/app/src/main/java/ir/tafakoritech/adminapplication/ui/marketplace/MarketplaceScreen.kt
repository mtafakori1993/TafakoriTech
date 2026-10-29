package ir.tafakoritech.adminapplication.ui.marketplace

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.data.Plugin
import ir.tafakoritech.adminapplication.data.PluginLibrary
import ir.tafakoritech.adminapplication.ui.theme.FreshGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    serverId: Long,
    installedPluginIds: List<String>,
    onInstall: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onPluginClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedCategory != null) { selectedCategory = null }

    val categories = remember { PluginLibrary.getCategories() }

    val searchResults = remember(searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else PluginLibrary.all.filter { p ->
            p.name.contains(searchQuery, ignoreCase = true) ||
                p.description.contains(searchQuery, ignoreCase = true) ||
                p.category.contains(searchQuery, ignoreCase = true)
        }
    }

    val inCategory = selectedCategory != null && searchQuery.isBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (inCategory) selectedCategory!! else "Plugin Marketplace") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedCategory != null) selectedCategory = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!inCategory) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search plugins") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            when {
                searchQuery.isNotBlank() -> {
                    if (searchResults.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No plugins match \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Text(
                                    "${searchResults.size} result(s)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(searchResults, key = { it.id }) { plugin ->
                                PluginCard(
                                    plugin = plugin,
                                    isInstalled = installedPluginIds.contains(plugin.id),
                                    onInstall = { onInstall(plugin.id) },
                                    onUninstall = { onUninstall(plugin.id) },
                                    onDetail = { onPluginClick(plugin.id) },
                                )
                            }
                        }
                    }
                }

                inCategory -> {
                    val categoryPlugins = PluginLibrary.getByCategory(selectedCategory!!)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(categoryPlugins, key = { it.id }) { plugin ->
                            PluginCard(
                                plugin = plugin,
                                isInstalled = installedPluginIds.contains(plugin.id),
                                onInstall = { onInstall(plugin.id) },
                                onUninstall = { onUninstall(plugin.id) },
                                onDetail = { onPluginClick(plugin.id) },
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(categories, key = { it }) { category ->
                            val pluginCount = PluginLibrary.getByCategory(category).size
                            val installedCount = PluginLibrary.getByCategory(category)
                                .count { installedPluginIds.contains(it.id) }
                            CategoryCard(
                                category = category,
                                pluginCount = pluginCount,
                                installedCount = installedCount,
                                onClick = { selectedCategory = category },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryCard(
    category: String,
    pluginCount: Int,
    installedCount: Int,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    PluginLibrary.getIcon(category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(category, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append("$pluginCount plugin${if (pluginCount != 1) "s" else ""}")
                        if (installedCount > 0) append(" · $installedCount installed")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (installedCount > 0) {
                Text("✓", style = MaterialTheme.typography.labelLarge, color = FreshGreen)
                Spacer(Modifier.size(6.dp))
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginCard(
    plugin: Plugin,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onDetail: () -> Unit,
) {
    Card(onClick = onDetail, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        PluginLibrary.getIcon(plugin.category),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${plugin.commands.size} commands",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isInstalled) {
                    Text("✓", style = MaterialTheme.typography.titleLarge, color = FreshGreen)
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                plugin.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(12.dp))
            if (isInstalled) {
                Button(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) {
                    Text("Uninstall")
                }
            } else {
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text("Install")
                }
            }
        }
    }
}
