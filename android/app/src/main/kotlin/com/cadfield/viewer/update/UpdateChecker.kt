package com.cadfield.viewer.update

import com.cadfield.viewer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable private data class Asset(val name: String, val browser_download_url: String, val size: Long = 0)
@Serializable private data class Release(val tag_name: String, val name: String? = null, val assets: List<Asset> = emptyList())

data class AvailableUpdate(val build: Int, val title: String, val apkUrl: String, val sizeBytes: Long)

/** Busca en GitHub Releases un APK de prueba más nuevo que el instalado (tags apk-build-N). */
object UpdateChecker {
    private const val RELEASES = "https://api.github.com/repos/JuanDeLeon24/App_Planos/releases?per_page=10"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (c.responseCode != 200) return@runCatching null
            val releases = json.decodeFromString<List<Release>>(c.inputStream.use { it.readBytes().decodeToString() })
            releases.mapNotNull { r ->
                val n = r.tag_name.removePrefix("apk-build-").toIntOrNull() ?: return@mapNotNull null
                val apk = r.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@mapNotNull null
                AvailableUpdate(n, r.name ?: r.tag_name, apk.browser_download_url, apk.size)
            }.maxByOrNull { it.build }?.takeIf { it.build > BuildConfig.VERSION_CODE }
        }.getOrNull()
    }
}
