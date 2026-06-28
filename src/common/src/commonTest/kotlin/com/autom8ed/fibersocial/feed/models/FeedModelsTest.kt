package com.autom8ed.fibersocial.feed.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

class GroupTest {
    @Test
    fun `deserializes all fields`() {
        val g = json.decodeFromString<Group>(
            """{"id":1,"name":"KAL Hub","permalink":"kal-hub","forum_id":42,
               "badge_url":"https://example.com/badge.png",
               "short_description":"Knit-alongs and fun"}"""
        )
        assertEquals(1L, g.id)
        assertEquals("KAL Hub", g.name)
        assertEquals("kal-hub", g.permalink)
        assertEquals(42L, g.forumId)
        assertEquals("https://example.com/badge.png", g.badgeUrl)
        assertEquals("Knit-alongs and fun", g.shortDescription)
    }

    @Test
    fun `optional fields default to null`() {
        val g = json.decodeFromString<Group>(
            """{"id":2,"name":"Sock Society","permalink":"sock-society","forum_id":7}"""
        )
        assertNull(g.badgeUrl)
        assertNull(g.shortDescription)
    }

    @Test
    fun `ignores unknown keys`() {
        val g = json.decodeFromString<Group>(
            """{"id":3,"name":"Lace Guild","permalink":"lace","forum_id":99,
               "unexpected_field":"ignored"}"""
        )
        assertEquals(3L, g.id)
    }
}

class RavelryUserTest {
    @Test
    fun `deserializes username and avatar`() {
        val u = json.decodeFromString<RavelryUser>(
            """{"username":"yarnie","small_photo_url":"https://example.com/avatar.jpg"}"""
        )
        assertEquals("yarnie", u.username)
        assertEquals("https://example.com/avatar.jpg", u.avatarUrl)
    }

    @Test
    fun `avatar url defaults to null`() {
        val u = json.decodeFromString<RavelryUser>("""{"username":"yarnie"}""")
        assertEquals("yarnie", u.username)
        assertNull(u.avatarUrl)
    }
}

class TopicTest {
    @Test
    fun `deserializes full detail response`() {
        val t = json.decodeFromString<Topic>(
            """{
               "id":100,"title":"My New Sweater",
               "forum_id":42,"forum_posts_count":5,"forum_images_count":3,
               "replied_at":"2024-01-15T10:00:00Z","created_at":"2024-01-10T08:00:00Z",
               "sticky":false,"archived":false,
               "created_by_user":{"username":"yarnie"},
               "summary":"Working on a new colorwork sweater!"
            }"""
        )
        assertEquals(100L, t.id)
        assertEquals("My New Sweater", t.title)
        assertEquals(42L, t.forumId)
        assertEquals(5, t.postsCount)
        assertEquals(3, t.imagesCount)
        assertEquals("2024-01-15T10:00:00Z", t.repliedAt)
        assertEquals(false, t.sticky)
        assertEquals(false, t.archived)
        assertEquals("yarnie", t.createdByUser?.username)
        assertEquals("Working on a new colorwork sweater!", t.summary)
    }

    @Test
    fun `deserializes minimal list response with defaults`() {
        val t = json.decodeFromString<Topic>("""{"id":200,"title":"Quick question"}""")
        assertEquals(200L, t.id)
        assertEquals("Quick question", t.title)
        assertEquals(0L, t.forumId)
        assertEquals(0, t.postsCount)
        assertEquals(0, t.imagesCount)
        assertNull(t.repliedAt)
        assertNull(t.createdAt)
        assertEquals(false, t.sticky)
        assertEquals(false, t.archived)
        assertNull(t.createdByUser)
        assertNull(t.summary)
    }

    @Test
    fun `deserializes sticky announcement topic`() {
        val t = json.decodeFromString<Topic>(
            """{"id":1,"title":"KAL Sign-up Thread","sticky":true,"forum_posts_count":42}"""
        )
        assertEquals(true, t.sticky)
        assertEquals(42, t.postsCount)
    }
}

class FeedItemTest {
    private val author = RavelryUser(username = "yarnie")

    @Test
    fun `ProjectPost carries image count and empty url list`() {
        val item = FeedItem.ProjectPost(
            id = 1L, groupId = 10L, groupName = "KAL Hub", lastPostAt = "2024-01-15",
            author = author, title = "My WIP", imageCount = 3, imageUrls = emptyList(),
            replyCount = 5,
        )
        assertIs<FeedItem>(item)
        assertEquals(3, item.imageCount)
        assertEquals(emptyList(), item.imageUrls)
        assertEquals(5, item.replyCount)
    }

    @Test
    fun `AnnouncementPost exposes body preview`() {
        val item = FeedItem.AnnouncementPost(
            id = 2L, groupId = 10L, groupName = "KAL Hub", lastPostAt = "2024-01-14",
            author = author, title = "KAL Sign-up", bodyPreview = "Join our new KAL!",
            replyCount = 12,
        )
        assertIs<FeedItem>(item)
        assertEquals("Join our new KAL!", item.bodyPreview)
    }

    @Test
    fun `DiscussionPost exposes body preview`() {
        val item = FeedItem.DiscussionPost(
            id = 3L, groupId = 10L, groupName = "KAL Hub", lastPostAt = "2024-01-13",
            author = author, title = "Yarn substitution help?",
            bodyPreview = "Can I sub DK for worsted?", replyCount = 7,
        )
        assertIs<FeedItem>(item)
        assertEquals("Can I sub DK for worsted?", item.bodyPreview)
    }

    @Test
    fun `abstract properties accessible from base type`() {
        val items: List<FeedItem> = listOf(
            FeedItem.ProjectPost(1L, 10L, "KAL Hub", "2024-01-15", author, "WIP", 1, emptyList(), 2),
            FeedItem.AnnouncementPost(2L, 10L, "KAL Hub", "2024-01-14", author, "KAL", "", 3),
            FeedItem.DiscussionPost(3L, 10L, "KAL Hub", "2024-01-13", author, "Q", "", 1),
        )
        assertEquals(listOf(1L, 2L, 3L), items.map { it.id })
        assertEquals(listOf(10L, 10L, 10L), items.map { it.groupId })
    }
}
