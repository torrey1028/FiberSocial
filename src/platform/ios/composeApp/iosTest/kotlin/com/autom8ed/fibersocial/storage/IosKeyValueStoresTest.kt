package com.autom8ed.fibersocial.storage

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class NsUserDefaultsKeyValueStoreTest {

    private val store = NsUserDefaultsKeyValueStore("test_store")
    private val other = NsUserDefaultsKeyValueStore("other_store")

    @AfterTest
    fun cleanUp() = runTest {
        for (s in listOf(store, other)) {
            s.remove("k")
            s.remove("k2")
        }
    }

    @Test
    fun missingKeyIsNull() = runTest {
        assertNull(store.getString("k"))
    }

    @Test
    fun putThenGetRoundTrips() = runTest {
        store.putString("k", """{"json":"blob"}""")
        assertEquals("""{"json":"blob"}""", store.getString("k"))
    }

    @Test
    fun putOverwrites() = runTest {
        store.putString("k", "first")
        store.putString("k", "second")
        assertEquals("second", store.getString("k"))
    }

    @Test
    fun removeDeletesAndIsIdempotent() = runTest {
        store.putString("k", "v")
        store.remove("k")
        assertNull(store.getString("k"))
        store.remove("k")
    }

    @Test
    fun storesAreNamespaced() = runTest {
        store.putString("k", "mine")
        assertNull(other.getString("k"))
        other.putString("k", "theirs")
        assertEquals("mine", store.getString("k"))
        assertEquals("theirs", other.getString("k"))
    }
}

/**
 * These also run as hosted XCTests (src/platform/ios/FiberSocialTests) where a real
 * keychain daemon exists. The bare simctl-spawned Kotlin/Native test process has none —
 * every write fails with errSecNotAvailable (-25291) — so here the write-path tests
 * skip themselves when the keychain isn't reachable rather than fail on environment.
 */
class KeychainKeyValueStoreTest {

    private val store = KeychainKeyValueStore("test_keychain")
    private val other = KeychainKeyValueStore("other_keychain")

    /** True when this process can write the keychain; logs a loud SKIP when it can't. */
    private suspend fun keychainAvailable(): Boolean {
        store.putString("availability_probe", "x")
        val available = store.getString("availability_probe") != null
        store.remove("availability_probe")
        if (!available) {
            println("FiberSocial: SKIPPED Keychain test — no keychain in this test process (errSecNotAvailable)")
        }
        return available
    }

    @AfterTest
    fun cleanUp() = runTest {
        for (s in listOf(store, other)) {
            s.remove("k")
            s.remove("k2")
        }
    }

    @Test
    fun missingKeyIsNull() = runTest {
        assertNull(store.getString("k"))
    }

    @Test
    fun putThenGetRoundTrips() = runTest {
        if (!keychainAvailable()) return@runTest
        store.putString("k", """{"accessToken":"abc","sessionCookie":"_ravelry_session=x"}""")
        assertEquals(
            """{"accessToken":"abc","sessionCookie":"_ravelry_session=x"}""",
            store.getString("k"),
        )
    }

    @Test
    fun putOverwritesExistingItem() = runTest {
        if (!keychainAvailable()) return@runTest
        // Exercises the SecItemAdd -> errSecDuplicateItem -> SecItemUpdate path.
        store.putString("k", "first")
        store.putString("k", "second")
        assertEquals("second", store.getString("k"))
    }

    @Test
    fun removeDeletesAndIsIdempotent() = runTest {
        if (!keychainAvailable()) return@runTest
        store.putString("k", "v")
        store.remove("k")
        assertNull(store.getString("k"))
        store.remove("k")
    }

    @Test
    fun servicesAreNamespaced() = runTest {
        if (!keychainAvailable()) return@runTest
        store.putString("k", "mine")
        assertNull(other.getString("k"))
        other.putString("k", "theirs")
        assertEquals("mine", store.getString("k"))
        assertEquals("theirs", other.getString("k"))
    }

    @Test
    fun unicodeSurvivesTheUtf8Bridge() = runTest {
        if (!keychainAvailable()) return@runTest
        store.putString("k2", "füzzy yårn 🧶")
        assertEquals("füzzy yårn 🧶", store.getString("k2"))
    }
}
