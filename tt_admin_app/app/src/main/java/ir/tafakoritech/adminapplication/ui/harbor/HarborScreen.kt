package ir.tafakoritech.adminapplication.ui.harbor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.*
import ir.tafakoritech.adminapplication.data.AuthTokenStore
import kotlinx.coroutines.launch

private enum class HarborTab { Leads, Stats, Admin }
private enum class AdminSection { Channels, Sources, Platforms, AdTypes, Campaigns }

private val statusColors = mapOf(
    "new"          to Color(0xFF2196F3),
    "contacted"    to Color(0xFFFF9800),
    "qualified"    to Color(0xFF4CAF50),
    "converted"    to Color(0xFF009688),
    "lost"         to Color(0xFFF44336),
    "disqualified" to Color(0xFF9E9E9E),
)
private val allStatuses = listOf("new","contacted","qualified","converted","lost","disqualified")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarborScreen(
    onBack: () -> Unit,
    autoCreatePhone: String? = null,
    onAutoCreateConsumed: () -> Unit = {},
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val token = remember { AuthTokenStore(context).apiKey.orEmpty() }
    var selectedTab by remember { mutableStateOf(HarborTab.Leads) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Harbor CRM") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    HarborTab.entries.forEach { tab ->
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
                HarborTab.Leads -> LeadsTab(token, autoCreatePhone, onAutoCreateConsumed)
                HarborTab.Stats -> StatsTab(token)
                HarborTab.Admin -> AdminTab(token)
            }
        }
    }
}

