package com.autom8ed.fibersocial.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.auth.AuthRepository
import com.autom8ed.fibersocial.auth.AuthViewModel
import com.autom8ed.fibersocial.auth.KeyValueTokenStorage
import com.autom8ed.fibersocial.auth.RavelryOAuthClient
import com.autom8ed.fibersocial.storage.AUTH_PREFS_NAME
import com.autom8ed.fibersocial.storage.encryptedKeyValueStore
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
    private val tokenStorage = KeyValueTokenStorage(encryptedKeyValueStore(app, AUTH_PREFS_NAME))
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
