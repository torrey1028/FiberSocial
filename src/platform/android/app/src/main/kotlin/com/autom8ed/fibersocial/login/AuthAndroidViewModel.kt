package com.autom8ed.fibersocial.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.auth.AndroidTokenStorage
import com.autom8ed.fibersocial.auth.AuthRepository
import com.autom8ed.fibersocial.auth.AuthViewModel
import com.autom8ed.fibersocial.auth.RavelryOAuthClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class AuthAndroidViewModel(app: Application) : AndroidViewModel(app) {

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val authManager = RavelryAuthManager()
    private val tokenStorage = AndroidTokenStorage(app)
    private val oauthClient = RavelryOAuthClient(
        httpClient = httpClient,
        clientId = BuildConfig.RAVELRY_CLIENT_ID,
        clientSecret = BuildConfig.RAVELRY_CLIENT_SECRET,
    )
    private val repository = AuthRepository(oauthClient, tokenStorage)

    val auth = AuthViewModel(repository, viewModelScope)

    init {
        // Builds without injected credentials (e.g. fork-PR CI artifacts, or a
        // stale build from before local.properties was filled in — see CLAUDE.md)
        // fail token exchange with an opaque invalid_client; say why up front.
        if (BuildConfig.RAVELRY_CLIENT_ID.isEmpty() || BuildConfig.RAVELRY_CLIENT_SECRET.isEmpty()) {
            println(
                "FiberSocial: WARNING — RAVELRY_CLIENT_ID/RAVELRY_CLIENT_SECRET is empty. " +
                    "OAuth login will fail with invalid_client. Set ravelry.client_id/" +
                    "ravelry.client_secret in local.properties (or CI secrets) and rebuild " +
                    "with ./gradlew clean."
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
