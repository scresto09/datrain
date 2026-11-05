package com.scresto.datrain.data

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

/** A single client: two OkHttp caches cannot share a folder. */
object Http {
    @Volatile private var instance: OkHttpClient? = null

    fun client(context: Context): OkHttpClient = instance ?: synchronized(this) {
        instance ?: OkHttpClient.Builder()
            // Honours the API headers and revalidates with the ETag when the copy has expired.
            .cache(Cache(File(context.cacheDir, "http"), 30L * 1024 * 1024))
            .build()
            .also { instance = it }
    }
}
