package ir.tafakoritech.adminapplication.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class TokenAuthenticator : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // Only handle Bearer token auth; stop after one retry attempt
        if (response.request.header("Authorization")?.startsWith("Bearer") != true) return null
        if (response.priorResponse?.code == 401) return null  // already retried once

        val newToken = TokenManager.refreshBlocking() ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }
}
