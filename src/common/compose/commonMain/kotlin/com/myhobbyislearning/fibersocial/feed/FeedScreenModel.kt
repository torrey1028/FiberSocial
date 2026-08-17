package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.events.EventDetailViewModel
import com.myhobbyislearning.fibersocial.events.EventsViewModel
import com.myhobbyislearning.fibersocial.events.NewEventViewModel
import com.myhobbyislearning.fibersocial.feedback.FeedbackViewModel
import com.myhobbyislearning.fibersocial.messages.MessagesViewModel
import com.myhobbyislearning.fibersocial.profile.UserProfileViewModel
import com.myhobbyislearning.fibersocial.projects.ProjectPageViewModel
import com.myhobbyislearning.fibersocial.projects.ProjectPhotoPickerViewModel

/**
 * The bundle of common-module ViewModels (plus the platform image-upload bridge) that
 * [FeedScreen] renders. The Android app's `FeedAndroidViewModel` implements this; an
 * iOS host will provide its own lifecycle-scoped implementation (#117).
 */
interface FeedScreenModel {
    val feed: FeedViewModel
    val topicDetail: TopicDetailViewModel
    val newTopic: NewTopicViewModel
    val newTopicImage: ImageAttachmentViewModel
    val replyImage: ImageAttachmentViewModel
    val projectPicker: ProjectPhotoPickerViewModel
    val projectPage: ProjectPageViewModel
    val userProfile: UserProfileViewModel
    val feedback: FeedbackViewModel
    val feedbackImage: ImageAttachmentViewModel
    val events: EventsViewModel
    val eventDetail: EventDetailViewModel
    val newEvent: NewEventViewModel
    val messages: MessagesViewModel

    /** Reads the picked image behind [uri] (a platform URI string) and uploads it for the new-topic composer. */
    fun attachNewTopicImage(uri: String)

    /** Reads the picked image behind [uri] and uploads it for the reply composer. */
    fun attachReplyImage(uri: String)

    /** Reads the picked image behind [uri] and uploads it for the feedback composer (issue #429). */
    fun attachFeedbackImage(uri: String)

    /**
     * The `www.ravelry.com` cookie captured at login (`AuthToken.sessionCookie`), or null.
     *
     * Seeds the in-app deletion page's web view so the user is not asked to sign in to
     * Ravelry a second time just to delete their account. Android never needed it — its
     * login WebView and deletion WebView share the app-global CookieManager — but the iOS
     * login web view uses a non-persistent data store on purpose, so the captured string
     * is the only copy of that session which outlives login.
     */
    suspend fun ravelrySessionCookie(): String?

    fun debugForceSessionExpiry()

    fun debugForceFeedError()
}