// ── Leads tab ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeadsTab(
    token: String,
    autoCreatePhone: String? = null,
    onAutoCreateConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val leads = remember { mutableStateListOf<Lead>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var createInitialPhone by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<Lead?>(null) }
    var contactTarget by remember { mutableStateOf<Lead?>(null) }
    var deleteTarget by remember { mutableStateOf<Lead?>(null) }
    var convertTarget by remember { mutableStateOf<Lead?>(null) }

    fun loadLeads(reset: Boolean = true) {
        scope.launch {
            if (reset) { loading = true; error = null }
            val q = searchQuery.trim()
            runCatching {
                if (q.isEmpty()) HarborClient.listLeads(token, if (reset) null else nextCursor)
                else HarborClient.searchLeads(q, token, if (reset) null else nextCursor)
            }.onSuccess { page ->
                if (reset) leads.clear()
                leads.addAll(page.data)
                nextCursor = page.nextCursor
                hasMore = page.hasMore
            }.onFailure { e ->
                if (reset) error = e.message else error = e.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadLeads() }

    LaunchedEffect(autoCreatePhone) {
        if (autoCreatePhone != null) {
            createInitialPhone = autoCreatePhone
            showCreate = true
            onAutoCreateConsumed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search by name or phone…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = ""; loadLeads() }) {
                        Icon(Icons.Rounded.Clear, null)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(12.dp),
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> ErrorState(error!!) { loadLeads() }
                leads.isEmpty() -> EmptyState(Icons.Rounded.AccountCircle, "No leads", "Tap + to add")
                else -> LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(leads, key = { it.id }) { lead ->
                        LeadCard(
                            lead = lead,
                            onEdit = { editTarget = lead },
                            onDelete = { deleteTarget = lead },
                            onContact = { contactTarget = lead },
                            onConvert = { convertTarget = lead },
                        )
                    }
                    if (hasMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) {
                                TextButton(onClick = { loadLeads(reset = false) }) { Text("Load more") }
                            }
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
        LeadFormDialog(
            initial = null,
            initialPhone = createInitialPhone,
            token = token,
            onDismiss = { showCreate = false; createInitialPhone = null },
            onDone = { showCreate = false; createInitialPhone = null; loadLeads() },
        )
    }
    editTarget?.let { lead ->
        LeadFormDialog(
            initial = lead,
            token = token,
            onDismiss = { editTarget = null },
            onDone = { editTarget = null; loadLeads() },
        )
    }
    contactTarget?.let { lead ->
        ContactDialog(
            lead = lead,
            token = token,
            onDismiss = { contactTarget = null },
            onDone = { contactTarget = null; loadLeads() },
        )
    }
    convertTarget?.let { lead ->
        AlertDialog(
            onDismissRequest = { convertTarget = null },
            title = { Text("Convert lead?") },
            text = { Text("Mark \"${lead.fullName}\" as converted?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { HarborClient.convertLead(lead.id, token) }
                        convertTarget = null; loadLeads()
                    }
                }) { Text("Convert", color = Color(0xFF009688)) }
            },
            dismissButton = { TextButton(onClick = { convertTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { lead ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete lead?") },
            text = { Text("\"${lead.fullName}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { HarborClient.deleteLead(lead.id, token) }
                        deleteTarget = null; loadLeads()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeadCard(
    lead: Lead,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onContact: () -> Unit,
    onConvert: () -> Unit,
) {
    val color = statusColors[lead.status] ?: MaterialTheme.colorScheme.primary
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(color.copy(alpha = 0.15f), CircleShape),
                    Alignment.Center,
                ) { Icon(Icons.Rounded.AccountCircle, null, tint = color, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(lead.fullName, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(lead.phone, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(lead.status.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall, color = color)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (lead.initialIdea.isNotBlank()) {
                    Text(lead.initialIdea, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onContact) { Text("Contact") }
                    if (lead.status != "converted") {
                        TextButton(onClick = onConvert) { Text("Convert") }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun LeadFormDialog(
    initial: Lead?,
    initialPhone: String? = null,
    token: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fullName by remember { mutableStateOf(initial?.fullName ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: initialPhone ?: "") }
    var idea by remember { mutableStateOf(initial?.initialIdea ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var status by remember { mutableStateOf(initial?.status ?: "new") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New lead" else "Edit lead") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(fullName, { fullName = it }, label = { Text("Full name *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(idea, { idea = it }, label = { Text("Initial idea") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
                if (initial != null) {
                    StatusDropdown(status) { status = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = fullName.isNotBlank() && phone.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching {
                            if (initial == null) {
                                HarborClient.createLead(CreateLeadRequest(fullName.trim(), phone.trim(), idea.trim(), notes.trim()), token)
                            } else {
                                HarborClient.updateLead(initial.id, UpdateLeadRequest(
                                    fullName = fullName.trim(), phone = phone.trim(),
                                    initialIdea = idea.trim(), notes = notes.trim(), status = status,
                                ), token)
                            }
                        }.onSuccess { onDone() }.onFailure { e -> error = e.message; saving = false }
                    }
                }
            ) { Text(if (initial == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusDropdown(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = current.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Status") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            allStatuses.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.replaceFirstChar { it.uppercase() }) },
                    onClick = { onSelect(s); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ContactDialog(lead: Lead, token: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record contact") },
        text = {
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth(), maxLines = 4)
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    runCatching { HarborClient.recordContact(lead.id, note.trim(), token) }
                    onDone()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Stats tab ─────────────────────────────────────────────────────────────────

@Composable
private fun StatsTab(token: String) {
    var stats by remember { mutableStateOf<LeadStatistics?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true; error = null
            runCatching { HarborClient.getStatistics(token) }
                .onSuccess { s -> stats = s }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    when {
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        error != null -> ErrorState(error!!) { load() }
        stats != null -> {
            val s = stats!!
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Total", s.total.toString(), Modifier.weight(1f))
                    StatCard("Converted", s.converted.toString(), Modifier.weight(1f))
                    StatCard("Rate", "%.1f%%".format(s.conversionRate * 100), Modifier.weight(1f))
                }
                if (s.bySource.isNotEmpty()) {
                    SectionTitle("By Source")
                    s.bySource.forEach { GroupStatRow(it) }
                }
                if (s.byPlatform.isNotEmpty()) {
                    SectionTitle("By Platform")
                    s.byPlatform.forEach { GroupStatRow(it) }
                }
                if (s.byCampaign.isNotEmpty()) {
                    SectionTitle("By Campaign")
                    s.byCampaign.forEach { CampaignStatRow(it) }
                }
                if (s.dailyVolume.isNotEmpty()) {
                    SectionTitle("Daily volume (last ${s.dailyVolume.size} days)")
                    s.dailyVolume.takeLast(14).forEach { day ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(day.date, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(100.dp))
                            LinearProgressIndicator(
                                progress = { (day.count.toFloat() / (s.dailyVolume.maxOfOrNull { it.count }?.toFloat()?.coerceAtLeast(1f) ?: 1f)) },
                                modifier = Modifier.weight(1f).height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(day.count.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun GroupStatRow(stat: GroupStat) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stat.name.ifBlank { "Unknown" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(stat.count.toString(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun CampaignStatRow(stat: CampaignStat) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stat.name, style = MaterialTheme.typography.bodyMedium)
            Text("${stat.converted}/${stat.total} · %.1f%%".format(stat.conversionRate * 100),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Admin tab ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminTab(token: String) {
    var section by remember { mutableStateOf(AdminSection.Channels) }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = 0.dp) {
            AdminSection.entries.forEach { s ->
                Tab(
                    selected = section == s,
                    onClick = { section = s },
                    text = { Text(s.name, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when (section) {
                AdminSection.Channels   -> RefListTab(token,
                    load = { HarborClient.listContactChannels(it).data },
                    create = { name, desc, tok -> HarborClient.createContactChannel(name, desc, tok) },
                    label = "channel",
                )
                AdminSection.Sources    -> RefListTab(token,
                    load = { HarborClient.listLeadSources(it).data },
                    create = { name, desc, tok -> HarborClient.createLeadSource(name, desc, tok) },
                    label = "source",
                )
                AdminSection.Platforms  -> RefListTab(token,
                    load = { HarborClient.listAdPlatforms(it).data },
                    create = { name, desc, tok -> HarborClient.createAdPlatform(name, desc, tok) },
                    label = "platform",
                )
                AdminSection.AdTypes    -> RefListTab(token,
                    load = { HarborClient.listAdTypes(it).data },
                    create = { name, desc, tok -> HarborClient.createAdType(name, desc, null, tok) },
                    label = "ad type",
                )
                AdminSection.Campaigns  -> RefListTab(token,
                    load = { HarborClient.listCampaigns(it).data },
                    create = { name, desc, tok -> HarborClient.createCampaign(name, desc, null, null, tok) },
                    label = "campaign",
                )
            }
        }
    }
}

@Composable
private fun <T : Any> RefListTab(
    token: String,
    load: suspend (String) -> List<T>,
    create: suspend (String, String, String) -> T,
    label: String,
    nameOf: (T) -> String = { item ->
        when (item) {
            is ContactChannel -> item.name
            is LeadSource     -> item.name
            is AdPlatform     -> item.name
            is AdType         -> item.name
            is Campaign       -> item.name
            else              -> item.toString()
        }
    },
    descOf: (T) -> String = { item ->
        when (item) {
            is ContactChannel -> item.description
            is LeadSource     -> item.description
            is AdPlatform     -> item.description
            is AdType         -> item.description
            is Campaign       -> item.description
            else              -> ""
        }
    },
) {
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<T>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true; error = null
            runCatching { load(token) }
                .onSuccess { list ->
                    items.clear()
                    items.addAll(list)
                }
                .onFailure { e -> error = e.message }
            loading = false
        }
    }
    LaunchedEffect(label) { reload() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null -> ErrorState(error!!) { reload() }
            items.isEmpty() -> EmptyState(Icons.Rounded.List, "No ${label}s", "Tap + to add")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(nameOf(item), style = MaterialTheme.typography.titleSmall)
                            val desc = descOf(item)
                            if (desc.isNotBlank()) {
                                Text(desc, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
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
        RefCreateDialog(
            label = label,
            onDismiss = { showCreate = false },
            onConfirm = { name, desc ->
                scope.launch {
                    runCatching { create(name, desc, token) }
                        .onSuccess { showCreate = false; reload() }
                }
            },
        )
    }
}

@Composable
private fun RefCreateDialog(label: String, onDismiss: () -> Unit, onConfirm: suspend (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { scope.launch { onConfirm(name.trim(), description.trim()) } },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
