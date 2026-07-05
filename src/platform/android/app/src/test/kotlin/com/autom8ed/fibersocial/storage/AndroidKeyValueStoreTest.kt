package com.autom8ed.fibersocial.storage

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AndroidKeyValueStoreTest {

    @Test
    fun `plain store round-trips a value`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = plainKeyValueStore(context, "test_plain_kv")
        store.putString("k", "v")
        assertEquals("v", store.getString("k"))
    }

    @Test
    fun `remove clears a value`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = plainKeyValueStore(context, "test_plain_kv_remove")
        store.putString("k", "v")
        store.remove("k")
        assertNull(store.getString("k"))
    }

    @Test
    fun `two prefs names do not collide`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val a = plainKeyValueStore(context, "test_kv_a")
        val b = plainKeyValueStore(context, "test_kv_b")
        a.putString("k", "a-value")
        b.putString("k", "b-value")
        assertEquals("a-value", a.getString("k"))
        assertEquals("b-value", b.getString("k"))
    }

    @Test
    fun `SharedPreferencesKeyValueStore wraps an arbitrary prefs instance`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("test_wrapped_prefs", Context.MODE_PRIVATE)
        val store = SharedPreferencesKeyValueStore(prefs)
        store.putString("k", "v")
        assertEquals("v", prefs.getString("k", null))
    }
}
