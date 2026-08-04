package com.myhobbyislearning.fibersocial.debug

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/**
 * In-memory capture of the diagnostic log lines the app already prints (issue: iOS login
 * failures on a physical device).
 *
 * On Android, `println` diagnostics land in logcat, reachable over `adb` from any dev
 * machine. On iOS they go to stdout, which is captured only when the app is launched from
 * Xcode — an OTA-installed build on a physical iPhone (docs/ios-debug-builds.md) has no
 * Mac in the loop, so those lines are unrecoverable exactly where login bugs are being
 * chased. [log] tees every line to `println` (keeping logcat/Xcode behavior identical)
 * and into a capped in-memory buffer that a debug build can export from the login screen
 * (long-press the branding block → share sheet).
 *
 * The buffer is process-lifetime and never persisted; export is only offered in debug
 * builds (the same [DebugFlags.debugToolsAvailable] gate as the debug panel). Log lines
 * must still respect the existing redaction rules — cookie values only ever pass through
 * [describeSessionCookie] — so the buffer is no more sensitive than logcat already is.
 */
@OptIn(ExperimentalAtomicApi::class)
object DebugLog {

    /** Bounds memory: ~400 lines comfortably covers several full login attempts. */
    private const val MAX_ENTRIES = 400

    // Copy-on-write list swapped by CAS: log() must be callable from any thread
    // (WebView callbacks on the main thread, token exchange on a coroutine dispatcher)
    // without a platform lock.
    private val entries = AtomicReference<List<String>>(emptyList())

    /** Prints "FiberSocial: [message]" (exactly as call sites used to) and buffers it. */
    fun log(message: String) {
        println("FiberSocial: $message")
        val stamped = "${Clock.System.now()} $message"
        while (true) {
            val current = entries.load()
            val appended = current + stamped
            val next = if (appended.size > MAX_ENTRIES) appended.drop(appended.size - MAX_ENTRIES) else appended
            if (entries.compareAndSet(current, next)) return
        }
    }

    // Keeps the Android share Intent (EXTRA_TEXT crosses Binder, whose transaction
    // budget is ~1MB shared with everything else in flight) comfortably clear of
    // TransactionTooLargeException even with 400 debug-length URL lines buffered.
    private const val MAX_DUMP_CHARS = 120_000

    /**
     * The buffered lines, oldest first, one per line — the text handed to the share
     * sheet. Capped at [MAX_DUMP_CHARS] keeping the newest text (the first line may be
     * cut mid-way when the cap bites).
     */
    fun dump(): String = entries.load().joinToString("\n").takeLast(MAX_DUMP_CHARS)

    /**
     * Empties the buffer. Called on logout (a later user of the same process must not
     * export the previous account's login trace) and when cookie-value logging is
     * switched off (see [DebugFlags.setSessionCookieLogging] — lines already buffered
     * while it was on contain the live credential).
     */
    fun clear() {
        entries.store(emptyList())
    }
}

/**
 * A URL rendered for logging: complete in a debug build, truncated otherwise.
 *
 * The release truncation to 120 chars is what hid the tail of Ravelry's OAuth
 * `error_description` (the generic prefix "The error is unrecognizable" fit; the specific
 * suffix naming the actual cause didn't). Debug builds log the whole URL — the only
 * sensitive query value a login-flow URL can carry is the one-time authorization code,
 * which is consumed within seconds of being logged and useless without the app's PKCE
 * verifier and client secret.
 */
fun describeUrlForLog(url: String): String =
    if (DebugFlags.debugToolsAvailable) url else url.take(120)

/**
 * One-line rendering of an exception and its cause chain, e.g.
 * `DarwinHttpRequestException: ... ← IOException: ...`. Exists because surfacing only
 * `e.message` (what the login screen shows) discards the exception class and causes —
 * the parts that distinguish a network drop from a serialization failure from a server
 * rejection.
 */
fun describeException(t: Throwable): String =
    generateSequence<Throwable>(t) { it.cause.takeIf { cause -> cause !== it } }
        .take(5)
        .joinToString(" ← ") { "${it::class.simpleName}: ${sanitizedExceptionMessage(it)}" }

/**
 * An exception message safe to buffer and print: kotlinx-serialization decode errors
 * append `JSON input: <minified document>` — a window of the raw response body. For the
 * token endpoint that body IS the token JSON (access + refresh token), so it must never
 * reach a log line; the class name and the message's diagnostic prefix carry everything
 * needed to tell a network drop from a serialization failure from a server rejection.
 * The cap bounds any other library's body-embedding habit the same way.
 */
private fun sanitizedExceptionMessage(t: Throwable): String =
    (t.message ?: "").substringBefore("JSON input:").trim().take(200)
