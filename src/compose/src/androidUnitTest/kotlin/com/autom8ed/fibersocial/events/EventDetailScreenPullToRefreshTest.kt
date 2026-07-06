package com.autom8ed.fibersocial.events

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Covers issue #69: pulling down on the event detail page calls the screen's onRefresh. */
@RunWith(RobolectricTestRunner::class)
class EventDetailScreenPullToRefreshTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val loaded = EventDetailState.Loaded(
        detail = EventDetail(
            title = "Stitch n Bitch",
            eventType = null,
            startsAt = null,
            whenText = "July 10, 2026 @ 5:30 PM",
            venue = null,
            descriptionHtml = "",
            discussions = emptyList(),
        ),
    )

    @Test
    fun `pulling down on a loaded event invokes onRefresh`() {
        var refreshCount = 0
        compose.setContent {
            EventDetailScreen(
                eventPermalink = "stitch-n-bitch",
                state = loaded,
                attendees = emptyList(),
                onBack = {},
                onToggleAttendance = {},
                onRefresh = { refreshCount++ },
            )
        }

        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, refreshCount)
    }
}
