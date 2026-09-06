package com.example.mddorma

import android.app.Application
import android.content.Context
import java.io.File

class MddormaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureWebViewCacheDirectories(this)
    }

    companion object {
        fun ensureWebViewCacheDirectories(context: Context) {
            try {
                // Ensure cache directories exist so Chromium's simple_file_enumerator
                // and simple_index_file do not throw ENOENT / "No such file or directory"
                val cache = context.cacheDir
                if (cache != null) {
                    val paths = listOf(
                        "WebView",
                        "WebView/Default",
                        "WebView/Default/HTTP Cache",
                        "WebView/Default/HTTP Cache/Code Cache",
                        "WebView/Default/HTTP Cache/Code Cache/js",
                        "WebView/Default/HTTP Cache/Code Cache/wasm",
                        "WebView/Default/GPUCache",
                        "WebView/Crash Reports"
                    )
                    for (path in paths) {
                        val dir = File(cache, path)
                        if (!dir.exists()) {
                            dir.mkdirs()
                        }
                    }
                }

                // Also ensure under dataDir/cache
                val dataDir = context.applicationInfo.dataDir
                if (dataDir != null) {
                    val appDataCache = File(dataDir, "cache")
                    if (!appDataCache.exists()) appDataCache.mkdirs()
                    val codeCache = File(appDataCache, "WebView/Default/HTTP Cache/Code Cache")
                    File(codeCache, "js").mkdirs()
                    File(codeCache, "wasm").mkdirs()
                }
            } catch (_: Throwable) {
                // Ignore any disk creation errors
            }
        }
    }
}
