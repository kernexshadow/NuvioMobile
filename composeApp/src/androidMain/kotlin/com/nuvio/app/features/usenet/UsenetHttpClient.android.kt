package com.nuvio.app.features.usenet

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

internal actual fun createUsenetHttpClient(socketTimeoutMillis: Long, requestTimeoutMillis: Long): HttpClient =
    HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            this.socketTimeoutMillis = socketTimeoutMillis
            this.requestTimeoutMillis = requestTimeoutMillis
        }
    }

/** Holds no credentials and is shared by profiles; only an account reset clears it. */
internal class SharedPreferencesNewznabStateStorage(context: Context) : NewznabStateStorage {
    private val preferences = context.getSharedPreferences("usenet_indexer_state", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(value: String) { preferences.edit().putString(KEY, value).apply() }
    @Volatile override var generation = 0
        private set

    fun clear() {
        preferences.edit().clear().apply()
        generation += 1
    }

    private companion object { const val KEY = "indexers" }
}
