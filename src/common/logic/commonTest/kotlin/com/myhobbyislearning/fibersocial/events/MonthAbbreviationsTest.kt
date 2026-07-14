package com.myhobbyislearning.fibersocial.events

import kotlin.test.Test
import kotlin.test.assertEquals

class MonthAbbreviationsTest {
    @Test
    fun `twelve months january through december`() {
        assertEquals(12, MONTH_ABBREVIATIONS.size)
        assertEquals("JAN", MONTH_ABBREVIATIONS.first())
        assertEquals("DEC", MONTH_ABBREVIATIONS.last())
    }

    @Test
    fun `indexed by month number minus one`() {
        assertEquals("JUL", MONTH_ABBREVIATIONS[7 - 1])
    }
}
