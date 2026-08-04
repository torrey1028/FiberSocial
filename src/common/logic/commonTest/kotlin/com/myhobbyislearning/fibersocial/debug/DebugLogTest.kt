package com.myhobbyislearning.fibersocial.debug

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugLogTest {

    @BeforeTest
    fun setUp() {
        DebugLog.clear()
        DebugFlags.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        DebugLog.clear()
        DebugFlags.resetForTest()
    }

    @Test
    fun `logged lines come back from dump oldest first`() {
        DebugLog.log("first")
        DebugLog.log("second")

        val lines = DebugLog.dump().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("first"), lines[0])
        assertTrue(lines[1].endsWith("second"), lines[1])
    }

    @Test
    fun `every line carries a timestamp prefix`() {
        DebugLog.log("stamped")

        val line = DebugLog.dump()
        // ISO instant, e.g. "2026-07-28T19:15:03.214Z stamped" — the exact format is
        // Instant.toString's; what matters is that a prefix exists and is dated.
        assertTrue(line.matches(Regex("""\d{4}-\d{2}-\d{2}T\S+ stamped""")), line)
    }

    @Test
    fun `the buffer drops the oldest lines beyond the cap`() {
        repeat(450) { DebugLog.log("line $it") }

        val lines = DebugLog.dump().lines()
        assertEquals(400, lines.size)
        assertTrue(lines.first().endsWith("line 50"), "oldest kept: ${lines.first()}")
        assertTrue(lines.last().endsWith("line 449"), "newest kept: ${lines.last()}")
    }

    @Test
    fun `clear empties the buffer`() {
        DebugLog.log("gone")
        DebugLog.clear()

        assertEquals("", DebugLog.dump())
    }

    @Test
    fun `urls are logged whole in a debug build`() {
        DebugFlags.initDebugBuild(true)
        val long = "https://example.com/?q=" + "x".repeat(300)

        assertEquals(long, describeUrlForLog(long))
    }

    @Test
    fun `urls are truncated outside a debug build`() {
        // Fail-closed like everything else in DebugFlags: no initDebugBuild call means
        // release rules. The 120-char cap is the pre-existing release behavior.
        val long = "https://example.com/?q=" + "x".repeat(300)

        assertEquals(long.take(120), describeUrlForLog(long))
    }

    @Test
    fun `an exception renders with its class and cause chain`() {
        val root = IllegalStateException("root cause")
        val wrapper = RuntimeException("wrapper", root)

        assertEquals(
            "RuntimeException: wrapper ← IllegalStateException: root cause",
            describeException(wrapper),
        )
    }

    @Test
    fun `an exception message embedding a response body is stripped at the JSON input marker`() {
        // kotlinx-serialization decode errors append "JSON input: <minified body>"; for
        // the token endpoint that body IS the token JSON, which must never reach a log.
        val e = RuntimeException(
            "Illegal input: Field 'access_token' is required\n" +
                """JSON input: {"access_token":"tok-secret","refresh_token":"ref-secret"}""",
        )

        val rendered = describeException(e)
        assertTrue(rendered.contains("Field 'access_token' is required"), rendered)
        assertFalse(rendered.contains("tok-secret"), rendered)
        assertFalse(rendered.contains("ref-secret"), rendered)
    }

    @Test
    fun `an oversized exception message is capped per link`() {
        val rendered = describeException(RuntimeException("x".repeat(500)))
        assertTrue(rendered.length < 300, "capped: ${rendered.length}")
    }

    @Test
    fun `a deep cause chain is capped rather than rendered in full`() {
        val chain = (1..7).fold<Int, Throwable?>(null) { cause, i ->
            RuntimeException("e$i", cause)
        }!!

        val rendered = describeException(chain)
        // Outermost first (e7), capped at five links.
        assertEquals(5, rendered.split(" ← ").size, rendered)
        assertTrue(rendered.startsWith("RuntimeException: e7"), rendered)
    }
}
