package ir.tafakoritech.adminapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import ir.tafakoritech.adminapplication.auth.TokenManager
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.tafakoritech.adminapplication.api.HarborClient
import ir.tafakoritech.adminapplication.api.Lead
import ir.tafakoritech.adminapplication.api.SentinelClient
import ir.tafakoritech.adminapplication.data.AuthTokenStore
import ir.tafakoritech.adminapplication.util.normalizePhone
import ir.tafakoritech.adminapplication.ui.about.AboutScreen
import ir.tafakoritech.adminapplication.ui.auth.LoginScreen
import ir.tafakoritech.adminapplication.ui.sentinel.SentinelScreen
import ir.tafakoritech.adminapplication.ui.harbor.HarborScreen
import ir.tafakoritech.adminapplication.ui.herald.HeraldScreen
import ir.tafakoritech.adminapplication.ui.persona.PersonaScreen
import ir.tafakoritech.adminapplication.ui.projects.ProjectsScreen
import ir.tafakoritech.adminapplication.ui.theme.BrightGreen
import ir.tafakoritech.adminapplication.ui.theme.CommanderTheme
import ir.tafakoritech.adminapplication.ui.theme.DeepNavy
import ir.tafakoritech.adminapplication.ui.theme.FreshGreen
import ir.tafakoritech.adminapplication.ui.theme.ThemeMode
import ir.tafakoritech.adminapplication.ui.theme.ThemePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = ir.tafakoritech.adminapplication.data.AuthTokenStore(this)
        TokenManager.init(
            store = store,
            sentinel = ir.tafakoritech.adminapplication.api.SentinelClient(store.gatewayUrl),
        )

        NetworkMonitor.init(this)
        requestCallPermissions()
        handleIntent(intent)

        setContent {
            val context = LocalContext.current
            var themeMode by remember { mutableStateOf(ThemePreferences.load(context)) }
            CommanderTheme(themeMode = themeMode) {
                App(
                    themeMode = themeMode,
                    onThemeChange = { mode ->
                        themeMode = mode
                        ThemePreferences.save(context, mode)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isInForeground = true
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(CallReceiver.EXTRA_OPEN_HARBOR_CREATE, false)) {
            val phone = intent.getStringExtra(CallReceiver.EXTRA_PHONE_NUMBER) ?: return
            IncomingCallManager.onNotificationCreate(phone)
        }
        if (intent.getBooleanExtra(CallReceiver.EXTRA_OPEN_HARBOR, false)) {
            IncomingCallManager.onNotificationOpenHarbor()
        }
    }

    private fun requestCallPermissions() {
        val needed = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 0)
        }
    }

    companion object {
        @Volatile var isInForeground = false
    }
}

private enum class AppSection { Marshal, Harbor, Herald, Persona, Sentinel, Settings, About }

// ── Root ─────────────────────────────────────────────────────────────────────

