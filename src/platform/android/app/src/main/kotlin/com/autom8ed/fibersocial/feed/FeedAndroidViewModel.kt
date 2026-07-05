package com.autom8ed.fibersocial.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.auth.KeyValueTokenStorage
import com.autom8ed.fibersocial.events.EventDetailViewModel
import com.autom8ed.fibersocial.events.EventsViewModel
import com.autom8ed.fibersocial.net.ravelryApiClient
import com.autom8ed.fibersocial.net.ravelryAuthRepository
import com.autom8ed.fibersocial.net.ravelryHttpClient
import com.autom8ed.fibersocial.notifications.EventSyncWorker
import com.autom8ed.fibersocial.storage.AUTH_PREFS_NAME
import com.autom8ed.fibersocial.storage.encryptedKeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class FeedAndroidViewModel(app: Application) : AndroidViewModel(app) {

    private val httpClient = ravelryHttpClient()
    private val tokenStorage = KeyValueTokenStorage(encryptedKeyValueStore(app, AUTH_PREFS_NAME))
    private val authRepository = ravelryAuthRepository(
        httpClient = httpClient,
        tokenStorage = tokenStorage,
        clientId = BuildConfig.RAVELRY_CLIENT_ID,
        clientSecret = BuildConfig.RAVELRY_CLIENT_SECRET,
    )
    private val apiClient = ravelryApiClient(httpClient, tokenStorage, authRepository)
    private val repository = FeedRepository(apiClient)
    val feed = FeedViewModel(repository, viewModelScope, AndroidGroupOrderStore(app))
    val topicDetail = TopicDetailViewModel(apiClient, viewModelScope)
    val newTopic = NewTopicViewModel(apiClient, viewModelScope)
    val events = EventsViewModel(apiClient, viewModelScope)
    val eventDetail = EventDetailViewModel(
        apiClient,
        viewModelScope,
        // RSVP changes resync notifications immediately: reminders for a fresh RSVP are
        // scheduled — and pending ones for a withdrawn RSVP cancelled — without waiting
        // for the next background poll.
        onAttendanceChanged = { EventSyncWorker.runOnce(app) },
    )

    /** Emits when any screen's data source encounters a session expiry. */
    val sessionExpired: Flow<Unit> = merge(
        feed.sessionExpired,
        topicDetail.sessionExpired,
        newTopic.sessionExpired,
        events.sessionExpired,
        eventDetail.sessionExpired,
    )

    init {
        // Keep the events list's "N going" counts in step with RSVP changes made on
        // the detail screen — the counts come from a one-time scrape (issue #74).
        viewModelScope.launch {
            eventDetail.attendanceChanged.collect { change ->
                events.applyAttendanceChange(change.permalink, change.attending)
            }
        }
    }

    fun load() = feed.load()

    fun reset() = feed.reset()

    fun debugForceSessionExpiry() = feed.forceSessionExpiry()

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
