package com.autom8ed.fibersocial.feed.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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

    @Test
    fun `direct construction with only some optional fields set`() {
        val g = Group(id = 4L, name = "Fiber Circle", permalink = "fiber-circle", forumId = 5L, badgeUrl = "https://example.com/b.png")
        assertEquals("https://example.com/b.png", g.badgeUrl)
        assertNull(g.shortDescription)
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
    fun `value semantics hold across copy, equals, and toString`() {
        val t = Topic(
            id = 100L, title = "My New Sweater", forumId = 42L, postsCount = 5,
            imagesCount = 3, repliedAt = "2024-01-15T10:00:00Z",
            createdAt = "2024-01-10T08:00:00Z", sticky = true, archived = false,
            createdByUser = RavelryUser(username = "yarnie"),
            summary = "Working on a new colorwork sweater!",
        )
        assertEquals(t, t.copy())
        assertEquals(t.hashCode(), t.copy().hashCode())
        assertNotEquals(t, t.copy(id = 101L))
        assertNotEquals(t, t.copy(sticky = false))
        assertEquals("yarnie", t.copy(title = "Renamed").createdByUser?.username)
        assertTrue(t.toString().contains("My New Sweater"))
    }

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

    @Test
    fun `direct construction falls back to default parameter values`() {
        // Unlike the JSON-deserialization tests above, this constructs a Topic straight
        // from Kotlin, omitting every optional param.
        val t = Topic(id = 300L, title = "Direct construction")
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
    fun `direct construction with only some optional params set`() {
        val t = Topic(id = 301L, title = "Partial construction", sticky = true, summary = "Pinned")
        assertEquals(0L, t.forumId)
        assertEquals(true, t.sticky)
        assertEquals("Pinned", t.summary)
        assertNull(t.repliedAt)
    }
}

class FeedItemTest {
    private val author = RavelryUser(username = "yarnie")

    private fun item(
        sticky: Boolean = false,
        replyCount: Int = 7,
        latestReplyAuthor: RavelryUser? = null,
        latestReplyPreview: String? = null,
    ) = FeedItem(
        id = 3L, groupId = 10L, groupName = "KAL Hub", lastPostAt = "2024-01-13",
        author = author, title = "Yarn substitution help?",
        bodyPreview = "Can I sub DK for worsted?",
        bodySummary = "Can I sub DK for worsted? I have this beautiful skein...",
        replyCount = replyCount,
        sticky = sticky,
        latestReplyAuthor = latestReplyAuthor,
        latestReplyPreview = latestReplyPreview,
    )

    @Test
    fun `exposes body preview and full summary`() {
        val item = item()
        assertEquals("Yarn substitution help?", item.title)
        assertEquals(7, item.replyCount)
        assertEquals("Can I sub DK for worsted?", item.bodyPreview)
        assertEquals("Can I sub DK for worsted? I have this beautiful skein...", item.bodySummary)
    }

    @Test
    fun `sticky defaults to false`() {
        assertFalse(item().sticky)
        assertTrue(item(sticky = true).sticky)
    }

    @Test
    fun `sticky item with latest-reply attribution is rejected`() {
        // A pinned topic always attributes to the opening post — the two fields
        // are mutually exclusive by construction, not just by convention.
        assertFailsWith<IllegalArgumentException> {
            item(sticky = true, latestReplyAuthor = author)
        }
        assertFailsWith<IllegalArgumentException> {
            item(sticky = true, latestReplyPreview = "Newest reply")
        }
    }

    @Test
    fun `display fields fall back to opening post without latest reply`() {
        val item = item(replyCount = 0)
        assertEquals(author, item.displayAuthor)
        assertEquals("Can I sub DK for worsted?", item.displayPreview)
    }

    @Test
    fun `display fields prefer the latest reply`() {
        val replier = RavelryUser(username = "replier")
        val item = item(
            replyCount = 3,
            latestReplyAuthor = replier,
            latestReplyPreview = "Newest reply",
        )
        assertEquals(replier, item.displayAuthor)
        assertEquals("Newest reply", item.displayPreview)
    }
}

class PostTest {
    @Test
    fun `deserializes all fields`() {
        val p = json.decodeFromString<Post>(
            """{"id":1,"body_html":"<p>Hello</p>","created_at":"2024-01-15T10:00:00Z","user":{"username":"yarnie","small_photo_url":"https://example.com/a.jpg"}}"""
        )
        assertEquals(1L, p.id)
        assertEquals("<p>Hello</p>", p.bodyHtml)
        assertEquals("2024-01-15T10:00:00Z", p.createdAt)
        assertEquals("yarnie", p.user?.username)
        assertEquals("https://example.com/a.jpg", p.user?.avatarUrl)
    }

    @Test
    fun `optional fields default gracefully`() {
        val p = json.decodeFromString<Post>("""{"id":2}""")
        assertEquals(2L, p.id)
        assertEquals("", p.bodyHtml)
        assertNull(p.createdAt)
        assertNull(p.user)
    }

    @Test
    fun `ignores unknown keys`() {
        val p = json.decodeFromString<Post>("""{"id":3,"unexpected":"ignored"}""")
        assertEquals(3L, p.id)
        assertTrue(p.bodyHtml.isEmpty())
    }

    @Test
    fun `equality copy and hashCode`() {
        val p1 = Post(id = 1L, bodyHtml = "<p>hi</p>", createdAt = "2024-01-15T10:00:00Z")
        val p2 = Post(id = 1L, bodyHtml = "<p>hi</p>", createdAt = "2024-01-15T10:00:00Z")
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
        assertEquals(p1, p1.copy())
        assertNotEquals(p1, p1.copy(id = 2L))
        assertTrue(p1.toString().contains("1"))
    }

    @Test
    fun `deserializes vote_totals and user_votes when present`() {
        val p = json.decodeFromString<Post>(
            """{"id":4,"vote_totals":{"love":2,"funny":1},"user_votes":["love"]}"""
        )
        assertEquals(mapOf("love" to 2, "funny" to 1), p.voteTotals)
        assertEquals(listOf("love"), p.userVotes)
        assertTrue(p.hasVoted(VoteType.LOVE))
        assertFalse(p.hasVoted(VoteType.FUNNY))
        assertEquals(2, p.voteCount(VoteType.LOVE))
        assertEquals(1, p.voteCount(VoteType.FUNNY))
        assertEquals(0, p.voteCount(VoteType.AGREE))
    }

    @Test
    fun `vote fields default to empty and unvoted when absent`() {
        val p = json.decodeFromString<Post>("""{"id":5}""")
        assertEquals(emptyMap(), p.voteTotals)
        assertEquals(emptyList(), p.userVotes)
        assertFalse(p.hasVoted(VoteType.LOVE))
        assertEquals(0, p.voteCount(VoteType.LOVE))
    }
}
