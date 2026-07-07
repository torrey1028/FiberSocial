package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.models.Group
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupOrderTest {

    private fun group(id: Long) = Group(id = id, name = "G$id", permalink = "g$id", forumId = id * 10)

    @Test
    fun `no stored order keeps the fetched order`() {
        val groups = listOf(group(1), group(2), group(3))
        assertEquals(groups, reconcileGroupOrder(groups, storedOrder = null))
    }

    @Test
    fun `groups follow the stored order`() {
        val groups = listOf(group(1), group(2), group(3))
        assertEquals(
            listOf(2L, 3L, 1L),
            reconcileGroupOrder(groups, storedOrder = listOf(2L, 3L, 1L)).map { it.id },
        )
    }

    @Test
    fun `newly joined groups append at the bottom in fetched order`() {
        val groups = listOf(group(4), group(1), group(5))
        assertEquals(
            listOf(1L, 4L, 5L),
            reconcileGroupOrder(groups, storedOrder = listOf(1L)).map { it.id },
        )
    }

    @Test
    fun `stored ids for departed groups drop out`() {
        val groups = listOf(group(1), group(2))
        assertEquals(
            listOf(2L, 1L),
            reconcileGroupOrder(groups, storedOrder = listOf(99L, 2L, 1L)).map { it.id },
        )
    }

    @Test
    fun `join and leave apply together while kept groups hold position`() {
        // Stored: [3, 99, 1]; group 99 was left, group 2 was joined.
        val groups = listOf(group(1), group(2), group(3))
        assertEquals(
            listOf(3L, 1L, 2L),
            reconcileGroupOrder(groups, storedOrder = listOf(3L, 99L, 1L)).map { it.id },
        )
    }

    @Test
    fun `empty group list reconciles to empty`() {
        assertEquals(emptyList(), reconcileGroupOrder(emptyList(), storedOrder = listOf(1L, 2L)))
    }

    @Test
    fun `a duplicate id in stored order doesn't duplicate the group`() {
        // Defensive: corrupted prefs or a future reorder-persistence bug could write a
        // duplicate id. It must not make the group appear twice.
        val groups = listOf(group(1), group(2))
        assertEquals(
            listOf(1L, 2L),
            reconcileGroupOrder(groups, storedOrder = listOf(1L, 1L, 2L)).map { it.id },
        )
    }
}
