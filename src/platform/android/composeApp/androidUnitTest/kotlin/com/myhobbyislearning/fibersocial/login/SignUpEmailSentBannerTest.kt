package com.myhobbyislearning.fibersocial.login

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SignUpEmailSentBannerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `carries the whole handover since the page that explained it is gone`() {
        // The web view has been reset to the login form by the time this shows, so this
        // copy is the only thing telling the user what happened and what to do next.
        compose.setContent { SignUpEmailSentBanner(onDismiss = {}) }
        compose.onNodeWithText("Check your email").assertIsDisplayed()
        compose.onNodeWithText(
            "Ravelry is sending you a link to finish creating your account. " +
                "Once it's done, come back here and sign in.",
        ).assertIsDisplayed()
    }

    @Test
    fun `can be dismissed`() {
        var dismissed = 0
        compose.setContent { SignUpEmailSentBanner(onDismiss = { dismissed++ }) }
        compose.onNodeWithText("Got it").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun `is tagged so the login screens can assert it appears`() {
        compose.setContent { SignUpEmailSentBanner(onDismiss = {}) }
        compose.onNodeWithTag("SignUpEmailSentBanner").assertIsDisplayed()
    }
}
