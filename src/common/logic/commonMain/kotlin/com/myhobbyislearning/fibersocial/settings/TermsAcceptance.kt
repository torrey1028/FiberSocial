package com.myhobbyislearning.fibersocial.settings

import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.serialization.Serializable

/**
 * Version of the hosted `legal/terms-of-use.html` the app currently requires agreement to
 * (issue #408, Apple Guideline 1.2). Bump this whenever the terms change in a way that needs
 * renewed agreement — a stored [TermsAcceptance] with an older [TermsAcceptance.version] no
 * longer satisfies [TermsAcceptance.isCurrent], so the pre-login terms gate reappears for
 * every logged-out user, exactly once, until they agree again.
 */
const val CURRENT_TERMS_VERSION = 1

/**
 * Whether the signed-out user has agreed to the terms gate, and which version they agreed to.
 *
 * @property version The terms version last agreed to; `0` (the default) means never agreed.
 */
@Serializable
data class TermsAcceptance(
    val version: Int = 0,
) {
    /** True once [version] is at least [CURRENT_TERMS_VERSION] — the gate can stay hidden. */
    val isCurrent: Boolean get() = version >= CURRENT_TERMS_VERSION
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
