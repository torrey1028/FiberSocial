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
    fun `does not gate an authenticated user regardless of acceptance`() {
        val authenticated = AuthState.Authenticated(AuthToken("access", "refresh", Long.MAX_VALUE))
        assertFalse(shouldShowTermsGate(authenticated, TermsAcceptance()))
    }

    @Test
    fun `does not gate a never-agreed user whose login attempt errored`() {
        // A user authenticated before this feature shipped has a never-agreed
        // TermsAcceptance. If a later re-login attempt (e.g. after session expiry)
        // fails, AuthState.Error must still surface the failure message instead of
        // being replaced by an unrelated terms re-prompt.
        assertFalse(shouldShowTermsGate(AuthState.Error("session expired"), TermsAcceptance()))
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
