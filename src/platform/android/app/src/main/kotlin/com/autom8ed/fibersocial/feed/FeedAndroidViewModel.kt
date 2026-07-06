package com.autom8ed.fibersocial.feed

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.auth.KeyValueTokenStorage
import com.autom8ed.fibersocial.events.EventDetailViewModel
import com.autom8ed.fibersocial.events.EventsViewModel
import com.autom8ed.fibersocial.feedback.FeedbackViewModel
import com.autom8ed.fibersocial.net.ravelryApiClient
import com.autom8ed.fibersocial.net.ravelryAuthRepository
import com.autom8ed.fibersocial.net.ravelryHttpClient
import com.autom8ed.fibersocial.notifications.EventSyncWorker
import com.autom8ed.fibersocial.projects.ProjectPhotoPickerViewModel
import com.autom8ed.fibersocial.storage.AUTH_PREFS_NAME
import com.autom8ed.fibersocial.storage.encryptedKeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class FeedAndroidViewModel(app: Application) : AndroidViewModel(app), FeedScreenModel {

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
    override val feed = FeedViewModel(repository, viewModelScope, AndroidGroupOrderStore(app))
    override val topicDetail = TopicDetailViewModel(apiClient, viewModelScope)
    override val newTopic = NewTopicViewModel(apiClient, viewModelScope)

    // One attach-image flow per composer, so an upload finishing for one composer
    // can never deliver its markdown into the other's draft.
    override val newTopicImage = ImageAttachmentViewModel(apiClient, viewModelScope)
    override val replyImage = ImageAttachmentViewModel(apiClient, viewModelScope)

    // Shared by both composers: only one is visible at a time, and the picked photo
    // is routed to the visible composer's ImageAttachmentViewModel at the call site.
    override val projectPicker = ProjectPhotoPickerViewModel(apiClient, viewModelScope)
    override val feedback = FeedbackViewModel(apiClient, viewModelScope)
    override val events = EventsViewModel(apiClient, viewModelScope)
    override val eventDetail = EventDetailViewModel(
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
        newTopicImage.sessionExpired,
        replyImage.sessionExpired,
        projectPicker.sessionExpired,
        feedback.sessionExpired,
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

    /** Reads the picked image behind [uri] and uploads it for the new-topic composer. */
    override fun attachNewTopicImage(uri: String) =
        newTopicImage.attach { readImageForUpload(getApplication(), Uri.parse(uri)) }

    /** Reads the picked image behind [uri] and uploads it for the reply composer. */
    override fun attachReplyImage(uri: String) =
        replyImage.attach { readImageForUpload(getApplication(), Uri.parse(uri)) }

    fun load() = feed.load()

    fun reset() = feed.reset()

    override fun debugForceSessionExpiry() = feed.forceSessionExpiry()

    override fun debugForceFeedError() = feed.forceError()

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
