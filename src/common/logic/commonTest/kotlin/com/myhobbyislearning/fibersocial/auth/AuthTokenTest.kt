package com.myhobbyislearning.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class AuthTokenTest {

    @Test
    fun `decodes from JSON that omits sessionCookie`() {
        val token = Json.decodeFromString<AuthToken>(
            """{"accessToken":"a","refreshToken":"r","expiresAt":1000}""",
        )
        assertEquals(null, token.sessionCookie)
    }

    @Test
    fun `decoding JSON missing a required field fails loudly`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<AuthToken>("""{"refreshToken":"r"}""")
        }
    }

    @Test
    fun `equal when all fields match`() {
        assertEquals(
            AuthToken("access", "refresh", 1000L),
            AuthToken("access", "refresh", 1000L),
        )
    }

    @Test
    fun `not equal when accessToken differs`() {
        assertNotEquals(
            AuthToken("a1", "refresh", 1000L),
            AuthToken("a2", "refresh", 1000L),
        )
    }

    @Test
    fun `not equal when refreshToken differs`() {
        assertNotEquals(
            AuthToken("access", "r1", 1000L),
            AuthToken("access", "r2", 1000L),
        )
    }

    @Test
    fun `not equal when expiresAt differs`() {
        assertNotEquals(
            AuthToken("access", "refresh", 1000L),
            AuthToken("access", "refresh", 2000L),
        )
    }

    @Test
    fun `copy overrides only specified field`() {
        val original = AuthToken("access", "refresh", 1000L)
        val copy = original.copy(accessToken = "new")
        assertEquals("new", copy.accessToken)
        assertEquals(original.refreshToken, copy.refreshToken)
        assertEquals(original.expiresAt, copy.expiresAt)
    }

    @Test
    fun `sessionCookie defaults to null`() {
        val token = AuthToken("access", "refresh", 1000L)
        assertEquals(null, token.sessionCookie)
    }

    @Test
    fun `sessionCookie is stored and retrievable`() {
        val token = AuthToken("access", "refresh", 1000L, sessionCookie = "cookie=value")
        assertEquals("cookie=value", token.sessionCookie)
    }

    @Test
    fun `tokens with different sessionCookie are not equal`() {
        assertNotEquals(
            AuthToken("access", "refresh", 1000L, sessionCookie = "a=1"),
            AuthToken("access", "refresh", 1000L, sessionCookie = "b=2"),
        )
    }

    @Test
    fun `copy with sessionCookie overrides only that field`() {
        val original = AuthToken("access", "refresh", 1000L)
        val withCookie = original.copy(sessionCookie = "sess=abc")
        assertEquals("sess=abc", withCookie.sessionCookie)
        assertEquals(original.accessToken, withCookie.accessToken)
        assertEquals(original.refreshToken, withCookie.refreshToken)
    }
}
