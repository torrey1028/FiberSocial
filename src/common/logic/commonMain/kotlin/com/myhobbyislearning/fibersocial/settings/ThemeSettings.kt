package com.myhobbyislearning.fibersocial.settings

import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.serialization.Serializable

/**
 * Which color theme the app renders in (issue #153). [SYSTEM] follows the device's
 * light/dark setting; [LIGHT]/[DARK] override it app-wide.
 */
@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Human label for a theme mode choice. */
fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/**
 * User-configurable appearance settings.
 *
 * @property mode Theme override; defaults to following the system setting.
 */
@Serializable
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
)

/** Persistence for [ThemeSettings]; implemented per platform. */
interface ThemeSettingsStore {
    /** Returns the stored settings, or defaults when none were saved. */
    suspend fun load(): ThemeSettings

    suspend fun save(settings: ThemeSettings)
}

/** [ThemeSettingsStore] backed by a [KeyValueStore]. */
class KeyValueThemeSettingsStore(store: KeyValueStore) : ThemeSettingsStore {
    private val entry = JsonKeyValueEntry(store, KEY, ThemeSettings.serializer())

    override suspend fun load(): ThemeSettings = entry.load() ?: ThemeSettings()

    override suspend fun save(settings: ThemeSettings) = entry.save(settings)

    private companion object {
        const val KEY = "settings"
    }
}
