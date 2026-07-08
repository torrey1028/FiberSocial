package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.events.EventDetailViewModel
import com.autom8ed.fibersocial.events.EventsViewModel
import com.autom8ed.fibersocial.feedback.FeedbackViewModel
import com.autom8ed.fibersocial.profile.UserProfileViewModel
import com.autom8ed.fibersocial.projects.ProjectPageViewModel
import com.autom8ed.fibersocial.projects.ProjectPhotoPickerViewModel

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
    val events: EventsViewModel
    val eventDetail: EventDetailViewModel

    /** Reads the picked image behind [uri] (a platform URI string) and uploads it for the new-topic composer. */
    fun attachNewTopicImage(uri: String)

    /** Reads the picked image behind [uri] and uploads it for the reply composer. */
    fun attachReplyImage(uri: String)

    fun debugForceSessionExpiry()

    fun debugForceFeedError()
}
