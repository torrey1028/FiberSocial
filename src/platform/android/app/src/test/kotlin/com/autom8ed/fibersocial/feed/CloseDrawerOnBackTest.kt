package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CloseDrawerOnBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `system back press closes an open drawer without finishing the activity`() {
        val drawerState = DrawerState(DrawerValue.Open)
        compose.setContent { CloseDrawerOnBack(drawerState) }

        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()

        assertTrue(drawerState.isClosed)
        compose.runOnIdle { assertFalse(compose.activity.isFinishing) }
    }

    @Test
    fun `system back press falls through when the drawer is closed`() {
        val drawerState = DrawerState(DrawerValue.Closed)
        compose.setContent { CloseDrawerOnBack(drawerState) }

        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }

        // No enabled BackHandler → the dispatcher's default behavior finishes the activity.
        compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    }
}
