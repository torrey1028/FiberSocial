package com.autom8ed.fibersocial.settings

import com.autom8ed.fibersocial.storage.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class KeyValueThemeSettingsStoreTest {
    @Test
    fun `load defaults to following the system when nothing saved`() = runTest {
        assertEquals(ThemeMode.SYSTEM, KeyValueThemeSettingsStore(FakeKeyValueStore()).load().mode)
    }

    @Test
    fun `save then load round-trips each mode`() = runTest {
        val store = KeyValueThemeSettingsStore(FakeKeyValueStore())
        ThemeMode.entries.forEach { mode ->
            store.save(ThemeSettings(mode = mode))
            assertEquals(mode, store.load().mode)
        }
    }

    @Test
    fun `corrupt data degrades to the default`() = runTest {
        val fake = FakeKeyValueStore()
        fake.putString("settings", "not json")
        assertEquals(ThemeMode.SYSTEM, KeyValueThemeSettingsStore(fake).load().mode)
    }
}

class ThemeModeLabelTest {
    @Test
    fun `every mode has a distinct human label`() {
        assertEquals("Follow system", themeModeLabel(ThemeMode.SYSTEM))
        assertEquals("Light", themeModeLabel(ThemeMode.LIGHT))
        assertEquals("Dark", themeModeLabel(ThemeMode.DARK))
        assertEquals(
            ThemeMode.entries.size,
            ThemeMode.entries.map { themeModeLabel(it) }.distinct().size,
        )
    }
}
