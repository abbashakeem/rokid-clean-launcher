package com.abbas.hudlauncher

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Tiny authenticated GET helper shared by both repositories. */
object HudApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    fun get(path: String): String {
        val req = Request.Builder()
            .url(Config.baseUrl + path)
            .header("X-API-KEY", Config.apiKey)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $path")
            return resp.body?.string() ?: throw IOException("empty body for $path")
        }
    }
}
