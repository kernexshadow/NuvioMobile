package com.nuvio.app.features.usenet

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

internal actual fun createUsenetHttpClient(socketTimeoutMillis: Long, requestTimeoutMillis: Long): HttpClient =
    HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            this.socketTimeoutMillis = socketTimeoutMillis
            this.requestTimeoutMillis = requestTimeoutMillis
        }
    }
