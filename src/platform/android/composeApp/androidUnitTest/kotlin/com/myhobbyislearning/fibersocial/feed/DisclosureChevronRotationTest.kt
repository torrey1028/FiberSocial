package com.myhobbyislearning.fibersocial.feed

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [disclosureChevronRotation] drives the pinned-topics header's and the drawer's "Your
 * Groups" header's chevrons via a raw screen-space rotation, which doesn't automatically
 * account for the icon's own RTL auto-mirroring — so the sign has to flip for RTL to keep
 * "expanded" pointing down in both directions.
 * No Robolectric needed: this is a pure function over an enum, not a composable.
 */
class DisclosureChevronRotationTest {

    @Test
    fun `folded points right in LTR and left in RTL, both unrotated`() {
        assertEquals(0f, disclosureChevronRotation(collapsed = true, layoutDirection = LayoutDirection.Ltr))
        assertEquals(0f, disclosureChevronRotation(collapsed = true, layoutDirection = LayoutDirection.Rtl))
    }

    @Test
    fun `expanded rotates clockwise in LTR to point down`() {
        assertEquals(90f, disclosureChevronRotation(collapsed = false, layoutDirection = LayoutDirection.Ltr))
    }

    @Test
    fun `expanded rotates counterclockwise in RTL to still point down`() {
        // The icon is already horizontally mirrored by AutoMirrored in RTL (pointing
        // left), so a further +90 clockwise would land it pointing up, not down — the
        // sign must flip to -90 to land on down from a mirrored starting position.
        assertEquals(-90f, disclosureChevronRotation(collapsed = false, layoutDirection = LayoutDirection.Rtl))
    }
}
