package com.autom8ed.fibersocial.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidTokenStorageTest {

    private lateinit var storage: AndroidTokenStorage

    @Before
    fun setUp() {
        // RuntimeEnvironment.getApplication() comes from Robolectric directly,
        // avoiding the need for the separate androidx.test:core artifact.
        // Inject plain SharedPreferences so tests avoid EncryptedSharedPreferences/KeyStore
        // on JVM (no hardware-backed keystore available).
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        storage = AndroidTokenStorage(prefs)
    }

    @Test
    fun `load returns null when nothing saved`() = runTest {
        assertNull(storage.load())
    }

    @Test
    fun `save then load round-trips all fields`() = runTest {
        val token = AuthToken("access-abc", "refresh-xyz", 99999L)
        storage.save(token)
        assertEquals(token, storage.load())
    }

    @Test
    fun `save overwrites previous token`() = runTest {
        storage.save(AuthToken("old", "old-r", 1L))
        val newer = AuthToken("new", "new-r", 2L)
        storage.save(newer)
        assertEquals(newer, storage.load())
    }

    @Test
    fun `clear removes saved token`() = runTest {
        storage.save(AuthToken("access", "refresh", 1000L))
        storage.clear()
        assertNull(storage.load())
    }

    @Test
    fun `load returns null after clear`() = runTest {
        storage.save(AuthToken("access", "refresh", 1000L))
        storage.clear()
        storage.clear() // idempotent
        assertNull(storage.load())
    }
}
