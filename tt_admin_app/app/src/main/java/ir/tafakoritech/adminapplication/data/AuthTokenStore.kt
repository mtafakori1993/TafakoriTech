package ir.tafakoritech.adminapplication.data

import android.content.Context
import ir.tafakoritech.adminapplication.BuildConfig

class AuthTokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    var gatewayUrl: String
        get() = prefs.getString("gateway_url", null) ?: BuildConfig.GATEWAY_BASE_URL
        set(v) = prefs.edit().putString("gateway_url", v).apply()

    var apiKey: String?
        get() = prefs.getString("api_key", null)
        set(v) = if (v != null) prefs.edit().putString("api_key", v).apply()
                 else prefs.edit().remove("api_key").apply()

    var refreshKey: String?
        get() = prefs.getString("refresh_key", null)
        set(v) = if (v != null) prefs.edit().putString("refresh_key", v).apply()
                 else prefs.edit().remove("refresh_key").apply()

    val isLoggedIn: Boolean get() = !apiKey.isNullOrBlank()

    fun saveTokens(apiKey: String, refreshKey: String) {
        prefs.edit()
            .putString("api_key", apiKey)
            .putString("refresh_key", refreshKey)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove("api_key")
            .remove("refresh_key")
            .apply()
    }
}
