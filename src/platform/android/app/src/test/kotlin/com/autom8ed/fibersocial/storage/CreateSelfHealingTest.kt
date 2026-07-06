package com.autom8ed.fibersocial.storage

import java.security.GeneralSecurityException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CreateSelfHealingTest {

    @Test
    fun `recovers from a checked exception, not just a runtime one`() {
        // The catch is deliberately broad (Exception, not RuntimeException): Tink and
        // EncryptedSharedPreferences surface keyset corruption as checked exceptions
        // (GeneralSecurityException / IOException). Pin that breadth so a later narrowing
        // to RuntimeException — which would stop covering the real failure — fails here.
        var attempts = 0
        var wipes = 0
        val result = createSelfHealing(
            create = {
                attempts++
                if (attempts == 1) throw GeneralSecurityException("keyset failed to decrypt")
                "recovered"
            },
            wipeCorrupted = { wipes++ },
        )
        assertEquals("recovered", result)
        assertEquals(2, attempts)
        assertEquals(1, wipes)
    }

    @Test
    fun `returns the created value without wiping when creation succeeds`() {
        var wipes = 0
        val result = createSelfHealing(create = { "store" }, wipeCorrupted = { wipes++ })
        assertEquals("store", result)
        assertEquals(0, wipes)
    }

    @Test
    fun `wipes and retries once when the first creation fails`() {
        var attempts = 0
        var wipes = 0
        val result = createSelfHealing(
            create = {
                attempts++
                if (attempts == 1) throw RuntimeException("keyset corrupted")
                "recovered"
            },
            wipeCorrupted = { wipes++ },
        )
        assertEquals("recovered", result)
        assertEquals(2, attempts)
        assertEquals(1, wipes)
    }

    @Test
    fun `a failure after the wipe propagates instead of looping`() {
        var attempts = 0
        var wipes = 0
        assertFailsWith<RuntimeException> {
            createSelfHealing<String>(
                create = {
                    attempts++
                    throw RuntimeException("genuinely broken")
                },
                wipeCorrupted = { wipes++ },
            )
        }
        assertEquals(2, attempts)
        assertEquals(1, wipes)
    }
}
