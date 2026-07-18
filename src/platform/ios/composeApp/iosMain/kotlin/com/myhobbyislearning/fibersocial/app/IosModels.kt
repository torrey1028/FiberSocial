package com.myhobbyislearning.fibersocial.app

import com.myhobbyislearning.fibersocial.auth.AuthViewModel
import com.myhobbyislearning.fibersocial.auth.KeyValueTokenStorage
import com.myhobbyislearning.fibersocial.auth.RavelryAuthManager
import com.myhobbyislearning.fibersocial.events.EventDetailViewModel
import com.myhobbyislearning.fibersocial.events.EventsViewModel
import com.myhobbyislearning.fibersocial.events.NewEventViewModel
import com.myhobbyislearning.fibersocial.feed.FeedRepository
import com.myhobbyislearning.fibersocial.feed.FeedScreenModel
import com.myhobbyislearning.fibersocial.feed.FeedViewModel
import com.myhobbyislearning.fibersocial.feed.ImageAttachmentViewModel
import com.myhobbyislearning.fibersocial.feed.KeyValueGroupOrderStore
import com.myhobbyislearning.fibersocial.feed.readPickedImage
import com.myhobbyislearning.fibersocial.feed.NewTopicViewModel
import com.myhobbyislearning.fibersocial.feed.TopicDetailViewModel
import com.myhobbyislearning.fibersocial.feedback.FeedbackViewModel
import com.myhobbyislearning.fibersocial.profile.UserProfileViewModel
import com.myhobbyislearning.fibersocial.net.ravelryApiClient
import com.myhobbyislearning.fibersocial.notifications.EventSync
import com.myhobbyislearning.fibersocial.notifications.KeyValueNotificationStateStore
import com.myhobbyislearning.fibersocial.net.ravelryAuthRepository
import com.myhobbyislearning.fibersocial.net.ravelryHttpClient
import com.myhobbyislearning.fibersocial.projects.ProjectPageViewModel
import com.myhobbyislearning.fibersocial.projects.ProjectPhotoPickerViewModel
import com.myhobbyislearning.fibersocial.storage.AUTH_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.GROUP_ORDER_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_STATE_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.KeychainKeyValueStore
import com.myhobbyislearning.fibersocial.storage.NsUserDefaultsKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * iOS counterpart of `AuthAndroidViewModel`: owns the auth flow and its client graph.
 * Lives for the app's lifetime on [scope] (a single-window iOS app has no
 * Activity-recreation lifecycle to scope to).
 */
class IosAuthModel(scope: CoroutineScope) {

    private val httpClient = ravelryHttpClient()
    private val authManager = RavelryAuthManager()
    private val tokenStorage = KeyValueTokenStorage(KeychainKeyValueStore(AUTH_STORE_NAME))
    private val repository = ravelryAuthRepository(
        httpClient = httpClient,
        tokenStorage = tokenStorage,
        clientId = ravelryClientId(),
        clientSecret = ravelryClientSecret(),
    )

    val auth = AuthViewModel(repository, scope)

    init {
        // Builds without injected credentials fail token exchange with an opaque
        // invalid_client; say why up front (same warning as Android).
        val missing = listOfNotNull(
            "RAVELRY_CLIENT_ID".takeIf { ravelryClientId().isBlank() },
            "RAVELRY_CLIENT_SECRET".takeIf { ravelryClientSecret().isBlank() },
        )
        if (missing.isNotEmpty()) {
            println(
                "FiberSocial: WARNING — ${missing.joinToString(" and ")} " +
                    "${if (missing.size == 1) "is" else "are"} blank. OAuth login will " +
                    "fail with invalid_client. Set them in Config.local.xcconfig " +
                    "(see src/platform/ios/README.md) and rebuild."
            )
        }
        auth.checkStoredAuth()
    }

    fun buildAuthUrl(): String = authManager.buildAuthUrl(ravelryClientId())

