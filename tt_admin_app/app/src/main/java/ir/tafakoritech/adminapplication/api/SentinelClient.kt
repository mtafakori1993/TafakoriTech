package ir.tafakoritech.adminapplication.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class TokenPair(
    @SerialName("api_key") val apiKey: String,
    @SerialName("refresh_key") val refreshKey: String,
)

@Serializable
private data class OtpRequestBody(val identifier: String)

@Serializable
private data class OtpVerifyBody(val identifier: String, val otp: String)

@Serializable
private data class MfaChallengeResponse(
    @SerialName("mfa_required") val mfaRequired: Boolean = false,
    @SerialName("mfa_token") val mfaToken: String = "",
)

@Serializable
private data class MfaLoginBody(@SerialName("mfa_token") val mfaToken: String, val code: String)

/** Result of OTP verification: either logged in, or a second factor is required. */
sealed class OtpResult {
    data class Success(val tokens: TokenPair) : OtpResult()
    data class MfaRequired(val mfaToken: String) : OtpResult()
}

@Serializable
private data class RefreshBody(@SerialName("refresh_key") val refreshKey: String)

@Serializable
private data class AltchaChallenge(
    val algorithm: String,
    val challenge: String,
    val maxNumber: Long,
    val salt: String,
    val signature: String,
)

@Serializable
private data class AltchaSolution(
    val algorithm: String,
    val challenge: String,
    val number: Long,
    val salt: String,
    val signature: String,
)

@Serializable
private data class LogoutBody(@SerialName("refresh_key") val refreshKey: String)

@Serializable
private data class MfaCodeBody(val code: String)

@Serializable
private data class LockAccountBody(
    @SerialName("user_id") val userId: Long,
    val reason: String,
    @SerialName("locked_until") val lockedUntil: String? = null,
)

@Serializable
private data class UnlockAccountBody(@SerialName("user_id") val userId: Long)

@Serializable
data class Session(
    @SerialName("ID") val id: Long = 0,
    @SerialName("SessionUUID") val sessionUuid: String = "",
    @SerialName("UserID") val userId: Long = 0,
    @SerialName("IPAddress") val ipAddress: String = "",
    @SerialName("UserAgent") val userAgent: String = "",
    @SerialName("CreatedAt") val createdAt: String = "",
    @SerialName("LastActive") val lastActive: String = "",
    @SerialName("ExpiresAt") val expiresAt: String = "",
    @SerialName("Revoked") val revoked: Boolean = false,
    @SerialName("RevokedAt") val revokedAt: String? = null,
)

@Serializable
data class SessionsResponse(
    val sessions: List<Session> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
)

@Serializable
data class MfaSetupResponse(
    @SerialName("otpauth_url") val otpauthUrl: String,
    @SerialName("qr_png_base64") val qrPngBase64: String,
    val secret: String,
)

@Serializable
data class MfaVerifySetupResponse(
    val message: String,
    @SerialName("recovery_codes") val recoveryCodes: List<String>,
)

@Serializable
data class RecoveryCodesResponse(
    @SerialName("recovery_codes") val recoveryCodes: List<String>,
)

class SentinelClient(gatewayUrl: String) {

