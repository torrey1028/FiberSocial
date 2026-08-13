package com.myhobbyislearning.fibersocial.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class WebPageScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val deletionUrl = "https://www.ravelry.com/people/knitwit/edit"

    @Test
    fun `shows the title and the host the page is on`() {
        // The host label is the user's check that a page asking for their Ravelry
        // password is really Ravelry — the same assurance Apple cites for
        // SFSafariViewController (issue #481).
        compose.setContent {
            WebPageScreen(url = deletionUrl, title = "Delete account", onClose = {})
        }
        compose.onNodeWithText("Delete account").assertIsDisplayed()
        compose.onNodeWithText("www.ravelry.com").assertIsDisplayed()
    }

    @Test
    fun `the close button invokes its handler`() {
        // Closing is what signs the user out on the deletion path, so a dead close
        // button would strand them on the page with their session still live.
        var closes = 0
        compose.setContent {
            WebPageScreen(url = deletionUrl, title = "Delete account", onClose = { closes++ })
        }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun `system back closes the screen when the page has no history`() {
        var closes = 0
        compose.setContent {
            WebPageScreen(url = deletionUrl, title = "Delete account", onClose = { closes++ })
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun `host label reduces a url to its host and survives a malformed one`() {
        assertEquals("www.ravelry.com", webPageHost(deletionUrl))
        assertEquals("ravelry.com", webPageHost("https://ravelry.com/"))
        // No scheme, no crash — the label just shows whatever it was given rather than
        // the chrome rendering an empty subtitle.
        assertEquals("not a url", webPageHost("not a url"))
    }
}
