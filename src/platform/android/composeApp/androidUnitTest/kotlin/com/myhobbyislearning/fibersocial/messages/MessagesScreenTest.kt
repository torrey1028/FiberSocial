package com.myhobbyislearning.fibersocial.messages

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun user(name: String) = RavelryUser(username = name)

private fun message(
    id: Long,
    subject: String = "Yarn talk",
    sender: RavelryUser? = user("friend"),
    recipient: RavelryUser? = user("yarnie"),
    sentAt: String? = "2026/07/03 10:00:00 +0000",
    contentHtml: String? = "<p>Do you have any merino left?</p>",
) = Message(
    id = id,
    subject = subject,
    sender = sender,
    recipient = recipient,
    sentAt = sentAt,
    readMessage = true,
    contentHtml = contentHtml,
)

private fun thread(
    rootId: Long = 1,
    subject: String = "Yarn talk",
    counterpart: RavelryUser? = user("friend"),
    lastActivityAt: Long? = 1_780_000_000_000,
    hasUnread: Boolean = false,
    messages: List<Message> = listOf(message(rootId, subject)),
) = MessageThread(
    rootId = rootId,
    messages = messages,
    subject = subject,
    counterpart = counterpart,
    lastActivityAt = lastActivityAt,
    hasUnread = hasUnread,
)

@RunWith(RobolectricTestRunner::class)
class MessagesScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setScreen(
        state: MessagesState,
        onRetry: () -> Unit = {},
        onLoadMore: () -> Unit = {},
        onThreadClick: (MessageThread) -> Unit = {},
    ) = compose.setContent {
        MessagesScreen(
            state = state,
            onRefresh = {},
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            onThreadClick = onThreadClick,
        )
    }

    @Test
    fun `a row renders the counterpart subject and preview`() {
        setScreen(MessagesState.Loaded(threads = listOf(thread())))

        compose.onNodeWithTag("MessagesList").assertIsDisplayed()
        compose.onNodeWithText("friend").assertIsDisplayed()
        compose.onNodeWithText("Yarn talk").assertIsDisplayed()
        // Preview text comes from content_html flattened through the shared preview
        // machinery — the tags must not reach the screen.
        compose.onNodeWithText("Do you have any merino left?").assertIsDisplayed()
    }

    @Test
    fun `the empty state shows the no-messages copy`() {
        setScreen(MessagesState.Loaded(threads = emptyList()))

        compose.onNodeWithTag("MessagesEmpty").assertIsDisplayed()
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
    }

    @Test
    fun `tapping a row invokes the open callback with that thread`() {
        val opened = mutableListOf<Long>()
        setScreen(
            MessagesState.Loaded(threads = listOf(thread(rootId = 1), thread(rootId = 2))),
            onThreadClick = { opened += it.rootId },
        )

        compose.onNodeWithTag("MessageThreadRow-2").performClick()

        assertEquals(listOf(2L), opened)
    }

    @Test
    fun `an unread thread shows the unread dot`() {
        setScreen(MessagesState.Loaded(threads = listOf(thread(hasUnread = true))))

        compose.onNodeWithContentDescription("Unread messages").assertIsDisplayed()
    }

    @Test
    fun `a read thread shows no unread dot`() {
        setScreen(MessagesState.Loaded(threads = listOf(thread(hasUnread = false))))

        assertEquals(
            0,
            compose.onAllNodesWithContentDescription("Unread messages").fetchSemanticsNodes().size,
        )
    }

    /**
     * A null counterpart is a real state (system notices, deleted accounts) and
     * `RavelryUser` carries nothing to synthesize a name from — the row must name the
     * unknown rather than render a blank where the name goes.
     */
    @Test
    fun `a thread with no counterpart renders the unknown fallback`() {
        setScreen(MessagesState.Loaded(threads = listOf(thread(counterpart = null))))

        compose.onNodeWithText("(unknown)").assertIsDisplayed()
    }

    /**
     * lastActivityAt is nullable and null means "no parseable timestamp anywhere in the
     * thread". Formatting that as epoch millis would print a 1970-relative age.
     */
    @Test
    fun `a thread with unknown activity renders a placeholder instead of a 1970 age`() {
        setScreen(MessagesState.Loaded(threads = listOf(thread(lastActivityAt = null))))

        compose.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun `a thread with a blank subject renders a subject placeholder`() {
        setScreen(MessagesState.Loaded(threads = listOf(thread(subject = ""))))

        compose.onNodeWithText("(no subject)").assertIsDisplayed()
    }

    @Test
    fun `a message with no body renders the row without a preview line`() {
        val bodyless = thread(messages = listOf(message(1, contentHtml = null)))
        setScreen(MessagesState.Loaded(threads = listOf(bodyless)))

        // The row still identifies itself; only the preview line is absent.
        compose.onNodeWithText("friend").assertIsDisplayed()
        compose.onNodeWithText("Yarn talk").assertIsDisplayed()
    }

    @Test
    fun `the loading state shows a spinner`() {
        setScreen(MessagesState.Loading)

        compose.onNodeWithTag("MessagesLoading").assertIsDisplayed()
    }

    /**
     * Issue #330 is the open bug where the events screen's error state had no working way
     * out. The Retry button must be present AND must actually invoke the retry callback.
     */
    @Test
    fun `the error state offers a retry that fires the callback`() {
        var retries = 0
        setScreen(MessagesState.Error("boom"), onRetry = { retries++ })

        compose.onNodeWithTag("MessagesError").assertIsDisplayed()
        compose.onNodeWithText("Couldn't load your messages. Check your connection and try again.")
            .assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `an auth-shaped error explains the session expired`() {
        setScreen(MessagesState.Error("HTTP 401 Unauthorized"))

        compose.onNodeWithText("Session expired. Please log out and sign in again.")
            .assertIsDisplayed()
    }

    /** The newest message drives the preview — threads order oldest to newest. */
    @Test
    fun `the preview comes from the newest message in the thread`() {
        val conversation = thread(
            messages = listOf(
                message(1, contentHtml = "<p>Oldest message</p>"),
                message(2, contentHtml = "<p>Newest message</p>"),
            ),
        )
        setScreen(MessagesState.Loaded(threads = listOf(conversation)))

        compose.onNodeWithText("Newest message").assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("Oldest message").fetchSemanticsNodes().isEmpty(),
        )
    }
}
