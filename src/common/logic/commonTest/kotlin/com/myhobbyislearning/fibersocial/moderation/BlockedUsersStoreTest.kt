package com.myhobbyislearning.fibersocial.moderation

import com.myhobbyislearning.fibersocial.storage.FakeKeyValueStore
import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyValueBlockedUsersStoreTest {
    @Test
    fun `blockedUsernames is empty before load`() {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        assertEquals(emptySet(), store.blockedUsernames.value)
    }

    @Test
    fun `load returns empty when nothing saved`() = runTest {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        store.load()
        assertEquals(emptySet(), store.blockedUsernames.value)
    }

    @Test
    fun `block updates blockedUsernames immediately`() = runTest {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        store.block("someone")
        assertEquals(setOf("someone"), store.blockedUsernames.value)
    }

    @Test
    fun `block then load round-trips through persistence`() = runTest {
        val backing = FakeKeyValueStore()
        KeyValueBlockedUsersStore(backing).block("someone")

        val reloaded = KeyValueBlockedUsersStore(backing)
        reloaded.load()

        assertEquals(setOf("someone"), reloaded.blockedUsernames.value)
    }

    @Test
    fun `unblock removes a username and updates immediately`() = runTest {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        store.block("someone")
        store.block("someone-else")

        store.unblock("someone")

        assertEquals(setOf("someone-else"), store.blockedUsernames.value)
    }

    @Test
    fun `unblocking a username that was never blocked is a no-op`() = runTest {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        store.unblock("nobody")
        assertEquals(emptySet(), store.blockedUsernames.value)
    }

    @Test
    fun `corrupt data degrades to empty on load`() = runTest {
        val fake = FakeKeyValueStore()
        fake.putString("blocked_usernames", "not json")
        val store = KeyValueBlockedUsersStore(fake)
        store.load()
        assertEquals(emptySet(), store.blockedUsernames.value)
    }

    @Test
    fun `mutate serializes concurrent block calls across separate store instances`() = runTest {
        // Two screens (e.g. a post's overflow menu and the profile screen) each construct
        // their own KeyValueBlockedUsersStore over the same backing store. A
        // YieldingKeyValueStore forces a real suspension point between the read and the
        // write inside block()/unblock(), so without the companion-object-wide mutex both
        // coroutines would read the pre-mutation set and the second save would silently
        // clobber the first (mirrors KeyValueMutedTopicsStore's own concurrency test).
        val backing = YieldingKeyValueStore(FakeKeyValueStore())
        val storeA = KeyValueBlockedUsersStore(backing)
        val storeB = KeyValueBlockedUsersStore(backing)

        coroutineScope {
            launch { storeA.block("alice") }
            launch { storeB.block("bob") }
        }

        val reloaded = KeyValueBlockedUsersStore(backing)
        reloaded.load()
        assertEquals(setOf("alice", "bob"), reloaded.blockedUsernames.value)
    }
}

class BlockedUsersStoreIsBlockedTest {
    @Test
    fun `isBlocked is true for an exact username match`() = runTest {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        store.block("someone")
        assertTrue(store.isBlocked("someone"))
    }

    @Test
    fun `isBlocked compares case-insensitively`() = runTest {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        store.block("Someone")
        assertTrue(store.isBlocked("someone"))
        assertTrue(store.isBlocked("SOMEONE"))
    }

    @Test
    fun `isBlocked is false for a null username`() {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        assertFalse(store.isBlocked(null))
    }

    @Test
    fun `isBlocked is false for someone not on the list`() = runTest {
        val store = KeyValueBlockedUsersStore(FakeKeyValueStore())
        store.block("someone")
        assertFalse(store.isBlocked("someone-else"))
    }
}

/** Wraps a [KeyValueStore], yielding before every call to force real interleaving in tests. */
private class YieldingKeyValueStore(private val delegate: KeyValueStore) : KeyValueStore {
    override suspend fun getString(key: String): String? {
        yield()
        return delegate.getString(key)
    }

    override suspend fun putString(key: String, value: String) {
        yield()
        delegate.putString(key, value)
    }

    override suspend fun remove(key: String) {
        yield()
        delegate.remove(key)
    }
}
