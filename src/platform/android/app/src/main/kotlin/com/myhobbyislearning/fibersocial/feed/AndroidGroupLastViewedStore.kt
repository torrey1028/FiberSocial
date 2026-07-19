package com.myhobbyislearning.fibersocial.feed

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * SharedPreferences-backed [GroupLastViewedStore]. Plain (not encrypted) prefs, same
 * rationale as `AndroidGroupOrderStore`: public group IDs and when the user looked at
 * them — nothing sensitive.
 */
class AndroidGroupLastViewedStore(private val prefs: SharedPreferences) : GroupLastViewedStore {

    constructor(context: Context) :
        this(context.getSharedPreferences("group_last_viewed", Context.MODE_PRIVATE))

    override suspend fun load(): Map<Long, Long>? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString<Map<Long, Long>>(raw) }.getOrNull()
    }

    override suspend fun save(lastViewed: Map<Long, Long>) {
        prefs.edit().putString(KEY, json.encodeToString(lastViewed)).apply()
    }

    private companion object {
        const val KEY = "last_viewed"
    }
}
