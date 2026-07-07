package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #204: opening a topic removes the feed list from composition, so its scroll
 * position must live in a hoisted [LazyListState] to survive coming back — not reset to
 * the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedScrollPositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun feedItem(id: Long) = FeedItem(
        id = id,
        groupId = 1L,
        groupName = "KAL Hub",
        lastPostAt = null,
        author = RavelryUser(username = "yarnie"),
        title = "Topic $id",
        bodySummary = "",
        postCount = 1,
    )

    @Test
    fun `feed scroll position survives the list leaving and re-entering composition`() {
        val items = (1..40L).map(::feedItem)
        lateinit var listState: LazyListState
        var showList by mutableStateOf(true)

        compose.setContent {
            // Hoisted above the branch, exactly as FeedScreen hoists it above the
            // topic-detail early-return so it isn't disposed with the list.
            listState = rememberLazyListState()
            val itemsState = remember { items }
            if (showList) {
                FeedList(
                    items = itemsState,
                    hasMore = false,
                    loadingMore = false,
                    onLoadMore = {},
                    listState = listState,
                    onTopicClick = {},
                )
            } else {
                Text("topic detail")
            }
        }

        compose.onNode(hasScrollAction()).performScrollToIndex(20)
        compose.waitForIdle()
        val scrolledIndex = listState.firstVisibleItemIndex
        assertTrue(scrolledIndex > 0, "precondition: the list actually scrolled down")

        // Open a topic (list leaves composition) and come back.
        showList = false
        compose.waitForIdle()
        showList = true
        compose.waitForIdle()

        assertEquals(scrolledIndex, listState.firstVisibleItemIndex)
    }

    @Test
    fun `feed scroll position resets to the top when switching groups`() {
        // The counterpart to the topic-return case: because the hoisted state now survives
        // the list leaving composition, a group SWITCH (different content) must NOT keep the
        // old group's offset. FeedScreen keys the hoisted state by the selected group id;
        // reproduce that keying here and confirm switching groups lands back at the top.
        val items = (1..40L).map(::feedItem)
        lateinit var listState: LazyListState
        var selectedGroupId by mutableStateOf(1L)

        compose.setContent {
            listState = key(selectedGroupId) { rememberLazyListState() }
            val itemsState = remember { items }
            FeedList(
                items = itemsState,
                hasMore = false,
                loadingMore = false,
                onLoadMore = {},
                listState = listState,
                onTopicClick = {},
            )
        }

        compose.onNode(hasScrollAction()).performScrollToIndex(20)
        compose.waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0, "precondition: the list actually scrolled down")

        // Switch to a different group.
        selectedGroupId = 2L
        compose.waitForIdle()

        assertEquals(0, listState.firstVisibleItemIndex)
    }
}
