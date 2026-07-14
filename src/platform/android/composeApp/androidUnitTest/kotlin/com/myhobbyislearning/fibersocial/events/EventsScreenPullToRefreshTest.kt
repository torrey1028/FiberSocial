package com.myhobbyislearning.fibersocial.events

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.myhobbyislearning.fibersocial.feed.models.Group
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Covers issue #69: pulling down on the events list calls the screen's onRefresh. */
@RunWith(RobolectricTestRunner::class)
class EventsScreenPullToRefreshTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val group = Group(id = 1L, name = "Kirkland Knitters", permalink = "kirkland-knitters", forumId = 1L)

    private val loaded = EventsState.Loaded(
        events = listOf(
            GroupEvent(
                group = group,
                event = EventSummary(
                    permalink = "stitch-n-bitch",
                    title = "Stitch n Bitch",
                    startsAt = null,
                    whenText = "July 10, 2026 @ 5:30 PM",
                    attendeeCount = 3,
                ),
            ),
        ),
    )

    @Test
    fun `pulling down on a loaded events list invokes onRefresh`() {
        var refreshCount = 0
        compose.setContent {
            EventsScreen(
                state = loaded,
                group = group,
                onBack = {},
                onEventClick = {},
                onRefresh = { refreshCount++ },
            )
        }

        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, refreshCount)
    }

    @Test
    fun `pulling down on the empty-events state invokes onRefresh`() {
        var refreshCount = 0
        compose.setContent {
            EventsScreen(
                state = EventsState.Loaded(events = emptyList()),
                group = group,
                onBack = {},
                onEventClick = {},
                onRefresh = { refreshCount++ },
            )
        }

        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, refreshCount)
    }
}
