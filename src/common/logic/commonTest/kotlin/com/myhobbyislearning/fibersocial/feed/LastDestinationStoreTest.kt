package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.storage.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class LastDestinationStoreTest {

    @Test
    fun `round-trips each destination variant`() = runTest {
        val store = KeyValueLastDestinationStore(FakeKeyValueStore())

        store.save(LastDestination.Group(42L))
        assertEquals(LastDestination.Group(42L), store.load())

        store.save(LastDestination.MyPosts)
        assertEquals(LastDestination.MyPosts, store.load())

        store.save(LastDestination.Messages)
        assertEquals(LastDestination.Messages, store.load())
    }

    @Test
    fun `loads null when nothing was ever saved`() = runTest {
        assertNull(KeyValueLastDestinationStore(FakeKeyValueStore()).load())
    }

    @Test
    fun `loads null instead of throwing on corrupt stored data`() = runTest {
        // JsonKeyValueEntry's decode-fallback is what gives the "fall back to the first
        // group" launch behaviour for free — corrupt bytes must read as "nothing saved".
        val backing = FakeKeyValueStore()
        backing.putString("last_destination", "{not json at all")
        assertNull(KeyValueLastDestinationStore(backing).load())
    }

    @Test
    fun `loads null on an unknown destination type from a future version`() = runTest {
        val backing = FakeKeyValueStore()
        backing.putString("last_destination", """{"type":"video_calls"}""")
        assertNull(KeyValueLastDestinationStore(backing).load())
    }
}
