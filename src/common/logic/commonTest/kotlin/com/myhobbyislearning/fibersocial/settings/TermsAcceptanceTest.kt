package com.myhobbyislearning.fibersocial.settings

import com.myhobbyislearning.fibersocial.auth.AuthState
import com.myhobbyislearning.fibersocial.auth.AuthToken
import com.myhobbyislearning.fibersocial.storage.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TermsAcceptanceTest {
    @Test
    fun `default acceptance is not current`() {
        assertFalse(TermsAcceptance().isCurrent)
    }

    @Test
    fun `acceptance at the current version is current`() {
        assertTrue(TermsAcceptance(version = CURRENT_TERMS_VERSION).isCurrent)
    }

    @Test
    fun `acceptance of a newer version than required is still current`() {
        assertTrue(TermsAcceptance(version = CURRENT_TERMS_VERSION + 1).isCurrent)
    }

    @Test
    fun `acceptance of an older version than required is not current`() {
        assertFalse(TermsAcceptance(version = CURRENT_TERMS_VERSION - 1).isCurrent)
    }
}

class ShouldShowTermsGateTest {
    @Test
    fun `gates a never-agreed unauthenticated user`() {
        assertTrue(shouldShowTermsGate(AuthState.Unauthenticated, TermsAcceptance()))
    }

    @Test
    fun `does not gate an unauthenticated user who already agreed`() {
        assertFalse(
            shouldShowTermsGate(AuthState.Unauthenticated, TermsAcceptance(version = CURRENT_TERMS_VERSION)),
        )
    }

    @Test
    fun `does not gate a never-agreed unauthenticated user while acceptance is still loading`() {
        assertFalse(shouldShowTermsGate(AuthState.Unauthenticated, acceptance = null))
    }

    @Test
    fun `gates an authenticated user whose acceptance was wiped`() {
        // iOS reinstall repro (issue #424): the Keychain token survives an
        // uninstall/reinstall but the NSUserDefaults acceptance is wiped, so the app
        // relaunches Authenticated with the never-agreed default. The session-expiry
        // path then opens the login WebView directly, so the gate must trigger on the
        // Authenticated state itself.
        val authenticated = AuthState.Authenticated(AuthToken("access", "refresh", Long.MAX_VALUE))
        assertTrue(shouldShowTermsGate(authenticated, TermsAcceptance()))
    }

    @Test
    fun `gates an authenticated user whose accepted version is stale but nonzero`() {
        // A CURRENT_TERMS_VERSION bump must reach logged-in users (issue #424), not
        // only users who happen to log out. The required version is injected because
        // with the real constant still at 1, "stale" (constant - 1 = 0) is
        // indistinguishable from the never-agreed default — this pins the actual
        // agreed-once-but-outdated row of the matrix.
        val authenticated = AuthState.Authenticated(AuthToken("access", "refresh", Long.MAX_VALUE))
        assertTrue(
            shouldShowTermsGate(authenticated, TermsAcceptance(version = 1), currentVersion = 2),
        )
    }

    @Test
    fun `a version-bump gates while the previously accepted version stays satisfied for itself`() {
        // Pins the comparison direction of isCurrentFor: 1 satisfies 1, not 2.
        assertTrue(TermsAcceptance(version = 1).isCurrentFor(1))
        assertFalse(TermsAcceptance(version = 1).isCurrentFor(2))
        assertTrue(TermsAcceptance(version = 3).isCurrentFor(2))
    }

    @Test
    fun `does not gate an authenticated user whose acceptance is current`() {
        val authenticated = AuthState.Authenticated(AuthToken("access", "refresh", Long.MAX_VALUE))
        assertFalse(shouldShowTermsGate(authenticated, TermsAcceptance(version = CURRENT_TERMS_VERSION)))
    }

    @Test
    fun `does not gate an authenticated user while acceptance is still loading`() {
        // null means the store hasn't loaded yet; gating on it would flash the gate
        // at every launch for users whose stored acceptance is in fact current.
        val authenticated = AuthState.Authenticated(AuthToken("access", "refresh", Long.MAX_VALUE))
        assertFalse(shouldShowTermsGate(authenticated, acceptance = null))
    }

    @Test
    fun `gates both sides of the session-expiry logout transition with a stale nonzero acceptance`() {
        // The session-expiry collector opens the login WebView and logs out
        // (Authenticated -> Unauthenticated). With a stale acceptance the gate must
        // hold on BOTH sides of that transition — using an injected required version
        // so this covers the agreed-once cohort, not just the wiped default the
        // other tests already pin.
        val authenticated = AuthState.Authenticated(AuthToken("access", "refresh", Long.MAX_VALUE))
        assertTrue(shouldShowTermsGate(authenticated, TermsAcceptance(version = 1), currentVersion = 2))
        assertTrue(
            shouldShowTermsGate(AuthState.Unauthenticated, TermsAcceptance(version = 1), currentVersion = 2),
        )
    }

    @Test
    fun `does not gate a never-agreed user whose login attempt errored`() {
        // A user authenticated before this feature shipped has a never-agreed
        // TermsAcceptance. If a later re-login attempt (e.g. after session expiry)
        // fails, AuthState.Error must still surface the failure message instead of
        // being replaced by an unrelated terms re-prompt. Deliberately unchanged by
        // issue #424.
        assertFalse(shouldShowTermsGate(AuthState.Error("session expired"), TermsAcceptance()))
    }

    @Test
    fun `does not gate an errored login even with a stale accepted version`() {
        assertFalse(
            shouldShowTermsGate(
                AuthState.Error("invalid_client"),
                TermsAcceptance(version = CURRENT_TERMS_VERSION - 1),
            ),
        )
    }

    @Test
    fun `does not gate a loading auth state`() {
        assertFalse(shouldShowTermsGate(AuthState.Loading, TermsAcceptance()))
    }
}

class KeyValueTermsAcceptanceStoreTest {
    @Test
    fun `load defaults to never agreed when nothing saved`() = runTest {
        val acceptance = KeyValueTermsAcceptanceStore(FakeKeyValueStore()).load()
        assertEquals(0, acceptance.version)
        assertFalse(acceptance.isCurrent)
    }

    @Test
    fun `save then load round-trips the accepted version`() = runTest {
        val store = KeyValueTermsAcceptanceStore(FakeKeyValueStore())
        store.save(TermsAcceptance(version = CURRENT_TERMS_VERSION))
        assertEquals(CURRENT_TERMS_VERSION, store.load().version)
        assertTrue(store.load().isCurrent)
    }

    @Test
    fun `corrupt data degrades to never agreed`() = runTest {
        val fake = FakeKeyValueStore()
        fake.putString("acceptance", "not json")
        assertFalse(KeyValueTermsAcceptanceStore(fake).load().isCurrent)
    }

    @Test
    fun `an acceptance saved under an older terms version round-trips as not current`() = runTest {
        val store = KeyValueTermsAcceptanceStore(FakeKeyValueStore())
        store.save(TermsAcceptance(version = CURRENT_TERMS_VERSION - 1))
        assertFalse(store.load().isCurrent)
    }
}