@Composable
fun App(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    val context = LocalContext.current
    val store = remember { AuthTokenStore(context) }
    val sentinel = remember { SentinelClient(store.gatewayUrl) }
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(store.isLoggedIn) }

    LaunchedEffect(Unit) {
        TokenManager.onForceLogout = { loggedIn = false }
    }

    if (!loggedIn) {
        LoginScreen(onLoggedIn = { loggedIn = true })
        return
    }

    var openSection by remember { mutableStateOf<AppSection?>(null) }
    var pendingHarborPhone by remember { mutableStateOf<String?>(null) }

    val incomingPhone by IncomingCallManager.incomingPhone.collectAsState()
    val incomingLead by IncomingCallManager.incomingLead.collectAsState()
    val createPhone by IncomingCallManager.createPhone.collectAsState()
    val openHarborSignal by IncomingCallManager.openHarbor.collectAsState()
    // true while we're checking if the incoming number matches a lead
    var checkingLead by remember { mutableStateOf(false) }

    LaunchedEffect(createPhone) {
        if (createPhone != null) {
            pendingHarborPhone = createPhone
            openSection = AppSection.Harbor
            IncomingCallManager.clearCreate()
        }
    }

    LaunchedEffect(openHarborSignal) {
        if (openHarborSignal) {
            openSection = AppSection.Harbor
            IncomingCallManager.clearOpenHarbor()
        }
    }

    LaunchedEffect(incomingPhone) {
        if (incomingPhone == null) { checkingLead = false; return@LaunchedEffect }
        checkingLead = true
        val normalized = normalizePhone(incomingPhone!!)
        val token = store.apiKey.orEmpty()

        val networkLead = runCatching {
            HarborClient.searchLeads(normalized, token).data
                .firstOrNull { normalizePhone(it.phone) == normalized }
        }.getOrNull()

        IncomingCallManager.setIncomingLead(networkLead)
        checkingLead = false
    }

    Box(Modifier.fillMaxSize()) {
        SuperHomeScreen(
            onOpen = { openSection = it },
            onLogout = {
                scope.launch {
                    val refreshKey = store.refreshKey
                    if (refreshKey != null) {
                        runCatching { sentinel.logout(refreshKey) }
                    }
                    store.clear()
                    loggedIn = false
                }
            },
        )

        AnimatedVisibility(
            visible = openSection == AppSection.Marshal,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            BackHandler(onBack = { openSection = null })
            ProjectsScreen(onBack = { openSection = null })
        }

        AnimatedVisibility(
            visible = openSection == AppSection.Harbor,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            BackHandler(onBack = { openSection = null; pendingHarborPhone = null })
            HarborScreen(
                onBack = { openSection = null; pendingHarborPhone = null },
                autoCreatePhone = pendingHarborPhone,
                onAutoCreateConsumed = { pendingHarborPhone = null },
            )
        }

        AnimatedVisibility(
            visible = openSection == AppSection.Herald,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            BackHandler(onBack = { openSection = null })
            HeraldScreen(onBack = { openSection = null })
        }

        AnimatedVisibility(
            visible = openSection == AppSection.Persona,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            BackHandler(onBack = { openSection = null })
            PersonaScreen(onBack = { openSection = null })
        }

        AnimatedVisibility(
            visible = openSection == AppSection.Sentinel,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            BackHandler(onBack = { openSection = null })
            SentinelScreen(onBack = { openSection = null })
        }

        AnimatedVisibility(
            visible = openSection == AppSection.About,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            BackHandler(onBack = { openSection = null })
            AboutScreen(onBack = { openSection = null })
        }

        AnimatedVisibility(
            visible = openSection == AppSection.Settings,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            SettingsSection(
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                onClose = { openSection = null },
            )
        }

        // Incoming call overlay — shown after lead lookup completes
        if (incomingPhone != null && !checkingLead) {
            val lead = incomingLead
            if (lead != null) {
                KnownLeadCallDialog(
                    lead = lead,
                    onViewInHarbor = {
                        openSection = AppSection.Harbor
                        IncomingCallManager.dismissIncoming()
                    },
                    onDismiss = { IncomingCallManager.dismissIncoming() },
                )
            } else {
                IncomingCallDialog(
                    phone = incomingPhone!!,
                    onYes = {
                        pendingHarborPhone = incomingPhone
                        openSection = AppSection.Harbor
                        IncomingCallManager.dismissIncoming()
                    },
                    onNo = { IncomingCallManager.dismissIncoming() },
                )
            }
        }
    }
}

// ── Banner data ───────────────────────────────────────────────────────────────

private data class BannerSlide(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientEnd: Color,
)

private val bannerSlides = listOf(
    BannerSlide(
        "Project Workspaces",
        "Organise servers and files into named project folders.",
        Icons.Rounded.FolderOpen,
        Color(0xFF14532D), Color(0xFF0F172A),
    ),
)

// ── Incoming call dialogs ─────────────────────────────────────────────────────

@Composable
private fun IncomingCallDialog(phone: String, onYes: () -> Unit, onNo: () -> Unit) {
    val callGreen = Color(0xFF4CAF50)

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(callGreen.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Call,
                        contentDescription = null,
                        tint = callGreen,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Incoming Call",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    phone,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Is this a customer?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onNo,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("No") }
                    Button(
                        onClick = onYes,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = callGreen),
                    ) { Text("Yes") }
                }
            }
        }
    }
}

@Composable
private fun KnownLeadCallDialog(
    lead: Lead,
    onViewInHarbor: () -> Unit,
    onDismiss: () -> Unit,
) {
    val callGreen = Color(0xFF4CAF50)
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(callGreen.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        tint = callGreen,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Known Customer Calling",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    lead.fullName,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    lead.phone,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val (statusLabel, statusColor) = leadStatusDisplay(lead.status)
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
                Spacer(Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Dismiss") }
                    Button(
                        onClick = onViewInHarbor,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = callGreen),
                    ) { Text("View") }
                }
            }
        }
    }
}

private fun leadStatusDisplay(status: String): Pair<String, Color> = when (status) {
    "new"          -> "New" to Color(0xFF2196F3)
    "contacted"    -> "Contacted" to Color(0xFFFF9800)
    "qualified"    -> "Qualified" to Color(0xFF4CAF50)
    "converted"    -> "Converted" to Color(0xFF9C27B0)
    "lost"         -> "Lost" to Color(0xFFF44336)
    "disqualified" -> "Disqualified" to Color(0xFF607D8B)
    else           -> status to Color(0xFF9E9E9E)
}

