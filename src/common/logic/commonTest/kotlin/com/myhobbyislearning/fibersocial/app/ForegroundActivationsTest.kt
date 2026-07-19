package com.myhobbyislearning.fibersocial.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForegroundActivationsTest {

    @Test
    fun `ticks is readable before any activation so a cold-start collector still fires once`() {
        // The whole reason this is a StateFlow rather than an event stream: a collector
        // that only subscribes on first composition gets the current value immediately,
        // so FeedScreen needs no separate run-once effect and cannot miss the cold-start
        // activation by subscribing a frame after the platform emitted it (issue #350).
        val initial = ForegroundActivations.ticks.value
        assertTrue(initial >= 0L)
    }

    @Test
    fun `each foreground activation changes the tick so a keyed effect restarts`() {
        val before = ForegroundActivations.ticks.value

        ForegroundActivations.notifyForegrounded()
        val afterOne = ForegroundActivations.ticks.value
        ForegroundActivations.notifyForegrounded()
        val afterTwo = ForegroundActivations.ticks.value

        // Strictly increasing, never resetting: consecutive activations must each look
        // different to a LaunchedEffect keyed on this, or the second resume would be
        // silently dropped as "same key".
        assertEquals(before + 1, afterOne)
        assertEquals(before + 2, afterTwo)
    }
}
