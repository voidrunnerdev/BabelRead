package com.example.extensions

import com.example.data.parser.NetworkManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object ExtensionPageFetcher {

    private const val MAX_CACHED_CHAPTERS = 20

    private data class PageRef(val source: CatalogueSource, val page: Page)

    private val refs = ConcurrentHashMap<String, PageRef>()
    private val chapterOrder = ArrayDeque<String>()

    fun register(chapterId: String, source: CatalogueSource, pages: List<Page>): List<String> {
        val keys = ArrayList<String>(pages.size)
        synchronized(chapterOrder) {
            clearChapter(chapterId)
            chapterOrder.remove(chapterId)
            chapterOrder.addLast(chapterId)
            while (chapterOrder.size > MAX_CACHED_CHAPTERS) {
                val oldest = chapterOrder.pollFirst() ?: break
                clearChapter(oldest)
            }
            pages.forEachIndexed { index, page ->
                val key = "extpg:$chapterId:$index"
                refs[key] = PageRef(source, page)
                keys += key
            }
        }
        return keys
    }

    fun clearAll() {
        synchronized(chapterOrder) {
            chapterOrder.clear()
            refs.clear()
        }
    }

    private fun clearChapter(chapterId: String) {
        val prefix = "extpg:$chapterId:"
        refs.keys.removeIf { it.startsWith(prefix) }
    }

    suspend fun fetch(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val ref = refs[key] ?: return@withContext null
        val source = ref.source
        if (source is HttpSource) {
            val response = source.getImage(ref.page)
            return@withContext response.use {
                if (!it.isSuccessful) throw IOException("Eklenti sayfası indirilemedi (HTTP ${it.code})")
                it.body.bytes()
            }
        }
        val url = ref.page.imageUrl ?: ref.page.url.ifBlank { null } ?: return@withContext null
        val host = runCatching { URL(url).host }.getOrNull()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkManager.USER_AGENT)
        if (host != null) {
            requestBuilder.header("Referer", "https://$host/")
        }
        NetworkManager.executeRequest(requestBuilder.build(), source.name).use { it.body.bytes() }
    }
}
