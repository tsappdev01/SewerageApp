package com.meterreading.reader.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

/** An [ApiClient] over a given OkHttp client (tests use it to script the network or cut it off). */
fun ApiClient(baseUrl: String, http: OkHttpClient): ApiClient =
    ApiClient(baseUrl, HttpClient(OkHttp) { engine { preconfigured = http } })
