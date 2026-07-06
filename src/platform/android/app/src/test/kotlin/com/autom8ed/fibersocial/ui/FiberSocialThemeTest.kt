package com.autom8ed.fibersocial.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.autom8ed.fibersocial.settings.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FiberSocialThemeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun backgroundFor(mode: ThemeMode): Color {
        var background: Color? = null
        compose.setContent {
            FiberSocialTheme(mode) {
                background = MaterialTheme.colorScheme.background
            }
        }
        compose.waitForIdle()
        return background!!
    }

    @Test
    fun `DARK override renders the dark color scheme`() {
        assertEquals(darkColorScheme().background, backgroundFor(ThemeMode.DARK))
    }

    @Test
    fun `LIGHT override renders the light color scheme`() {
        assertEquals(lightColorScheme().background, backgroundFor(ThemeMode.LIGHT))
    }

    @Test
    fun `SYSTEM follows a light device setting`() {
        assertEquals(lightColorScheme().background, backgroundFor(ThemeMode.SYSTEM))
    }

    @Test
    @Config(sdk = [33], qualifiers = "night")
    fun `SYSTEM follows a dark device setting`() {
        assertEquals(darkColorScheme().background, backgroundFor(ThemeMode.SYSTEM))
    }

    @Test
    @Config(sdk = [33], qualifiers = "night")
    fun `LIGHT override wins over a dark device setting`() {
        assertEquals(lightColorScheme().background, backgroundFor(ThemeMode.LIGHT))
    }
}
