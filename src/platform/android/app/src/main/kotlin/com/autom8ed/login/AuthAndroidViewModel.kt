package com.autom8ed.login

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.BuildConfig
import com.autom8ed.auth.AndroidTokenStorage
import com.autom8ed.auth.AuthRepository
import com.autom8ed.auth.AuthViewModel
import com.autom8ed.auth.RavelryOAuthClient
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

    private val authManager = RavelryAuthManager(app)
    private val tokenStorage = AndroidTokenStorage(app)
    private val oauthClient = RavelryOAuthClient(
        httpClient = httpClient,
        clientId = BuildConfig.RAVELRY_CLIENT_ID,
        clientSecret = BuildConfig.RAVELRY_CLIENT_SECRET,
    )
    private val repository = AuthRepository(oauthClient, tokenStorage)

    val auth = AuthViewModel(repository, viewModelScope)

    init {
        auth.checkStoredAuth()
    }

    fun buildAuthIntent() = authManager.buildAuthIntent(BuildConfig.RAVELRY_CLIENT_ID)

    fun handleAuthRedirect(intent: Intent) {
        val (code, verifier) = authManager.extractAuthResult(intent) ?: return
        auth.onAuthCodeReceived(
            authCode = code,
            codeVerifier = verifier,
            redirectUri = RavelryAuthManager.REDIRECT_URI,
        )
    }

    override fun onCleared() {
        super.onCleared()
        authManager.dispose()
        httpClient.close()
    }
}
