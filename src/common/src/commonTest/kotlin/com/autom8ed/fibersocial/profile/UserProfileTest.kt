package com.autom8ed.fibersocial.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class UserProfileTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes with defaults for every absent field`() {
        val p = json.decodeFromString<UserProfile>("""{"username":"yarnie"}""")
        assertEquals("yarnie", p.username)
        assertEquals(0L, p.id)
        assertNull(p.firstName)
        assertNull(p.location)
        assertNull(p.aboutHtml)
        assertNull(p.avatarUrl)
    }

    @Test
    fun `built directly runs default expressions`() {
        val p = UserProfile(username = "yarnie")
        assertNull(p.firstName)
        assertNull(p.avatarUrl)
    }

    @Test
    fun `avatar prefers large, then photo, then small`() {
        assertEquals("l", UserProfile(largePhotoUrl = "l", photoUrl = "p", smallPhotoUrl = "s").avatarUrl)
        assertEquals("p", UserProfile(photoUrl = "p", smallPhotoUrl = "s").avatarUrl)
        assertEquals("s", UserProfile(smallPhotoUrl = "s").avatarUrl)
        assertNull(UserProfile(username = "x").avatarUrl)
    }
}
