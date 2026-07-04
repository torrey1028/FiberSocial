package com.autom8ed.fibersocial.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.auth.AndroidTokenStorage
import com.autom8ed.fibersocial.auth.AuthViewModel
import com.autom8ed.fibersocial.net.ravelryAuthRepository
import com.autom8ed.fibersocial.net.ravelryHttpClient

class AuthAndroidViewModel(app: Application) : AndroidViewModel(app) {

    private val httpClient = ravelryHttpClient()
    private val authManager = RavelryAuthManager()
    private val tokenStorage = AndroidTokenStorage(app)
    private val repository = ravelryAuthRepository(
        httpClient = httpClient,
        tokenStorage = tokenStorage,
        clientId = BuildConfig.RAVELRY_CLIENT_ID,
        clientSecret = BuildConfig.RAVELRY_CLIENT_SECRET,
    )

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
