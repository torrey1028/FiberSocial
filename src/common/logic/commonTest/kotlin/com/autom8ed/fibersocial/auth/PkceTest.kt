package com.autom8ed.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun `RFC 7636 appendix B code challenge test vector`() {
        // https://www.rfc-editor.org/rfc/rfc7636#appendix-B
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = base64UrlEncode(sha256(verifier.encodeToByteArray()))
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun `sha256 of empty input matches the known digest`() {
        val digest = sha256(ByteArray(0))
        val hex = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex)
    }

    @Test
    fun `secureRandomBytes returns the requested length`() {
        assertEquals(16, secureRandomBytes(16).size)
        assertEquals(32, secureRandomBytes(32).size)
        assertEquals(0, secureRandomBytes(0).size)
    }

    @Test
    fun `secureRandomBytes is not deterministic`() {
        // Not a rigorous randomness test, just a smoke check that two draws differ.
        val a = secureRandomBytes(32)
        val b = secureRandomBytes(32)
        assertTrue(!a.contentEquals(b))
    }
}
