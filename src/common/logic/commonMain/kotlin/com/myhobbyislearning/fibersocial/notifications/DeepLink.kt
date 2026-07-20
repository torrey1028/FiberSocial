package com.myhobbyislearning.fibersocial.notifications

/**
 * Where a tapped notification should land the user (issue #351).
 *
 * One sealed type rather than one parameter pair per destination: each platform host
 * (Android's `MainActivity`, iOS's `AppSetup` notification delegate) holds a single
 * `MutableStateFlow<DeepLink?>` and hands it to `FeedScreen` with one consume callback,
 * so adding a fourth destination later doesn't widen `FeedScreen`'s signature again.
 *
 * The link is consume-once: the host nulls its flow when `FeedScreen` reports it acted,
 * so a recomposition (or a config change) can't replay a link the user already dismissed.
 */
sealed interface DeepLink {

    /**
     * Opens an event's detail screen.
     *
     * @property permalink The event's Ravelry permalink.
     * @property groupId The group whose events list the detail should sit on top of, so
     *   back reaches that list and a second back reaches the feed. **Null for reminder
     *   notifications**, which are planned from [SavedEventWithTime] — the RSVP'd-events
     *   scrape records no group, and adding one would change the persisted
     *   [NotificationState] shape. A null group is not an error: it just means back from
     *   the event goes straight to the feed, the pre-#351 behaviour.
     */
    data class Event(val permalink: String, val groupId: Long?) : DeepLink

    /**
     * Opens a topic's thread, over the cross-group "My Posts" feed so back lands there.
     *
     * Only the id travels in the notification; `FeedScreen` resolves it to the full feed
     * item from the loaded My Posts page rather than synthesizing one, because a
     * synthetic item would carry bogus unread counts and no first-unread anchor.
     */
    data class Topic(val topicId: Long) : DeepLink

    /**
     * Opens the cross-group "My Posts" feed with no topic selected. Used by Android's
     * reply-notification group summary, which spans several topics and so has no single
     * right one to open.
     */
    data object MyPosts : DeepLink

    /**
     * Opens a private conversation from a new-message notification (issue #375).
     *
     * Both ids travel because they answer different questions. [threadRootId] is the
     * grouping key the notification was posted under — best-effort, reconstructed from an
     * unread-only inbox page (see `NewMessageNotification`). [messageId] is the exact
     * message that arrived, and is the id the conversation detail screen should resolve
     * against once it exists (#371): "the thread containing this message" survives a
     * regrouping that fetched more folders, whereas a best-effort root may not still be a
     * root once the parent chain is complete.
     *
     * Until #371 lands, `FeedScreen` routes this as far as selecting the Messages
     * destination.
     */
    data class Message(val threadRootId: Long, val messageId: Long) : DeepLink

    /**
     * Opens the Messages destination with no conversation selected. Used by Android's
     * new-message group summary, which spans several conversations and so has no single
     * right one to open — the messages counterpart of [MyPosts].
     */
    data object Messages : DeepLink
}
