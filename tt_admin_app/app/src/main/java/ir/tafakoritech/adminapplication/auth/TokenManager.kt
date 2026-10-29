package ir.tafakoritech.adminapplication.auth

import android.os.Handler
import android.os.Looper
import ir.tafakoritech.adminapplication.api.SentinelClient
import ir.tafakoritech.adminapplication.data.AuthTokenStore
import kotlinx.coroutines.runBlocking

object TokenManager {
    private var store: AuthTokenStore? = null
    private var sentinelClient: SentinelClient? = null
    var onForceLogout: (() -> Unit)? = null

    @Volatile private var isRefreshing = false

    fun init(store: AuthTokenStore, sentinel: SentinelClient) {
        this.store = store
        this.sentinelClient = sentinel
    }

    fun getToken(): String = store?.apiKey.orEmpty()

    @Synchronized
    fun refreshBlocking(): String? {
        if (isRefreshing) {
            // Another thread is already refreshing; wait briefly and return updated token
            Thread.sleep(600)
            return store?.apiKey
        }
        val s = store ?: return null
        val sc = sentinelClient ?: return null
        val refreshKey = s.refreshKey ?: run { forceLogout(); return null }

        isRefreshing = true
        return try {
            val pair = runBlocking { sc.refresh(refreshKey) }
            s.saveTokens(pair.apiKey, pair.refreshKey)
            pair.apiKey
        } catch (e: Exception) {
            forceLogout()
            null
        } finally {
            isRefreshing = false
        }
    }

    private fun forceLogout() {
        store?.clear()
        Handler(Looper.getMainLooper()).post { onForceLogout?.invoke() }
    }
}
