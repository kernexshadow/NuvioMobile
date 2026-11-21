package com.nuvio.app.features.usenet

import android.content.Context

internal class SharedPreferencesUsenetStatsStorage(context: Context) : UsenetStatsStorage {
    private val preferences = context.getSharedPreferences("usenet_source_stats", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(value: String) { preferences.edit().putString(KEY, value).apply() }
    override fun clear() { preferences.edit().remove(KEY).commit() }
    override fun loadPreference(name: String): String? = preferences.getString(name, null)
    override fun savePreference(name: String, value: String) { preferences.edit().putString(name, value).apply() }
    @Volatile override var generation = 0
        private set

    /** An account reset: the counts and the chosen filters go. They hold no credential and are shared by profiles. */
    fun clearAll() {
        preferences.edit().clear().commit()
        generation += 1
    }

    private companion object { const val KEY = "stats" }
}

