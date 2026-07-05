package com.autom8ed.fibersocial.storage

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonKeyValueEntryTest {
    @Serializable
    private data class Widget(val name: String, val count: Int = 0)

    @Test
    fun `load returns null when nothing saved`() = runTest {
        val entry = JsonKeyValueEntry(FakeKeyValueStore(), "widget", Widget.serializer())
        assertNull(entry.load())
    }

    @Test
    fun `save then load round-trips`() = runTest {
        val entry = JsonKeyValueEntry(FakeKeyValueStore(), "widget", Widget.serializer())
        entry.save(Widget("gizmo", 3))
        assertEquals(Widget("gizmo", 3), entry.load())
    }

    @Test
    fun `save overwrites the previous value`() = runTest {
        val entry = JsonKeyValueEntry(FakeKeyValueStore(), "widget", Widget.serializer())
        entry.save(Widget("old"))
        entry.save(Widget("new"))
        assertEquals(Widget("new"), entry.load())
    }

    @Test
    fun `corrupt data degrades to null instead of throwing`() = runTest {
        val store = FakeKeyValueStore()
        store.putString("widget", "not json")
        val entry = JsonKeyValueEntry(store, "widget", Widget.serializer())
        assertNull(entry.load())
    }

    @Test
    fun `clear removes the stored value`() = runTest {
        val entry = JsonKeyValueEntry(FakeKeyValueStore(), "widget", Widget.serializer())
        entry.save(Widget("gizmo"))
        entry.clear()
        assertNull(entry.load())
    }

    @Test
    fun `two entries in the same store under different keys do not collide`() = runTest {
        val store = FakeKeyValueStore()
        val a = JsonKeyValueEntry(store, "a", Widget.serializer())
        val b = JsonKeyValueEntry(store, "b", Widget.serializer())
        a.save(Widget("a-widget"))
        b.save(Widget("b-widget"))
        assertEquals(Widget("a-widget"), a.load())
        assertEquals(Widget("b-widget"), b.load())
    }
}
