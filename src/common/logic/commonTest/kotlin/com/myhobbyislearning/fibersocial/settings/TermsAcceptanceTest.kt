package com.myhobbyislearning.fibersocial.settings

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
