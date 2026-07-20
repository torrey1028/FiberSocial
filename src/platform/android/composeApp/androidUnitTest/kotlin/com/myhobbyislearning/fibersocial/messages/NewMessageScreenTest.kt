package com.myhobbyislearning.fibersocial.messages

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.myhobbyislearning.fibersocial.feed.models.UserSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun hit(username: String, displayName: String = username) = UserSearchResult(
    id = 7,
    username = username,
    displayName = displayName,
    avatarUrl = null,
)

private val RESULTS = RecipientSearchState.Results(listOf(hit("yarnbarn"), hit("purlqueen")))

/** What a send handed back, so tests can assert on the committed recipient rather than text. */
private data class Sent(val recipient: UserSearchResult?, val subject: String, val body: String)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NewMessageScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var sent: Sent? = null
    private var queries = mutableListOf<String>()
    private var backs = 0

    private fun setScreen(
        sendState: SendMessageState = SendMessageState.Idle,
        searchState: RecipientSearchState = RecipientSearchState.Idle,
        replyTo: ReplyContext? = null,
        lockedRecipient: UserSearchResult? = null,
        onSent: () -> Unit = {},
    ) = compose.setContent {
        NewMessageScreen(
            sendState = sendState,
            searchState = searchState,
            onQueryChange = { queries += it },
            onSend = { recipient, subject, body -> sent = Sent(recipient, subject, body) },
            onSent = onSent,
            onBack = { backs++ },
            replyTo = replyTo,
            lockedRecipient = lockedRecipient,
        )
    }

    @Test
    fun `the composer renders its three fields`() {
        setScreen()

        compose.onNodeWithTag("NewMessageScreen").assertIsDisplayed()
        compose.onNodeWithTag("RecipientQueryField").assertIsDisplayed()
        compose.onNodeWithTag("MessageSubjectField").assertIsDisplayed()
        compose.onNodeWithTag("MessageBodyField").assertIsDisplayed()
        compose.onNodeWithText("New message").assertIsDisplayed()
    }

    @Test
    fun `typing in the To field reports the query for debouncing`() {
        setScreen()

        compose.onNodeWithTag("RecipientQueryField").performTextInput("yarn")

        assertTrue("expected the query to be reported", queries.isNotEmpty())
        assertEquals("yarn", queries.last())
    }

    @Test
    fun `search results render and tapping one commits the recipient`() {
        setScreen(searchState = RESULTS)

        compose.onNodeWithTag("RecipientResults").assertIsDisplayed()
        compose.onNodeWithTag("RecipientResult-yarnbarn").assertIsDisplayed()
        compose.onNodeWithTag("RecipientResult-purlqueen").assertIsDisplayed()

        compose.onNodeWithTag("RecipientResult-yarnbarn").performClick()

        // The query field is REPLACED by the committed choice — a recipient the user can
        // still type over is a recipient they can believe in and not actually address.
        compose.onNodeWithTag("SelectedRecipient").assertIsDisplayed()
        compose.onNodeWithTag("RecipientQueryField").assertDoesNotExist()
    }

    @Test
    fun `clearing a committed recipient brings the search field back`() {
        setScreen(searchState = RESULTS)
        compose.onNodeWithTag("RecipientResult-yarnbarn").performClick()

        compose.onNodeWithContentDescription("Choose someone else").performClick()

        compose.onNodeWithTag("RecipientQueryField").assertIsDisplayed()
        compose.onNodeWithTag("SelectedRecipient").assertDoesNotExist()
    }

    @Test
    fun `send stays disabled until a recipient a subject and a body are all present`() {
        setScreen(searchState = RESULTS)

        compose.onNodeWithTag("SendMessageButton").assertIsNotEnabled()
        compose.onNodeWithTag("MessageSubjectField").performTextInput("Yarn swap")
        compose.onNodeWithTag("SendMessageButton").assertIsNotEnabled()
        compose.onNodeWithTag("MessageBodyField").performTextInput("Interested?")
        // Everything typed but nobody chosen — the case a free-text "To" field would let
        // through and Ravelry would reject with a confusing error.
        compose.onNodeWithTag("SendMessageButton").assertIsNotEnabled()

        compose.onNodeWithTag("RecipientResult-yarnbarn").performClick()
        compose.onNodeWithTag("SendMessageButton").assertIsEnabled()
    }

    @Test
    fun `sending hands back the committed recipient rather than typed text`() {
        setScreen(searchState = RESULTS)
        compose.onNodeWithTag("MessageSubjectField").performTextInput("Yarn swap")
        compose.onNodeWithTag("MessageBodyField").performTextInput("Interested?")
        compose.onNodeWithTag("RecipientResult-yarnbarn").performClick()

        compose.onNodeWithTag("SendMessageButton").performClick()

        assertEquals("yarnbarn", sent?.recipient?.username)
        assertEquals("Yarn swap", sent?.subject)
        assertEquals("Interested?", sent?.body)
    }

    @Test
    fun `a blank-only subject does not enable send`() {
        setScreen(searchState = RESULTS)
        compose.onNodeWithTag("RecipientResult-yarnbarn").performClick()
        compose.onNodeWithTag("MessageSubjectField").performTextInput("   ")
        compose.onNodeWithTag("MessageBodyField").performTextInput("   ")

        compose.onNodeWithTag("SendMessageButton").assertIsNotEnabled()
    }

    @Test
    fun `an empty search result set says so instead of showing nothing`() {
        setScreen(searchState = RecipientSearchState.Results(emptyList()))

        compose.onNodeWithTag("RecipientNoResults").assertIsDisplayed()
    }

    @Test
    fun `a pending search shows it is working`() {
        setScreen(searchState = RecipientSearchState.Searching)

        compose.onNodeWithTag("RecipientSearching").assertIsDisplayed()
    }

    @Test
    fun `a failed search is reported without hiding the query field`() {
        setScreen(searchState = RecipientSearchState.Error("Couldn't search for people."))

        compose.onNodeWithTag("RecipientSearchError").assertIsDisplayed()
        compose.onNodeWithTag("RecipientQueryField").assertIsDisplayed()
    }

    // ---- reply mode -------------------------------------------------------------------

    @Test
    fun `reply mode fixes the recipient and the derived subject`() {
        setScreen(replyTo = ReplyContext(counterpartName = "friend", subject = "Re: Yarn talk"))

        compose.onNodeWithText("Reply").assertIsDisplayed()
        compose.onNodeWithTag("FixedRecipient").assertIsDisplayed()
        compose.onNodeWithText("friend").assertIsDisplayed()
        compose.onNodeWithText("Re: Yarn talk").assertIsDisplayed()
        // No picker at all: a reply's recipient is whoever the conversation is with.
        compose.onNodeWithTag("RecipientQueryField").assertDoesNotExist()
    }

    @Test
    fun `a reply needs only a body to send and carries the derived subject`() {
        setScreen(replyTo = ReplyContext(counterpartName = "friend", subject = "Re: Yarn talk"))

        compose.onNodeWithTag("SendMessageButton").assertIsNotEnabled()
        compose.onNodeWithTag("MessageBodyField").performTextInput("Yes please")
        compose.onNodeWithTag("SendMessageButton").assertIsEnabled()

        compose.onNodeWithTag("SendMessageButton").performClick()

        assertNull("a reply must not carry a recipient — reply.json derives it", sent?.recipient)
        assertEquals("Re: Yarn talk", sent?.subject)
        assertEquals("Yes please", sent?.body)
    }

    /**
     * A reply's subject must not be typeable: `reply.json` sends whatever subject it is
     * given, and an edited one is how `Re: Re: Re:` starts.
     *
     * Asserted as the ABSENCE of the SetText action rather than by typing and checking
     * nothing changed — a read-only field has no such action, so the framework refuses the
     * input outright and a `performTextInput` here fails the test for the right reason but
     * in the wrong place.
     */
    @Test
    fun `a reply subject cannot be edited`() {
        setScreen(replyTo = ReplyContext(counterpartName = "friend", subject = "Re: Yarn talk"))

        compose.onNodeWithTag("MessageSubjectField")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        compose.onNodeWithText("Re: Yarn talk").assertIsDisplayed()
    }

    // ---- locked recipient (from a profile, issue #373) ----------------------------------

    @Test
    fun `a locked recipient replaces the picker entirely`() {
        setScreen(searchState = RESULTS, lockedRecipient = hit("yarnbarn", "Yarn Barn"))

        // Still a NEW message, not a reply.
        compose.onNodeWithText("New message").assertIsDisplayed()
        compose.onNodeWithTag("FixedRecipient").assertIsDisplayed()
        compose.onNodeWithText("Yarn Barn").assertIsDisplayed()
        // Even with search results on hand, there is nothing to search or choose.
        compose.onNodeWithTag("RecipientQueryField").assertDoesNotExist()
        compose.onNodeWithTag("RecipientResults").assertDoesNotExist()
        compose.onNodeWithTag("SelectedRecipient").assertDoesNotExist()
    }

    @Test
    fun `a locked recipient shows the handle when it differs from the display name`() {
        setScreen(lockedRecipient = hit("yarnbarn", "Yarn Barn"))

        compose.onNodeWithText("@yarnbarn").assertIsDisplayed()
    }

    @Test
    fun `a locked recipient omits a handle that is just the display name again`() {
        setScreen(lockedRecipient = hit("yarnbarn"))

        compose.onNodeWithText("yarnbarn").assertIsDisplayed()
        compose.onNodeWithText("@yarnbarn").assertDoesNotExist()
    }

    @Test
    fun `a locked recipient needs only a subject and body to send and is handed back`() {
        setScreen(lockedRecipient = hit("yarnbarn", "Yarn Barn"))

        compose.onNodeWithTag("SendMessageButton").assertIsNotEnabled()
        compose.onNodeWithTag("MessageSubjectField").performTextInput("Yarn swap")
        compose.onNodeWithTag("MessageBodyField").performTextInput("Interested?")
        compose.onNodeWithTag("SendMessageButton").assertIsEnabled()

        compose.onNodeWithTag("SendMessageButton").performClick()

        // create.json, addressed by the locked handle — not null the way a reply is.
        assertEquals("yarnbarn", sent?.recipient?.username)
        assertEquals("Yarn swap", sent?.subject)
        assertEquals("Interested?", sent?.body)
    }

    @Test
    fun `a locked recipient still allows an editable subject unlike a reply`() {
        setScreen(lockedRecipient = hit("yarnbarn"))

        compose.onNodeWithTag("MessageSubjectField")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
    }

    @Test
    fun `backing out of an untouched locked composer leaves without asking`() {
        // The locked recipient is navigation, not typing — there is nothing to lose yet.
        setScreen(lockedRecipient = hit("yarnbarn"))

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
        compose.onNodeWithTag("MessageDiscardConfirm").assertDoesNotExist()
    }

    @Test
    fun `backing out of a locked composer with a typed body still asks`() {
        setScreen(lockedRecipient = hit("yarnbarn"))
        compose.onNodeWithTag("MessageBodyField").performTextInput("Half a thought")

        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithTag("MessageDiscardConfirm").assertIsDisplayed()
        assertEquals("nothing should be discarded yet", 0, backs)
    }

    @Test
    fun `reply mode wins over a locked recipient`() {
        // Never set together by FeedScreen; asserted so a future caller that does gets the
        // reply's fixed recipient rather than a new-message send to the locked one.
        setScreen(
            replyTo = ReplyContext(counterpartName = "friend", subject = "Re: Yarn talk"),
            lockedRecipient = hit("yarnbarn", "Yarn Barn"),
        )

        compose.onNodeWithText("Reply").assertIsDisplayed()
        compose.onNodeWithText("friend").assertIsDisplayed()
        compose.onNodeWithText("Yarn Barn").assertDoesNotExist()

        compose.onNodeWithTag("MessageBodyField").performTextInput("Yes please")
        compose.onNodeWithTag("SendMessageButton").performClick()

        assertNull("reply.json derives its own recipient", sent?.recipient)
    }

    // ---- send state -------------------------------------------------------------------

    @Test
    fun `a failed send shows its message and keeps what was typed`() {
        setScreen(
            sendState = SendMessageState.Error("This person isn't accepting messages.", messagingBlocked = true),
            searchState = RESULTS,
        )
        compose.onNodeWithTag("MessageBodyField").performTextInput("Interested?")

        compose.onNodeWithTag("SendMessageError").assertIsDisplayed()
        compose.onNodeWithText("This person isn't accepting messages.").assertIsDisplayed()
        compose.onNodeWithText("Interested?").assertIsDisplayed()
    }

    @Test
    fun `sending replaces the send button with a spinner`() {
        setScreen(sendState = SendMessageState.Sending)

        compose.onNodeWithTag("SendMessageButton").assertDoesNotExist()
    }

    @Test
    fun `a confirmed send notifies the caller exactly once`() {
        var sentCount = 0
        setScreen(
            sendState = SendMessageState.Sent(Message(id = 50, subject = "Yarn swap")),
            onSent = { sentCount++ },
        )
        compose.waitForIdle()

        assertEquals(1, sentCount)
    }

    // ---- discard confirmation -----------------------------------------------------------

    @Test
    fun `backing out of an empty composer leaves immediately`() {
        setScreen()

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
        compose.onNodeWithTag("MessageDiscardConfirm").assertDoesNotExist()
    }

    @Test
    fun `backing out of a started message asks before discarding it`() {
        setScreen()
        compose.onNodeWithTag("MessageBodyField").performTextInput("Half a thought")

        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithTag("MessageDiscardConfirm").assertIsDisplayed()
        assertEquals("nothing should be discarded yet", 0, backs)

        compose.onNodeWithText("Keep editing").performClick()
        assertEquals(0, backs)
        compose.onNodeWithText("Half a thought").assertIsDisplayed()
    }

    @Test
    fun `confirming the discard leaves the composer`() {
        setScreen()
        compose.onNodeWithTag("MessageBodyField").performTextInput("Half a thought")
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Discard").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `a half-typed recipient search counts as something worth confirming`() {
        setScreen()
        compose.onNodeWithTag("RecipientQueryField").performTextInput("yarn")

        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithTag("MessageDiscardConfirm").assertIsDisplayed()
    }
}
