package com.myhobbyislearning.fibersocial.feed

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AndroidGroupLastViewedStoreTest {

    private fun store(name: String): AndroidGroupLastViewedStore {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(name, Context.MODE_PRIVATE)
        return AndroidGroupLastViewedStore(prefs)
    }

    @Test
    fun `load returns null before anything is saved`() = runTest {
        // Distinct from "saved an empty map": null is what tells resolveGroupDots that
        // every group is unseen and should seed silently.
        assertNull(store("glv_empty").load())
    }

    @Test
    fun `round-trips a last-viewed map`() = runTest {
        val s = store("glv_roundtrip")
        val lastViewed = mapOf(1L to 1_700_000_000_000L, 2L to 1_700_000_005_000L)
        s.save(lastViewed)
        assertEquals(lastViewed, s.load())
    }

    @Test
    fun `a later save replaces the previous map`() = runTest {
        val s = store("glv_replace")
        s.save(mapOf(1L to 100L, 99L to 100L))
        // Pruning a departed group must actually shrink what's stored, not merge into it.
        s.save(mapOf(1L to 200L))
        assertEquals(mapOf(1L to 200L), s.load())
    }

    @Test
    fun `corrupt stored json decodes to null rather than throwing`() = runTest {
        // Same decode-fallback contract as AndroidGroupOrderStore: a garbled prefs value
        // degrades to "nothing saved" (every group re-seeds) instead of crashing launch.
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("glv_corrupt", Context.MODE_PRIVATE)
        prefs.edit().putString("last_viewed", "{not json").commit()
        assertNull(AndroidGroupLastViewedStore(prefs).load())
    }

    @Test
    fun `json of the wrong shape decodes to null`() = runTest {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("glv_wrong_shape", Context.MODE_PRIVATE)
        // Well-formed JSON, but a list where a map is expected (e.g. a stale format).
        prefs.edit().putString("last_viewed", "[1,2,3]").commit()
        assertNull(AndroidGroupLastViewedStore(prefs).load())
    }
}
