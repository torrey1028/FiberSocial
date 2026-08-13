package com.myhobbyislearning.fibersocial.messages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.profile.LocalProfileOpener
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
        onOpenProfile: ((String) -> Unit)? = null,
    ) = compose.setContent {
        CompositionLocalProvider(LocalProfileOpener provides onOpenProfile) {
            MessageThreadScreen(
                state = state,
                currentUsername = ME,
                onBack = onBack,
                onReply = onReply,
                onToggleMute = onToggleMute,
            )
        }
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

    /** A received message's sender attribution opens that person's profile (issue #400). */
    @Test
    fun `tapping a sender name opens their profile`() {
        val opened = mutableListOf<String>()
        // One received message only: the default conversation has two, and "friend" would
        // then match both attributions rather than naming a single node to tap.
        setScreen(
            state = OpenThreadState(
                conversation(listOf(message(1, from = "friend", to = ME, body = "Only one"))),
            ),
            onOpenProfile = { opened += it },
        )

        compose.onNodeWithText("friend").performClick()

        assertEquals(listOf("friend"), opened)
    }

    /**
     * The user's own messages are attributed to "You", which must not navigate anywhere —
     * a link to your own profile from your own message is a dead end, and "You" is not a
     * username the opener could resolve in the first place.
     */
    @Test
    fun `the You attribution is not a profile link`() {
        val opened = mutableListOf<String>()
        setScreen(
            state = OpenThreadState(
                conversation(listOf(message(2, from = ME, to = "friend", body = "Mine"))),
            ),
            onOpenProfile = { opened += it },
        )

        compose.onNodeWithText("You").performClick()

        assertTrue("\"You\" opened a profile: $opened", opened.isEmpty())
    }

    /**
     * The last message must be able to scroll CLEAR of the Reply FAB (issue #401).
     *
     * Asserting the message exists or `assertIsDisplayed()` would not catch this: the FAB
     * floats above the list without occupying layout space, so the occluded message is
     * still present, still "displayed", and simply unreadable underneath the button. Only
     * comparing bounds sees it — the bug is that the list's bottom `contentPadding`
     * reserved 8.dp against a ~56.dp button.
     *
     * Scrolls to the end first, because the failure only exists at the bottom of a list
     * long enough to scroll there.
     */
    @Test
    fun `the last message can scroll clear of the reply button`() {
        val many = (1L..30L).map { id ->
            message(id, from = if (id % 2 == 0L) ME else "friend", to = ME, body = "Body $id")
        }
        setScreen(state = OpenThreadState(conversation(messages = many)), onReply = {})

        compose.onNodeWithTag("MessageThreadList").performScrollToIndex(many.lastIndex)
        compose.waitForIdle()

        val lastMessageBottom = compose.onNodeWithText("Body 30").getBoundsInRoot().bottom
        val fabTop = compose.onNodeWithTag("ReplyFab").getBoundsInRoot().top

        assertTrue(
            "last message (bottom=$lastMessageBottom) is covered by the Reply FAB (top=$fabTop)",
            lastMessageBottom <= fabTop,
        )
    }

    /**
     * Losing the Reply action must also release the space reserved for its FAB (issue #401's
     * "no dead gap" half).
     *
     * `onSizeChanged` reports a size only while something is being measured — it does NOT
     * fire with zero when the node leaves composition. So a clearance that only ever
     * measured would latch the last height forever, and a thread with no reply action would
     * keep a button-sized strip of empty space under its final message. `FabClearance`
     * resets on dispose to prevent that; this is what would catch it going away.
     *
     * Toggles rather than composing the FAB-less case from scratch, because a clearance
     * that was never measured is `0.dp` anyway — only the measure-then-remove path can
     * strand a stale value.
     */
    @Test
    fun `dropping the reply action releases the space its FAB reserved`() {
        val many = (1L..30L).map { id ->
            message(id, from = if (id % 2 == 0L) ME else "friend", to = ME, body = "Body $id")
        }
        var repliable by mutableStateOf(true)
        compose.setContent {
            MessageThreadScreen(
                state = OpenThreadState(conversation(messages = many)),
                currentUsername = ME,
                onBack = {},
                onReply = if (repliable) ({}) else null,
                onToggleMute = null,
            )
        }

        compose.onNodeWithTag("MessageThreadList").performScrollToIndex(many.lastIndex)
        compose.waitForIdle()
        val withFab = compose.onNodeWithText("Body 30").getBoundsInRoot().bottom

        compose.runOnIdle { repliable = false }
        compose.onNodeWithTag("ReplyFab").assertDoesNotExist()
        compose.onNodeWithTag("MessageThreadList").performScrollToIndex(many.lastIndex)
        compose.waitForIdle()
        val withoutFab = compose.onNodeWithText("Body 30").getBoundsInRoot().bottom

        // With the FAB gone the reserved strip goes too, so the last message can now sit
        // lower than it could while the button was there. A latched height keeps the two
        // identical.
        assertTrue(
            "last message did not reclaim the FAB's space (with=$withFab without=$withoutFab)",
            withoutFab > withFab,
        )
    }
}
