package com.myhobbyislearning.fibersocial.auth

import com.myhobbyislearning.fibersocial.storage.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyValueTokenStorageTest {
    @Test
    fun `load returns null when nothing saved`() = runTest {
        assertNull(KeyValueTokenStorage(FakeKeyValueStore()).load())
    }

    @Test
    fun `save then load round-trips all fields`() = runTest {
        val storage = KeyValueTokenStorage(FakeKeyValueStore())
        val token = AuthToken("access-abc", "refresh-xyz", 99999L, "session=abc")
        storage.save(token)
        assertEquals(token, storage.load())
    }

    @Test
    fun `save overwrites previous token`() = runTest {
        val storage = KeyValueTokenStorage(FakeKeyValueStore())
        storage.save(AuthToken("old", "old-r", 1L))
        val newer = AuthToken("new", "new-r", 2L)
        storage.save(newer)
        assertEquals(newer, storage.load())
    }

    @Test
    fun `clear removes saved token`() = runTest {
        val storage = KeyValueTokenStorage(FakeKeyValueStore())
        storage.save(AuthToken("access", "refresh", 1000L))
        storage.clear()
        assertNull(storage.load())
    }

    @Test
    fun `clear is idempotent`() = runTest {
        val storage = KeyValueTokenStorage(FakeKeyValueStore())
        storage.save(AuthToken("access", "refresh", 1000L))
        storage.clear()
        storage.clear()
        assertNull(storage.load())
    }

    @Test
    fun `corrupt data degrades to null`() = runTest {
        val store = FakeKeyValueStore()
        store.putString("token", "not json")
        assertNull(KeyValueTokenStorage(store).load())
    }
}
