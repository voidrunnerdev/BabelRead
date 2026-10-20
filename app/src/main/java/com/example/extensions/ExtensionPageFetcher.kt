package com.example.extensions

import com.example.data.parser.NetworkManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object ExtensionPageFetcher {

    private data class PageRef(val source: CatalogueSource, val page: Page)

    private val refs = ConcurrentHashMap<String, PageRef>()

    fun register(chapterId: String, source: CatalogueSource, pages: List<Page>): List<String> {
        clearChapter(chapterId)
        val keys = ArrayList<String>(pages.size)
        pages.forEachIndexed { index, page ->
            val key = "extpg:$chapterId:$index"
            refs[key] = PageRef(source, page)
            keys += key
        }
        return keys
    }

    private fun clearChapter(chapterId: String) {
        val prefix = "extpg:$chapterId:"
        refs.keys.removeIf { it.startsWith(prefix) }
    }

    suspend fun fetch(key: String): ByteArray? {
        val ref = refs[key] ?: return null
        val source = ref.source
        if (source is HttpSource) {
            val response = source.getImage(ref.page)
            response.use { return it.body.bytes() }
        }
        val url = ref.page.imageUrl ?: ref.page.url.ifBlank { null } ?: return null
        val host = runCatching { URL(url).host }.getOrNull()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkManager.USER_AGENT)
        if (host != null) {
            requestBuilder.header("Referer", "https://$host/")
        }
        NetworkManager.executeRequest(requestBuilder.build(), source.name).use { return it.body.bytes() }
    }
}
