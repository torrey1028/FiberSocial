package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.VoteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoteTypeEmojiTest {
    @Test
    fun `every vote type has an emoji`() {
        VoteType.entries.forEach { type ->
            assertTrue(type in VOTE_TYPE_EMOJI, "missing emoji for $type")
        }
    }

    @Test
    fun `emojis are distinct`() {
        assertEquals(VoteType.entries.size, VOTE_TYPE_EMOJI.values.toSet().size)
    }
}
