package ir.tafakoritech.adminapplication.ui.auth

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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.OtpResult
import ir.tafakoritech.adminapplication.api.SentinelClient
import ir.tafakoritech.adminapplication.data.AuthTokenStore
import kotlinx.coroutines.launch

private enum class LoginStep { Identifier, Otp, Mfa }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AuthTokenStore(context) }
    val scope = rememberCoroutineScope()

    var gatewayUrl by remember { mutableStateOf(store.gatewayUrl) }
    var identifier by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var mfaCode by remember { mutableStateOf("") }
    var mfaToken by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(LoginStep.Identifier) }
    var loading by remember { mutableStateOf(false) }
    var loadingLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Sign in") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Terminal,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "TafakoriTech Admin",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(32.dp))

            when (step) {
                LoginStep.Identifier -> {
                    OutlinedTextField(
                        value = gatewayUrl,
                        onValueChange = { gatewayUrl = it; error = null },
                        label = { Text("Gateway URL") },
                        placeholder = { Text("http://192.168.1.x:9506") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = identifier,
                        onValueChange = { identifier = it; error = null },
                        label = { Text("Phone or email") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.AccountCircle, null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )

                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                val client = SentinelClient(gatewayUrl.trim())
                                runCatching {
                                    loadingLabel = "Solving CAPTCHA…"
                                    val token = client.solveAltcha()
                                    loadingLabel = "Sending OTP…"
                                    client.requestOtp(identifier.trim(), token)
                                }.onSuccess {
                                    store.gatewayUrl = gatewayUrl.trim()
                                    step = LoginStep.Otp
                                }.onFailure { e ->
                                    error = e.message ?: "Could not reach the server"
                                }
                                loading = false
                            }
                        },
                        enabled = gatewayUrl.isNotBlank() && identifier.isNotBlank() && !loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (loading) loadingLabel else "Send OTP")
                    }
                }

                LoginStep.Otp -> {
                    Text(
                        "Enter the OTP sent to\n${identifier.trim()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = otp,
                        onValueChange = { otp = it; error = null },
                        label = { Text("One-time password") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )

                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                val client = SentinelClient(store.gatewayUrl)
                                runCatching {
                                    loadingLabel = "Solving CAPTCHA…"
                                    val token = client.solveAltcha()
                                    loadingLabel = "Verifying…"
                                    client.verifyOtp(identifier.trim(), otp.trim(), token)
                                }.onSuccess { result ->
                                    when (result) {
                                        is OtpResult.Success -> {
                                            store.saveTokens(result.tokens.apiKey, result.tokens.refreshKey)
                                            onLoggedIn()
                                        }
                                        is OtpResult.MfaRequired -> {
                                            mfaToken = result.mfaToken
                                            mfaCode = ""
                                            step = LoginStep.Mfa
                                        }
                                    }
                                }.onFailure { e ->
                                    error = e.message ?: "Invalid or expired OTP"
                                }
                                loading = false
                            }
                        },
                        enabled = otp.isNotBlank() && !loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (loading) loadingLabel else "Verify OTP")
                    }

                    Spacer(Modifier.height(8.dp))

                    TextButton(
                        onClick = { step = LoginStep.Identifier; otp = ""; error = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("← Change identifier")
                    }
                }

                LoginStep.Mfa -> {
                    Text(
                        "Two-factor authentication\nEnter the code from your authenticator app, or a recovery code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = mfaCode,
                        onValueChange = { mfaCode = it; error = null },
                        label = { Text("Authenticator or recovery code") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )

                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                val client = SentinelClient(store.gatewayUrl)
                                runCatching {
                                    loadingLabel = "Verifying…"
                                    client.mfaLogin(mfaToken, mfaCode.trim())
                                }.onSuccess { tokens ->
                                    store.saveTokens(tokens.apiKey, tokens.refreshKey)
                                    onLoggedIn()
                                }.onFailure { e ->
                                    error = e.message ?: "Invalid code"
                                }
                                loading = false
                            }
                        },
                        enabled = mfaCode.isNotBlank() && !loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (loading) loadingLabel else "Verify code")
                    }

                    Spacer(Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            step = LoginStep.Identifier
                            otp = ""; mfaCode = ""; mfaToken = ""; error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("← Start over")
                    }
                }
            }
        }
    }
}
