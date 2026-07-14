package com.myhobbyislearning.fibersocial.feed

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * SharedPreferences-backed [GroupOrderStore]. Plain (not encrypted) prefs: the order is
 * a list of public group IDs — nothing sensitive.
 */
class AndroidGroupOrderStore(private val prefs: SharedPreferences) : GroupOrderStore {

    constructor(context: Context) :
        this(context.getSharedPreferences("group_order", Context.MODE_PRIVATE))

    override suspend fun load(): List<Long>? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
    }

    override suspend fun save(order: List<Long>) {
        prefs.edit().putString(KEY, json.encodeToString(order)).apply()
    }

    private companion object {
        const val KEY = "order"
    }
}
