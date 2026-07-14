package com.myhobbyislearning.fibersocial.projects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Zoom/pan math for the full-screen photo viewer (issue #192). */
class ComputeZoomTransformTest {

    private val size = IntSize(1000, 800)

    @Test
    fun `pinching out raises the scale`() {
        val (scale, _) = computeZoomTransform(1f, Offset.Zero, zoom = 2f, pan = Offset.Zero, size = size)
        assertEquals(2f, scale)
    }

    @Test
    fun `scale is clamped to a 4x maximum`() {
        val (scale, _) = computeZoomTransform(3f, Offset.Zero, zoom = 5f, pan = Offset.Zero, size = size)
        assertEquals(4f, scale)
    }

    @Test
    fun `zooming back below 1x recenters and pins to 1x`() {
        val (scale, offset) = computeZoomTransform(1.2f, Offset(120f, 60f), zoom = 0.5f, pan = Offset(10f, 10f), size = size)
        assertEquals(1f, scale)
        assertEquals(Offset.Zero, offset)
    }

    @Test
    fun `pan moves the image while zoomed`() {
        val (_, offset) = computeZoomTransform(2f, Offset.Zero, zoom = 1f, pan = Offset(30f, 20f), size = size)
        assertEquals(Offset(30f, 20f), offset)
    }

    @Test
    fun `pan is clamped so the image cannot be dragged past its edges`() {
        // At 2x, max pan is size * (2-1) / 2 = (500, 400). A huge pan clamps to that.
        val (_, offset) = computeZoomTransform(2f, Offset.Zero, zoom = 1f, pan = Offset(9999f, -9999f), size = size)
        assertEquals(500f, offset.x)
        assertEquals(-400f, offset.y)
    }

    @Test
    fun `zooming out re-clamps an existing pan into the smaller bounds`() {
        // At 4x, (1500, 1200) is a valid max pan (size * (4-1) / 2). Zooming down to 2x must
        // pull the existing offset back into 2x's tighter (500, 400) bounds — clamping only
        // the incoming pan, or against the old scale, would leave the image stuck off-center.
        val (scale, offset) = computeZoomTransform(4f, Offset(1500f, 1200f), zoom = 0.5f, pan = Offset.Zero, size = size)
        assertEquals(2f, scale)
        assertEquals(500f, offset.x)
        assertEquals(400f, offset.y)
    }

    @Test
    fun `a fit-to-screen image with no zoom stays put`() {
        val (scale, offset) = computeZoomTransform(1f, Offset.Zero, zoom = 1f, pan = Offset(50f, 50f), size = size)
        assertEquals(1f, scale)
        // No zoom means it never leaves 1x, so pan is discarded and it stays centered.
        assertTrue(offset == Offset.Zero)
    }
}
