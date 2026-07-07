package com.autom8ed.fibersocial.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class IntervalLabelTest {
    @Test
    fun `singular hour reads as every hour`() {
        assertEquals("Every hour", intervalLabel(1))
    }

    @Test
    fun `plural hours read as every N hours`() {
        assertEquals("Every 3 hours", intervalLabel(3))
        assertEquals("Every 24 hours", intervalLabel(24))
    }
}
