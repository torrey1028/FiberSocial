package com.autom8ed.fibersocial.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.auth.AuthViewModel
import com.autom8ed.fibersocial.auth.KeyValueTokenStorage
import com.autom8ed.fibersocial.auth.RavelryAuthManager
import com.autom8ed.fibersocial.net.ravelryAuthRepository
import com.autom8ed.fibersocial.net.ravelryHttpClient
import com.autom8ed.fibersocial.storage.AUTH_PREFS_NAME
import com.autom8ed.fibersocial.storage.encryptedKeyValueStore

class AuthAndroidViewModel(app: Application) : AndroidViewModel(app) {

    private val httpClient = ravelryHttpClient()
    private val authManager = RavelryAuthManager()
    private val tokenStorage = KeyValueTokenStorage(encryptedKeyValueStore(app, AUTH_PREFS_NAME))
    private val repository = ravelryAuthRepository(
        httpClient = httpClient,
        tokenStorage = tokenStorage,
        clientId = BuildConfig.RAVELRY_CLIENT_ID,
        clientSecret = BuildConfig.RAVELRY_CLIENT_SECRET,
    )

    val auth = AuthViewModel(repository, viewModelScope)

    init {
        // Builds without injected credentials (e.g. fork-PR CI artifacts, or a
        // stale build from before local.properties was filled in — see CLAUDE.md)
        // fail token exchange with an opaque invalid_client; say why up front.
        val missing = listOfNotNull(
            "RAVELRY_CLIENT_ID".takeIf { BuildConfig.RAVELRY_CLIENT_ID.isBlank() },
            "RAVELRY_CLIENT_SECRET".takeIf { BuildConfig.RAVELRY_CLIENT_SECRET.isBlank() },
        )
        if (missing.isNotEmpty()) {
            println(
                "FiberSocial: WARNING — ${missing.joinToString(" and ")} " +
                    "${if (missing.size == 1) "is" else "are"} blank. OAuth login will " +
                    "fail with invalid_client. Set ravelry.client_id/ravelry.client_secret " +
                    "in local.properties (or CI secrets) and rebuild with ./gradlew clean."
            )
        }
        auth.checkStoredAuth()
    }

    fun buildAuthUrl(): String = authManager.buildAuthUrl(BuildConfig.RAVELRY_CLIENT_ID)

    fun handleAuthCode(code: String, sessionCookie: String) {
        auth.onAuthCodeReceived(
            authCode = code,
            codeVerifier = authManager.consumeCodeVerifier(),
            redirectUri = RavelryAuthManager.REDIRECT_URI,
            sessionCookie = sessionCookie,
        )
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
