package com.example.extensions

import android.util.Base64
import com.example.data.parser.ChapterModel
import com.example.data.parser.MangaModel
import com.example.data.parser.MangaSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ExtensionMangaSource(
    val meta: ExtensionMeta,
    val ext: CatalogueSource,
    private val displayName: String? = null,
    val isDefaultForExtension: Boolean = true,
) : MangaSource {

    override val name: String get() = displayName ?: ext.name

    override fun getFilterList(): FilterList = ext.getFilterList()

    private fun String.toB64(): String =
        Base64.encodeToString(toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun String.fromB64(): String =
        String(Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    private fun encodeManga(manga: SManga): String = "extmg_" + JSONObject().apply {
        put("u", manga.safeUrl())
        put("t", manga.safeTitle())
        put("c", manga.thumbnail_url.orEmpty())
        put("a", manga.author.orEmpty())
        put("r", manga.artist.orEmpty())
        put("s", manga.status)
        put("d", manga.description.orEmpty())
        put("g", manga.genre.orEmpty())
        put("so", ext.id)
        put("memo", manga.safeMemoString())
    }.toString().toB64()

    private fun decodeManga(mangaId: String): SMangaImpl {
        val json = JSONObject(mangaId.removePrefix("extmg_").fromB64())
        return SMangaImpl().apply {
            url = json.optString("u")
            title = json.optString("t")
            thumbnail_url = json.optString("c").ifBlank { null }
            author = json.optString("a").ifBlank { null }
            artist = json.optString("r").ifBlank { null }
            status = json.optInt("s", 0)
            description = json.optString("d").ifBlank { null }
            genre = json.optString("g").ifBlank { null }
            memo = parseMemo(json.optString("memo"))
            initialized = true
        }
    }

    private fun SManga.safeUrl(): String = runCatching { url }.getOrDefault("")

    private fun SManga.safeTitle(): String = runCatching { title }.getOrDefault("")

    private fun SManga.safeMemoString(): String = runCatching { memo.toString() }.getOrDefault("{}")

    private fun SChapter.safeMemoString(): String = runCatching { memo.toString() }.getOrDefault("{}")

    private fun parseMemo(raw: String): JsonObject {
        if (raw.isBlank() || raw == "null") return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }

    private fun SChapter.safeUrl(): String = runCatching { url }.getOrDefault("")

    private fun SChapter.safeName(): String = runCatching { name }.getOrDefault("")

    private fun encodeChapter(chapter: SChapter): String = "extch_" + JSONObject().apply {
        put("u", chapter.safeUrl())
        put("n", chapter.safeName())
        put("num", chapter.chapter_number.toDouble())
        put("sc", chapter.scanlator.orEmpty())
        put("du", chapter.date_upload)
        put("so", ext.id)
        put("memo", chapter.safeMemoString())
    }.toString().toB64()

    private fun decodeChapter(chapterId: String): SChapterImpl {
        val json = JSONObject(chapterId.removePrefix("extch_").fromB64())
        return SChapterImpl().apply {
            url = json.optString("u")
            name = json.optString("n")
            chapter_number = json.optDouble("num", -1.0).toFloat()
            scanlator = json.optString("sc").ifBlank { null }
            date_upload = json.optLong("du", 0L)
            memo = parseMemo(json.optString("memo"))
        }
    }

    private fun toModel(manga: SManga): MangaModel = MangaModel(
        id = encodeManga(manga),
        title = manga.safeTitle(),
        coverUrl = manga.thumbnail_url.orEmpty(),
        author = manga.author.orEmpty(),
        artist = manga.artist.orEmpty(),
        description = manga.description.orEmpty(),
        status = statusText(manga.status),
        rating = 0.0,
        sourceName = name,
        genres = manga.genre
            ?.split(", ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList(),
        originLanguage = languageLabel(ext.lang),
        availableLanguages = listOf(languageLabel(ext.lang)),
    )

    private fun toChapterModel(chapter: SChapter, mangaId: String): ChapterModel = ChapterModel(
        id = encodeChapter(chapter),
        mangaId = mangaId,
        title = chapter.safeName(),
        chapterNumber = if (chapter.chapter_number < 0) 0.0 else chapter.chapter_number.toDouble(),
        uploadDate = formatDate(chapter.date_upload),
        language = ext.lang,
    )

    private fun statusText(status: Int): String = when (status) {
        SManga.ONGOING -> "Devam Ediyor"
        SManga.COMPLETED -> "Tamamlandı"
        SManga.LICENSED -> "Lisanslı"
        SManga.PUBLISHING_FINISHED -> "Yayını Bitti"
        SManga.CANCELLED -> "İptal Edildi"
        SManga.ON_HIATUS -> "Ara Verildi"
        else -> "Bilinmiyor"
    }

    private fun formatDate(epoch: Long): String {
        if (epoch <= 0) return "Bugün"
        val millis = if (epoch < 100_000_000_000L) epoch * 1000 else epoch
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.format(Date(millis))
        } catch (t: Throwable) {
            "Bugün"
        }
    }

    override suspend fun getMangas(
        page: Int,
        tab: String,
        language: String?,
        genre: String?,
        status: String?,
        filters: FilterList,
    ): List<MangaModel> = withContext(Dispatchers.IO) {
        val listing = if (filters.isNotEmpty()) {
            ext.getSearchManga(page, "", filters)
        } else if (
            tab.contains("Yen", ignoreCase = true) ||
            tab.contains("Son", ignoreCase = true) ||
            tab.contains("Güncell", ignoreCase = true) ||
            tab.contains("Latest", ignoreCase = true)
        ) {
            ext.getLatestUpdates(page)
        } else {
            ext.getPopularManga(page)
        }
        listing.mangas.map { toModel(it) }
    }

    override suspend fun searchMangas(
        query: String,
        page: Int,
        language: String?,
        genre: String?,
        filters: FilterList,
    ): List<MangaModel> = withContext(Dispatchers.IO) {
        ext.getSearchManga(page, query, filters).mangas.map { toModel(it) }
    }

    override suspend fun getMangaDetail(mangaId: String): Pair<MangaModel, List<ChapterModel>> =
        withContext(Dispatchers.IO) {
            val manga = decodeManga(mangaId)
            val update = ext.getMangaUpdate(
                manga = manga,
                chapters = emptyList(),
                fetchDetails = true,
                fetchChapters = true,
            )
            val model = toModel(update.manga).copy(id = mangaId)
            val chapters = update.chapters.map { toChapterModel(it, mangaId) }
            model to chapters
        }

    override suspend fun getChapterPages(chapterId: String): List<String> =
        withContext(Dispatchers.IO) {
            val chapter = decodeChapter(chapterId)
            val pages = ext.getPageList(chapter)
            ExtensionPageFetcher.register(chapterId, ext, pages)
        }
}