// ── Super Home ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuperHomeScreen(onOpen: (AppSection) -> Unit, onLogout: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var drawerSelected by remember { mutableStateOf<AppSection?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(FreshGreen, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = DeepNavy,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "TafakoriTech",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                    label = { Text("Marshal") },
                    selected = drawerSelected == AppSection.Marshal,
                    onClick = {
                        drawerSelected = AppSection.Marshal
                        scope.launch { drawerState.close() }
                        onOpen(AppSection.Marshal)
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.AccountCircle, contentDescription = null) },
                    label = { Text("Harbor") },
                    selected = drawerSelected == AppSection.Harbor,
                    onClick = {
                        drawerSelected = AppSection.Harbor
                        scope.launch { drawerState.close() }
                        onOpen(AppSection.Harbor)
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                    label = { Text("Herald") },
                    selected = drawerSelected == AppSection.Herald,
                    onClick = {
                        drawerSelected = AppSection.Herald
                        scope.launch { drawerState.close() }
                        onOpen(AppSection.Herald)
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    label = { Text("Persona") },
                    selected = drawerSelected == AppSection.Persona,
                    onClick = {
                        drawerSelected = AppSection.Persona
                        scope.launch { drawerState.close() }
                        onOpen(AppSection.Persona)
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                    label = { Text("Security") },
                    selected = drawerSelected == AppSection.Sentinel,
                    onClick = {
                        drawerSelected = AppSection.Sentinel
                        scope.launch { drawerState.close() }
                        onOpen(AppSection.Sentinel)
                    },
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = drawerSelected == AppSection.Settings,
                    onClick = {
                        drawerSelected = AppSection.Settings
                        scope.launch { drawerState.close() }
                        onOpen(AppSection.Settings)
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    label = { Text("About") },
                    selected = drawerSelected == AppSection.About,
                    onClick = {
                        drawerSelected = AppSection.About
                        scope.launch { drawerState.close() }
                        onOpen(AppSection.About)
                    },
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null) },
                    label = { Text("Sign out") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onLogout()
                    },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            // Top bar with burger icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(
                        Icons.Rounded.Menu,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Dashboard",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Choose a module",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Auto-scrolling carousel banner
            BannerCarousel(Modifier.fillMaxWidth())

            Spacer(Modifier.height(20.dp))

            Text(
                "Modules",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(10.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    GridFeatureCard(
                        title = "Marshal",
                        icon = Icons.Rounded.FolderOpen,
                        onClick = { onOpen(AppSection.Marshal) },
                    )
                }
                item {
                    GridFeatureCard(
                        title = "Harbor",
                        icon = Icons.Rounded.AccountCircle,
                        onClick = { onOpen(AppSection.Harbor) },
                    )
                }
                item {
                    GridFeatureCard(
                        title = "Herald",
                        icon = Icons.Rounded.Dns,
                        onClick = { onOpen(AppSection.Herald) },
                    )
                }
                item {
                    GridFeatureCard(
                        title = "Persona",
                        icon = Icons.Rounded.Key,
                        onClick = { onOpen(AppSection.Persona) },
                    )
                }
                item {
                    GridFeatureCard(
                        title = "Security",
                        icon = Icons.Rounded.Security,
                        onClick = { onOpen(AppSection.Sentinel) },
                    )
                }
            }
        }
    }
}

// ── Banner carousel ───────────────────────────────────────────────────────────

private const val CAROUSEL_VIRTUAL_PAGES = Int.MAX_VALUE

@Composable
private fun BannerCarousel(modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(
        initialPage = CAROUSEL_VIRTUAL_PAGES / 2,
        pageCount = { CAROUSEL_VIRTUAL_PAGES },
    )

    // settledPage only changes after the animation fully ends, so no mid-animation re-trigger
    LaunchedEffect(pagerState.settledPage) {
        delay(3500)
        pagerState.animateScrollToPage(pagerState.settledPage + 1)
    }

    Column(modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) { page ->
            val slide = bannerSlides[page % bannerSlides.size]
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(slide.gradientStart, slide.gradientEnd),
                        )
                    )
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .background(FreshGreen.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            slide.icon,
                            contentDescription = null,
                            tint = BrightGreen,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            slide.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            slide.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }

        // Page dots
        val currentSlide = pagerState.currentPage % bannerSlides.size
        Row(
            Modifier
                .wrapContentHeight()
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(bannerSlides.size) { idx ->
                val selected = currentSlide == idx
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (selected) 18.dp else 6.dp, 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) FreshGreen
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        ),
                )
            }
        }
    }
}

// ── Grid feature card ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridFeatureCard(
    title: String,
    icon: ImageVector,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge != null) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}

// ── Settings ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSection(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            ThemeOptionRow(
                icon = Icons.Rounded.BrightnessAuto,
                label = "System default",
                selected = themeMode == ThemeMode.System,
                onClick = { onThemeChange(ThemeMode.System) },
            )
            ThemeOptionRow(
                icon = Icons.Rounded.LightMode,
                label = "Light",
                selected = themeMode == ThemeMode.Light,
                onClick = { onThemeChange(ThemeMode.Light) },
            )
            ThemeOptionRow(
                icon = Icons.Rounded.DarkMode,
                label = "Dark",
                selected = themeMode == ThemeMode.Dark,
                onClick = { onThemeChange(ThemeMode.Dark) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeOptionRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(14.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
