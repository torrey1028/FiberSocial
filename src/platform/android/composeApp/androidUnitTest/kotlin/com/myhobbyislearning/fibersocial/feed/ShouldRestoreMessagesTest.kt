package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.notifications.DeepLink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decision behind FeedScreen's one-shot Messages restore (issue #381) — in
 * particular that a notification deep link always beats a restored destination.
 */
class ShouldRestoreMessagesTest {

    @Test
    fun `restores Messages when it was the last destination and nothing objects`() {
        assertTrue(shouldRestoreMessages(LastDestination.Messages, deepLink = null, messagesEnabled = true))
    }

    @Test
    fun `a deep link beats the restored destination`() {
        // A cold start from a tapped notification must land where the tap points; a
        // restore stomping it would make the notification silently do nothing.
        assertFalse(shouldRestoreMessages(LastDestination.Messages, DeepLink.Messages, messagesEnabled = true))
        assertFalse(shouldRestoreMessages(LastDestination.Messages, DeepLink.MyPosts, messagesEnabled = true))
        assertFalse(shouldRestoreMessages(LastDestination.Messages, DeepLink.Topic(7L), messagesEnabled = true))
    }

    @Test
    fun `only the Messages destination restores at the screen level`() {
        // Group and My Posts restore inside FeedViewModel.load(); null covers both
        // nothing-persisted and corrupt data (the store loads those as null).
        assertFalse(shouldRestoreMessages(LastDestination.Group(10L), deepLink = null, messagesEnabled = true))
        assertFalse(shouldRestoreMessages(LastDestination.MyPosts, deepLink = null, messagesEnabled = true))
        assertFalse(shouldRestoreMessages(destination = null, deepLink = null, messagesEnabled = true))
    }

    @Test
    fun `never restores Messages when the feature flag gates it out`() {
        // Release builds compile Messages out — restoring there would strand the user on
        // a destination with no drawer row to navigate back from.
        assertFalse(shouldRestoreMessages(LastDestination.Messages, deepLink = null, messagesEnabled = false))
    }
}
