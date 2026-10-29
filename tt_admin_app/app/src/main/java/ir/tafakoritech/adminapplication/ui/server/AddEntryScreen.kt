package ir.tafakoritech.adminapplication.ui.server

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.QrScannerScreen
import ir.tafakoritech.adminapplication.data.UrlEntry

private enum class ScanTarget { Url, Token }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(
    projects: List<Any> = emptyList(),
    existing: UrlEntry? = null,
    onBack: () -> Unit,
    onSave: (title: String, url: String, token: String, projectId: Long?) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var token by remember { mutableStateOf(existing?.token ?: "") }
    var scanTarget by remember { mutableStateOf<ScanTarget?>(null) }

    BackHandler(onBack = { if (scanTarget != null) scanTarget = null else onBack() })

    if (scanTarget != null) {
        QrScannerScreen(onResult = { scanned ->
            if (scanned != null) {
                val (base, parsedToken) = splitUrlAndToken(scanned)
                when (scanTarget) {
                    ScanTarget.Url -> {
                        url = base
                        if (!parsedToken.isNullOrBlank()) token = parsedToken
                        if (title.isBlank()) title = base.toReadableTitle()
                    }
                    ScanTarget.Token -> token = (parsedToken ?: scanned).trim()
                    null -> {}
                }
            }
            scanTarget = null
        })
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = { Text(if (existing != null) "Edit server" else "Add server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            OutlinedTextField(
                value = url,
                onValueChange = { input ->
                    if (input.contains("token=", ignoreCase = true)) {
                        val (base, parsedToken) = splitUrlAndToken(input)
                        url = base
                        if (!parsedToken.isNullOrBlank()) token = parsedToken
                        if (title.isBlank()) title = base.toReadableTitle()
                    } else {
                        url = input
                    }
                },
                label = { Text("Base URL") },
                placeholder = { Text("http://192.168.1.10:8000") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { scanTarget = ScanTarget.Url }) {
                        Icon(
                            Icons.Rounded.QrCodeScanner,
                            contentDescription = "Scan QR code",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                )
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API token") },
                placeholder = { Text("X-API-Key shown on the server") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { scanTarget = ScanTarget.Token }) {
                        Icon(
                            Icons.Rounded.QrCodeScanner,
                            contentDescription = "Scan token QR code",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Spacer(Modifier.size(8.dp))

            Button(
                onClick = {
                    val finalTitle = title.ifBlank { url.toReadableTitle() }
                    onSave(finalTitle, url.trim(), token.trim(), null)
                },
                enabled = url.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Save")
            }
        }
    }
}

private fun splitUrlAndToken(raw: String): Pair<String, String?> {
    val trimmed = raw.trim()
    val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
    val token = runCatching { uri?.getQueryParameter("token") }.getOrNull()
    if (token.isNullOrBlank()) return trimmed to null
    val base = buildString {
        uri?.scheme?.let { append(it).append("://") }
        uri?.host?.let { append(it) }
        if ((uri?.port ?: -1) != -1) append(":").append(uri?.port)
    }.ifBlank { trimmed.substringBefore("?") }
    return base.trimEnd('/') to token.trim()
}

private fun String.toReadableTitle(): String {
    val trimmed = trim()
    val host = trimmed.substringAfter("://", trimmed).substringBefore("/").removePrefix("www.")
    return host.ifBlank { trimmed }
}
