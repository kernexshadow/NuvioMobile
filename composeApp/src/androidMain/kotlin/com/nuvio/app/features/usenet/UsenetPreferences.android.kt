package com.nuvio.app.features.usenet

import android.content.Context

/** One private SharedPreferences file. */
internal class SharedUsenetPreferences(context: Context, name: String) : UsenetPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun putString(key: String, value: String) { preferences.edit().putString(key, value).apply() }
    override fun remove(key: String) { preferences.edit().remove(key).apply() }
}
