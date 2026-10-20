package com.example.extensions

import com.example.data.parser.NetworkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

object ExtensionIndex {

    data class SourceRef(
        val id: Long,
        val name: String,
        val language: String,
    )

    data class StoreItem(
        val name: String,
        val pkgName: String,
        val versionName: String,
        val versionCode: Long,
        val sources: List<SourceRef>,
        val apkUrl: String,
        val iconUrl: String?,
        val contentWarning: String,
    ) {
        val languages: List<String>
            get() = sources.map { it.language }.distinct()

        val isNsfw: Boolean
            get() = contentWarning.contains("NSFW", ignoreCase = true)
    }

    fun normalizeRepoUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase()
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }

        // GitHub sayfa linkleri: github.com/<owner>/<repo>[/tree/<dal>[/alt yol]]
        if (host == "github.com" && segments.size >= 2) {
            val owner = segments[0]
            val repo = segments[1]
            val treeIndex = segments.indexOfFirst { it == "tree" || it == "blob" }
            val branch = if (treeIndex >= 0 && segments.size > treeIndex + 1) {
                segments[treeIndex + 1]
            } else "HEAD"
            val subPath = if (treeIndex >= 0 && segments.size > treeIndex + 2) {
                segments.drop(treeIndex + 2)
                    .dropLastWhile { it == "index.min.json" || it == "index.json" }
            } else emptyList()
            val raw = buildString {
                append("https://raw.githubusercontent.com/")
                append(owner).append('/').append(repo).append('/').append(branch)
                if (subPath.isNotEmpty()) append('/').append(subPath.joinToString("/"))
            }
            return raw.toHttpUrlOrNull()?.toString()
        }

        // GitHub Pages proje siteleri: <owner>.github.io/<repo>[/...]
        if (host.endsWith(".github.io")) {
            val owner = host.removeSuffix(".github.io")
            val repo = segments.firstOrNull() ?: return null
            val subPath = segments.drop(1)
                .dropLastWhile { it == "index.min.json" || it == "index.json" }
            val raw = buildString {
                append("https://raw.githubusercontent.com/")
                append(owner).append('/').append(repo).append("/HEAD")
                if (subPath.isNotEmpty()) append('/').append(subPath.joinToString("/"))
            }
            return raw.toHttpUrlOrNull()?.toString()
        }

        var url = parsed.newBuilder().query(null).fragment(null).build().toString()
        url = url.trimEnd('/')
        url = url.removeSuffix("/index.min.json").removeSuffix("/index.json")
        url = url.trimEnd('/')
        return url.toHttpUrlOrNull()?.toString()
    }

    suspend fun fetch(repoUrl: String): List<StoreItem> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (candidate in listOf("$repoUrl/index.min.json", "$repoUrl/index.json")) {
            try {
                return@withContext fetchIndex(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Depo yüklenemedi: $repoUrl")
    }

    private suspend fun fetchIndex(indexUrl: String): List<StoreItem> {
        val request = Request.Builder()
            .url(indexUrl)
            .header("User-Agent", NetworkManager.USER_AGENT)
            .build()
        val bodyText = NetworkManager.executeRequest(request, "Eklenti Deposu").use { response ->
            response.body?.string() ?: throw IllegalStateException("Depo boş yanıt döndü")
        }
        val root = JSONObject(bodyText)
        val array = root.optJSONObject("extensionList")?.optJSONArray("extensions")
            ?: root.optJSONArray("extensions")
            ?: throw IllegalStateException("Beklenmeyen dizin formatı")

        val items = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val resources = item.optJSONObject("resources")
                val apkUrl = resources?.optString("apkUrl").orEmpty()
                if (apkUrl.isBlank()) continue
                val sources = mutableListOf<SourceRef>()
                item.optJSONArray("sources")?.let { array ->
                    for (j in 0 until array.length()) {
                        val source = array.optJSONObject(j) ?: continue
                        val id = source.optLong("id")
                        val language = source.optString("language").orEmpty()
                        if (id <= 0L || language.isBlank()) continue
                        if (sources.any { it.id == id }) continue
                        sources += SourceRef(
                            id = id,
                            name = source.optString("name").ifBlank { item.optString("name") },
                            language = language,
                        )
                    }
                }
                add(
                    StoreItem(
                        name = item.optString("name"),
                        pkgName = item.optString("packageName"),
                        versionName = item.optString("versionName"),
                        versionCode = item.optString("versionCode").toLongOrNull() ?: 0L,
                        sources = sources,
                        apkUrl = apkUrl,
                        iconUrl = resources?.optString("iconUrl")?.ifBlank { null },
                        contentWarning = item.optString("contentWarning"),
                    )
                )
            }
        }
        // Depo dizininde aynı paket birden çok kez gelebilir; en yüksek sürümü tut
        val byPkg = LinkedHashMap<String, StoreItem>()
        for (item in items) {
            val existing = byPkg[item.pkgName]
            if (existing == null || item.versionCode > existing.versionCode) {
                byPkg[item.pkgName] = item
            }
        }
        return byPkg.values.toList()
    }

    suspend fun downloadApk(item: StoreItem): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(item.apkUrl)
            .header("User-Agent", NetworkManager.USER_AGENT)
            .build()
        NetworkManager.executeRequest(request, "Eklenti İndirme").use { response ->
            response.body?.bytes() ?: throw IllegalStateException("Boş APK indirildi")
        }
    }
}
