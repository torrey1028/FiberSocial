package com.myhobbyislearning.fibersocial.feed

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myhobbyislearning.fibersocial.BuildConfig
import com.myhobbyislearning.fibersocial.auth.KeyValueTokenStorage
import com.myhobbyislearning.fibersocial.events.EventDetailViewModel
import com.myhobbyislearning.fibersocial.events.EventsViewModel
import com.myhobbyislearning.fibersocial.events.NewEventViewModel
import com.myhobbyislearning.fibersocial.feedback.FeedbackViewModel
import com.myhobbyislearning.fibersocial.messages.MessagesViewModel
import com.myhobbyislearning.fibersocial.net.ravelryApiClient
import com.myhobbyislearning.fibersocial.net.ravelryAuthRepository
import com.myhobbyislearning.fibersocial.net.ravelryHttpClient
import com.myhobbyislearning.fibersocial.notifications.EventSyncWorker
import com.myhobbyislearning.fibersocial.notifications.KeyValueNotificationStateStore
import com.myhobbyislearning.fibersocial.profile.UserProfileViewModel
import com.myhobbyislearning.fibersocial.projects.ProjectPageViewModel
import com.myhobbyislearning.fibersocial.projects.ProjectPhotoPickerViewModel
import com.myhobbyislearning.fibersocial.storage.AUTH_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_STATE_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.encryptedKeyValueStore
import com.myhobbyislearning.fibersocial.storage.plainKeyValueStore
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
    override val feed = FeedViewModel(
        repository,
        viewModelScope,
        AndroidGroupOrderStore(app),
        AndroidGroupLastViewedStore(app),
    )
    override val topicDetail = TopicDetailViewModel(apiClient, viewModelScope)
    override val newTopic = NewTopicViewModel(apiClient, viewModelScope)

    // One attach-image flow per composer, so an upload finishing for one composer
    // can never deliver its markdown into the other's draft.
    override val newTopicImage = ImageAttachmentViewModel(apiClient, viewModelScope)
    override val replyImage = ImageAttachmentViewModel(apiClient, viewModelScope)

    // Shared by both composers: only one is visible at a time, and the picked photo
    // is routed to the visible composer's ImageAttachmentViewModel at the call site.
    override val projectPicker = ProjectPhotoPickerViewModel(apiClient, viewModelScope)

    // In-app project page for tapped ravelry.com/projects links (issue #103).
    override val projectPage = ProjectPageViewModel(apiClient, viewModelScope)

    // In-app user profile for tapped usernames (issue #194).
    override val userProfile = UserProfileViewModel(apiClient, viewModelScope)
    override val feedback = FeedbackViewModel(apiClient, viewModelScope)
    override val events = EventsViewModel(apiClient, viewModelScope)

    // Private-message conversation list (issue #370, epic #365).
    override val messages = MessagesViewModel(apiClient, viewModelScope)
    override val newEvent = NewEventViewModel(
        apiClient,
        viewModelScope,
        // Same store as EventSyncWorker: a just-created event is pre-seeded as known so
        // the next sync doesn't pop a "new event" notification at its own creator.
        KeyValueNotificationStateStore(plainKeyValueStore(app, NOTIFICATION_STATE_PREFS_NAME)),
    )
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
        projectPage.sessionExpired,
        userProfile.sessionExpired,
        feedback.sessionExpired,
        events.sessionExpired,
        eventDetail.sessionExpired,
        newEvent.sessionExpired,
        messages.sessionExpired,
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

    fun reset() {
        feed.reset()
        // Dismiss the ViewModel-held project page too: it survives across logout/re-login
        // on the reused ViewModel, so without this a re-logged-in (possibly different)
        // user would see the previous session's project page over their feed.
        projectPage.dismiss()
    }

    override fun debugForceSessionExpiry() = feed.forceSessionExpiry()

    override fun debugForceFeedError() = feed.forceError()

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
