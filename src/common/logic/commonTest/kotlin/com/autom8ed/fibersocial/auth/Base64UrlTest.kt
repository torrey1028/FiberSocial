package com.autom8ed.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class Base64UrlTest {
    @Test
    fun `empty input encodes to empty string`() {
        assertEquals("", base64UrlEncode(ByteArray(0)))
    }

    @Test
    fun `encodes without padding for input not a multiple of three`() {
        // "f" -> "Zg", "fo" -> "Zm8", "foo" -> "Zm9v" (standard base64 vectors, minus '=' padding)
        assertEquals("Zg", base64UrlEncode("f".encodeToByteArray()))
        assertEquals("Zm8", base64UrlEncode("fo".encodeToByteArray()))
        assertEquals("Zm9v", base64UrlEncode("foo".encodeToByteArray()))
    }

    @Test
    fun `uses url-safe alphabet instead of plus and slash`() {
        // Bytes chosen so standard base64 would emit '+' and '/'.
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        assertEquals("-_-_", base64UrlEncode(bytes))
    }
}
