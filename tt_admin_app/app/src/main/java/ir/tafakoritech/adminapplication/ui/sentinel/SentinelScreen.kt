package ir.tafakoritech.adminapplication.ui.sentinel

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.MfaSetupResponse
import ir.tafakoritech.adminapplication.api.MfaVerifySetupResponse
import ir.tafakoritech.adminapplication.api.RecoveryCodesResponse
import ir.tafakoritech.adminapplication.api.Session
import ir.tafakoritech.adminapplication.api.SentinelClient
import ir.tafakoritech.adminapplication.data.AuthTokenStore
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class MfaStep { Idle, SetupQr, SetupVerify, SetupDone, DisableInput }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AuthTokenStore(context) }
    val sentinel = remember { SentinelClient(store.gatewayUrl) }
    val apiKey = store.apiKey.orEmpty()
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var sessionsError by remember { mutableStateOf<String?>(null) }

    var mfaStep by remember { mutableStateOf(MfaStep.Idle) }
    var mfaSetupData by remember { mutableStateOf<MfaSetupResponse?>(null) }
    var mfaCode by remember { mutableStateOf("") }
    var mfaRecoveryCodes by remember { mutableStateOf<List<String>?>(null) }
    var mfaLoading by remember { mutableStateOf(false) }
    var mfaError by remember { mutableStateOf<String?>(null) }

    var lockUserId by remember { mutableStateOf("") }
    var lockReason by remember { mutableStateOf("") }
    var accountLoading by remember { mutableStateOf(false) }
    var accountMsg by remember { mutableStateOf<String?>(null) }

    fun loadSessions() {
        scope.launch {
            sessionsLoading = true
            sessionsError = null
            runCatching { sessions = sentinel.getSessions(apiKey).sessions }
                .onFailure { sessionsError = it.message }
            sessionsLoading = false
        }
    }

    LaunchedEffect(Unit) { loadSessions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Sessions ──────────────────────────────────────────────────────

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Active Sessions",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    if (sessionsLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = ::loadSessions) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh sessions")
                        }
                    }
                }
            }

            if (sessionsError != null) {
                item {
                    Text(
                        sessionsError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            items(sessions, key = { it.sessionUuid }) { session ->
                SessionCard(
                    session = session,
                    onRevoke = {
                        scope.launch {
                            runCatching { sentinel.deleteSession(apiKey, session.sessionUuid) }
                                .onSuccess { loadSessions() }
                                .onFailure { sessionsError = it.message }
                        }
                    },
                )
            }

            if (sessions.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                sessionsLoading = true
                                sessionsError = null
                                runCatching { sentinel.logoutAll(apiKey) }
                                    .onSuccess { sessions = emptyList() }
                                    .onFailure { sessionsError = it.message }
                                sessionsLoading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Log out all sessions")
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            // ── MFA ───────────────────────────────────────────────────────────

            item {
                Text(
                    "Two-Factor Authentication",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "TOTP-based second factor. Recovery codes are shown once — save them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                when (mfaStep) {
                    MfaStep.Idle -> MfaIdleSection(
                        loading = mfaLoading,
                        error = mfaError,
                        onSetup = {
                            scope.launch {
                                mfaLoading = true
                                mfaError = null
                                runCatching { sentinel.setupMfa(apiKey) }
                                    .onSuccess { mfaSetupData = it; mfaStep = MfaStep.SetupQr }
                                    .onFailure { mfaError = it.message }
                                mfaLoading = false
                            }
                        },
                        onDisable = { mfaStep = MfaStep.DisableInput; mfaCode = ""; mfaError = null },
                        onRegenCodes = {
                            scope.launch {
                                mfaLoading = true
                                mfaError = null
                                runCatching { sentinel.generateRecoveryCodes(apiKey) }
                                    .onSuccess { mfaRecoveryCodes = it.recoveryCodes; mfaStep = MfaStep.SetupDone }
                                    .onFailure { mfaError = it.message }
                                mfaLoading = false
                            }
                        },
                    )

                    MfaStep.SetupQr -> MfaSetupQrSection(
                        setup = mfaSetupData!!,
                        onNext = { mfaStep = MfaStep.SetupVerify; mfaCode = ""; mfaError = null },
                        onCancel = { mfaStep = MfaStep.Idle; mfaSetupData = null },
                    )

                    MfaStep.SetupVerify -> MfaVerifySection(
                        code = mfaCode,
                        onCodeChange = { mfaCode = it; mfaError = null },
                        loading = mfaLoading,
                        error = mfaError,
                        onVerify = {
                            scope.launch {
                                mfaLoading = true
                                mfaError = null
                                runCatching { sentinel.verifyMfaSetup(apiKey, mfaCode.trim()) }
                                    .onSuccess {
                                        mfaRecoveryCodes = it.recoveryCodes
                                        mfaStep = MfaStep.SetupDone
                                        mfaCode = ""
                                    }
                                    .onFailure { mfaError = it.message }
                                mfaLoading = false
                            }
                        },
                        onCancel = { mfaStep = MfaStep.Idle; mfaSetupData = null; mfaCode = "" },
                    )

                    MfaStep.SetupDone -> MfaRecoveryCodesSection(
                        codes = mfaRecoveryCodes ?: emptyList(),
                        onDone = { mfaStep = MfaStep.Idle; mfaRecoveryCodes = null; mfaSetupData = null },
                    )

                    MfaStep.DisableInput -> MfaDisableSection(
                        code = mfaCode,
                        onCodeChange = { mfaCode = it; mfaError = null },
                        loading = mfaLoading,
                        error = mfaError,
                        onDisable = {
                            scope.launch {
                                mfaLoading = true
                                mfaError = null
                                runCatching { sentinel.disableMfa(apiKey, mfaCode.trim()) }
                                    .onSuccess { mfaStep = MfaStep.Idle; mfaCode = "" }
                                    .onFailure { mfaError = it.message }
                                mfaLoading = false
                            }
                        },
                        onCancel = { mfaStep = MfaStep.Idle; mfaCode = ""; mfaError = null },
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            // ── Account Management (super_admin) ──────────────────────────────

            item {
                Text(
                    "Account Management",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Requires super_admin role",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = lockUserId,
                    onValueChange = { lockUserId = it; accountMsg = null },
                    label = { Text("User ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lockReason,
                    onValueChange = { lockReason = it; accountMsg = null },
                    label = { Text("Lock reason") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(8.dp))
                if (accountMsg != null) {
                    Text(
                        accountMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val uid = lockUserId.trim().toLongOrNull() ?: return@OutlinedButton
                            scope.launch {
                                accountLoading = true
                                accountMsg = null
                                runCatching {
                                    sentinel.lockAccount(apiKey, uid, lockReason.ifBlank { "admin action" })
                                }
                                    .onSuccess { accountMsg = "Account $uid locked" }
                                    .onFailure { accountMsg = "Error: ${it.message}" }
                                accountLoading = false
                            }
                        },
                        enabled = lockUserId.isNotBlank() && !accountLoading,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Lock") }
                    Button(
                        onClick = {
                            val uid = lockUserId.trim().toLongOrNull() ?: return@Button
                            scope.launch {
                                accountLoading = true
                                accountMsg = null
                                runCatching { sentinel.unlockAccount(apiKey, uid) }
                                    .onSuccess { accountMsg = "Account $uid unlocked" }
                                    .onFailure { accountMsg = "Error: ${it.message}" }
                                accountLoading = false
                            }
                        },
                        enabled = lockUserId.isNotBlank() && !accountLoading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Unlock") }
                }
                if (accountLoading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun SessionCard(session: Session, onRevoke: () -> Unit) {
    val fmt = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
    val lastActive = remember(session.lastActive) {
        runCatching { fmt.format(Instant.parse(session.lastActive)) }
            .getOrDefault(session.lastActive.take(16))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (session.revoked)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else
            CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.ipAddress.ifBlank { "Unknown IP" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    session.userAgent.take(72),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Last active: $lastActive",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!session.revoked) {
                IconButton(onClick = onRevoke) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Revoke session",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun MfaIdleSection(
    loading: Boolean,
    error: String?,
    onSetup: () -> Unit,
    onDisable: () -> Unit,
    onRegenCodes: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onSetup,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Enable TOTP")
        }
        OutlinedButton(
            onClick = onDisable,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Disable MFA") }
        OutlinedButton(
            onClick = onRegenCodes,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Regenerate Recovery Codes") }
    }
}

@Composable
private fun MfaSetupQrSection(
    setup: MfaSetupResponse,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bitmap = remember(setup.qrPngBase64) {
        runCatching {
            val bytes = Base64.decode(setup.qrPngBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Scan this QR code with your authenticator app", style = MaterialTheme.typography.bodyMedium)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "TOTP QR Code",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
        }
        Text(
            "Or enter the secret manually (tap to copy):",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().clickable {
                clipboard.setText(AnnotatedString(setup.secret))
                Toast.makeText(context, "Secret copied", Toast.LENGTH_SHORT).show()
            },
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    setup.secret,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "Copy secret",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("I've scanned it →") }
        }
    }
}

@Composable
private fun MfaVerifySection(
    code: String,
    onCodeChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onVerify: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Enter the 6-digit code from your authenticator app",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("TOTP code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
        )
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), enabled = !loading) {
                Text("Cancel")
            }
            Button(
                onClick = onVerify,
                enabled = code.isNotBlank() && !loading,
                modifier = Modifier.weight(1f),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Verify")
            }
        }
    }
}

@Composable
private fun MfaRecoveryCodesSection(codes: List<String>, onDone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Save your recovery codes",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            "These are shown once. Each code can be used once to regain access if you lose your authenticator. Tap to copy all.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().clickable {
                clipboard.setText(AnnotatedString(codes.joinToString("\n")))
                Toast.makeText(context, "Recovery codes copied", Toast.LENGTH_SHORT).show()
            },
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recovery codes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "Copy recovery codes",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                codes.forEach { code ->
                    Text(
                        code,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("I've saved these codes")
        }
    }
}

@Composable
private fun MfaDisableSection(
    code: String,
    onCodeChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onDisable: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Enter your current TOTP code or a recovery code to disable MFA",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("Code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), enabled = !loading) {
                Text("Cancel")
            }
            Button(
                onClick = onDisable,
                enabled = code.isNotBlank() && !loading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Disable MFA")
            }
        }
    }
}
