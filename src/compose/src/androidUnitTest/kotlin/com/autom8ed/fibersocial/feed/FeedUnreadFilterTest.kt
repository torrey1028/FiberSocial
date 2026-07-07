package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.RavelryUser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [filterUnread] backs the top bar's "Unread only" menu option (issue #210). Sticky is just a
 * sort-order flag on [FeedItem] — pinned topics live in the same flat list as everything else —
 * so these cases pin down that sticky topics are filtered by [FeedItem.unreadCount] exactly like
 * non-sticky ones, rather than being exempt from the filter.
 */
class FeedUnreadFilterTest {

    private fun item(id: Long, unreadCount: Int, sticky: Boolean) = FeedItem(
        id = id,
        groupId = 1L,
        groupName = "Group",
        lastPostAt = null,
        author = RavelryUser(username = "author"),
        title = "Topic $id",
        bodySummary = "",
        postCount = 5,
        unreadCount = unreadCount,
        sticky = sticky,
    )

    @Test
    fun `filter off returns every item, sticky or not`() {
        val items = listOf(
            item(id = 1, unreadCount = 0, sticky = true),
            item(id = 2, unreadCount = 3, sticky = false),
        )

        assertEquals(items, filterUnread(items, showUnreadOnly = false))
    }

    @Test
    fun `filter on drops a read sticky topic just like a read non-sticky one`() {
        val readSticky = item(id = 1, unreadCount = 0, sticky = true)
        val unreadSticky = item(id = 2, unreadCount = 2, sticky = true)
        val readTopic = item(id = 3, unreadCount = 0, sticky = false)
        val unreadTopic = item(id = 4, unreadCount = 1, sticky = false)

        val result = filterUnread(
            listOf(readSticky, unreadSticky, readTopic, unreadTopic),
            showUnreadOnly = true,
        )

        assertEquals(listOf(unreadSticky, unreadTopic), result)
    }
}
