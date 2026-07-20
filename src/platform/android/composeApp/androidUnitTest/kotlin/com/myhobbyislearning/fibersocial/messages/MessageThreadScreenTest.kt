package com.myhobbyislearning.fibersocial.messages

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

private const val ME = "yarnie"

private fun user(name: String) = RavelryUser(username = name)

private fun message(
    id: Long,
    from: String,
    to: String,
    body: String?,
    sentAt: String? = "2026/07/03 10:00:00 +0000",
) = Message(
    id = id,
    subject = "Yarn talk",
    sender = user(from),
    recipient = user(to),
    sentAt = sentAt,
    readMessage = true,
    contentHtml = body?.let { "<p>$it</p>" },
)

/**
 * A three-message back-and-forth: they opened it, we answered, they answered back. Held in
 * the order `MessageThread` guarantees — OLDEST FIRST — since that is what the screen
 * renders and what the ordering test below asserts about.
 */
private fun conversation(
    messages: List<Message> = listOf(
        message(1, from = "friend", to = ME, body = "First message"),
        message(2, from = ME, to = "friend", body = "Second message"),
        message(3, from = "friend", to = ME, body = "Third message"),
    ),
) = MessageThread(
    rootId = 1,
    messages = messages,
    subject = "Yarn talk",
    counterpart = user("friend"),
    lastActivityAt = 1_780_000_000_000,
    hasUnread = false,
)

@RunWith(RobolectricTestRunner::class)
class MessageThreadScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setScreen(
        state: OpenThreadState = OpenThreadState(conversation()),
        onBack: () -> Unit = {},
        onReply: (() -> Unit)? = null,
        onToggleMute: (() -> Unit)? = null,
    ) = compose.setContent {
        MessageThreadScreen(
            state = state,
            currentUsername = ME,
            onBack = onBack,
            onReply = onReply,
            onToggleMute = onToggleMute,
        )
    }

    /**
     * OLDEST → NEWEST, asserted by where the bodies actually land on screen rather than by
     * the order they were passed in — a reversed `LazyColumn` would satisfy the latter.
     */
    @Test
    fun `messages render oldest first`() {
        setScreen()

        val first = compose.onNodeWithText("First message").getBoundsInRoot().top
        val second = compose.onNodeWithText("Second message").getBoundsInRoot().top
        val third = compose.onNodeWithText("Third message").getBoundsInRoot().top

        assertTrue("first should sit above second", first < second)
        assertTrue("second should sit above third", second < third)
    }

    /**
     * Sent and received must be TELLABLE APART. Colour is the least of the four signals the
     * screen uses and the one a test can least honestly assert, so this checks the two that
     * carry to a screen reader and a greyscale display: which side the bubble is on (its
     * tag) and how it is attributed ("You" versus the sender's name).
     */
    @Test
    fun `sent and received messages are rendered differently`() {
        setScreen()

        compose.onNodeWithTag("MessageReceived-1").assertIsDisplayed()
        compose.onNodeWithTag("MessageSent-2").assertIsDisplayed()
        compose.onNodeWithTag("MessageReceived-3").assertIsDisplayed()
        // The user's own message is attributed to them, not to their username.
        compose.onNodeWithText("You").assertIsDisplayed()
    }

    /** Bodies go through the shared PostBody renderer — no HTML may reach the screen. */
    @Test
    fun `bodies render as text rather than as markup`() {
        setScreen(
            OpenThreadState(
                conversation(
                    listOf(message(1, from = "friend", to = ME, body = "<b>bold</b> words")),
                ),
            ),
        )

        compose.onNodeWithText("bold words").assertIsDisplayed()
    }

    @Test
    fun `the top bar back arrow invokes the back callback`() {
        var backs = 0
        setScreen(onBack = { backs++ })

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `the subject is the screen title`() {
        setScreen()

        compose.onNodeWithText("Yarn talk").assertIsDisplayed()
    }

    /** The mute affordance is present — disabled — while issue #377 is unbuilt. */
    @Test
    fun `the overflow menu offers the mute placeholder`() {
        setScreen(onToggleMute = null)

        compose.onNodeWithContentDescription("More options").performClick()

        compose.onNodeWithText("Mute notifications").assertIsDisplayed()
        compose.onNodeWithText("Mute notifications").assertIsNotEnabled()
    }

    /**
     * The other half of the placeholder contract: handing #377's behaviour in is all that
     * is left to do here. If this ever stops passing the hook has rotted.
     */
    @Test
    fun `supplying a mute handler makes the menu item live`() {
        var mutes = 0
        setScreen(onToggleMute = { mutes++ })

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Mute notifications").performClick()

        assertEquals(1, mutes)
    }

    /** A missing body is routine — it says so rather than rendering an empty bubble. */
    @Test
    fun `a message with no body shows a placeholder`() {
        setScreen(
            OpenThreadState(
                conversation(listOf(message(1, from = "friend", to = ME, body = null))),
            ),
        )

        compose.onNodeWithText("(no message body)").assertIsDisplayed()
    }

    @Test
    fun `a failed body backfill is reported above the thread`() {
        setScreen(OpenThreadState(conversation(), bodyError = "boom"))

        compose.onNodeWithTag("MessageBodiesError").assertIsDisplayed()
        // The conversation is still there — a failed backfill is not a dead screen.
        compose.onNodeWithText("First message").assertIsDisplayed()
    }

    /** The reply entry point (issue #374) — the only way into the composer from a thread. */
    @Test
    fun `the reply button opens the composer`() {
        var replies = 0
        setScreen(onReply = { replies++ })

        compose.onNodeWithTag("ReplyFab").assertIsDisplayed()
        compose.onNodeWithTag("ReplyFab").performClick()

        assertEquals(1, replies)
    }

    /** A caller with no composer wired up gets no dead control. */
    @Test
    fun `no reply button without a reply handler`() {
        setScreen()

        compose.onNodeWithTag("ReplyFab").assertDoesNotExist()
    }
}
