package com.myhobbyislearning.fibersocial.terms

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class TermsGateScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the zero-tolerance summary`() {
        compose.setContent {
            TermsGateScreen(onOpenFullTerms = {}, onAgree = {})
        }
        compose.onNodeWithText(
            "FiberSocial has no tolerance for objectionable content or abusive " +
                "users. Every post can be reported, and every user can be blocked — " +
                "blocking removes their content from your feed instantly.",
        ).assertIsDisplayed()
    }

    @Test
    fun `tapping the full terms link invokes onOpenFullTerms`() {
        var opened = 0
        compose.setContent {
            TermsGateScreen(onOpenFullTerms = { opened++ }, onAgree = {})
        }
        compose.onNodeWithText("Read the full Terms of Use").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `tapping agree and continue invokes onAgree`() {
        var agreed = 0
        compose.setContent {
            TermsGateScreen(onOpenFullTerms = {}, onAgree = { agreed++ })
        }
        compose.onNodeWithText("Agree and continue").performClick()
        compose.runOnIdle { assertEquals(1, agreed) }
    }
}