    fun handleAuthCode(code: String, state: String?, sessionCookie: String) {
        // Reject a redirect whose state doesn't match the one we issued before exchanging
        // the code — login-CSRF defense (issue #149), same as Android.
        if (!authManager.validateState(state)) {
            println("FiberSocial: OAuth state mismatch — rejecting login (possible CSRF)")
            auth.failLogin("Login could not be verified. Please try again.")
            return
        }
        auth.onAuthCodeReceived(
            authCode = code,
            codeVerifier = authManager.consumeCodeVerifier(),
            redirectUri = RavelryAuthManager.REDIRECT_URI,
            sessionCookie = sessionCookie,
        )
    }
}

/**
 * iOS counterpart of `FeedAndroidViewModel`: the [FeedScreenModel] bundle over the
 * common ViewModels, with iOS stores behind them.
 */
class IosFeedModel(scope: CoroutineScope) : FeedScreenModel {

    private val httpClient = ravelryHttpClient()
    internal val tokenStorage = KeyValueTokenStorage(KeychainKeyValueStore(AUTH_STORE_NAME))
    private val authRepository = ravelryAuthRepository(
        httpClient = httpClient,
        tokenStorage = tokenStorage,
        clientId = ravelryClientId(),
        clientSecret = ravelryClientSecret(),
    )
    private val apiClient = ravelryApiClient(httpClient, tokenStorage, authRepository)
    private val repository = FeedRepository(apiClient)

    override val feed = FeedViewModel(
        repository,
        scope,
        KeyValueGroupOrderStore(NsUserDefaultsKeyValueStore(GROUP_ORDER_STORE_NAME)),
    )
    override val topicDetail = TopicDetailViewModel(apiClient, scope)
    override val newTopic = NewTopicViewModel(apiClient, scope)

    // One attach-image flow per composer, so an upload finishing for one composer
    // can never deliver its markdown into the other's draft.
    override val newTopicImage = ImageAttachmentViewModel(apiClient, scope)
    override val replyImage = ImageAttachmentViewModel(apiClient, scope)

    // Shared by both composers: only one is visible at a time, and the picked photo
    // is routed to the visible composer's ImageAttachmentViewModel at the call site.
    override val projectPicker = ProjectPhotoPickerViewModel(apiClient, scope)

    // In-app project page for tapped ravelry.com/projects links (issue #103).
    override val projectPage = ProjectPageViewModel(apiClient, scope)

    // In-app user profile for tapped usernames (issue #194).
    override val userProfile = UserProfileViewModel(apiClient, scope)
    override val feedback = FeedbackViewModel(apiClient, scope)
    override val events = EventsViewModel(apiClient, scope)
    override val newEvent = NewEventViewModel(
        apiClient,
        scope,
        // Same store as EventSync: a just-created event is pre-seeded as known so the
        // next sync doesn't pop a "new event" notification at its own creator.
        KeyValueNotificationStateStore(NsUserDefaultsKeyValueStore(NOTIFICATION_STATE_STORE_NAME)),
    )
    override val eventDetail = EventDetailViewModel(
        apiClient,
        scope,
        // RSVP changes resync notifications immediately: reminders for a fresh RSVP
        // are scheduled — and pending ones for a withdrawn RSVP cancelled — without
        // waiting for the next foreground/background sync (same as Android).
        onAttendanceChanged = { EventSync.runOnce() },
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
    )

    init {
        // Keep the events list's "N going" counts in step with RSVP changes made on
        // the detail screen — the counts come from a one-time scrape (issue #74).
        scope.launch {
            eventDetail.attendanceChanged.collect { change ->
                events.applyAttendanceChange(change.permalink, change.attending)
            }
        }
    }

    /** Reads the picked image behind [uri] (a tmp-file path from the PHPicker
     *  bridge) and uploads it for the new-topic composer. */
    override fun attachNewTopicImage(uri: String) =
        newTopicImage.attach { readPickedImage(uri) }

    /** Reads the picked image behind [uri] and uploads it for the reply composer. */
    override fun attachReplyImage(uri: String) =
        replyImage.attach { readPickedImage(uri) }

    fun load() = feed.load()

    fun reset() {
        feed.reset()
        // Dismiss the model-held project page too: this model lives for the whole process
        // (never recreated), so without this a re-logged-in (possibly different) user would
        // see the previous session's project page over their feed. Mirrors Android's reset().
        projectPage.dismiss()
    }

    override fun debugForceSessionExpiry() = feed.forceSessionExpiry()

    override fun debugForceFeedError() = feed.forceError()
}