    private val base = gatewayUrl.trimEnd('/') + "/sentinel"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun post(path: String, body: String, captchaToken: String? = null): Request =
        Request.Builder()
            .url("$base$path")
            .header("Accept", "application/json")
            .apply { if (captchaToken != null) header("X-Captcha-Token", captchaToken) }
            .post(body.toRequestBody(jsonMedia))
            .build()

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }

    /**
     * Fetches an Altcha PoW challenge from the server and solves it on Dispatchers.Default.
     * Returns null if Altcha is not configured on the server (503) — callers proceed without a token.
     */
    suspend fun solveAltcha(): String? = withContext(Dispatchers.Default) {
        val challengeText = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(
                    Request.Builder().url("$base/altcha/challenge")
                        .header("Accept", "application/json").build()
                ).execute().use { resp ->
                    if (resp.code == 503) return@withContext null
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string()
                }
            }.getOrNull()
        } ?: return@withContext null

        val ch = runCatching { json.decodeFromString<AltchaChallenge>(challengeText) }
            .getOrNull() ?: return@withContext null

        // Parallel brute-force across all CPU cores.
        // Each worker owns a stripe of [0, maxNumber] and stops as soon as any worker finds it.
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val chunkSize = (ch.maxNumber / cores) + 1
        val found = AtomicLong(-1L)

        coroutineScope {
            (0 until cores).forEach { i ->
                launch {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize - 1, ch.maxNumber)
                    val digest = MessageDigest.getInstance("SHA-256")
                    for (n in start..end) {
                        if (!isActive || found.get() >= 0L) break
                        if (sha256hex(digest, ch.salt + n.toString()) == ch.challenge) {
                            found.compareAndSet(-1L, n)
                            break
                        }
                    }
                }
            }
        }

        val n = found.get()
        if (n < 0L) return@withContext null

        val solution = AltchaSolution(
            algorithm = ch.algorithm,
            challenge = ch.challenge,
            number = n,
            salt = ch.salt,
            signature = ch.signature,
        )
        val encoded = json.encodeToString(solution).toByteArray(Charsets.UTF_8)
        Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    /** Request an OTP for the given identifier. */
    suspend fun requestOtp(identifier: String, captchaToken: String?) {
        call(post("/otp/request", json.encodeToString(OtpRequestBody(identifier)), captchaToken))
    }

    /**
     * Verify OTP. Returns [OtpResult.Success] with tokens, or
     * [OtpResult.MfaRequired] when the account has MFA enabled and a second
     * factor must be supplied via [mfaLogin].
     */
    suspend fun verifyOtp(identifier: String, otp: String, captchaToken: String?): OtpResult {
        val body = call(post("/otp/verify", json.encodeToString(OtpVerifyBody(identifier, otp)), captchaToken))
        val challenge = runCatching { json.decodeFromString<MfaChallengeResponse>(body) }.getOrNull()
        if (challenge?.mfaRequired == true && challenge.mfaToken.isNotBlank()) {
            return OtpResult.MfaRequired(challenge.mfaToken)
        }
        return OtpResult.Success(json.decodeFromString(body))
    }

    /** Complete a login that required MFA, using the challenge token + TOTP/recovery code. */
    suspend fun mfaLogin(mfaToken: String, code: String): TokenPair =
        json.decodeFromString(
            call(post("/mfa/login", json.encodeToString(MfaLoginBody(mfaToken, code))))
        )

    /** Exchange a refresh key for a new token pair. */
    suspend fun refresh(refreshKey: String): TokenPair =
        json.decodeFromString(
            call(post("/refresh", json.encodeToString(RefreshBody(refreshKey))))
        )

    /** Check if the sentinel service is reachable. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base/health").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun authGet(path: String, apiKey: String): Request =
        Request.Builder()
            .url("$base$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

    private fun authPost(path: String, body: String, apiKey: String): Request =
        Request.Builder()
            .url("$base$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(jsonMedia))
            .build()

    private fun authDelete(path: String, apiKey: String): Request =
        Request.Builder()
            .url("$base$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .delete()
            .build()

    /** Revoke the current session. */
    suspend fun logout(refreshKey: String) {
        call(post("/logout", json.encodeToString(LogoutBody(refreshKey))))
    }

    /** List active sessions (paginated). */
    suspend fun getSessions(apiKey: String, page: Int = 1, pageSize: Int = 20): SessionsResponse =
        json.decodeFromString(call(authGet("/sessions?page=$page&page_size=$pageSize", apiKey)))

    /** Revoke a specific session by UUID. */
    suspend fun deleteSession(apiKey: String, sessionUuid: String) {
        call(authDelete("/sessions/$sessionUuid", apiKey))
    }

    /** Revoke all sessions for the current user. */
    suspend fun logoutAll(apiKey: String) {
        call(authPost("/sessions/logout-all", "{}", apiKey))
    }

    /** Begin TOTP enrollment. Returns QR PNG (base64) and the raw secret for manual entry. */
    suspend fun setupMfa(apiKey: String): MfaSetupResponse =
        json.decodeFromString(call(authPost("/mfa/setup", "{}", apiKey)))

    /** Confirm first TOTP code, activate MFA, and get single-use recovery codes. */
    suspend fun verifyMfaSetup(apiKey: String, code: String): MfaVerifySetupResponse =
        json.decodeFromString(
            call(authPost("/mfa/verify-setup", json.encodeToString(MfaCodeBody(code)), apiKey))
        )

    /** Disable MFA using a current TOTP code or a recovery code. */
    suspend fun disableMfa(apiKey: String, code: String) {
        call(authPost("/mfa/disable", json.encodeToString(MfaCodeBody(code)), apiKey))
    }

    /** Regenerate recovery codes (MFA must already be enabled). */
    suspend fun generateRecoveryCodes(apiKey: String): RecoveryCodesResponse =
        json.decodeFromString(call(authPost("/mfa/recovery-codes/generate", "{}", apiKey)))

    /** Lock a user account. Requires super_admin role. */
    suspend fun lockAccount(apiKey: String, userId: Long, reason: String, lockedUntil: String? = null) {
        call(authPost("/accounts/lock", json.encodeToString(LockAccountBody(userId, reason, lockedUntil)), apiKey))
    }

    /** Unlock a user account. Requires super_admin role. */
    suspend fun unlockAccount(apiKey: String, userId: Long) {
        call(authPost("/accounts/unlock", json.encodeToString(UnlockAccountBody(userId)), apiKey))
    }

    private fun sha256hex(digest: MessageDigest, input: String): String {
        digest.reset()
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.fold(StringBuilder(64)) { sb, b ->
            sb.append("%02x".format(b.toInt() and 0xFF))
        }.toString()
    }
}
