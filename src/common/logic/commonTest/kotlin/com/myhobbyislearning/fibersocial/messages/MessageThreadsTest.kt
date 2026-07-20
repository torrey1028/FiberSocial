package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for the client-side conversation threading (issue #368).
 *
 * No MockEngine and no network: [groupIntoThreads] takes a pre-merged list of messages
 * (see its KDoc for why the caller, not this layer, concatenates the inbox/sent folders)
 * and returns threads.
 */
class MessageThreadsTest {

    private val me = "knitterjane"
    private val them = RavelryUser(username = "purlpete")
    private val myself = RavelryUser(username = me)

    /** Ravelry's `sent_at` wire format. */
    private fun at(day: Int, hour: Int = 12) =
        "2026/07/${day.toString().padStart(2, '0')} ${hour.toString().padStart(2, '0')}:00:00 +0000"

    private fun inbound(
        id: Long,
        parent: Long? = null,
        subject: String = "Yarn swap",
        sentAt: String? = at(1),
        read: Boolean = true,
        viaScrape: Boolean = false,
    ) = Message(
        id = id,
        subject = subject,
        sender = them,
        recipient = myself,
        sentAt = sentAt,
        readMessage = read,
        parentMessageId = parent,
        viaScrape = viaScrape,
    )

    private fun outbound(
        id: Long,
        parent: Long? = null,
        subject: String = "Yarn swap",
        sentAt: String? = at(1),
        read: Boolean = true,
        viaScrape: Boolean = false,
    ) = Message(
        id = id,
        subject = subject,
        sender = myself,
        recipient = them,
        sentAt = sentAt,
        readMessage = read,
        parentMessageId = parent,
        viaScrape = viaScrape,
    )

    @Test
    fun `empty input yields no threads`() {
        assertEquals(emptyList(), groupIntoThreads(emptyList(), me))
    }

    @Test
    fun `a reply and its parent collapse into one thread`() {
        val root = inbound(id = 1, sentAt = at(1))
        val reply = outbound(id = 2, parent = 1, subject = "Re: Yarn swap", sentAt = at(2))

        val threads = groupIntoThreads(listOf(reply, root), me)

        assertEquals(1, threads.size)
        assertEquals(1L, threads[0].rootId)
        assertEquals(listOf(1L, 2L), threads[0].messages.map { it.id })
    }

    @Test
    fun `a three deep chain orders oldest to newest regardless of input order`() {
        val root = inbound(id = 10, sentAt = at(1))
        val reply = outbound(id = 11, parent = 10, sentAt = at(2))
        val replyToReply = inbound(id = 12, parent = 11, sentAt = at(3))

        val threads = groupIntoThreads(listOf(replyToReply, root, reply), me)

        assertEquals(1, threads.size)
        assertEquals(10L, threads[0].rootId)
        assertEquals(listOf(10L, 11L, 12L), threads[0].messages.map { it.id })
    }

    @Test
    fun `an orphaned reply becomes its own thread instead of vanishing`() {
        // Parent 99 is older than this page, archived, or deleted — routine, not exotic.
        val orphan = inbound(id = 5, parent = 99, subject = "Re: Older chat")

        val threads = groupIntoThreads(listOf(orphan), me)

        assertEquals(1, threads.size)
        assertEquals(5L, threads[0].rootId)
        assertEquals(listOf(5L), threads[0].messages.map { it.id })
        assertEquals("Re: Older chat", threads[0].subject)
    }

    @Test
    fun `a self parenting message terminates and roots itself`() {
        val loop = inbound(id = 7, parent = 7)

        val threads = groupIntoThreads(listOf(loop), me)

        assertEquals(1, threads.size)
        assertEquals(7L, threads[0].rootId)
    }

    @Test
    fun `a two node cycle terminates with each message rooted at itself`() {
        val a = inbound(id = 1, parent = 2, sentAt = at(1))
        val b = outbound(id = 2, parent = 1, sentAt = at(2))

        val threads = groupIntoThreads(listOf(a, b), me)

        // The important assertion is simply that we got here: a naive parent walk hangs.
        assertEquals(setOf(1L, 2L), threads.map { it.rootId }.toSet())
        threads.forEach { thread -> assertTrue(thread.messages.any { it.id == thread.rootId }) }
    }

    @Test
    fun `a chain longer than the walk cap still terminates`() {
        // 200 links, well past MAX_PARENT_WALK_DEPTH (64).
        val chain = (1L..200L).map { id ->
            inbound(id = id, parent = if (id == 1L) null else id - 1, sentAt = at(1))
        }

        val threads = groupIntoThreads(chain, me)

        // Everything within the cap collapses onto message 1; the deeper tail roots
        // itself rather than walking forever. No message is lost.
        assertEquals(200, threads.sumOf { it.messages.size })
        assertTrue(threads.size > 1)
        assertEquals(1L, threads.minOf { it.rootId })
    }

    @Test
    fun `a chain needing exactly the walk cap's hop count still fully resolves`() {
        // A regression pin for an off-by-one: the walk cap is MAX_PARENT_WALK_DEPTH (64)
        // HOPS, so a chain where the deepest message needs exactly 64 hops to reach its
        // true root (65 messages: ids 1..65, id 1 rootless) must still fully collapse
        // onto message 1 — the boundary the loose "> 1 threads" assertion in the 200-link
        // test above can't distinguish from an off-by-one that stops one hop short.
        val chain = (1L..65L).map { id ->
            inbound(id = id, parent = if (id == 1L) null else id - 1, sentAt = at(1))
        }

        val threads = groupIntoThreads(chain, me)

        assertEquals(1, threads.size)
        assertEquals(1L, threads[0].rootId)
        assertEquals(65, threads[0].messages.size)
    }

    @Test
    fun `a chain needing one hop more than the walk cap still splits at the boundary`() {
        // The other half of the same pin: one hop past the cap (66 messages, id 66 needs
        // 65 hops) must genuinely still bail, not have the boundary fix accidentally
        // widen the cap instead of correcting the off-by-one.
        val chain = (1L..66L).map { id ->
            inbound(id = id, parent = if (id == 1L) null else id - 1, sentAt = at(1))
        }

        val threads = groupIntoThreads(chain, me)

        assertEquals(2, threads.size)
        assertEquals(setOf(1L, 66L), threads.map { it.rootId }.toSet())
        assertEquals(65, threads.first { it.rootId == 1L }.messages.size)
        assertEquals(1, threads.first { it.rootId == 66L }.messages.size)
    }

    @Test
    fun `counterpart is the other party for an inbound root`() {
        val threads = groupIntoThreads(listOf(inbound(id = 1)), me)

        assertEquals(them, threads[0].counterpart)
    }

    @Test
    fun `counterpart is the other party for an outbound root`() {
        val threads = groupIntoThreads(listOf(outbound(id = 1)), me)

        assertEquals(them, threads[0].counterpart)
    }

    @Test
    fun `counterpart matches the current username case insensitively`() {
        val threads = groupIntoThreads(listOf(outbound(id = 1)), "KnitterJane")

        assertEquals(them, threads[0].counterpart)
    }

    @Test
    fun `counterpart falls back to a later message when the root names nobody`() {
        val root = Message(id = 1, subject = "Notice", sentAt = at(1))
        val reply = outbound(id = 2, parent = 1, sentAt = at(2))

        val threads = groupIntoThreads(listOf(root, reply), me)

        assertEquals(1, threads.size)
        assertEquals(them, threads[0].counterpart)
    }

    @Test
    fun `hasUnread is true for an unread inbound message`() {
        val threads = groupIntoThreads(listOf(inbound(id = 1, read = false)), me)

        assertTrue(threads[0].hasUnread)
    }

    @Test
    fun `hasUnread ignores an unread outbound message`() {
        // readMessage on an outbound message means "did THEY read it" — not our unread.
        val root = outbound(id = 1, read = false, sentAt = at(1))
        val reply = inbound(id = 2, parent = 1, read = true, sentAt = at(2))

        val threads = groupIntoThreads(listOf(root, reply), me)

        assertEquals(1, threads.size)
        assertFalse(threads[0].hasUnread)
    }

    @Test
    fun `the root subject wins over the Re prefixed leaf`() {
        val root = inbound(id = 1, subject = "Sock club", sentAt = at(1))
        val reply = outbound(id = 2, parent = 1, subject = "Re: Sock club", sentAt = at(2))
        val third = inbound(id = 3, parent = 2, subject = "Re: Re: Sock club", sentAt = at(3))

        val threads = groupIntoThreads(listOf(third, reply, root), me)

        assertEquals("Sock club", threads[0].subject)
    }

    @Test
    fun `a blank root subject falls back to the oldest non blank subject in the thread`() {
        val root = inbound(id = 1, subject = "", sentAt = at(1))
        val reply = outbound(id = 2, parent = 1, subject = "Re: Sock club", sentAt = at(2))

        val threads = groupIntoThreads(listOf(root, reply), me)

        assertEquals("Re: Sock club", threads[0].subject)
    }

    @Test
    fun `a thread with no usable subject anywhere reports an empty subject`() {
        val threads = groupIntoThreads(listOf(inbound(id = 1, subject = "  ")), me)

        assertEquals("", threads[0].subject)
    }

    @Test
    fun `an unparseable timestamp does not crash and does not become the last activity`() {
        val root = inbound(id = 1, sentAt = at(1))
        val garbled = outbound(id = 2, parent = 1, sentAt = "not a date")

        val threads = groupIntoThreads(listOf(root, garbled), me)

        assertEquals(1, threads.size)
        // The good timestamp still drives activity; the garbled one sorts last rather
        // than to the epoch.
        assertEquals(listOf(1L, 2L), threads[0].messages.map { it.id })
        assertEquals(
            parsedMillis(at(1)),
            threads[0].lastActivityAt,
        )
    }

    @Test
    fun `a thread whose timestamps are all unusable reports unknown activity`() {
        val threads = groupIntoThreads(listOf(inbound(id = 1, sentAt = null)), me)

        assertNull(threads[0].lastActivityAt)
    }

    @Test
    fun `threads sort newest activity first with unknown activity last`() {
        val old = inbound(id = 1, subject = "Old", sentAt = at(1))
        val recent = inbound(id = 2, subject = "Recent", sentAt = at(9))
        val undated = inbound(id = 3, subject = "Undated", sentAt = null)

        val threads = groupIntoThreads(listOf(old, undated, recent), me)

        assertEquals(listOf("Recent", "Old", "Undated"), threads.map { it.subject })
    }

    @Test
    fun `a null sender is handled without crashing and never invents unread`() {
        // A system notice with no sender: direction is unknown, so it must not light the
        // unread dot, and it must not blow up counterpart resolution.
        val orphanNotice = Message(
            id = 1,
            subject = "Notice",
            sender = null,
            recipient = null,
            sentAt = at(1),
            readMessage = false,
        )

        val threads = groupIntoThreads(listOf(orphanNotice), me)

        assertEquals(1, threads.size)
        assertNull(threads[0].counterpart)
        assertFalse(threads[0].hasUnread)
    }

    @Test
    fun `a null sender addressed to us still counts as unread`() {
        val notice = Message(
            id = 1,
            subject = "Notice",
            sender = null,
            recipient = myself,
            sentAt = at(1),
            readMessage = false,
        )

        val threads = groupIntoThreads(listOf(notice), me)

        assertTrue(threads[0].hasUnread)
        assertNull(threads[0].counterpart)
    }

    @Test
    fun `a null recipient on an outbound message leaves the counterpart unknown`() {
        val sentToNobody = Message(id = 1, subject = "Draft", sender = myself, sentAt = at(1))

        val threads = groupIntoThreads(listOf(sentToNobody), me)

        assertNull(threads[0].counterpart)
        assertFalse(threads[0].hasUnread)
    }

    @Test
    fun `the same message arriving from two folders is de duplicated`() {
        // Inbox and sent are separate API calls; the caller concatenates them, so an
        // overlapping page must not double a message into the thread.
        val root = inbound(id = 1, sentAt = at(1))
        val reply = outbound(id = 2, parent = 1, sentAt = at(2))

        val threads = groupIntoThreads(listOf(root, reply, root.copy(), reply), me)

        assertEquals(1, threads.size)
        assertEquals(listOf(1L, 2L), threads[0].messages.map { it.id })
    }

    @Test
    fun `two separate conversations stay separate`() {
        val first = inbound(id = 1, subject = "Yarn swap", sentAt = at(1))
        val second = inbound(
            id = 2,
            subject = "Pattern question",
            sentAt = at(2),
        ).copy(sender = RavelryUser(username = "casteron"))

        val threads = groupIntoThreads(listOf(first, second), me)

        assertEquals(2, threads.size)
        assertEquals("Pattern question", threads[0].subject)
        assertEquals("casteron", threads[0].counterpart?.username)
        assertEquals("purlpete", threads[1].counterpart?.username)
    }

    // --- the scrape-mode subject merge (issue #396) ----------------------------------

    @Test
    fun `subject merge folds parentless messages with one counterpart into one thread`() {
        // Scrape-shaped data: no parent ids anywhere. An exchange under one subject —
        // inbound and the Re: reply outbound — must become a single conversation.
        val original = inbound(1L, subject = "Yarn swap", sentAt = at(1), viaScrape = true)
        val reply = outbound(2L, subject = "Re: Yarn swap", sentAt = at(2), viaScrape = true)
        val later = inbound(3L, subject = "Re: Re: Yarn swap", sentAt = at(3), viaScrape = true)

        val threads = groupIntoThreads(listOf(original, reply, later), me, mergeBySubjectFallback = true)

        val thread = threads.single()
        assertEquals(listOf(1L, 2L, 3L), thread.messages.map { it.id })
        // Root is the oldest message, so the thread's identity is stable.
        assertEquals(1L, thread.rootId)
        assertEquals("Yarn swap", thread.subject)
        assertEquals("purlpete", thread.counterpart?.username)
    }

    @Test
    fun `subject merge keeps different counterparts apart`() {
        val toPete = inbound(1L, subject = "Hello", sentAt = at(1), viaScrape = true)
        val toCaste = inbound(2L, subject = "Hello", sentAt = at(2), viaScrape = true)
            .copy(sender = RavelryUser(username = "casteron"))

        val threads = groupIntoThreads(listOf(toPete, toCaste), me, mergeBySubjectFallback = true)

        assertEquals(2, threads.size)
    }

    @Test
    fun `subject merge never touches buckets that carry real parent ids`() {
        // JSON-shaped data mixed in: the reply chain groups by parent id, and the merge
        // must not stitch an unrelated same-subject conversation onto it.
        val root = inbound(1L, subject = "Yarn swap", sentAt = at(1))
        val reply = outbound(2L, parent = 1L, subject = "Yarn swap", sentAt = at(2))
        val separate = inbound(5L, subject = "Yarn swap", sentAt = at(5), viaScrape = true)

        val threads = groupIntoThreads(listOf(root, reply, separate), me, mergeBySubjectFallback = true)

        assertEquals(2, threads.size)
        val chained = threads.first { it.rootId == 1L }
        assertEquals(listOf(1L, 2L), chained.messages.map { it.id })
    }

    @Test
    fun `subject merge skips blank subjects and unknown counterparts`() {
        val blankA = inbound(1L, subject = "", sentAt = at(1), viaScrape = true)
        val blankB = inbound(2L, subject = "", sentAt = at(2), viaScrape = true)
        val nobodyA = inbound(3L, subject = "Hi", sentAt = at(3), viaScrape = true)
            .copy(sender = null, recipient = null)
        val nobodyB = inbound(4L, subject = "Hi", sentAt = at(4), viaScrape = true)
            .copy(sender = null, recipient = null)

        val threads = groupIntoThreads(listOf(blankA, blankB, nobodyA, nobodyB), me, mergeBySubjectFallback = true)

        assertEquals(4, threads.size)
    }

    @Test
    fun `subject merge skips subjects that are nothing but Re prefixes`() {
        // "Re:" survives the blank-subject guard (it isn't blank) but normalizes to
        // nothing — a subject with no identity of its own must never glue two unrelated
        // conversations with the same person together.
        val a = inbound(1L, subject = "Re:", sentAt = at(1), viaScrape = true)
        val b = inbound(2L, subject = "Re: Re:", sentAt = at(2), viaScrape = true)

        val threads = groupIntoThreads(listOf(a, b), me, mergeBySubjectFallback = true)

        assertEquals(2, threads.size)
    }

    @Test
    fun `subject merge never touches genuinely parentless JSON messages even when the flag is set`() {
        // Regression pin: mergeBySubjectFallback is a SESSION-WIDE flag (any page in the
        // accumulated corpus fell back to scraping — see MessagesViewModel's
        // anyPageViaScrape), so it can be true while most of the accumulated list is still
        // ordinary JSON. Two standalone JSON messages (no parent id, viaScrape = false)
        // sharing a subject and counterpart must stay separate threads — only messages
        // actually built from the scrape are eligible for the subject+counterpart merge.
        val first = inbound(1L, subject = "Yarn swap", sentAt = at(1))
        val second = inbound(2L, subject = "Yarn swap", sentAt = at(2))

        val threads = groupIntoThreads(listOf(first, second), me, mergeBySubjectFallback = true)

        assertEquals(2, threads.size)
    }

    @Test
    fun `subject merge only pulls in the scraped members of an otherwise mixed corpus`() {
        // The realistic shape: a JSON-sourced standalone message plus two genuinely
        // scraped, parentless messages under the same subject/counterpart. Only the
        // scraped pair merges; the JSON message stays on its own.
        val jsonStandalone = inbound(1L, subject = "Yarn swap", sentAt = at(1))
        val scrapedOriginal = inbound(2L, subject = "Yarn swap", sentAt = at(2), viaScrape = true)
        val scrapedReply = outbound(3L, subject = "Re: Yarn swap", sentAt = at(3), viaScrape = true)

        val threads = groupIntoThreads(
            listOf(jsonStandalone, scrapedOriginal, scrapedReply),
            me,
            mergeBySubjectFallback = true,
        )

        assertEquals(2, threads.size)
        val merged = threads.first { it.rootId == 2L }
        assertEquals(listOf(2L, 3L), merged.messages.map { it.id })
        val untouched = threads.first { it.rootId == 1L }
        assertEquals(listOf(1L), untouched.messages.map { it.id })
    }

    @Test
    fun `without the fallback flag parentless messages stay separate threads`() {
        val original = inbound(1L, subject = "Yarn swap", sentAt = at(1))
        val reply = outbound(2L, subject = "Re: Yarn swap", sentAt = at(2))

        val threads = groupIntoThreads(listOf(original, reply), me)

        assertEquals(2, threads.size)
    }

    @Test
    fun `subject merge reports unread from any inbound unread member`() {
        val readOne = inbound(1L, subject = "Swap", sentAt = at(1), viaScrape = true)
        val unreadReply = inbound(2L, subject = "Re: Swap", sentAt = at(2), read = false, viaScrape = true)

        val threads = groupIntoThreads(listOf(readOne, unreadReply), me, mergeBySubjectFallback = true)

        assertTrue(threads.single().hasUnread)
    }

    private fun parsedMillis(wire: String): Long =
        com.myhobbyislearning.fibersocial.feed.parseRavelryTimestamp(wire)!!.toEpochMilliseconds()
}
