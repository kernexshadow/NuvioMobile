package com.nuvio.app.features.usenet

import android.content.Context

internal class SharedPreferencesUnplayableReleaseStorage(context: Context) : UnplayableReleaseStorage {
    private val preferences = context.getSharedPreferences("usenet_unplayable_releases", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(value: String) { preferences.edit().putString(KEY, value).apply() }
    @Volatile override var generation = 0
        private set

    // Holds no credentials and is shared by profiles; only an account reset clears it.
    fun clear() {
        preferences.edit().clear().apply()
        generation += 1
    }

    private companion object { const val KEY = "releases" }
}

