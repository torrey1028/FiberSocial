package com.autom8ed.fibersocial.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.fibersocial.auth.AndroidTokenStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
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
    private val apiClient = RavelryApiClient(httpClient, tokenStorage)
    private val repository = FeedRepository(apiClient)
    val feed = FeedViewModel(repository, viewModelScope)
    val topicDetail = TopicDetailViewModel(apiClient, viewModelScope)

    fun load() = feed.load()

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
