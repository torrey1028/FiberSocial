package com.autom8ed.fibersocial.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationSettingsTest {
    @Test
    fun `effective poll interval clamps off-menu values to the default`() {
        assertEquals(6, NotificationSettings(pollIntervalHours = 0).effectivePollIntervalHours)
        assertEquals(6, NotificationSettings(pollIntervalHours = 7).effectivePollIntervalHours)
    }

    @Test
    fun `effective poll interval passes supported choices through`() {
        for (choice in NotificationSettings.POLL_INTERVAL_CHOICES) {
            assertEquals(choice, NotificationSettings(pollIntervalHours = choice).effectivePollIntervalHours)
        }
    }
}
