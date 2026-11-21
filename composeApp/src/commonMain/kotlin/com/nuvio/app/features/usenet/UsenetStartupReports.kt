package com.nuvio.app.features.usenet

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One file of private key-value preferences on the device. */
internal interface UsenetPreferences {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * The last Usenet startup's timings, kept on this device for the diagnostics page: elapsed marks
 * and the engine's own report, in NuvioTV's format. No link, release name or provider detail.
 */
internal object UsenetStartupReports {
    /** The preferences file the platform hands in. */
    const val PREFERENCES = "usenet_startup_diagnostics"
    private const val KEY = "lastReport"
    private val lock = SynchronizedObject()
    private var preferences: UsenetPreferences? = null
    private val state = MutableStateFlow("")
    val latest: StateFlow<String> = state.asStateFlow()

    fun initialize(preferences: UsenetPreferences) {
        synchronized(lock) {
            if (this.preferences != null) return
            this.preferences = preferences
            state.value = preferences.getString(KEY).orEmpty()
        }
    }

    fun save(report: String) = synchronized(lock) {
        state.value = report
        preferences?.putString(KEY, report)
    }

    fun clear() = synchronized(lock) {
        state.value = ""
        preferences?.remove(KEY)
    }
}
