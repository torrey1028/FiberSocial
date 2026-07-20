package com.myhobbyislearning.fibersocial.debug

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #395. The session cookie is a live credential, so these tests are less about the
 * happy path than about proving the thing CANNOT leak: default off, release off, and off
 * again when a debug flag is carried into a release binary.
 */
class DebugFlagsTest {

    @BeforeTest
    fun setUp() = DebugFlags.resetForTest()

    @AfterTest
    fun tearDown() = DebugFlags.resetForTest()

    @Test
    fun `cookie logging is off before anything initialises the build type`() {
        // The fail-closed case: a new entry point that forgets to call initDebugBuild
        // must not log, rather than logging because nothing said not to.
        assertFalse(DebugFlags.sessionCookieLoggingEnabled)
        assertFalse(DebugFlags.debugToolsAvailable)
    }

    @Test
    fun `a debug build still starts with cookie logging off`() {
        DebugFlags.initDebugBuild(true)

        assertTrue(DebugFlags.debugToolsAvailable)
        assertFalse(DebugFlags.sessionCookieLoggingEnabled)
    }

    @Test
    fun `a debug build can opt in and back out`() {
        DebugFlags.initDebugBuild(true)

        DebugFlags.setSessionCookieLogging(true)
        assertTrue(DebugFlags.sessionCookieLoggingEnabled)

        DebugFlags.setSessionCookieLogging(false)
        assertFalse(DebugFlags.sessionCookieLoggingEnabled)
    }

    @Test
    fun `a release build refuses to enable cookie logging`() {
        DebugFlags.initDebugBuild(false)

        DebugFlags.setSessionCookieLogging(true)

        assertFalse(DebugFlags.sessionCookieLoggingEnabled)
        assertFalse(DebugFlags.debugToolsAvailable)
    }

    @Test
    fun `learning it is a release build clears an already-enabled flag`() {
        // Guards the ordering hazard: something enables the flag before initDebugBuild
        // runs, and the build then turns out to be a release. The flag must not survive.
        DebugFlags.initDebugBuild(true)
        DebugFlags.setSessionCookieLogging(true)

        DebugFlags.initDebugBuild(false)

        assertFalse(DebugFlags.sessionCookieLoggingEnabled)
    }

    @Test
    fun `describeSessionCookie hides the value by default`() {
        val cookie = "ravelrys_pocketses=super-secret-session-blob; rsigned=also-secret"

        val described = describeSessionCookie(cookie)

        assertFalse(described.contains("super-secret-session-blob"))
        assertFalse(described.contains("also-secret"))
        assertTrue(described.contains("present=true"))
        assertTrue(described.contains("${cookie.length} chars"))
    }

    @Test
    fun `describeSessionCookie hides the value in a release build even when asked`() {
        DebugFlags.initDebugBuild(false)
        DebugFlags.setSessionCookieLogging(true)

        val described = describeSessionCookie("ravelrys_pocketses=super-secret-session-blob")

        assertFalse(described.contains("super-secret-session-blob"))
    }

    @Test
    fun `describeSessionCookie reveals the value only in a debug build that opted in`() {
        DebugFlags.initDebugBuild(true)
        DebugFlags.setSessionCookieLogging(true)
        val cookie = "ravelrys_pocketses=super-secret-session-blob"

        assertEquals(cookie, describeSessionCookie(cookie))
    }

    @Test
    fun `an absent cookie is reported as absent rather than as a mystery`() {
        // The diagnostic these log lines actually exist for: did the WebView hand us a
        // session at all? That answer must survive the redaction.
        val described = describeSessionCookie("")

        assertTrue(described.contains("present=false"))
        assertTrue(described.contains("0 chars"))
    }
}
