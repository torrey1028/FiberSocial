package com.myhobbyislearning.fibersocial.settings

import com.myhobbyislearning.fibersocial.auth.AuthState
import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.serialization.Serializable

/**
 * Version of the hosted `legal/terms-of-use.html` the app currently requires agreement to
 * (issue #408, Apple Guideline 1.2). Bump this whenever the terms change in a way that needs
 * renewed agreement — a stored [TermsAcceptance] with an older [TermsAcceptance.version] no
 * longer satisfies [TermsAcceptance.isCurrent], so the terms gate reappears exactly once for
 * every user, logged-in (over the feed, issue #424) or logged-out (before login), until they
 * agree again.
 */
const val CURRENT_TERMS_VERSION = 1

/**
 * Whether the user has agreed to the terms gate, and which version they agreed to.
 * Independent of login state — both the pre-login gate and the over-the-feed gate
 * (issue #424) read the same record.
 *
 * @property version The terms version last agreed to; `0` (the default) means never agreed.
 */
@Serializable
data class TermsAcceptance(
    val version: Int = 0,
) {
    /** True once [version] is at least [CURRENT_TERMS_VERSION] — the gate can stay hidden. */
    val isCurrent: Boolean get() = isCurrentFor(CURRENT_TERMS_VERSION)

    /**
     * [isCurrent] against an explicit required version. Exists so the staleness
     * comparison is testable with a genuinely old-but-nonzero version: with
     * [CURRENT_TERMS_VERSION] still at 1, `version - 1` is `0` — indistinguishable from
     * the never-agreed default — so tests against the constant can't tell "stale"
     * apart from "wiped".
     */
    fun isCurrentFor(currentVersion: Int): Boolean = version >= currentVersion
}

/** Persistence for [TermsAcceptance]; implemented per platform. */
interface TermsAcceptanceStore {
    /** Returns the stored acceptance, or the never-agreed default when none was saved. */
    suspend fun load(): TermsAcceptance

    suspend fun save(acceptance: TermsAcceptance)
}

/**
 * [TermsAcceptanceStore] backed by a [KeyValueStore]. Always the *plain* store (theme/
 * notification settings' sibling), never the encrypted token store — acceptance isn't a
 * secret, and it must survive independently of login state so a logged-out user who already
 * agreed isn't asked again.
 */
class KeyValueTermsAcceptanceStore(store: KeyValueStore) : TermsAcceptanceStore {
    private val entry = JsonKeyValueEntry(store, KEY, TermsAcceptance.serializer())

    override suspend fun load(): TermsAcceptance = entry.load() ?: TermsAcceptance()

    override suspend fun save(acceptance: TermsAcceptance) = entry.save(acceptance)

    private companion object {
        const val KEY = "acceptance"
    }
}

/**
 * Whether the terms gate should be shown ahead of the normal [AuthState] screens, given
 * the current auth state and stored [acceptance].
 *
 * [AuthState.Unauthenticated] gates the fresh pre-login flow. [AuthState.Authenticated]
 * is gated too (issue #424), for two reasons:
 * - On iOS the Keychain token survives an uninstall/reinstall while the NSUserDefaults
 *   acceptance is wiped, so the app relaunches Authenticated with a never-agreed
 *   acceptance — without this branch that user reached the feed (and, via the
 *   session-expiry path, the login WebView) without ever seeing the gate.
 * - Bumping [CURRENT_TERMS_VERSION] must reach logged-in users, not only those who
 *   happen to log out: a stale accepted version re-shows the gate over the feed until
 *   they agree to the new terms.
 *
 * [AuthState.Error] stays deliberately excluded: a user who was authenticated before
 * this feature shipped has a never-agreed (default) [TermsAcceptance], so gating on
 * [AuthState.Error] would replace their login-failure message with an unrelated "agree
 * to terms" prompt on every failed re-login attempt, hiding the retry information
 * [AuthState.Error] exists to surface. [AuthState.Loading] is likewise never gated —
 * the gate waits for a settled state.
 *
 * A `null` [acceptance] (store still loading) never gates, so an already-authenticated
 * user with a current acceptance isn't flashed the gate during the load gap. The
 * platform hosts hold ALL content back during that gap too (a brief blank frame) —
 * otherwise the feed, its network load, and the iOS notification-permission prompt
 * would render for the first frames of a launch that then turns out to need the gate,
 * showing content before agreement (the exact thing Guideline 1.2 forbids).
 *
 * @param currentVersion Defaulted to [CURRENT_TERMS_VERSION]; injectable so staleness
 *   is testable with a nonzero old version (see [TermsAcceptance.isCurrentFor]).
 */
fun shouldShowTermsGate(
    authState: AuthState,
    acceptance: TermsAcceptance?,
    currentVersion: Int = CURRENT_TERMS_VERSION,
): Boolean =
    (authState is AuthState.Unauthenticated || authState is AuthState.Authenticated) &&
        acceptance?.isCurrentFor(currentVersion) == false
