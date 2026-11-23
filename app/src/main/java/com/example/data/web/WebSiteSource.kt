package com.example.data.web

import android.content.Context
import android.util.Base64
import com.example.data.net.PageRefererRegistry
import com.example.data.parser.ChapterModel
import com.example.data.parser.MangaModel
import com.example.data.parser.MangaSource
import eu.kanade.tachiyomi.source.model.FilterList
import java.io.IOException

class WebSiteSource(
    private val site: WebSite,
    private val appContext: Context
) : MangaSource {
    override val name: String = "Web: ${site.name}"

    override suspend fun getMangas(
        page: Int,
        tab: String,
        language: String?,
        genre: String?,
        status: String?,
        filters: FilterList
    ): List<MangaModel> = emptyList()

    override suspend fun searchMangas(
        query: String,
        page: Int,
        language: String?,
        genre: String?,
        filters: FilterList
    ): List<MangaModel> = emptyList()

    override suspend fun getMangaDetail(mangaId: String): Pair<MangaModel, List<ChapterModel>> =
        throw IOException("Web kaynaklarındaki seriler yerel kayıttan açılır.")

    override suspend fun getChapterPages(chapterId: String): List<String> {
        val encoded = chapterId.removePrefix("webch_")
        if (encoded.isBlank()) throw IOException("Geçersiz bölüm kimliği.")
        val url = try {
            String(
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            throw IOException("Bölüm bağlantısı çözümlenemedi.")
        }
        val pages = WebPageExtractor.fetchPages(appContext, url, site.origin)
        if (pages.isEmpty()) {
            throw IOException("Bu sayfadan görsel bulunamadı. Siteyi tarayıcıda açıp bölüm sayfasını kontrol edin.")
        }
        PageRefererRegistry.registerForUrls(pages, site.origin)
        return pages
    }
}
