package com.autom8ed.fibersocial.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.auth.AndroidTokenStorage
import com.autom8ed.fibersocial.auth.AuthRepository
import com.autom8ed.fibersocial.auth.RavelryOAuthClient
import com.autom8ed.fibersocial.events.EventDetailViewModel
import com.autom8ed.fibersocial.events.EventsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.Json

class FeedAndroidViewModel(app: Application) : AndroidViewModel(app) {

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    private val tokenStorage = AndroidTokenStorage(app)
    private val oauthClient = RavelryOAuthClient(
        httpClient = httpClient,
        clientId = BuildConfig.RAVELRY_CLIENT_ID,
        clientSecret = BuildConfig.RAVELRY_CLIENT_SECRET,
    )
    private val authRepository = AuthRepository(oauthClient, tokenStorage)
    private val apiClient = RavelryApiClient(
        httpClient = httpClient,
        tokenStorage = tokenStorage,
        refreshToken = { authRepository.refreshToken() },
    )
    private val repository = FeedRepository(apiClient)
    val feed = FeedViewModel(repository, viewModelScope)
    val topicDetail = TopicDetailViewModel(apiClient, viewModelScope)
    val events = EventsViewModel(apiClient, viewModelScope)
    val eventDetail = EventDetailViewModel(apiClient, viewModelScope)

    /** Emits when any screen's data source encounters a session expiry. */
    val sessionExpired: Flow<Unit> = merge(
        feed.sessionExpired,
        topicDetail.sessionExpired,
        events.sessionExpired,
        eventDetail.sessionExpired,
    )

    fun load() = feed.load()

    fun debugForceSessionExpiry() = feed.forceSessionExpiry()

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
