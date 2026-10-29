package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.db.*
import com.example.data.net.CloudflareChallengeException
import com.example.data.parser.*
import com.example.data.pref.MangaPreferences
import com.example.data.pref.ReadingHistoryEntry
import com.example.data.web.CaptureResult
import com.example.data.web.CaptureSeries
import com.example.data.web.WebCaptureParser
import com.example.data.web.WebPageExtractor
import com.example.data.web.WebSite
import com.example.data.web.WebSiteSource
import com.example.data.web.WebSiteStore
import com.example.extensions.ExtensionMangaSource
import com.example.extensions.languageCode
import com.example.i18n.TranslationLanguages
import com.example.service.BubbleBox
import com.example.service.BubbleDetector
import com.example.service.BulkLocalImportResult
import com.example.service.LocalMangaImporter
import com.example.service.TextBlockObj
import com.example.service.TranslationRequest
import com.example.service.TranslationService
import com.example.work.NewChapterWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val WEB_PAGE_CACHE_TTL_MS = 6L * 60 * 60 * 1000

class MangaViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = MangaDatabase.getDatabase(context)
    private val mangaDao = database.mangaDao()
    private val chapterDao = database.chapterDao()
    private val correctionDao = database.translationCorrectionDao()
    private val queueDao = database.downloadQueueDao()
    private val translationCacheDao = database.translationCacheDao()

    val preferences = MangaPreferences(context)
    val translationService = TranslationService(correctionDao, context)
    val bubbleDetector = BubbleDetector(context)
    val localMangaImporter = LocalMangaImporter(context, mangaDao, chapterDao)
    val webSiteStore = WebSiteStore(preferences, viewModelScope)

    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _importStatusText = MutableStateFlow("")
    val importStatusText = _importStatusText.asStateFlow()

    // UI Theme & Layout Toggles
    private val _isGridView = MutableStateFlow(true)
    val isGridView = _isGridView.asStateFlow()

    fun toggleLayout() {
        _isGridView.value = !_isGridView.value
    }

    // 1. LIBRARY SCREEN STATE
    private val _librarySearchQuery = MutableStateFlow("")
    val librarySearchQuery = _librarySearchQuery.asStateFlow()

    // Kullanıcının gizlediği manga kimlikleri (kütüphanede gösterilmez).
    val hiddenMangaIds: StateFlow<Set<String>> = preferences.hiddenMangaIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Yönetim ekranı için arama/gizleme filtresi uygulanmamış tüm kütüphane.
    val allFavoriteMangas: StateFlow<List<MangaEntity>> = mangaDao.getFavoriteMangas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteMangas: StateFlow<List<MangaEntity>> = combine(
        mangaDao.getFavoriteMangas(),
        _librarySearchQuery,
        preferences.hiddenMangaIds
    ) { mangas, query, hidden ->
        mangas.filter { manga ->
            manga.id !in hidden &&
                (query.isBlank() || manga.title.contains(query, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateLibraryQuery(q: String) {
        _librarySearchQuery.value = q
    }

    fun hideMangas(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val current = preferences.hiddenMangaIds.first()
            preferences.setHiddenMangaIds(current + ids)
        }
    }

    fun unhideManga(id: String) {
        viewModelScope.launch {
            val current = preferences.hiddenMangaIds.first()
            preferences.setHiddenMangaIds(current - id)
        }
    }

    // 2. DISCOVER SCREEN STATE
    val selectedSource = MutableStateFlow("Cihaz / Yerel Mangalar")
    val discoverTab = MutableStateFlow("Popüler")
    val discoverQuery = MutableStateFlow("")
    val filterGenre = MutableStateFlow<String?>(null)
    val filterStatus = MutableStateFlow<String?>(null)
    val filterLanguage = MutableStateFlow<String?>(null)

    private val _discoverLoading = MutableStateFlow(false)
    val discoverLoading = _discoverLoading.asStateFlow()

    private val _discoverItems = MutableStateFlow<List<MangaModel>>(emptyList())
    val discoverItems = _discoverItems.asStateFlow()

    private val _discoverError = MutableStateFlow<String?>(null)
    val discoverError = _discoverError.asStateFlow()

    val currentDiscoverPage = MutableStateFlow(1)
    private val _isDiscoverEndReached = MutableStateFlow(false)
    val isEndReached = _isDiscoverEndReached.asStateFlow()

    fun loadNextDiscoverPage() {
        if (_discoverLoading.value || _isDiscoverEndReached.value) return
        val nextPage = currentDiscoverPage.value + 1
        val criteria = MangaFilterCriteria(
            source = selectedSource.value,
            tab = discoverTab.value,
            query = discoverQuery.value,
            genre = filterGenre.value,
            status = filterStatus.value,
            lang = filterLanguage.value
        )
        fetchDiscoverItems(criteria, isNextPage = true, pageToFetch = nextPage)
    }

    fun refreshDiscover() {
        if (_discoverLoading.value) return
        val criteria = MangaFilterCriteria(
            source = selectedSource.value,
            tab = discoverTab.value,
            query = discoverQuery.value,
            genre = filterGenre.value,
            status = filterStatus.value,
            lang = filterLanguage.value
        )
        fetchDiscoverItems(criteria, isNextPage = false, pageToFetch = 1)
    }

    private data class MangaFilterCriteria(
        val source: String,
        val tab: String,
        val query: String,
        val genre: String?,
        val status: String?,
        val lang: String?
    )

    private var discoverJob: Job? = null
    private val discoverGeneration = AtomicLong(0L)
    private var consecutiveFilteredEmptyPages = 0

    // GÖZ AT — SABİTLENEN KAYNAKLAR VE GLOBAL ARAMA
    private val _pinnedSources = MutableStateFlow<Set<String>>(emptySet())
    val pinnedSources = _pinnedSources.asStateFlow()

    data class GlobalSearchGroup(val sourceName: String, val items: List<MangaModel>, val error: String?)

    private val _globalSearchGroups = MutableStateFlow<List<GlobalSearchGroup>>(emptyList())
    val globalSearchGroups = _globalSearchGroups.asStateFlow()

    private val _globalSearchLoading = MutableStateFlow(false)
    val globalSearchLoading = _globalSearchLoading.asStateFlow()

    private val _globalSearchDone = MutableStateFlow(false)
    val globalSearchDone = _globalSearchDone.asStateFlow()

    private var globalSearchJob: Job? = null

    fun toggleSourcePin(sourceName: String) {
        viewModelScope.launch {
            val current = _pinnedSources.value
            val next = if (sourceName in current) current - sourceName else current + sourceName
            preferences.setPinnedSources(next)
            _pinnedSources.value = next
        }
    }

    private val _recentSources = MutableStateFlow<List<String>>(emptyList())
    val recentSources = _recentSources.asStateFlow()

    fun addRecentSource(sourceName: String) {
        viewModelScope.launch {
            val next = (listOf(sourceName) + _recentSources.value.filter { it != sourceName }).take(6)
            preferences.setRecentSources(next)
            _recentSources.value = next
        }
    }

    private val _readingHistory = MutableStateFlow<List<ReadingHistoryEntry>>(emptyList())
    val readingHistory = _readingHistory.asStateFlow()
    private var lastHistoryWriteAt = 0L

    private fun recordHistory(pageIndex: Int) {
        val manga = _readerManga.value ?: return
        val chapter = _activeChapter.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastHistoryWriteAt < 1500) return
        lastHistoryWriteAt = now
        val entry = ReadingHistoryEntry(
            mangaId = manga.id,
            title = manga.title,
            coverUrl = manga.coverUrl,
            sourceName = manga.sourceName,
            chapterId = chapter.id,
            chapterTitle = chapter.title,
            pageIndex = pageIndex.coerceAtLeast(0),
            totalPages = _readerPages.value.size,
            updatedAt = now
        )
        viewModelScope.launch {
            runCatching { preferences.upsertHistoryEntry(entry) }
        }
    }

    fun removeHistoryEntry(mangaId: String) {
        viewModelScope.launch {
            runCatching { preferences.removeHistoryEntry(mangaId) }
        }
    }

    fun clearReadingHistory() {
        viewModelScope.launch {
            runCatching { preferences.clearHistory() }
        }
    }

    fun addMangaToLibrary(item: MangaModel) {
        viewModelScope.launch {
            val existing = mangaDao.getMangaById(item.id)
            val entity = if (existing != null) {
                existing.copy(isFavorite = true)
            } else {
                MangaEntity(
                    id = item.id,
                    title = item.title,
                    coverUrl = item.coverUrl,
                    author = item.author,
                    artist = item.artist,
                    description = item.description,
                    status = item.status,
                    rating = item.rating,
                    sourceName = item.sourceName,
                    originLanguage = item.originLanguage,
                    availableLanguages = if (item.availableLanguages.isNotEmpty()) item.availableLanguages.joinToString(", ") else "TR, EN",
                    isFavorite = true
                )
            }
            mangaDao.insertManga(entity)
        }
    }

    fun runGlobalSearch(rawQuery: String) {
        val query = rawQuery.trim()
        globalSearchJob?.cancel()
        if (query.isEmpty()) {
            _globalSearchGroups.value = emptyList()
            _globalSearchLoading.value = false
            _globalSearchDone.value = false
            return
        }
        globalSearchJob = viewModelScope.launch {
            _globalSearchLoading.value = true
            _globalSearchDone.value = false
            _globalSearchGroups.value = emptyList()
            // Aynı eklentinin dil varyantlarını global aramada tekrar tekrar sorgulama
            val sources = MangaSourceRegistry.sources.filter {
                it !is LocalDeviceSource &&
                    (it !is ExtensionMangaSource || it.isDefaultForExtension)
            }
            coroutineScope {
                sources.forEach { source ->
                    launch {
                        val group = try {
                            val items = source.searchMangas(query, page = 1, language = null, genre = null)
                            GlobalSearchGroup(source.name, items, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: CloudflareChallengeException) {
                            beginCloudflareVerification(e.host, e.challengedUrl) {
                                runGlobalSearch(rawQuery)
                            }
                            GlobalSearchGroup(source.name, emptyList(), "Cloudflare doğrulaması gerekiyor. Doğrulayıp yeniden deneyin.")
                        } catch (t: Throwable) {
                            GlobalSearchGroup(source.name, emptyList(), t.detailedMessage())
                        }
                        _globalSearchGroups.value = _globalSearchGroups.value + group
                    }
                }
            }
            _globalSearchGroups.value = _globalSearchGroups.value.sortedBy { group ->
                sources.indexOfFirst { it.name == group.sourceName }
            }
            _globalSearchLoading.value = false
            _globalSearchDone.value = true
        }
    }

    fun clearGlobalSearch() {
        globalSearchJob?.cancel()
        _globalSearchGroups.value = emptyList()
        _globalSearchLoading.value = false
        _globalSearchDone.value = false
    }

    // CLOUDFLARE DOĞRULAMA KÖPRÜSÜ
    data class CfChallenge(val host: String, val url: String)

    private val _cfChallenge = MutableStateFlow<CfChallenge?>(null)
    val cfChallenge = _cfChallenge.asStateFlow()

    private var cfRetryAction: (() -> Unit)? = null

    fun beginCloudflareVerification(host: String, url: String, retry: () -> Unit) {
        cfRetryAction = retry
        _cfChallenge.value = CfChallenge(host, url)
    }

    fun completeCloudflareVerification() {
        val retry = cfRetryAction
        cfRetryAction = null
        _cfChallenge.value = null
        retry?.invoke()
    }

    fun cancelCloudflareVerification() {
        cfRetryAction = null
        _cfChallenge.value = null
    }

    private val _readerPageRetryTick = MutableStateFlow(0)
    val readerPageRetryTick = _readerPageRetryTick.asStateFlow()

    // WEB KAYNAKLARI (KULLANICI SİTELERİ)
    val webSites: StateFlow<List<WebSite>> = webSiteStore.sites
    private val webChapterAttempted = mutableSetOf<String>()

    fun isWebSource(source: String?): Boolean = source?.startsWith("Web: ") == true

    fun normalizeWebUrl(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://$value"
        return runCatching {
            val url = java.net.URL(value)
            val host = url.host.orEmpty()
            if (host.isBlank() || !host.contains(".")) return null
            val port = if (url.port > 0) ":${url.port}" else ""
            "${url.protocol}://$host$port"
        }.getOrNull()
    }

    fun addWebSite(rawUrl: String, displayName: String?): WebSite? {
        val origin = normalizeWebUrl(rawUrl) ?: return null
        val host = runCatching { java.net.URL(origin).host }.getOrDefault(origin)
        val baseName = displayName?.trim().takeUnless { it.isNullOrBlank() } ?: host
        val uniqueName = webSiteStore.uniqueName(baseName)
        val site = WebSite(id = origin, name = uniqueName, origin = origin)
        viewModelScope.launch { webSiteStore.add(site) }
        return site
    }

    fun removeWebSite(id: String) {
        viewModelScope.launch { webSiteStore.remove(id) }
    }

    suspend fun captureWebManga(
        sourceName: String,
        pageUrl: String,
        title: String,
        coverUrl: String,
        description: String,
        chapters: List<Triple<String, String, Double>>
    ): String? {
        val site = webSiteStore.siteBySourceName(sourceName) ?: return null
        if (chapters.isEmpty()) return null
        val mangaId = "web_" + sha1Hex("${site.origin}|$pageUrl").take(16)
        mangaDao.insertManga(
            MangaEntity(
                id = mangaId,
                title = title.ifBlank { site.name },
                coverUrl = coverUrl,
                description = description.ifBlank { "Açıklama yok" },
                sourceName = "Web: ${site.name}",
                originLanguage = "İngilizce",
                isFavorite = true
            )
        )
        preferences.setWebMangaPage(mangaId, pageUrl)
        val entities = chapters.map { (chapterTitle, chapterUrl, chapterNumber) ->
            ChapterEntity(
                id = "webch_" + Base64.encodeToString(
                    chapterUrl.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ),
                mangaId = mangaId,
                title = chapterTitle,
                chapterNumber = chapterNumber,
                uploadDate = "Bugün",
                language = "en"
            )
        }
        chapterDao.insertChapters(entities)
        if (isWebSource(selectedSource.value)) {
            fetchDiscoverItems(
                MangaFilterCriteria(
                    source = selectedSource.value,
                    tab = discoverTab.value,
                    query = discoverQuery.value,
                    genre = filterGenre.value,
                    status = filterStatus.value,
                    lang = filterLanguage.value
                ),
                isNextPage = false,
                pageToFetch = 1
            )
        }
        return mangaId
    }

    suspend fun captureWebSeries(
        sourceName: String,
        items: List<Triple<String, String, String>>,
        favorite: Boolean = false
    ): Int {
        if (items.isEmpty()) return 0
        val series = items.map { (title, url, cover) ->
            CaptureSeries(title = title, url = url, cover = cover)
        }
        return upsertWebSeries(sourceName, series, favorite).size
    }

    // ---- Web sitesi gezinme: popüler / yeni / kategoriler / arama ----

    data class WebSitePlan(
        val homeSeries: List<Triple<String, String, String>>,
        val popularUrl: String?,
        val latestUrl: String?,
        val categories: List<Pair<String, String>>,
        val searchAction: String?,
        val searchParam: String?
    )

    private val webSitePlans = mutableMapOf<String, WebSitePlan>()

    private val _webSitePlan = MutableStateFlow<WebSitePlan?>(null)
    val webSitePlan = _webSitePlan.asStateFlow()

    private val webPopularRegex = Regex("popüler|populer|popular|en çok|en cok|trend", RegexOption.IGNORE_CASE)
    private val webLatestRegex = Regex(
        "yeni|son eklenen|son güncel|son guncel|güncel|guncel|son bölüm|son bolum|son çıkan|son cikan|latest|updated",
        RegexOption.IGNORE_CASE
    )
    private val webCategoryPathHints = listOf(
        "/kategori", "/category", "/categorie", "/genre", "/tur/", "/tür/",
        "/manga-tur", "/manga-tür", "/seri-tur", "/seri-tür"
    )

    private fun planFromParsed(parsed: CaptureResult?, site: WebSite): WebSitePlan {
        val series = parsed?.series.orEmpty()
            .filter { it.url.startsWith("http") && it.title.isNotBlank() }
            .distinctBy { it.url }
            .map { Triple(it.title, it.url, it.cover) }
            .take(600)
        var popular: String? = null
        var latest: String? = null
        val categories = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        for (link in parsed?.links.orEmpty()) {
            if (!link.url.startsWith("http")) continue
            val title = link.title.trim()
            val lower = title.lowercase()
            val path = runCatching { java.net.URL(link.url).path.lowercase() }
                .getOrDefault(link.url.lowercase())
            if (popular == null &&
                (webPopularRegex.containsMatchIn(lower) || path.contains("populer") ||
                    path.contains("popular") || path.contains("trend"))
            ) {
                popular = link.url
                continue
            }
            if (latest == null &&
                (webLatestRegex.containsMatchIn(lower) || path.contains("latest") ||
                    path.contains("son-eklenen") || path.contains("son-guncellenen") ||
                    path.contains("last-updates"))
            ) {
                latest = link.url
                continue
            }
            if (title.length in 2..30 && webCategoryPathHints.any { path.contains(it) } && seen.add(link.url)) {
                categories.add(title to link.url)
            }
        }
        return WebSitePlan(
            homeSeries = series,
            popularUrl = popular,
            latestUrl = latest,
            categories = categories.take(30),
            searchAction = parsed?.searchAction,
            searchParam = parsed?.searchParam
        )
    }

    private fun buildWebSearchUrl(origin: String, plan: WebSitePlan, query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val action = plan.searchAction
        val param = plan.searchParam ?: "s"
        return if (action != null) {
            val separator = if (action.contains('?')) '&' else '?'
            "$action$separator$param=$encoded"
        } else {
            "$origin/?s=$encoded"
        }
    }

    private suspend fun upsertWebSeries(
        sourceName: String,
        series: List<CaptureSeries>,
        favorite: Boolean = false
    ): List<MangaModel> {
        val site = webSiteStore.siteBySourceName(sourceName) ?: return emptyList()
        val out = mutableListOf<MangaModel>()
        for (entry in series.take(600)) {
            if (entry.url.isBlank()) continue
            val mangaId = "web_" + sha1Hex("${site.origin}|${entry.url}").take(16)
            if (mangaId in hiddenMangaIds.value) continue
            val existing = mangaDao.getMangaById(mangaId)
            val entity = if (existing != null) {
                existing.copy(
                    title = entry.title.ifBlank { existing.title },
                    coverUrl = entry.cover.ifBlank { existing.coverUrl },
                    isFavorite = existing.isFavorite || favorite
                )
            } else {
                MangaEntity(
                    id = mangaId,
                    title = entry.title.ifBlank { site.name },
                    coverUrl = entry.cover,
                    description = "Bölümler seri açıldığında sayfadan otomatik yüklenecek.",
                    sourceName = sourceName,
                    originLanguage = "İngilizce",
                    isFavorite = favorite
                )
            }
            mangaDao.insertManga(entity)
            preferences.setWebMangaPage(mangaId, entry.url)
            out.add(entity.toWebModel(sourceName))
        }
        return out
    }

    private fun MangaEntity.toWebModel(sourceName: String): MangaModel = MangaModel(
        id = id,
        title = title,
        coverUrl = coverUrl,
        author = author,
        artist = artist,
        description = description,
        status = status,
        rating = rating,
        sourceName = sourceName,
        genres = listOf("Web"),
        originLanguage = originLanguage,
        availableLanguages = listOf("WEB")
    )

    private suspend fun loadWebSiteItems(criteria: MangaFilterCriteria): List<MangaModel> {
        val source = criteria.source
        val site = webSiteStore.siteBySourceName(source)
        if (site == null) {
            _webCatalogStatus.value = "Site bulunamadı."
            return emptyList()
        }
        val cachedPlan = webSitePlans[source]
        _webSitePlan.value = cachedPlan
        val plan = cachedPlan ?: run {
            _webCatalogStatus.value = "Site menüsü okunuyor…"
            val raw = WebPageExtractor.capture(context, site.origin)
            val built = planFromParsed(WebCaptureParser.parse(raw), site)
            webSitePlans[source] = built
            built
        }
        _webSitePlan.value = plan
        val query = criteria.query.trim()
        val targetUrl = when {
            query.isNotBlank() -> buildWebSearchUrl(site.origin, plan, query)
            criteria.tab == "Yeni" -> plan.latestUrl ?: site.origin
            else -> plan.popularUrl ?: site.origin
        }
        val series = if (targetUrl == site.origin && plan.homeSeries.size >= 3) {
            plan.homeSeries.map { CaptureSeries(title = it.first, url = it.second, cover = it.third) }
        } else {
            _webCatalogStatus.value = "Sayfa yükleniyor: $targetUrl"
            val raw = WebPageExtractor.capture(context, targetUrl)
            val parsed = WebCaptureParser.parse(raw)
            parsed?.series.orEmpty()
                .filter { it.url.startsWith("http") && it.title.isNotBlank() }
                .distinctBy { it.url }
        }
        if (series.isEmpty()) {
            _webCatalogStatus.value = if (query.isNotBlank()) {
                "Sonuç bulunamadı: \"$query\""
            } else {
                "Bu sayfada manga listesi bulunamadı."
            }
            return emptyList()
        }
        val models = upsertWebSeries(source, series)
        _webCatalogStatus.value = when {
            query.isNotBlank() -> "\"$query\" için ${models.size} sonuç."
            criteria.tab == "Yeni" -> "${models.size} manga listelendi (yeni eklenenler)."
            else -> "${models.size} manga listelendi."
        }
        return models
    }

    fun loadWebCategory(sourceName: String, url: String) {
        if (_webCatalogScanning.value) return
        if (!url.startsWith("http")) return
        viewModelScope.launch {
            _webCatalogScanning.value = true
            try {
                _webCatalogStatus.value = "Kategori yükleniyor…"
                val raw = WebPageExtractor.capture(context, url)
                val parsed = WebCaptureParser.parse(raw)
                val series = parsed?.series.orEmpty()
                    .filter { it.url.startsWith("http") && it.title.isNotBlank() }
                    .distinctBy { it.url }
                if (series.isEmpty()) {
                    _webCatalogStatus.value = "Bu kategoride manga bulunamadı."
                    _discoverItems.value = emptyList()
                } else {
                    _discoverItems.value = upsertWebSeries(sourceName, series)
                    _webCatalogStatus.value = "${series.size} manga listelendi (kategori)."
                }
                _isDiscoverEndReached.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                beginCloudflareVerification(e.host, e.challengedUrl) {
                    loadWebCategory(sourceName, url)
                }
                _webCatalogStatus.value = "Cloudflare doğrulaması gerekiyor: ${e.host}"
            } catch (e: Exception) {
                _webCatalogStatus.value = "Kategori yüklenemedi: ${e.message ?: "bilinmeyen hata"}"
            } finally {
                _webCatalogScanning.value = false
            }
        }
    }

    private val _webCatalogScanning = MutableStateFlow(false)
    val webCatalogScanning = _webCatalogScanning.asStateFlow()

    private val _webCatalogStatus = MutableStateFlow<String?>(null)
    val webCatalogStatus = _webCatalogStatus.asStateFlow()

    fun clearWebCatalogStatus() {
        _webCatalogStatus.value = null
    }

    fun scanWebSiteCatalog(sourceName: String, listUrl: String? = null) {
        if (_webCatalogScanning.value) return
        val site = webSiteStore.siteBySourceName(sourceName)
        if (site == null) {
            _webCatalogStatus.value = "Site bulunamadı."
            return
        }
        val targetUrl = listUrl?.trim()?.takeIf { it.startsWith("http") } ?: site.origin
        _webCatalogScanning.value = true
        _webCatalogStatus.value = "Site taranıyor: $targetUrl"
        viewModelScope.launch {
            try {
                val raw = WebPageExtractor.capture(context, targetUrl)
                val parsed = WebCaptureParser.parse(raw)
                if (parsed == null) {
                    _webCatalogStatus.value = "Sayfa okunamadı. Adresi kontrol edip tekrar deneyin."
                    return@launch
                }
                val valid = parsed.series
                    .filter { it.url.startsWith("http") && it.title.isNotBlank() }
                    .distinctBy { runCatching { java.net.URL(it.url).path }.getOrDefault(it.url) }
                val withCovers = valid.filter { it.cover.isNotBlank() }
                val chosen = if (withCovers.size >= 3) withCovers else valid
                if (chosen.isEmpty()) {
                    _webCatalogStatus.value = "Bu sayfada manga listesi bulunamadı. Bir katalog/liste sayfası adresi deneyin."
                    return@launch
                }
                val models = upsertWebSeries("Web: ${site.name}", chosen)
                webSitePlans["Web: ${site.name}"] = planFromParsed(parsed, site)
                _discoverItems.value = models
                _isDiscoverEndReached.value = true
                _webCatalogStatus.value = if (models.isNotEmpty()) {
                    "${models.size} manga site kataloğuna eklendi. Kütüphaneye almak için seriyi açıp favorileyin."
                } else {
                    "Manga eklenemedi."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                beginCloudflareVerification(e.host, e.challengedUrl) {
                    scanWebSiteCatalog(sourceName, listUrl)
                }
                _webCatalogStatus.value = "Cloudflare doğrulaması gerekiyor: ${e.host}"
            } catch (e: Exception) {
                _webCatalogStatus.value = "Tarama hatası: ${e.message ?: "bilinmeyen hata"}"
            } finally {
                _webCatalogScanning.value = false
            }
        }
    }

    private data class QuickReadPayload(
        val manga: MangaEntity,
        val chapter: ChapterEntity,
        val pages: List<String>
    )

    private var quickReadPayload: QuickReadPayload? = null

    fun startQuickRead(
        sourceName: String,
        pageTitle: String,
        pageUrl: String,
        coverUrl: String,
        pages: List<String>,
        translate: Boolean = false
    ): String? {
        if (pages.isEmpty()) return null
        val chapterId = "webquick_" + Base64.encodeToString(
            pageUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val mangaId = "webquick_" + sha1Hex(pageUrl).take(16)
        val manga = MangaEntity(
            id = mangaId,
            title = pageTitle.ifBlank { "Hızlı Okuma" },
            coverUrl = coverUrl,
            description = "Hızlı okuma: kütüphaneye eklenmedi, yalnızca bu oturum için açıldı.",
            sourceName = sourceName.ifBlank { "Web" },
            originLanguage = "İngilizce",
            isFavorite = false,
            addedAt = System.currentTimeMillis()
        )
        val chapter = ChapterEntity(
            id = chapterId,
            mangaId = mangaId,
            title = pageTitle.ifBlank { "Hızlı Okuma" },
            chapterNumber = 0.0,
            uploadDate = "Bugün",
            language = "en"
        )
        quickReadPayload = QuickReadPayload(manga = manga, chapter = chapter, pages = pages)
        _readerManga.value = manga
        _readerChapters.value = listOf(chapter)
        _activeChapter.value = chapter
        _readerPages.value = pages
        _requestedReaderChapterId.value = chapterId
        _currentPageIndex.value = 0
        _readerError.value = null
        _readerLoading.value = false
        _translatedPagesMap.value = emptyMap()
        _detectedBubblesMap.value = emptyMap()
        _maskedPagesMap.value = emptyMap()
        _translationActive.value = false
        detectionPrefetchJob?.cancel()
        detectionPrefetchJob = null
        translationPipelineCursor = 0
        pageBitmapCache.evictAll()
        viewModelScope.launch {
            if (translate || preferences.autoTranslate.first()) {
                toggleLiveTranslation(true)
            }
        }
        return chapterId
    }

    private fun sha1Hex(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) builder.append(String.format("%02x", byte))
        return builder.toString()
    }

    private fun fetchDiscoverItems(criteria: MangaFilterCriteria, isNextPage: Boolean, pageToFetch: Int) {
        if (isNextPage && (_discoverLoading.value || _isDiscoverEndReached.value)) return
        if (!isNextPage) {
            discoverJob?.cancel()
            currentDiscoverPage.value = 1
            _isDiscoverEndReached.value = false
            _discoverItems.value = emptyList()
            consecutiveFilteredEmptyPages = 0
        }
        val requestId = discoverGeneration.incrementAndGet()
        _discoverLoading.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _discoverError.value = null
            var autoLoadNextPage = false
            try {
                val sourceName = criteria.source.takeIf { name ->
                    name.startsWith("Web: ") || MangaSourceRegistry.sources.any { it.name.equals(name, ignoreCase = true) }
                } ?: MangaSourceRegistry.sources.firstOrNull()?.name
                    ?: throw IOException("Kullanılabilir kaynak bulunamadı.")
                val src = MangaSourceRegistry.getSource(sourceName)
                val isLocalSource = sourceName.contains("Yerel", ignoreCase = true) || sourceName.contains("Cihaz", ignoreCase = true)
                val isWebSource = sourceName.startsWith("Web: ")
                var items = if (isWebSource) {
                    if (pageToFetch > 1) emptyList() else loadWebSiteItems(criteria)
                } else if (isLocalSource) {
                    if (pageToFetch > 1) emptyList() else mangaDao.getAllMangas()
                        .filter {
                            (it.sourceName.contains("Yerel", ignoreCase = true) || it.id.startsWith("local_")) &&
                                it.id !in hiddenMangaIds.value
                        }
                        .map { m ->
                        MangaModel(
                            id = m.id,
                            title = m.title,
                            coverUrl = m.coverUrl,
                            author = m.author,
                            artist = m.artist,
                            description = m.description,
                            status = m.status,
                            rating = m.rating,
                            sourceName = "Cihaz / Yerel Mangalar",
                            genres = listOf("Yerel"),
                            originLanguage = m.originLanguage,
                            availableLanguages = listOf("TR", "YEREL")
                        )
                    }
                } else if (criteria.query.isNotBlank()) {
                    src.searchMangas(
                        query = criteria.query,
                        page = pageToFetch,
                        language = criteria.lang,
                        genre = criteria.genre
                    )
                } else {
                    src.getMangas(
                        page = pageToFetch,
                        tab = criteria.tab,
                        language = criteria.lang,
                        genre = criteria.genre,
                        status = criteria.status
                    )
                }

                if (requestId != discoverGeneration.get()) return@launch
                val sourceHadItems = items.isNotEmpty()
                if (isLocalSource && criteria.query.isNotBlank()) {
                    items = items.filter { it.title.contains(criteria.query.trim(), ignoreCase = true) }
                }
                if (!isWebSource && criteria.genre != null && criteria.genre != "Hepsi") {
                    items = items.filter { it.genres.any { g -> g.contains(criteria.genre, ignoreCase = true) } }
                }
                if (!isWebSource && criteria.status != null && criteria.status != "Hepsi") {
                    items = items.filter { it.status.equals(criteria.status, ignoreCase = true) }
                }
                if (!isWebSource && criteria.lang != null && criteria.lang != "Hepsi") {
                    val label = criteria.lang.trim()
                    val code = languageCode(label)
                    items = items.filter { item ->
                        item.availableLanguages.any { lang ->
                            lang.equals(label, ignoreCase = true) ||
                                (code != null && lang.equals(code, ignoreCase = true))
                        }
                    }
                }

                if (requestId != discoverGeneration.get()) return@launch
                currentDiscoverPage.value = pageToFetch
                if (items.isEmpty()) {
                    // A raw page may contain results that the client-side filters remove.
                    _isDiscoverEndReached.value = !sourceHadItems || isLocalSource
                    if (sourceHadItems && !isLocalSource) {
                        consecutiveFilteredEmptyPages++
                        autoLoadNextPage = consecutiveFilteredEmptyPages < 4
                    } else {
                        consecutiveFilteredEmptyPages = 0
                    }
                } else {
                    consecutiveFilteredEmptyPages = 0
                    if (isNextPage) {
                        _discoverItems.value = (_discoverItems.value + items)
                            .distinctBy { "${it.sourceName}:${it.id}" }
                    } else {
                        _discoverItems.value = items.distinctBy { "${it.sourceName}:${it.id}" }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                if (requestId == discoverGeneration.get()) {
                    beginCloudflareVerification(e.host, e.challengedUrl) {
                        fetchDiscoverItems(criteria, isNextPage = false, pageToFetch = 1)
                    }
                    _discoverError.value = "Cloudflare doğrulaması gerekiyor: ${e.host}. Açılan pencerede doğrulayın."
                }
            } catch (t: Throwable) {
                if (requestId == discoverGeneration.get()) {
                    _discoverError.value = "Kaynak yüklenirken hata oluştu: ${t.detailedMessage()}"
                    if (!isNextPage) _discoverItems.value = emptyList()
                }
            } finally {
                if (requestId == discoverGeneration.get()) {
                    _discoverLoading.value = false
                    if (autoLoadNextPage && !_isDiscoverEndReached.value) {
                        loadNextDiscoverPage()
                    }
                }
            }
        }
        discoverJob = job
        job.start()
    }

    // 3. DETAIL SCREEN STATE
    private val _detailLoading = MutableStateFlow(false)
    val detailLoading = _detailLoading.asStateFlow()

    private val _detailManga = MutableStateFlow<MangaEntity?>(null)
    val detailManga = _detailManga.asStateFlow()

    private val _detailChapters = MutableStateFlow<List<ChapterEntity>>(emptyList())
    val detailChapters = _detailChapters.asStateFlow()

    private val _detailError = MutableStateFlow<String?>(null)
    val detailError = _detailError.asStateFlow()

    private val _isFavoriteManga = MutableStateFlow(false)
    val isFavoriteManga = _isFavoriteManga.asStateFlow()

    private var detailChaptersJob: Job? = null
    private var detailLoadJob: Job? = null
    private val detailLoadGeneration = AtomicLong(0L)

    fun loadMangaDetail(mangaId: String, sourceName: String) {
        val requestId = detailLoadGeneration.incrementAndGet()
        detailLoadJob?.cancel()
        detailChaptersJob?.cancel()
        detailChaptersJob = viewModelScope.launch {
            chapterDao.getChaptersForManga(mangaId).collect {
                if (detailLoadGeneration.get() == requestId) _detailChapters.value = it
            }
        }

        detailLoadJob = viewModelScope.launch {
            _detailLoading.value = true
            _detailError.value = null
            _detailManga.value = null
            _detailChapters.value = emptyList()
            try {
                val cached = mangaDao.getMangaById(mangaId)
                if (detailLoadGeneration.get() != requestId) return@launch
                if (cached != null) {
                    _detailManga.value = cached
                    _isFavoriteManga.value = cached.isFavorite
                }
                if (cached != null) _detailLoading.value = false
                val cachedChapters = chapterDao.getChaptersByMangaIdSync(mangaId)
                if (detailLoadGeneration.get() != requestId) return@launch
                if (cachedChapters.isNotEmpty()) _detailChapters.value = cachedChapters
                if (mangaId.startsWith("local_") || mangaId.startsWith("web_") || sourceName.startsWith("Web: ") || sourceName.contains("Yerel", ignoreCase = true) || sourceName.contains("Cihaz", ignoreCase = true)) {
                    val isWeb = mangaId.startsWith("web_") || sourceName.startsWith("Web: ")
                    if (isWeb && mangaId !in webChapterAttempted) {
                        webChapterAttempted.add(mangaId)
                        val pageUrl = preferences.webMangaPages.first()[mangaId]
                        if (!pageUrl.isNullOrBlank()) {
                            val raw = WebPageExtractor.capture(context, pageUrl)
                            if (detailLoadGeneration.get() != requestId) return@launch
                            val parsed = WebCaptureParser.parse(raw)
                            if (parsed != null) {
                                val chosen = WebCaptureParser.seriesChaptersOf(parsed)
                                if (chosen.isNotEmpty()) {
                                    val existingById = chapterDao.getChaptersByMangaIdSync(mangaId).associateBy { it.id }
                                    val entities = chosen.map { ch ->
                                        val id = "webch_" + Base64.encodeToString(
                                            ch.url.toByteArray(Charsets.UTF_8),
                                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                                        )
                                        val previous = existingById[id]
                                        ChapterEntity(
                                            id = id,
                                            mangaId = mangaId,
                                            title = ch.title.ifBlank { "Bölüm" },
                                            chapterNumber = ch.number,
                                            uploadDate = previous?.uploadDate ?: "Bugün",
                                            isRead = previous?.isRead ?: false,
                                            readPage = previous?.readPage ?: 0,
                                            isDownloaded = previous?.isDownloaded ?: false,
                                            localImagePathList = previous?.localImagePathList ?: "",
                                            totalPages = previous?.totalPages ?: 0,
                                            language = "en"
                                        )
                                    }
                                    chapterDao.upsertChapters(entities)
                                    // Artık sayfada olmayan eski kayıtları (ör. hatalı ayrıştırılmış
                                    // bağlantılar) temizle; ancak taze liste beklenenden küçükse
                                    // eksik tarama olabilir diye silme yapma.
                                    val newIds = entities.map { it.id }.toSet()
                                    val stale = existingById.values.filter { it.id !in newIds && !it.isDownloaded }
                                    if (stale.isNotEmpty() && entities.size * 5 >= existingById.size * 4) {
                                        stale.forEach { chapterDao.deleteChapterById(it.id) }
                                    }
                                }
                                val current = mangaDao.getMangaById(mangaId)
                                if (current != null && current.coverUrl.isBlank() && parsed.cover.isNotBlank()) {
                                    val updated = current.copy(coverUrl = parsed.cover)
                                    mangaDao.updateManga(updated)
                                    _detailManga.value = updated
                                }
                            }
                        }
                    }
                    if (detailLoadGeneration.get() != requestId) return@launch
                    val refreshedChapters = chapterDao.getChaptersByMangaIdSync(mangaId)
                    if (detailLoadGeneration.get() != requestId) return@launch
                    _detailChapters.value = refreshedChapters
                    _detailLoading.value = false
                    return@launch
                }

                val src = MangaSourceRegistry.getSource(sourceName)
                val (model, chapters) = src.getMangaDetail(mangaId)
                if (detailLoadGeneration.get() != requestId) return@launch

                val entity = MangaEntity(
                    id = model.id,
                    title = model.title,
                    coverUrl = model.coverUrl,
                    author = model.author,
                    artist = model.artist,
                    description = model.description,
                    status = model.status,
                    rating = model.rating,
                    sourceName = model.sourceName,
                    originLanguage = model.originLanguage,
                    availableLanguages = if (model.availableLanguages.isNotEmpty()) model.availableLanguages.joinToString(", ") else "TR, EN",
                    isFavorite = cached?.isFavorite ?: false,
                    addedAt = cached?.addedAt ?: System.currentTimeMillis()
                )
                val savedEntity = database.withTransaction {
                    val latestFavorite = mangaDao.getMangaById(mangaId)?.isFavorite
                        ?: entity.isFavorite
                    val mergedEntity = entity.copy(isFavorite = latestFavorite)
                    mangaDao.insertManga(mergedEntity)
                    mergedEntity
                }
                if (detailLoadGeneration.get() != requestId) return@launch
                _detailManga.value = savedEntity
                _isFavoriteManga.value = savedEntity.isFavorite

                val currentLocalChapters = chapterDao.getChaptersByMangaIdSync(mangaId)
                if (detailLoadGeneration.get() != requestId) return@launch
                val newChapterEntities = chapters.map { ch ->
                    val match = currentLocalChapters.firstOrNull { it.id == ch.id }
                        ?: currentLocalChapters.firstOrNull {
                            it.id.startsWith("extch_") && it.chapterNumber == ch.chapterNumber
                        }
                    ChapterEntity(
                        id = ch.id,
                        mangaId = ch.mangaId,
                        title = ch.title,
                        chapterNumber = ch.chapterNumber,
                        uploadDate = ch.uploadDate,
                        isRead = match?.isRead ?: false,
                        readPage = match?.readPage ?: 0,
                        isDownloaded = match?.isDownloaded ?: false,
                        localImagePathList = match?.localImagePathList ?: "",
                        totalPages = match?.totalPages ?: 0,
                        language = ch.language
                    )
                }
                chapterDao.insertChapters(newChapterEntities)
                val newIds = newChapterEntities.mapTo(HashSet()) { it.id }
                currentLocalChapters
                    .filter { old ->
                        old.id.startsWith("extch_") && old.id !in newIds && !old.isDownloaded &&
                            newChapterEntities.any { it.id != old.id && it.chapterNumber == old.chapterNumber }
                    }
                    .forEach { chapterDao.deleteChapterById(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                if (detailLoadGeneration.get() == requestId) {
                    beginCloudflareVerification(e.host, e.challengedUrl) {
                        loadMangaDetail(mangaId, sourceName)
                    }
                    if (_detailManga.value == null && _detailChapters.value.isEmpty()) {
                        _detailError.value = "Cloudflare doğrulaması gerekiyor: ${e.host}. Açılan pencerede doğrulayın."
                    }
                }
            } catch (t: Throwable) {
                if (detailLoadGeneration.get() == requestId) {
                    // Keep cached manga and chapter data visible when the source is offline.
                    if (_detailManga.value == null && _detailChapters.value.isEmpty()) {
                        _detailError.value = "Detaylar getirilirken hata oluştu: ${t.detailedMessage()}"
                    }
                }
            } finally {
                if (detailLoadGeneration.get() == requestId) _detailLoading.value = false
            }
        }
    }

    fun toggleFavorite(mangaId: String) {
        viewModelScope.launch {
            val manga = mangaDao.getMangaById(mangaId) ?: return@launch
            val nextFav = !manga.isFavorite
            val updated = manga.copy(isFavorite = nextFav)
            mangaDao.insertManga(updated)
            if (_detailManga.value?.id == mangaId) {
                _detailManga.value = updated
                _isFavoriteManga.value = nextFav
            }
        }
    }

    fun markChaptersReadFromHere(mangaId: String, currentChapterNum: Double) {
        viewModelScope.launch {
            chapterDao.markChaptersReadFromHere(mangaId, currentChapterNum)
        }
    }

    // 4. READER SCREEN STATE
    private val _readerLoading = MutableStateFlow(false)
    val readerLoading = _readerLoading.asStateFlow()

    private val _requestedReaderChapterId = MutableStateFlow("")
    val requestedReaderChapterId = _requestedReaderChapterId.asStateFlow()

    private val _readerError = MutableStateFlow<String?>(null)
    val readerError = _readerError.asStateFlow()

    private val _readerManga = MutableStateFlow<MangaEntity?>(null)
    val readerManga = _readerManga.asStateFlow()

    private val _readerChapters = MutableStateFlow<List<ChapterEntity>>(emptyList())
    val readerChapters = _readerChapters.asStateFlow()

    private val _activeChapter = MutableStateFlow<ChapterEntity?>(null)
    val activeChapter = _activeChapter.asStateFlow()

    private val _readerPages = MutableStateFlow<List<String>>(emptyList())
    val readerPages = _readerPages.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex = _currentPageIndex.asStateFlow()

    private val _translationActive = MutableStateFlow(false)
    val translationActive = _translationActive.asStateFlow()

    private val _translatedPagesMap = MutableStateFlow<Map<Int, List<TextBlockObj>>>(emptyMap())
    val translatedPagesMap = _translatedPagesMap.asStateFlow()

    private val _detectedBubblesMap = MutableStateFlow<Map<Int, List<BubbleBox>>>(emptyMap())
    val detectedBubblesMap = _detectedBubblesMap.asStateFlow()

    private val _maskedPagesMap = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val maskedPagesMap = _maskedPagesMap.asStateFlow()

    private val _isTranslationLoading = MutableStateFlow(false)
    val isTranslationLoading = _isTranslationLoading.asStateFlow()

    private val _translationNotice = MutableStateFlow<String?>(null)
    val translationNotice = _translationNotice.asStateFlow()
    private var lastNoticeText: String? = null
    private var lastNoticeAt = 0L

    fun clearTranslationNotice() {
        _translationNotice.value = null
    }

    private fun emitTranslationNotice(message: String?) {
        if (message.isNullOrBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (message == lastNoticeText && now - lastNoticeAt < 60_000L) return
        lastNoticeText = message
        lastNoticeAt = now
        _translationNotice.value = message
    }

    private val _engineTestState = MutableStateFlow<String?>(null)
    val engineTestState = _engineTestState.asStateFlow()
    private val _engineTestRunning = MutableStateFlow(false)
    val engineTestRunning = _engineTestRunning.asStateFlow()

    fun clearEngineTestState() {
        _engineTestState.value = null
    }

    fun testSelectedTranslationEngine() {
        if (_engineTestRunning.value) return
        viewModelScope.launch {
            _engineTestRunning.value = true
            _engineTestState.value = null
            try {
                val engine = preferences.translationEngine.first()
                val geminiKey = preferences.geminiApiKey.first()
                val deepLKey = preferences.deeplApiKey.first()
                val geminiModel = preferences.geminiModel.first()
                val configuredTarget = preferences.translationTargetLang.first()
                val targetCode = if (configuredTarget == "en") "tr" else configuredTarget
                val outcome = translationService.translateBlocks(
                    requests = listOf(TranslationRequest("engine_test", "Hello, how are you today?")),
                    engine = engine,
                    userGeminiApiKey = geminiKey,
                    userDeepLApiKey = deepLKey,
                    originLang = TranslationLanguages.turkishName("en") ?: "İngilizce",
                    targetLangCode = targetCode,
                    turkishConvertEnabled = false,
                    geminiModel = geminiModel,
                )
                val result = outcome.results.firstOrNull()
                val targetName = TranslationLanguages.turkishName(targetCode) ?: targetCode
                val base = if (result?.succeeded == true) {
                    "Test başarılı ($targetName): \"${result.translatedText}\""
                } else {
                    "Test başarısız (motor: $engine): ${result?.error ?: "sonuç yok"}"
                }
                _engineTestState.value = outcome.warning?.let { "$base — $it" } ?: base
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _engineTestState.value = "Test hatası: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                _engineTestRunning.value = false
            }
        }
    }

    val isDebugMode = preferences.debugMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleDebugMode() {
        viewModelScope.launch {
            preferences.setDebugMode(!preferences.debugMode.first())
        }
    }

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setDebugMode(enabled)
        }
    }

    val readingDirection = preferences.readingMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Dikey")
    val keepScreenOn = preferences.keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val brightnessLock = preferences.brightnessLock.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val translationBubbleStyle = preferences.translationBubbleStyle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Doğal Balon")
    val translationOpacity = preferences.translationOpacity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 90)
    val translationTargetLanguage = preferences.translationTargetLang.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        TranslationLanguages.defaultTargetCode()
    )
    val translationSourceLanguage = preferences.translationSourceLang.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        TranslationLanguages.AUTO
    )
    val recentTranslationLanguages = preferences.recentTranslationLangs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val pageCacheMaxKb = ((Runtime.getRuntime().maxMemory() / 10L) / 1024L)
        .coerceIn(8L * 1024L, 64L * 1024L).toInt()
    private val maskedPageBudgetBytes = (Runtime.getRuntime().maxMemory() / 12L)
        .coerceIn(6L * 1024L * 1024L, 40L * 1024L * 1024L)
    private val pageBitmapCache = object : LruCache<String, Bitmap>(pageCacheMaxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    val pageQuality = preferences.pageQuality.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Orta")

    // Web bölüm kimliğinden sayfa adresini çözer (webch_/webquick_ önekleri base64 URL taşır).
    fun webChapterUrl(chapterId: String): String? {
        val prefix = listOf("webch_", "webquick_").firstOrNull { chapterId.startsWith(it) } ?: return null
        val data = chapterId.removePrefix(prefix)
        if (data.isBlank()) return null
        return runCatching {
            String(
                Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private fun Throwable.detailedMessage(): String {
        val base = localizedMessage ?: message ?: this::class.simpleName.orEmpty()
        val causeMessage = cause?.let { it.message ?: it::class.simpleName.orEmpty() }
        return if (causeMessage.isNullOrBlank() || causeMessage == base) base else "$base — $causeMessage"
    }

    fun loadChapterInReader(chapterId: String) {
        _requestedReaderChapterId.value = chapterId
        val loadGeneration = readerGeneration.incrementAndGet()
        translationSequenceJob?.cancel()
        translationSequenceJob = null
        detectionPrefetchJob?.cancel()
        detectionPrefetchJob = null
        translationPipelineCursor = 0
        prewarmPpOcrIfNeeded()
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
        inProgressPages.clear()
        _isTranslationLoading.value = false
        viewModelScope.launch(viewModelExceptionHandler) {
            if (readerGeneration.get() != loadGeneration) return@launch
            prefetchJobMap.values.forEach { it.cancel() }
            prefetchJobMap.clear()
            pageJobsMutex.withLock {
                activePageJobs.values.forEach { it.cancel() }
                activePageJobs.clear()
            }
            if (readerGeneration.get() != loadGeneration) return@launch
            _readerPages.value = emptyList()
            _readerLoading.value = true
            _readerError.value = null
            _translatedPagesMap.value = emptyMap()
            _detectedBubblesMap.value = emptyMap()
            _maskedPagesMap.value = emptyMap()
            _currentPageIndex.value = 0
            pageBitmapCache.evictAll()

            try {
                val quick = quickReadPayload
                if (chapterId.startsWith("webquick_") && quick != null && quick.chapter.id == chapterId) {
                    if (readerGeneration.get() != loadGeneration) return@launch
                    _activeChapter.value = quick.chapter
                    _readerManga.value = quick.manga
                    _readerChapters.value = listOf(quick.chapter)
                    _readerPages.value = quick.pages
                    _currentPageIndex.value = 0
                    _readerLoading.value = false
                    return@launch
                }
                val dbChapter = chapterDao.getChapterById(chapterId)
                if (readerGeneration.get() != loadGeneration) return@launch
                if (dbChapter == null) {
                    _readerError.value = if (chapterId.startsWith("webquick_")) {
                        "Hızlı okuma oturumu sona erdi. Tarayıcıdan yeniden başlatın."
                    } else {
                        "Bölüm veritabanında bulunamadı."
                    }
                    return@launch
                }
                var dbManga = mangaDao.getMangaById(dbChapter.mangaId)
                if (readerGeneration.get() != loadGeneration) return@launch
                if (dbManga == null) {
                    val originLanguage = if (dbChapter.id.contains("mangafire_")) "Japonca"
                    else if (dbChapter.id.contains("manhuaplus_")) "Çince"
                    else if (dbChapter.id.contains("webtoonxyz_")) "Korece"
                    else "İngilizce"

                    val srcName = if (dbChapter.id.contains("mangafire_")) "MangaFire"
                    else if (dbChapter.id.contains("manhuaplus_")) "ManhuaPlus"
                    else if (dbChapter.id.contains("webtoonxyz_")) "WebtoonXYZ"
                    else "MangaDex"

                    dbManga = MangaEntity(
                        id = dbChapter.mangaId,
                        title = "Manga",
                        coverUrl = "",
                        author = "Bilinmiyor",
                        artist = "Bilinmiyor",
                        description = "",
                        status = "Devam Ediyor",
                        rating = 4.5,
                        sourceName = srcName,
                        originLanguage = originLanguage,
                        isFavorite = false,
                        addedAt = System.currentTimeMillis()
                    )
                }
                _readerManga.value = dbManga
                val chapterList = chapterDao.getChaptersByMangaIdSync(dbChapter.mangaId)
                val pages: List<String>
                if (dbChapter.isDownloaded && dbChapter.localImagePathList.isNotBlank()) {
                    pages = PagePathCodec.decode(dbChapter.localImagePathList)
                } else {
                    val srcName = dbManga.sourceName
                    val src = MangaSourceRegistry.getSource(srcName)
                    val cacheable = chapterId.startsWith("webch_") || chapterId.startsWith("comick_ch_")
                    val cachedPages: List<String>? = if (cacheable) {
                        val cached = runCatching { database.webPageCacheDao().get(chapterId) }.getOrNull()
                        if (cached != null && System.currentTimeMillis() - cached.fetchedAt <= WEB_PAGE_CACHE_TTL_MS) {
                            runCatching {
                                val array = JSONArray(cached.urlsJson)
                                (0 until array.length()).mapNotNull { index ->
                                    array.optString(index).takeIf { url -> url.startsWith("http") }
                                }
                            }.getOrNull()?.takeIf { urls -> urls.isNotEmpty() }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                    if (cachedPages != null) {
                        pages = cachedPages
                    } else {
                        val fetched = src.getChapterPages(chapterId)
                        val webChapter = chapterId.startsWith("webch_") || chapterId.startsWith("webquick_")
                        if (webChapter && fetched.size < 2) {
                            throw IOException(
                                "Bu site sayfa görsellerini gizli/korumalı okuyucuyla sunuyor; " +
                                    "doğrudan çekilemiyor. 'Tarayıcıda Aç' düğmesiyle site okuyucusunda açabilirsiniz."
                            )
                        }
                        if (fetched.isEmpty()) {
                            throw IOException("$srcName kaynağından hiçbir sayfa bulunamadı.")
                        }
                        if (cacheable && fetched.size >= 3) {
                            val urlsJson = JSONArray(fetched).toString()
                            runCatching {
                                database.webPageCacheDao().put(
                                    WebPageCacheEntity(chapterId = chapterId, urlsJson = urlsJson)
                                )
                            }
                        }
                        pages = fetched
                    }
                }
                if (pages.isEmpty()) {
                    throw IOException("Bölümde yüklenecek sayfa bulunamadı.")
                }

                if (readerGeneration.get() != loadGeneration) return@launch
                _activeChapter.value = dbChapter
                _readerManga.value = dbManga
                _readerChapters.value = chapterList
                _readerPages.value = pages
                _currentPageIndex.value = dbChapter.readPage.coerceIn(0, pages.size - 1)
                recordHistory(_currentPageIndex.value)

                preferences.autoTranslate.first().let { auto ->
                    if (auto || _translationActive.value) {
                        toggleLiveTranslation(true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                if (readerGeneration.get() != loadGeneration) return@launch
                beginCloudflareVerification(e.host, e.challengedUrl) {
                    loadChapterInReader(chapterId)
                }
                _readerError.value = "Cloudflare doğrulaması gerekiyor: ${e.host}. Açılan pencerede doğrulayınca bölüm otomatik yüklenecek."
            } catch (t: Throwable) {
                if (readerGeneration.get() != loadGeneration) return@launch
                val srcName = _readerManga.value?.sourceName ?: "Bilinmeyen"
                val errMsg = "Bölüm yüklenemedi ($srcName): ${t.detailedMessage()}"
                _readerError.value = errMsg
                _isTranslationLoading.value = false
                inProgressPages.clear()
            } finally {
                if (readerGeneration.get() == loadGeneration) _readerLoading.value = false
            }
        }
    }

    fun onPageIndexChanged(index: Int) {
        _currentPageIndex.value = index
        recordHistory(index)
        prefetchNeighborPages(index)
        viewModelScope.launch {
            val ch = _activeChapter.value ?: return@launch
            chapterDao.updateReadPage(ch.id, index)
            if (index == _readerPages.value.size - 1) {
                chapterDao.updateReadStatus(ch.id, true)
            }
        }
    }

    private val prefetchJobMap = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    private val translationJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var translationSequenceJob: Job? = null
    private var detectionPrefetchJob: Job? = null
    private var ppOcrPrewarmJob: Job? = null
    @Volatile private var readerVisible = false
    private val bitmapDecodeSemaphore = Semaphore(2)
    private val prefetchDecodeSemaphore = Semaphore(1)

    private companion object {
        const val DETECTION_PREFETCH_AHEAD = 6
        const val TRANSLATION_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }

    @Volatile private var translationPipelineCursor = 0

    /**
     * PP-OCRv5 model extraction and ORT session creation are blocking. Warm the
     * engine up before page OCR so the first page does not pay that cost while
     * holding the translation pipeline.
     */
    private fun prewarmPpOcrIfNeeded() {
        if (ppOcrPrewarmJob?.isActive == true) return
        ppOcrPrewarmJob = viewModelScope.launch(Dispatchers.IO + viewModelExceptionHandler) {
            try {
                if (preferences.ocrEngine.first().equals("PP-OCRv5", ignoreCase = true)) {
                    val ready = translationService.prewarmOcrEngine("PP-OCRv5")
                    if (ready) {
                        Log.d("TR", "PP-OCRv5 prewarm tamamlandı")
                    } else {
                        Log.w("TR", "PP-OCRv5 prewarm tamamlanamadı")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("TR", "PP-OCRv5 prewarm başarısız: ${t.message}")
            }
        }
    }

    /** Translate pages in chapter order so scrolling cannot reorder or cancel the work. */
    private fun startSequentialTranslation() {
        val generation = readerGeneration.get()
        if (translationSequenceJob?.isActive == true) return

        val sequence = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val pageCount = _readerPages.value.size
                for (pageIndex in 0 until pageCount) {
                    currentCoroutineContext().ensureActive()
                    if (readerGeneration.get() != generation || !_translationActive.value) break
                    if (_translatedPagesMap.value.containsKey(pageIndex)) continue

                    val progressKey = "$generation:$pageIndex"
                    translationPipelineCursor = pageIndex
                    translatePageIfNeeded(pageIndex)
                    translationJobs[progressKey]?.join()
                }
            } finally {
                if (translationSequenceJob === currentCoroutineContext()[Job]) {
                    translationSequenceJob = null
                }
            }
        }
        translationSequenceJob = sequence
        sequence.start()
        startDetectionPrefetch(generation)
    }

    /**
     * Yalnızca balon tespiti (YOLO) sıradaki sayfalar için önceden çalıştırılır ve
     * sonuç `_detectedBubblesMap`'e yazılır; işi biten tespit işçisi hemen bir
     * sonraki sayfaya geçer. OCR/çeviri/onarım hattı ise değişmediği gibi sayfa
     * sayfa sırayla kalır. Ön-tarama, çeviri hattının en fazla
     * [DETECTION_PREFETCH_AHEAD] sayfa önünden ilerler ki gereksiz sayfa indirme
     * ve bellek kullanımı olmasın.
     */
    private fun startDetectionPrefetch(generation: Long) {
        if (detectionPrefetchJob?.isActive == true) return
        detectionPrefetchJob = viewModelScope.launch(Dispatchers.Default + viewModelExceptionHandler) {
            try {
                val pageCount = _readerPages.value.size
                for (pageIndex in 0 until pageCount) {
                    currentCoroutineContext().ensureActive()
                    if (readerGeneration.get() != generation || !_translationActive.value) break
                    if (!preferences.yoloAiEnabled.first()) break
                    if (_detectedBubblesMap.value.containsKey(pageIndex)) continue
                    if (pageIndex < translationPipelineCursor) continue

                    while (pageIndex > maxOf(translationPipelineCursor, _currentPageIndex.value) + DETECTION_PREFETCH_AHEAD &&
                        readerGeneration.get() == generation && _translationActive.value
                    ) {
                        delay(250)
                    }
                    if (readerGeneration.get() != generation || !_translationActive.value) break
                    if (_detectedBubblesMap.value.containsKey(pageIndex)) continue

                    val bitmap = getPageBitmap(pageIndex, fullRes = true) ?: getPageBitmap(pageIndex) ?: continue
                    val confThreshold = preferences.confidenceThreshold.first()
                    val detections: List<BubbleBox> = try {
                        bubbleDetector.detectBubbles(bitmap, pageIndex, confThreshold)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        emptyList()
                    }
                    _detectedBubblesMap.update { current ->
                        if (readerGeneration.get() == generation && !current.containsKey(pageIndex)) {
                            current + (pageIndex to detections)
                        } else {
                            current
                        }
                    }
                }
            } finally {
                if (detectionPrefetchJob === currentCoroutineContext()[Job]) {
                    detectionPrefetchJob = null
                }
            }
        }
    }

    private fun prefetchNeighborPages(currentIndex: Int) {
        val pages = _readerPages.value
        val validIndices = (currentIndex - 1..currentIndex + 1)
            .filter { it in pages.indices }
            .toSet()

        prefetchJobMap.entries.toList()
            .filter { it.key !in validIndices }
            .forEach { (index, job) ->
                job.cancel()
                prefetchJobMap.remove(index)
        }

        val range = currentIndex - 1..currentIndex + 1
        val chapterId = _activeChapter.value?.id ?: return
        for (i in range) {
            if (i in pages.indices) {
                if (prefetchJobMap[i]?.isActive != true) {
                    prefetchJobMap[i] = viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val quality = preferences.pageQuality.first()
                            val cacheKey = pageCacheKey(chapterId, i, quality, fullRes = false)
                            if (pageBitmapCache.get(cacheKey) != null) return@launch
                            val path = pages[i]
                            prefetchDecodeSemaphore.withPermit {
                                loadCachedPageBitmap(cacheKey, path, fullRes = false, quality = quality)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Ignored
                        } finally {
                            if (prefetchJobMap[i] === coroutineContext[Job]) {
                                prefetchJobMap.remove(i)
                            }
                        }
                    }
                }
            }
        }
    }

    private val activePageJobs = mutableMapOf<String, Deferred<Bitmap?>>()
    private val pageJobsMutex = Mutex()

    private fun pageCacheKey(chapterId: String, index: Int, quality: String, fullRes: Boolean): String {
        // The same page can be decoded at Low, Medium, High, or OCR full
        // resolution. Keep those bitmaps separate so a preference change cannot
        // reuse a bitmap decoded with an older, lower quality profile.
        val profile = if (fullRes && quality != "Yüksek") {
            "full"
        } else {
            when (quality) {
                "Düşük" -> "low"
                "Yüksek" -> "high"
                else -> "medium"
            }
        }
        return "${chapterId}_${index}_$profile"
    }

    suspend fun getPageBitmap(index: Int, fullRes: Boolean = false): Bitmap? {
        val chId = _activeChapter.value?.id ?: return null
        return getPageBitmap(chId, index, fullRes)
    }

    suspend fun getPageBitmap(chapterId: String, index: Int, fullRes: Boolean = false): Bitmap? {
        val generation = readerGeneration.get()
        if (_activeChapter.value?.id != chapterId) return null
        val pages = _readerPages.value
        val path = pages.getOrNull(index) ?: return null
        val quality = preferences.pageQuality.first()
        if (readerGeneration.get() != generation ||
            _activeChapter.value?.id != chapterId ||
            _readerPages.value !== pages
        ) return null
        val effectiveFullRes = fullRes && quality != "Yüksek"
        val cacheKey = pageCacheKey(chapterId, index, quality, effectiveFullRes)
        val bitmap = loadCachedPageBitmap(cacheKey, path, effectiveFullRes, quality)
        return bitmap?.takeIf {
            !it.isRecycled &&
                readerGeneration.get() == generation &&
                _activeChapter.value?.id == chapterId
        }
    }

    private suspend fun loadCachedPageBitmap(cacheKey: String, path: String, fullRes: Boolean, quality: String): Bitmap? {
        val cached = pageBitmapCache.get(cacheKey)
        if (cached != null) {
            if (!cached.isRecycled) return cached
            pageBitmapCache.remove(cacheKey)
        }

        val deferred = pageJobsMutex.withLock {
            val existing = activePageJobs[cacheKey]
            if (existing != null && !existing.isCancelled) {
                existing
            } else {
                val newJob = viewModelScope.async(Dispatchers.IO) {
                    try {
                        val bmp = bitmapDecodeSemaphore.withPermit {
                            loadAndScaleBitmap(path, fullRes = fullRes, quality = quality)
                        }
                        if (bmp != null) {
                            pageBitmapCache.put(cacheKey, bmp)
                        }
                        bmp
                    } finally {
                        pageJobsMutex.withLock {
                            if (activePageJobs[cacheKey] === currentCoroutineContext()[Job]) {
                                activePageJobs.remove(cacheKey)
                            }
                        }
                    }
                }
                activePageJobs[cacheKey] = newJob
                newJob
            }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadAndScaleBitmap(path: String, fullRes: Boolean = false, quality: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val maxPixels = when {
                fullRes -> 8_000_000L
                quality == "Düşük" -> 2_500_000L
                quality == "Yüksek" -> 8_000_000L
                else -> 5_000_000L
            }
            fun sampleFor(width: Int, height: Int): Int {
                if (width <= 0 || height <= 0) return 4
                var sample = 1
                while (true) {
                    val sampledWidth = width / sample
                    val sampledHeight = height / sample
                    if (sampledWidth <= 0 || sampledHeight <= 0 ||
                        (sampledWidth.toLong() * sampledHeight.toLong() <= maxPixels &&
                            maxOf(sampledWidth, sampledHeight) <= 8192)
                    ) break
                    sample *= 2
                }
                return sample
            }
            fun decodeLocal(filePath: String): Bitmap? {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, bounds)
                return BitmapFactory.decodeFile(filePath, BitmapFactory.Options().apply {
                    inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
            if (path.startsWith("/") || path.startsWith("file://")) {
                val cleaned = path.removePrefix("file://")
                decodeLocal(cleaned)
            } else {
                val tempFile = File.createTempFile("manga_page_", ".img", context.cacheDir)
                try {
                    val downloaded = NetworkManager.downloadPageStream(path)?.use { input ->
                        tempFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            val ioContext = currentCoroutineContext()
                            while (true) {
                                ioContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        true
                    } ?: false
                    if (downloaded) decodeLocal(tempFile.absolutePath) else null
                } finally {
                    tempFile.delete()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareChallengeException) {
            beginCloudflareVerification(e.host, e.challengedUrl) {
                _readerPageRetryTick.value += 1
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun toggleLiveTranslation(active: Boolean) {
        _translationActive.value = active
        if (active) {
            prewarmPpOcrIfNeeded()
            startSequentialTranslation()
        } else {
            detectionPrefetchJob?.cancel()
            detectionPrefetchJob = null
            val sequence = translationSequenceJob
            translationSequenceJob = null
            sequence?.cancel()
            val generationPrefix = "${readerGeneration.get()}:"
            translationJobs.entries
                .filter { it.key.startsWith(generationPrefix) }
                .forEach { it.value.cancel() }
        }
    }

    /** Uygulama arka plana alınınca bekleyen çeviri işlerini durdurur (işlem süreci öldürülmeden devam etmesin). */
    fun pauseBackgroundTranslation() {
        detectionPrefetchJob?.cancel()
        detectionPrefetchJob = null
        val sequence = translationSequenceJob
        translationSequenceJob = null
        sequence?.cancel()
        translationJobs.values.toList().forEach { it.cancel() }
        translationJobs.clear()
        _isTranslationLoading.value = false
    }

    /** Uygulama geri geldiğinde (okuyucu açıksa) çeviri açıksa kaldığı yerden devam eder. */
    fun resumeForegroundTranslation() {
        if (readerVisible) resumeReaderTranslation()
    }

    /**
     * Okuyucu görünürlüğü değişimini işler. Okuyucudan çıkılınca arka planda kalan
     * çeviri/tarama işleri durur; tamamlanan sayfalar önbellekte kalır ve okuyucuya
     * dönülünce çeviri kaldığı yerden sürer.
     */
    fun onReaderVisibilityChanged(visible: Boolean) {
        val changed = readerVisible != visible
        readerVisible = visible
        if (changed && !visible) stopReaderBackgroundWork()
    }

    /** Okuyucuya yeniden girildiğinde (aynı bölüm açıkken) çeviriyi sürdürür. */
    fun resumeReaderTranslation() {
        if (_translationActive.value && _activeChapter.value != null) {
            startSequentialTranslation()
        }
    }

    private fun stopReaderBackgroundWork() {
        detectionPrefetchJob?.cancel()
        detectionPrefetchJob = null
        val sequence = translationSequenceJob
        translationSequenceJob = null
        sequence?.cancel()
        val generationPrefix = "${readerGeneration.get()}:"
        translationJobs.entries
            .filter { it.key.startsWith(generationPrefix) }
            .forEach { it.value.cancel() }
        inProgressPages.removeAll(
            inProgressPages.filter { it.startsWith(generationPrefix) }.toSet()
        )
        prefetchJobMap.values.toList().forEach { it.cancel() }
        prefetchJobMap.clear()
        _isTranslationLoading.value = false
    }

    fun setTranslationEngine(engine: String) {
        viewModelScope.launch {
            if (preferences.translationEngine.first() == engine) return@launch

            preferences.setTranslationEngine(engine)

            val currentIdx = _currentPageIndex.value
            val progressKey = "${readerGeneration.get()}:$currentIdx"
            translationJobs[progressKey]?.let { currentJob ->
                currentJob.cancelAndJoin()
            }

            _translatedPagesMap.update { it - currentIdx }
            if (_translationActive.value && _activeChapter.value != null) {
                translatePageIfNeeded(currentIdx, forceReload = true)
            }
        }
    }

    fun setOcrEngine(engine: String) {
        viewModelScope.launch {
            if (preferences.ocrEngine.first() == engine) return@launch
            preferences.setOcrEngine(engine)
            if (engine.equals("PP-OCRv5", ignoreCase = true)) {
                prewarmPpOcrIfNeeded()
            }

            val currentIdx = _currentPageIndex.value
            val sequence = translationSequenceJob
            translationSequenceJob = null
            sequence?.cancelAndJoin()
            val oldJobs = translationJobs.values.toList()
            translationJobs.clear()
            oldJobs.forEach { it.cancelAndJoin() }
            inProgressPages.clear()
            translationPipelineCursor = 0
            _translatedPagesMap.value = emptyMap()
            _maskedPagesMap.value = emptyMap()
            _isTranslationLoading.value = false

            if (_translationActive.value && _activeChapter.value != null) {
                if (currentIdx in 0 until _readerPages.value.size) {
                    translatePageIfNeeded(currentIdx, forceReload = true)
                }
                startSequentialTranslation()
            }
        }
    }

    fun setTranslationTargetLanguage(code: String) {
        val resolved = TranslationLanguages.find(code)?.code ?: return
        viewModelScope.launch {
            val previous = preferences.translationTargetLang.first()
            preferences.setTranslationTargetLang(resolved)
            preferences.addRecentTranslationLang(resolved)
            if (previous != resolved) invalidateCurrentPageTranslation()
        }
    }

    fun setTranslationSourceLanguage(code: String) {
        val resolved = if (code == TranslationLanguages.AUTO) {
            TranslationLanguages.AUTO
        } else {
            TranslationLanguages.find(code)?.code ?: return
        }
        viewModelScope.launch {
            val previous = preferences.translationSourceLang.first()
            preferences.setTranslationSourceLang(resolved)
            if (resolved != TranslationLanguages.AUTO) {
                preferences.addRecentTranslationLang(resolved)
            }
            if (previous != resolved) invalidateCurrentPageTranslation()
        }
    }

    private suspend fun invalidateCurrentPageTranslation() {
        val currentIdx = _currentPageIndex.value
        val progressKey = "${readerGeneration.get()}:$currentIdx"
        translationJobs[progressKey]?.cancelAndJoin()
        _translatedPagesMap.update { it - currentIdx }
        if (_translationActive.value && _activeChapter.value != null) {
            translatePageIfNeeded(currentIdx, forceReload = true)
        }
    }

    private val inProgressPages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val readerGeneration = AtomicLong(0L)
    private val ocrExecutionMutex = Mutex()
    private val viewModelExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e("MangaViewModel", "Beklenmeyen coroutine hatası", throwable)
            _isTranslationLoading.value = false
            inProgressPages.clear()
            _readerLoading.value = false
        }
    }

    // ---- Kalıcı çeviri önbelleği (optimize sağlayıcılar ve Yerel) ----

    val translationCacheStats: StateFlow<List<TranslationCacheStat>> =
        translationCacheDao.statsBySource()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun isCacheableSource(chapterId: String, sourceName: String): Boolean {
        if (chapterId.startsWith("webquick_") || chapterId.startsWith("webch_")) return false
        if (sourceName.startsWith("Web: ")) return false
        return true
    }

    fun clearTranslationCache(sourceName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paths = if (sourceName == null) translationCacheDao.allPaths()
                else translationCacheDao.pathsBySource(sourceName)
                for (path in paths) {
                    runCatching { File(path).delete() }
                }
                if (sourceName == null) translationCacheDao.deleteAll()
                else translationCacheDao.deleteBySource(sourceName)
                Log.d("TR", "Çeviri önbelleği temizlendi: ${sourceName ?: "TÜMÜ"} (${paths.size} sayfa)")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w("TR", "Çeviri önbelleği temizlenemedi: ${t.message}")
            }
        }
    }

    private suspend fun resolveSourceDisplayForCache(): String {
        val sourcePref = preferences.translationSourceLang.first()
        if (sourcePref != TranslationLanguages.AUTO) {
            TranslationLanguages.turkishName(sourcePref)?.let { return it }
        }
        return _readerManga.value?.originLanguage ?: run {
            val ch = _activeChapter.value
            when {
                ch?.id?.contains("mangafire_") == true -> "Japonca"
                ch?.id?.contains("manhuaplus_") == true -> "Çince"
                ch?.id?.contains("webtoonxyz_") == true -> "Korece"
                else -> "İngilizce"
            }
        }
    }

    /** Çeviri önbelleği dil bazlı ayrılır: aynı sayfa farklı hedef dillerde ayrı saklanır. */
    private suspend fun translationCacheId(chapterId: String): Pair<String, String> {
        val target = preferences.translationTargetLang.first()
        val ocrEngine = preferences.ocrEngine.first()
        val sourceDisplay = resolveSourceDisplayForCache()
        val sourceCode = TranslationLanguages.codeForDisplayName(sourceDisplay) ?: "en"
        return "$chapterId|$sourceCode>$target|$ocrEngine" to target
    }

    private suspend fun loadCachedTranslation(pageIdx: Int, generation: Long): Boolean {
        val chapterId = _activeChapter.value?.id ?: return false
        val sourceName = _readerManga.value?.sourceName ?: return false
        if (!isCacheableSource(chapterId, sourceName)) return false
        val pageUrl = _readerPages.value.getOrNull(pageIdx) ?: return false
        val (cacheId, targetLangCode) = translationCacheId(chapterId)
        val row = withContext(Dispatchers.IO) {
            translationCacheDao.getCachedPage(cacheId, pageIdx)
        } ?: return false
        if (System.currentTimeMillis() - row.createdAt > TRANSLATION_CACHE_TTL_MS) {
            // Bir günden eski çeviri önbelleği geçersiz: kaydı ve görseli temizle, yeniden çevrilsin
            withContext(Dispatchers.IO) {
                runCatching { File(row.maskedPath).delete() }
                runCatching { translationCacheDao.deletePage(cacheId, pageIdx) }
            }
            return false
        }
        if (row.pageUrl != pageUrl) return false
        val file = File(row.maskedPath)
        if (!file.exists()) {
            withContext(Dispatchers.IO) {
                translationCacheDao.deletePage(cacheId, pageIdx)
            }
            return false
        }
        val cachedBlocks = withContext(Dispatchers.IO) {
            runCatching { TranslationCacheCodec.deserializeBlocks(row.blocksJson) }
                .getOrDefault(emptyList())
        }
        if (cachedBlocks.isEmpty()) return false
        val loadedBitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(row.maskedPath) }.getOrNull()
        }
        if (loadedBitmap == null) {
            withContext(Dispatchers.IO) {
                translationCacheDao.deletePage(cacheId, pageIdx)
            }
            return false
        }
        if (readerGeneration.get() != generation) return false

        // Sonradan yapılan düzeltmeleri önbellekteki metinlere de uygula.
        val blocks = cachedBlocks.map { block ->
            if (block.originalText.isBlank()) return@map block
            val corrected = translationService.lookupCorrection(block.originalText, targetLangCode)
            if (corrected != null && corrected.isNotBlank() && corrected != block.translatedText) {
                block.copy(translatedText = corrected)
            } else {
                block
            }
        }

        _translatedPagesMap.update { current ->
            if (readerGeneration.get() != generation) current else current + (pageIdx to blocks)
        }
        storeMaskedPage(pageIdx, loadedBitmap, generation)
        Log.d("TR", "Sayfa $pageIdx önbellekten yüklendi ($cacheId)")
        return true
    }

    private suspend fun persistTranslatedPage(pageIdx: Int, blocks: List<TextBlockObj>, masked: Bitmap) {
        val chapterId = _activeChapter.value?.id ?: return
        val sourceName = _readerManga.value?.sourceName ?: return
        if (!isCacheableSource(chapterId, sourceName)) return
        val pageUrl = _readerPages.value.getOrNull(pageIdx) ?: return
        val (cacheId, _) = translationCacheId(chapterId)
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "translation_cache").apply { mkdirs() }
                val file = File(dir, sha1Hex(cacheId).take(16) + "_" + pageIdx + ".webp")
                FileOutputStream(file).use { out ->
                    masked.compress(translationCacheFormat(), 80, out)
                }
                translationCacheDao.put(
                    TranslatedPageCacheEntity(
                        chapterId = cacheId,
                        pageIndex = pageIdx,
                        pageUrl = pageUrl,
                        sourceName = sourceName,
                        blocksJson = TranslationCacheCodec.serializeBlocks(blocks),
                        maskedPath = file.absolutePath,
                        byteSize = file.length(),
                        createdAt = System.currentTimeMillis()
                    )
                )
                pruneTranslationCache()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w("TR", "Çeviri önbelleği yazılamadı: ${t.message}")
            }
        }
    }

    private suspend fun pruneTranslationCache() {
        purgeExpiredTranslationCache()
        val budgetBytes = 450L * 1024L * 1024L
        var total = translationCacheDao.totalBytes()
        if (total <= budgetBytes) return
        while (total > budgetBytes) {
            val oldest = translationCacheDao.oldest(40)
            if (oldest.isEmpty()) break
            for (row in oldest) {
                runCatching { File(row.maskedPath).delete() }
                translationCacheDao.deletePage(row.chapterId, row.pageIndex)
                total -= row.byteSize
            }
        }
        Log.d("TR", "Çeviri önbelleği budandı, kalan: ${total / 1048576} MB")
    }

    /** Bir günden eski tüm çeviri önbelleği kayıtlarını ve görsellerini siler. */
    private suspend fun purgeExpiredTranslationCache() {
        val cutoff = System.currentTimeMillis() - TRANSLATION_CACHE_TTL_MS
        val expired = withContext(Dispatchers.IO) {
            runCatching { translationCacheDao.expired(cutoff) }.getOrDefault(emptyList())
        }
        if (expired.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (row in expired) {
                runCatching { File(row.maskedPath).delete() }
            }
            runCatching { translationCacheDao.deleteExpired(cutoff) }
        }
        Log.d("TR", "Çeviri önbelleğinden ${expired.size} eski sayfa silindi (24 saat sınırı)")
    }

    private fun translationCacheFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    /** Eski JPEG önbellek görsellerini yerinde WEBP'ye çevirir; dosya adı korunur (yaklaşık %35 alan tasarrufu). */
    private fun optimizeTranslationCacheStorage(maxPerLaunch: Int = 300) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var processed = 0
                var converted = 0
                while (processed < maxPerLaunch) {
                    val batch = translationCacheDao.cachedPagesBySuffix(".jpg", 60)
                    if (batch.isEmpty()) break
                    for (row in batch) {
                        if (processed >= maxPerLaunch) break
                        processed++
                        val file = File(row.maskedPath)
                        if (!file.exists()) {
                            translationCacheDao.deletePage(row.chapterId, row.pageIndex)
                            continue
                        }
                        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                        if (bitmap == null) {
                            runCatching { file.delete() }
                            translationCacheDao.deletePage(row.chapterId, row.pageIndex)
                            continue
                        }
                        val tmp = File(file.parentFile, file.name + ".webp.tmp")
                        val ok = runCatching {
                            FileOutputStream(tmp).use { out ->
                                bitmap.compress(translationCacheFormat(), 80, out)
                            }
                            tmp.length() > 0
                        }.getOrDefault(false)
                        bitmap.recycle()
                        if (ok && tmp.renameTo(file)) {
                            translationCacheDao.updateByteSize(row.chapterId, row.pageIndex, file.length())
                            converted++
                        } else {
                            runCatching { tmp.delete() }
                        }
                    }
                }
                if (processed > 0) {
                    Log.d("TR", "Çeviri önbelleği optimize edildi: $converted/$processed sayfa WEBP'ye çevrildi")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w("TR", "Önbellek optimizasyonu başarısız: ${t.message}")
            }
        }
    }

    private fun storeMaskedPage(pageIdx: Int, bitmap: Bitmap, generation: Long) {
        _maskedPagesMap.update { current ->
            if (readerGeneration.get() != generation) current else {
                val next = linkedMapOf<Int, Bitmap>()
                var retainedBytes = 0L
                val candidates = (current + (pageIdx to bitmap)).entries
                    .sortedBy { kotlin.math.abs(it.key - pageIdx) }
                for ((index, candidate) in candidates) {
                    if (kotlin.math.abs(index - pageIdx) > 2) continue
                    val bytes = candidate.allocationByteCount.toLong()
                    if (index == pageIdx || retainedBytes + bytes <= maskedPageBudgetBytes) {
                        next[index] = candidate
                        retainedBytes += bytes
                    }
                }
                next
            }
        }
    }

    /**
     * Bölge OCR için YOLO kutularını sadeleştirir: balon kutuları esas alınır,
     * balonla örtüşen metin kutuları ve başka bir bölgeyle çok örtüşen kopyalar
     * elenir. Yalnızca balon içi metin bölgeleri döner; text_free hariç tutulur.
     */
    private fun buildBubbleOcrRegions(bubbles: List<BubbleBox>, width: Int, height: Int): List<Rect> {
        if (bubbles.isEmpty() || width <= 0 || height <= 0) return emptyList()

        fun areaOf(rect: Rect): Long = rect.width().toLong() * rect.height()
        fun overlapArea(a: Rect, b: Rect): Long {
            val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
            val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
            return w.toLong() * h
        }

        val balloonLabels = setOf("bubble", "balloon")
        val textLabels = setOf("text_bubble", "dialogue_text", "text")
        val balloonRects = bubbles.filter { it.label in balloonLabels }
            .map { it.toPixelRect(width, height) }
            .filter { !it.isEmpty }
        val textRects = bubbles.filter { it.label in textLabels }
            .map { it.toPixelRect(width, height) }
            .filter { !it.isEmpty }

        val candidates = mutableListOf<Rect>()
        candidates.addAll(balloonRects)
        for (textRect in textRects) {
            val alreadyCovered = candidates.any { overlapArea(it, textRect) >= areaOf(textRect) * 0.5f }
            if (!alreadyCovered) candidates.add(textRect)
        }

        val pruned = mutableListOf<Rect>()
        for (candidate in candidates) {
            val duplicate = pruned.any { existing -> overlapArea(existing, candidate) >= areaOf(candidate) * 0.7f }
            if (!duplicate) pruned.add(candidate)
        }
        return pruned
    }

    /**
     * PP-OCRv5 recognition is line-oriented, so prefer the detector's
     * text_bubble boxes over the larger balloon boxes. The line segmenter
     * splits each text block into line crops. If the model returned no text
     * boxes, fall back to balloon boxes.
     */
    private fun buildPpOcrV5Regions(bubbles: List<BubbleBox>, width: Int, height: Int): List<Rect> {
        if (bubbles.isEmpty() || width <= 0 || height <= 0) return emptyList()

        fun areaOf(rect: Rect): Long = rect.width().toLong() * rect.height()
        fun overlapArea(a: Rect, b: Rect): Long {
            val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
            val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
            return w.toLong() * h
        }

        fun dedupe(rects: List<Rect>): List<Rect> {
            val result = mutableListOf<Rect>()
            for (rect in rects) {
                if (rect.isEmpty) continue
                val duplicate = result.any { existing ->
                    overlapArea(existing, rect).toFloat() >=
                        minOf(areaOf(existing), areaOf(rect)).toFloat() * 0.70f
                }
                if (duplicate) continue
                result.add(rect)
            }
            return result
        }

        val textLabels = setOf("text_bubble", "dialogue_text", "text")
        val balloonLabels = setOf("bubble", "balloon")
        val textRects = bubbles.filter { it.label in textLabels }
            .map { it.toPixelRect(width, height) }
            .filter { !it.isEmpty }
        val balloonRects = bubbles.filter { it.label in balloonLabels }
            .map { it.toPixelRect(width, height) }
            .filter { !it.isEmpty }

        // Prefer tight text boxes, but never drop a balloon that has no text box.
        // The detector does not guarantee a text_bubble detection for every balloon.
        val candidates = mutableListOf<Rect>()
        candidates.addAll(textRects)
        for (balloon in balloonRects) {
            val coveredByText = textRects.any { text ->
                overlapArea(balloon, text) >= areaOf(text) * 0.5f
            }
            if (!coveredByText) candidates.add(balloon)
        }
        return dedupe(candidates)
    }

    fun translatePageIfNeeded(pageIdx: Int, forceReload: Boolean = false) {
        Log.d("TR", "translatePageIfNeeded çağrıldı: sayfa=$pageIdx, forceReload=$forceReload")
        val generation = readerGeneration.get()
        val progressKey = "$generation:$pageIdx"
        if (!forceReload && _translatedPagesMap.value.containsKey(pageIdx)) {
            Log.d("TR", "Sayfa $pageIdx zaten çevrili, işlem atlandı")
            return
        }
        if (!inProgressPages.add(progressKey)) {
            Log.d("TR", "Sayfa $pageIdx şu an zaten işleniyor (inProgressPages).")
            return
        }
        val translationJob = viewModelScope.launch(
            Dispatchers.Default + viewModelExceptionHandler,
            start = CoroutineStart.LAZY
        ) {
            if (readerGeneration.get() == generation) _isTranslationLoading.value = true
            try {
                if (readerGeneration.get() != generation) return@launch
                if (!forceReload && loadCachedTranslation(pageIdx, generation)) return@launch
                val bitmap = getPageBitmap(pageIdx, fullRes = true) ?: getPageBitmap(pageIdx)
                if (bitmap == null) {
                    Log.d("TR", "Sayfa $pageIdx için bitmap alınamadı (null)!")
                    return@launch
                }
                val targetLangCode = preferences.translationTargetLang.first()
                val ocrEngine = preferences.ocrEngine.first()
                val sourcePref = preferences.translationSourceLang.first()
                val forcedSourceCode = if (sourcePref == TranslationLanguages.AUTO) null else sourcePref
                val metadataOriginLang = _readerManga.value?.originLanguage ?: run {
                    val ch = _activeChapter.value
                    if (ch?.id?.contains("mangafire_") == true) "Japonca"
                    else if (ch?.id?.contains("manhuaplus_") == true) "Çince"
                    else if (ch?.id?.contains("webtoonxyz_") == true) "Korece"
                    else "İngilizce"
                }
                val originLang = forcedSourceCode?.let {
                    TranslationLanguages.turkishName(it) ?: metadataOriginLang
                } ?: metadataOriginLang

                val originCode = TranslationLanguages.codeForDisplayName(originLang)
                if (ocrEngine.equals("PP-OCRv5", ignoreCase = true)) {
                    if (originCode == "zh" || originCode == "ko") {
                        Log.d("TR", "PP-OCRv5: $originLang kaynağı sessizce atlandı (sayfa=$pageIdx)")
                        return@launch
                    }
                    if (originCode != "en" && originCode != "ja") {
                        emitTranslationNotice("PP-OCRv5 yalnızca İngilizce ve Japonca kaynakları destekler.")
                        return@launch
                    }
                }

                val prefEngine = preferences.translationEngine.first()
                val apiK = preferences.geminiApiKey.first()
                val deepLK = preferences.deeplApiKey.first()
                val tC = preferences.turkishCharConvert.first()
                val yoloEnabled = preferences.yoloAiEnabled.first()
                val confThreshold = preferences.confidenceThreshold.first()

                if (bitmap != null) {
                    val bmpW = bitmap.width.toFloat().coerceAtLeast(1f)
                    val bmpH = bitmap.height.toFloat().coerceAtLeast(1f)
                    val timingStart = System.currentTimeMillis()

                    // 1. YOLO açıksa balon/metin kutularını önce topla: bölge OCR
                    // bu kutuların içinde çalışır, tüm sayfa taranmaz.
                    val yoloBubbles = if (yoloEnabled) {
                        val prefetchedBubbles = _detectedBubblesMap.value[pageIdx]
                        if (prefetchedBubbles != null) {
                            Log.d("TR", "Sayfa $pageIdx balon tespiti ön-taramadan alındı (${prefetchedBubbles.size} kutu)")
                            prefetchedBubbles
                        } else {
                            try {
                                bubbleDetector.detectBubbles(bitmap, pageIdx, confThreshold)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                emptyList()
                            }
                        }
                    } else emptyList()
                    val timingAfterDetect = System.currentTimeMillis()

                    // 2. Bölge OCR: yalnızca balon kutuları okunur. Balon dışı
                    // (text_free) yazılar şimdilik çevrilmez ve maskelenmez.
                    val regionMode = yoloEnabled && bubbleDetector.isAiModelReady()
                    val bubbleOcrRegions = if (regionMode) {
                        if (ocrEngine.equals("PP-OCRv5", ignoreCase = true)) {
                            buildPpOcrV5Regions(yoloBubbles, bitmap.width, bitmap.height)
                        } else {
                            buildBubbleOcrRegions(yoloBubbles, bitmap.width, bitmap.height)
                        }
                    } else {
                        emptyList()
                    }
                    if (ocrEngine.equals("PP-OCRv5", ignoreCase = true) && !regionMode) {
                        Log.w("TR", "PP-OCRv5 requires RT-DETR regions; page=$pageIdx skipped")
                        emitTranslationNotice("PP-OCRv5 için RT-DETR açık olmalı; tüm sayfa OCR'ı desteklenmiyor.")
                        return@launch
                    }

                    var ocrTimeoutMs = 120000L
                    val ocrResult: List<TranslationService.RectAndText>? = if (regionMode) {
                        if (bubbleOcrRegions.isEmpty()) {
                            Log.d("TR", "Bölge OCR: sayfa=$pageIdx için balon kutusu yok; metin yok sayılıyor")
                            emptyList()
                        } else {
                            val regionArea = bubbleOcrRegions.sumOf { it.width().toLong() * it.height() }
                            val pageArea = (bitmap.width.toLong() * bitmap.height).coerceAtLeast(1L)
                            ocrTimeoutMs = (6000L + bubbleOcrRegions.size * 2000L).coerceAtMost(120000L)
                            Log.d("TR", "Bölge OCR başladı: sayfa=$pageIdx, ${bubbleOcrRegions.size} bölge (sayfa alanının %${regionArea * 100 / pageArea}'si)")
                            ocrExecutionMutex.withLock {
                                withTimeoutOrNull(ocrTimeoutMs) {
                                    translationService.performRegionOcr(bitmap, bubbleOcrRegions, originLang, ocrEngine)
                                }
                            }
                        }
                    } else {
                        Log.d("TR", "OCR başladı: sayfa=$pageIdx")
                        val estimatedOcrTiles = if (bitmap.height > bitmap.width * 2.2f && bitmap.height > 2400) {
                            val tileStep = 1400 - 450
                            ((bitmap.height - 1400).coerceAtLeast(0) + tileStep - 1) / tileStep + 1
                        } else {
                            1
                        }
                        ocrTimeoutMs = (20000L + (estimatedOcrTiles - 1L) * 10000L).coerceAtMost(120000L)
                        ocrExecutionMutex.withLock {
                            withTimeoutOrNull(ocrTimeoutMs) {
                                translationService.performOcr(bitmap, originLang, ocrEngine)
                            }
                        }
                    }
                    if (ocrResult == null) {
                        Log.w("TR", "OCR zaman aşımına uğradı: sayfa=$pageIdx, süre=${ocrTimeoutMs}ms; sonuç cache'lenmedi ve yeniden denenebilir")
                        return@launch
                    }
                    val ocrBlocks = ocrResult
                    val timingAfterOcr = System.currentTimeMillis()
                    Log.d("TR", "OCR bitti: ${ocrBlocks.size} blok (${timingAfterOcr - timingAfterDetect}ms)")
                    if (ocrBlocks.isEmpty()) {
                        Log.d("TR", "OCR boş liste döndü, sayfada yazı tespit edilmedi.")
                        _translatedPagesMap.update { current ->
                            if (readerGeneration.get() == generation) current + (pageIdx to emptyList()) else current
                        }
                        return@launch
                    }

                    val finalBlocks = mutableListOf<TextBlockObj>()
                    var translationCanBeCached = false

                    data class BubbleCandidate(
                        val targetBox: Rect,
                        val text: String,
                        val textRect: Rect,
                        val isBubble: Boolean,
                        val isDark: Boolean,
                        val bubbleBounds: Rect = targetBox
                    )
                    val candidates = mutableListOf<BubbleCandidate>()
                    // Modelin ham text_free/onomatopeya kutuları. Balon sınırı dar
                    // kaldığında yerleşim alanını ve maske kapsamını büyütmek için
                    // kullanılır (balon içinde kalan orijinal harf kalıntıları dahil).
                    val pageAuxBoxes = mutableListOf<Rect>()

                    // Safety net: when OCR splits one balloon into several blocks,
                    // join vertically adjacent fragments that share the same balloon
                    // so they are translated as a single unit.
                    fun mergeSameBubbleFragments(input: List<BubbleCandidate>): List<BubbleCandidate> {
                        if (input.size <= 1) return input
                        fun areaOf(r: Rect): Long = r.width().toLong() * r.height().toLong()
                        fun sameBalloon(a: Rect, b: Rect): Boolean {
                            if (a.isEmpty || b.isEmpty) return false
                            if (a == b) return true
                            val interW = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
                            val interH = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
                            val inter = interW.toLong() * interH
                            val minArea = minOf(areaOf(a), areaOf(b)).coerceAtLeast(1L)
                            return inter.toFloat() / minArea >= 0.75f
                        }
                        fun tryMerge(a: BubbleCandidate, b: BubbleCandidate): BubbleCandidate? {
                            if (!sameBalloon(a.bubbleBounds, b.bubbleBounds)) return null
                            val lineHeight = minOf(a.textRect.height(), b.textRect.height())
                                .coerceAtLeast(1).toFloat()

                            fun buildMerged(
                                first: BubbleCandidate,
                                second: BubbleCandidate,
                                joinedText: String
                            ): BubbleCandidate = BubbleCandidate(
                                targetBox = Rect(
                                    minOf(first.targetBox.left, second.targetBox.left),
                                    minOf(first.targetBox.top, second.targetBox.top),
                                    maxOf(first.targetBox.right, second.targetBox.right),
                                    maxOf(first.targetBox.bottom, second.targetBox.bottom)
                                ),
                                text = joinedText,
                                textRect = Rect(
                                    minOf(first.textRect.left, second.textRect.left),
                                    minOf(first.textRect.top, second.textRect.top),
                                    maxOf(first.textRect.right, second.textRect.right),
                                    maxOf(first.textRect.bottom, second.textRect.bottom)
                                ),
                                isBubble = first.isBubble || second.isBubble,
                                isDark = first.isDark,
                                bubbleBounds = if (areaOf(first.bubbleBounds) >= areaOf(second.bubbleBounds)) {
                                    first.bubbleBounds
                                } else {
                                    second.bubbleBounds
                                }
                            )

                            fun joinFragmentText(firstText: String, secondText: String): String {
                                if (firstText.length >= 3 && secondText.length >= 3 &&
                                    (firstText.contains(secondText, ignoreCase = true) ||
                                        secondText.contains(firstText, ignoreCase = true))
                                ) {
                                    return if (firstText.length >= secondText.length) firstText else secondText
                                }
                                return translationService.joinTextFragments(firstText, secondText, true)
                            }

                            // Aynı satırda sol/sağ bölünmüş parçalar önce denenir;
                            // kutular yatayda komşu ve dikeyde büyük ölçüde
                            // örtüşüyorsa tek metin olarak soldan sağa birleştirilir.
                            val left = if (a.textRect.left <= b.textRect.left) a else b
                            val right = if (left === a) b else a
                            val vOverlap = minOf(left.textRect.bottom, right.textRect.bottom) -
                                maxOf(left.textRect.top, right.textRect.top)
                            val minH = minOf(left.textRect.height(), right.textRect.height())
                                .coerceAtLeast(1)
                            val hGap = (right.textRect.left - left.textRect.right).toFloat()
                            if (vOverlap >= minH * 0.5f &&
                                hGap <= lineHeight * 1.0f &&
                                hGap > -lineHeight * 0.5f
                            ) {
                                return buildMerged(
                                    left,
                                    right,
                                    joinFragmentText(left.text.trim(), right.text.trim())
                                )
                            }

                            val upper = if (a.textRect.top <= b.textRect.top) a else b
                            val lower = if (upper === a) b else a
                            val gap = (lower.textRect.top - upper.textRect.bottom).toFloat()
                            if (gap > lineHeight * 2.0f) return null
                            val interW = minOf(upper.textRect.right, lower.textRect.right) -
                                maxOf(upper.textRect.left, lower.textRect.left)
                            if (interW < minOf(upper.textRect.width(), lower.textRect.width()) * 0.30f) return null
                            return buildMerged(
                                upper,
                                lower,
                                joinFragmentText(upper.text.trim(), lower.text.trim())
                            )
                        }
                        val working = input.sortedWith(
                            compareBy({ it.textRect.top }, { it.textRect.left })
                        ).toMutableList()
                        var changed = true
                        while (changed) {
                            changed = false
                            mergeLoop@ for (i in working.indices) {
                                for (j in i + 1 until working.size) {
                                    val mergedPair = tryMerge(working[i], working[j]) ?: continue
                                    working[i] = mergedPair
                                    working.removeAt(j)
                                    changed = true
                                    break@mergeLoop
                                }
                            }
                        }
                        return working
                    }

                    // Use the detected bubble's usable interior as the layout area. The OCR
                    // rectangle is only an association check; sizing from that tight text
                    // rectangle can leave longer translations needlessly tiny.
                    // Returns null when the bubble/anchor pair cannot be trusted, so the
                    // caller can fall back to the OCR text rectangle.
                    fun centeredLayoutBox(anchor: Rect, bubble: Rect): Rect? {
                        if (anchor.isEmpty || bubble.isEmpty) return null
                        val minSide = minOf(bubble.width(), bubble.height()).coerceAtLeast(0)
                        val margin = (minSide * 0.04f).toInt().coerceAtMost(minSide / 4).coerceAtLeast(0)
                        val safe = Rect(
                            bubble.left + margin,
                            bubble.top + margin,
                            bubble.right - margin,
                            bubble.bottom - margin
                        )

                        // Detector output contains rectangles, not the balloon contour.
                        // Use an 80% ellipse inscribed in that rectangle so translated
                        // text uses most of the balloon interior while the rounded overlay
                        // corners still stay inside oval balloons.
                        val centerX = bubble.exactCenterX()
                        val centerY = bubble.exactCenterY()
                        val ovalSafe = Rect(
                            (centerX - bubble.width() * 0.40f).toInt().coerceAtLeast(bubble.left),
                            (centerY - bubble.height() * 0.40f).toInt().coerceAtLeast(bubble.top),
                            (centerX + bubble.width() * 0.40f).toInt().coerceAtMost(bubble.right),
                            (centerY + bubble.height() * 0.40f).toInt().coerceAtMost(bubble.bottom)
                        )
                        if (ovalSafe.isEmpty) return null
                        val safeOval = Rect(ovalSafe)
                        if (!safeOval.intersect(safe)) return ovalSafe

                        if (safeOval.isEmpty) return null
                        val anchorIntersection = Rect(anchor)
                        if (!anchorIntersection.intersect(bubble) || anchorIntersection.isEmpty) {
                            // A false or mismatched detector must not move unrelated OCR text.
                            return null
                        }
                        // Keep this rectangle centered on the actual bubble. Its 80% width
                        // and height let the line breaker use the balloon instead of the
                        // much smaller OCR text box.
                        return safeOval
                    }

                    // Algılayıcı balon kutusu balonun tamamını kapsamayabilir; bu durumda
                    // çeviri yazısı gereğinden küçük kalıyor ve balon içinde kalan orijinal
                    // harf parçaları (ör. kesilen kelime sonları) maskelenemiyordu.
                    // Metnin hemen çevresine değen text_free adaylarını balon sınırına
                    // katarak hem yerleşim alanını hem maske kapsamını genişlet.
                    fun grownBubbleBounds(anchor: Rect, bubble: Rect): Rect {
                        if (bubble.isEmpty || anchor.isEmpty || pageAuxBoxes.isEmpty()) return bubble
                        val padX = maxOf(14f, anchor.width() * 0.25f)
                        val padY = maxOf(14f, anchor.height() * 0.25f)
                        val anchorReach = Rect(
                            (anchor.left - padX).toInt(),
                            (anchor.top - padY).toInt(),
                            (anchor.right + padX).toInt(),
                            (anchor.bottom + padY).toInt()
                        )
                        val bubbleArea = bubble.width().toLong() * bubble.height()
                        var result = Rect(bubble)
                        for (aux in pageAuxBoxes) {
                            if (aux.isEmpty) continue
                            val auxArea = aux.width().toLong() * aux.height()
                            if (auxArea > bubbleArea * 2.5f) continue
                            if (!Rect(aux).intersect(anchorReach)) continue
                            val unioned = Rect(result).apply { union(aux) }
                            if (unioned.width().toLong() * unioned.height() > bubbleArea * 3f) continue
                            result = unioned
                        }
                        return result
                    }

                    // 3. Eşleştirme ve Çeviri Akışı
                    if (yoloBubbles.isNotEmpty()) {
                        val sfxBoxes = yoloBubbles.filter {
                            it.label in setOf("text_free", "onomatopoeia_text")
                        }
                            .map { it.toPixelRect(bmpW.toInt(), bmpH.toInt()) }
                        pageAuxBoxes.addAll(sfxBoxes)
                        val balloonBoxes = yoloBubbles.filter { it.label in setOf("bubble", "balloon") }
                            .map { it.toPixelRect(bmpW.toInt(), bmpH.toInt()) }
                        val dialogueBoxes = yoloBubbles.filter { it.label in setOf("text_bubble", "dialogue_text", "text") }
                            .map { it.toPixelRect(bmpW.toInt(), bmpH.toInt()) }

                        fun matchingTextDetection(textRect: Rect): Rect? {
                            if (dialogueBoxes.isEmpty() || textRect.isEmpty) return null
                            val textArea = (textRect.width().toLong() * textRect.height()).coerceAtLeast(1L)
                            return dialogueBoxes.mapNotNull { detection ->
                                val intersectionWidth = (minOf(textRect.right, detection.right) - maxOf(textRect.left, detection.left))
                                    .coerceAtLeast(0)
                                val intersectionHeight = (minOf(textRect.bottom, detection.bottom) - maxOf(textRect.top, detection.top))
                                    .coerceAtLeast(0)
                                val overlap = (intersectionWidth.toLong() * intersectionHeight).toFloat() / textArea.toFloat()
                                val containsCenter = detection.contains(textRect.centerX(), textRect.centerY())
                                val score = if (containsCenter) maxOf(overlap, 0.70f) else overlap
                                if (score < 0.20f) null else detection to score
                            }
                                .sortedWith(
                                    compareByDescending<Pair<Rect, Float>> { it.second }
                                        .thenBy { it.first.width().toLong() * it.first.height() }
                                )
                                .firstOrNull()
                                ?.first
                                ?.let(::Rect)
                        }

                        // Keep tight text detections even when they lie inside a
                        // balloon; they are useful anchors for OCR assignment.
                        // Use the actual balloon detection as the stable parent for OCR
                        // grouping. text_bubble detections describe text regions and may
                        // split one sentence into several boxes inside the same balloon.
                        // Use them only when the model found no balloon at all.
                        val yoloBoxes = (if (balloonBoxes.isNotEmpty()) balloonBoxes else dialogueBoxes).distinct()

                        val pureSfxBoxes = sfxBoxes.filter { sfx ->
                            yoloBoxes.none { yBox ->
                                val interL = kotlin.math.max(sfx.left, yBox.left)
                                val interT = kotlin.math.max(sfx.top, yBox.top)
                                val interR = kotlin.math.min(sfx.right, yBox.right)
                                val interB = kotlin.math.min(sfx.bottom, yBox.bottom)
                                val interW = kotlin.math.max(0, interR - interL)
                                val interH = kotlin.math.max(0, interB - interT)
                                val interArea = interW.toLong() * interH.toLong()
                                val sfxArea = (sfx.width().toLong() * sfx.height().toLong()).coerceAtLeast(1)
                                (interArea.toFloat() / sfxArea.toFloat()) > 0.25f || yBox.contains(sfx.centerX(), sfx.centerY())
                            }
                        }

                        fun overlapsPureSfx(textRect: Rect): Boolean = pureSfxBoxes.any { sfx ->
                            val interL = kotlin.math.max(textRect.left, sfx.left)
                            val interT = kotlin.math.max(textRect.top, sfx.top)
                            val interR = kotlin.math.min(textRect.right, sfx.right)
                            val interB = kotlin.math.min(textRect.bottom, sfx.bottom)
                            val interW = kotlin.math.max(0, interR - interL)
                            val interH = kotlin.math.max(0, interB - interT)
                            val interArea = interW.toLong() * interH.toLong()
                            val textArea = (textRect.width().toLong() * textRect.height()).coerceAtLeast(1)
                            (interArea.toFloat() / textArea.toFloat()) > 0.40f ||
                                sfx.contains(textRect.centerX(), textRect.centerY())
                        }

                        val ocrAssignments = mutableMapOf<Int, Int>()
                        for ((ocrIdx, ocr) in ocrBlocks.withIndex()) {
                            val overlapsWithDialogueOrBalloon = yoloBoxes.any { yBox ->
                                val interL = kotlin.math.max(ocr.rect.left, yBox.left)
                                val interT = kotlin.math.max(ocr.rect.top, yBox.top)
                                val interR = kotlin.math.min(ocr.rect.right, yBox.right)
                                val interB = kotlin.math.min(ocr.rect.bottom, yBox.bottom)
                                val interW = kotlin.math.max(0, interR - interL)
                                val interH = kotlin.math.max(0, interB - interT)
                                val interArea = interW.toLong() * interH.toLong()
                                val ocrArea = (ocr.rect.width().toLong() * ocr.rect.height().toLong()).coerceAtLeast(1)
                                (interArea.toFloat() / ocrArea.toFloat()) > 0.20f || yBox.contains(ocr.rect.centerX(), ocr.rect.centerY())
                            }
                            val isSfx = !overlapsWithDialogueOrBalloon && overlapsPureSfx(ocr.rect)
                            if (isSfx) continue

                            var bestYoloIdx = -1
                            var bestOverlap = 0f
                            val ocrArea = (ocr.rect.width().toLong() * ocr.rect.height().toLong()).coerceAtLeast(1)
                            for ((yoloIdx, yoloBox) in yoloBoxes.withIndex()) {
                                val interL = kotlin.math.max(ocr.rect.left, yoloBox.left)
                                val interT = kotlin.math.max(ocr.rect.top, yoloBox.top)
                                val interR = kotlin.math.min(ocr.rect.right, yoloBox.right)
                                val interB = kotlin.math.min(ocr.rect.bottom, yoloBox.bottom)
                                val interW = kotlin.math.max(0, interR - interL)
                                val interH = kotlin.math.max(0, interB - interT)
                                val interArea = interW.toLong() * interH.toLong()
                                val overlap = interArea.toFloat() / ocrArea.toFloat()
                                val isCenterIn = yoloBox.contains(ocr.rect.centerX(), ocr.rect.centerY())
                                val vDistToTop = kotlin.math.abs(ocr.rect.bottom - yoloBox.top)
                                val vDistToBottom = kotlin.math.abs(ocr.rect.top - yoloBox.bottom)
                                val isNearVerticalEdge = (vDistToTop <= yoloBox.height() * 0.28f || vDistToBottom <= yoloBox.height() * 0.28f) &&
                                    ocr.rect.centerX() in (yoloBox.left - 20)..(yoloBox.right + 20)
                                val score = when {
                                    isCenterIn -> kotlin.math.max(overlap, 0.70f)
                                    overlap >= 0.25f -> overlap
                                    isNearVerticalEdge -> 0.42f
                                    else -> 0f
                                }
                                val currentBestArea = yoloBoxes.getOrNull(bestYoloIdx)?.let {
                                    it.width().toLong() * it.height()
                                } ?: Long.MAX_VALUE
                                val candidateArea = yoloBox.width().toLong() * yoloBox.height()
                                if (score >= 0.30f &&
                                    (score > bestOverlap || (score == bestOverlap && candidateArea < currentBestArea))
                                ) {
                                    bestOverlap = score
                                    bestYoloIdx = yoloIdx
                                }
                            }
                            if (bestYoloIdx != -1) {
                                ocrAssignments[ocrIdx] = bestYoloIdx
                            }
                        }

                        var expandedAssignment = true
                        while (expandedAssignment) {
                            expandedAssignment = false
                            val currentAssigned = ocrAssignments.toMap()
                            for ((ocrIdx, ocr) in ocrBlocks.withIndex()) {
                                if (!ocrAssignments.containsKey(ocrIdx)) {
                                    val matched = currentAssigned.entries.firstOrNull { (assignedIdx, parentYoloIdx) ->
                                        val parentBox = yoloBoxes.getOrNull(parentYoloIdx) ?: return@firstOrNull false
                                        val interWidth = (minOf(ocr.rect.right, parentBox.right) - maxOf(ocr.rect.left, parentBox.left)).coerceAtLeast(0)
                                        val interHeight = (minOf(ocr.rect.bottom, parentBox.bottom) - maxOf(ocr.rect.top, parentBox.top)).coerceAtLeast(0)
                                        val ocrArea = (ocr.rect.width().toLong() * ocr.rect.height().toLong()).coerceAtLeast(1L)
                                        val overlapRatio = interWidth.toLong() * interHeight / ocrArea.toFloat()
                                        // Allow a small spill beyond tightly fitted detector boxes;
                                        // OCR grouping still has to confirm the same text neighborhood.
                                        val insideParent = parentBox.contains(ocr.rect.centerX(), ocr.rect.centerY()) || overlapRatio >= 0.12f
                                        insideParent && translationService.areInSameSpeechBubble(
                                            ocr,
                                            ocrBlocks[assignedIdx],
                                            bitmap,
                                            parentBubble = parentBox
                                        )
                                    }
                                    if (matched != null) {
                                        ocrAssignments[ocrIdx] = matched.value
                                        expandedAssignment = true
                                    }
                                }
                            }
                        }

                        val matchedOcrIndices = mutableSetOf<Int>()
                        val isJapanese = originLang.equals("Japonca", ignoreCase = true)
                        for ((yoloIdx, yoloBox) in yoloBoxes.withIndex()) {
                            val assigned = ocrAssignments.filter { it.value == yoloIdx }.keys
                            if (assigned.isNotEmpty()) {
                                matchedOcrIndices.addAll(assigned)
                                val assignedBlocksUnsorted = assigned.map { ocrBlocks[it] }
                                val verticalMode = isJapanese &&
                                    assignedBlocksUnsorted.count { it.rect.height() > it.rect.width() * 1.3f } * 2 >= assignedBlocksUnsorted.size
                                val assignedBlocks = assignedBlocksUnsorted.sortedWith(Comparator { a, b ->
                                    if (verticalMode) {
                                        val aCx = (a.rect.left + a.rect.right) / 2f
                                        val bCx = (b.rect.left + b.rect.right) / 2f
                                        val minW = minOf(a.rect.width(), b.rect.width()).toFloat()
                                        val sameColumn = kotlin.math.abs(aCx - bCx) < minW * 0.6f
                                        if (!sameColumn) {
                                            b.rect.right.compareTo(a.rect.right).takeIf { it != 0 }
                                                ?: a.rect.top.compareTo(b.rect.top)
                                        } else {
                                            a.rect.top.compareTo(b.rect.top).takeIf { it != 0 }
                                                ?: b.rect.right.compareTo(a.rect.right)
                                        }
                                    } else if (isJapanese) {
                                        val hDist = kotlin.math.abs(a.rect.left - b.rect.left)
                                        val bothNarrow = a.rect.height() > a.rect.width() * 1.3f && b.rect.height() > b.rect.width() * 1.3f
                                        if (bothNarrow && hDist > 12) {
                                            b.rect.right.compareTo(a.rect.right).takeIf { it != 0 }
                                                ?: a.rect.top.compareTo(b.rect.top)
                                        } else {
                                            a.rect.top.compareTo(b.rect.top).takeIf { it != 0 }
                                                ?: b.rect.right.compareTo(a.rect.right)
                                        }
                                    } else {
                                        a.rect.top.compareTo(b.rect.top).takeIf { it != 0 }
                                            ?: a.rect.left.compareTo(b.rect.left)
                                    }
                                })
                                // Regroup OCR fragments using their geometry and stable
                                // parent balloon identity. A broad detector box alone
                                // must not join text from different speech balloons.
                                val textGroups = translationService.clusterAdjacentBlocks(
                                    assignedBlocks,
                                    bitmap,
                                    detectedBubbles = balloonBoxes
                                )
                                for (textGroup in textGroups) {
                                    val cleanedText = translationService.cleanOcrArtifacts(textGroup.text, originLang)
                                    val textBounds = Rect(textGroup.rect)
                                    val analysis = translationService.analyzeBubble(bitmap, textBounds)
                                    val containingBubble = balloonBoxes
                                        .filter { bBox ->
                                            val interWidth = (minOf(textBounds.right, bBox.right) - maxOf(textBounds.left, bBox.left)).coerceAtLeast(0)
                                            val interHeight = (minOf(textBounds.bottom, bBox.bottom) - maxOf(textBounds.top, bBox.top)).coerceAtLeast(0)
                                            val intersection = interWidth.toLong() * interHeight.toLong()
                                            val textArea = (textBounds.width().toLong() * textBounds.height()).coerceAtLeast(1L)
                                            bBox.contains(textBounds.centerX(), textBounds.centerY()) || intersection.toFloat() / textArea >= 0.50f
                                        }
                                        .minByOrNull { bBox -> bBox.width().toLong() * bBox.height() }
                                    val finalIsBubble = containingBubble != null || analysis.isBubble

                                    if (cleanedText.isNotBlank() && translationService.isValidDialogueText(cleanedText, isBubble = finalIsBubble)) {
                                        val bubbleBounds = when {
                                            containingBubble != null -> containingBubble
                                            analysis.isBubble -> translationService.refineBubbleBounds(bitmap, textBounds, analysis.isDarkBubble)
                                            else -> textBounds
                                        }.let { grownBubbleBounds(textBounds, it) }
                                    // Size bubble text from the balloon interior. The tight
                                    // text_bubble detection remains a fallback anchor, but
                                    // fitting to it left longer translations needlessly tiny.
                                    val finalTargetBox = (if (finalIsBubble) {
                                        centeredLayoutBox(textBounds, bubbleBounds)
                                    } else {
                                        null
                                    })
                                        ?: matchingTextDetection(textBounds)
                                        ?: Rect(textBounds)
                                        candidates.add(
                                            BubbleCandidate(
                                                targetBox = finalTargetBox,
                                                text = cleanedText,
                                                textRect = textBounds,
                                                isBubble = finalIsBubble,
                                                isDark = analysis.isDarkBubble,
                                                bubbleBounds = bubbleBounds
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        val unmatchedBlocks = ocrBlocks.filterIndexed { idx, raw ->
                            if (matchedOcrIndices.contains(idx) || raw.text.trim().isEmpty()) {
                                false
                            } else {
                                !overlapsPureSfx(raw.rect)
                            }
                        }
                        val clusteredUnmatched = translationService.clusterAdjacentBlocks(
                            unmatchedBlocks,
                            bitmap,
                            detectedBubbles = balloonBoxes
                        )
                        clusteredUnmatched.forEach { cluster ->
                            val cleanedText = translationService.cleanOcrArtifacts(cluster.text, originLang)
                            val analysis = translationService.analyzeBubble(bitmap, cluster.rect)
                            if (translationService.isValidDialogueText(cleanedText, isBubble = analysis.isBubble)) {
                                val refinedBox = (if (analysis.isBubble) translationService.refineBubbleBounds(bitmap, cluster.rect, analysis.isDarkBubble) else cluster.rect)
                                    .let { grownBubbleBounds(cluster.rect, it) }
                                candidates.add(
                                    BubbleCandidate(
                                        targetBox = (if (analysis.isBubble) {
                                            centeredLayoutBox(cluster.rect, refinedBox)
                                        } else {
                                            null
                                        })
                                            ?: matchingTextDetection(cluster.rect)
                                            ?: Rect(cluster.rect),
                                        text = cleanedText,
                                        textRect = cluster.rect,
                                        isBubble = analysis.isBubble,
                                        isDark = analysis.isDarkBubble,
                                        bubbleBounds = refinedBox
                                    )
                                )
                            }
                        }

                        _detectedBubblesMap.update { current ->
                            if (readerGeneration.get() == generation) current + (pageIdx to yoloBubbles) else current
                        }
                    } else {
                        val filteredBlocks = ocrBlocks.filter { it.text.trim().isNotEmpty() }
                        val clusteredBlocks = translationService.clusterAdjacentBlocks(filteredBlocks, bitmap)
                        clusteredBlocks.forEach { raw ->
                            val cleanedText = translationService.cleanOcrArtifacts(raw.text, originLang)
                            val analysis = translationService.analyzeBubble(bitmap, raw.rect)
                            if (translationService.isValidDialogueText(cleanedText, isBubble = analysis.isBubble)) {
                                val refinedBox = if (analysis.isBubble) translationService.refineBubbleBounds(bitmap, raw.rect, analysis.isDarkBubble) else raw.rect
                                candidates.add(
                                    BubbleCandidate(
                                        targetBox = (if (analysis.isBubble) centeredLayoutBox(raw.rect, refinedBox) else null) ?: raw.rect,
                                        text = cleanedText,
                                        textRect = raw.rect,
                                        isBubble = analysis.isBubble,
                                        isDark = analysis.isDarkBubble,
                                        bubbleBounds = refinedBox
                                    )
                                )
                            }
                        }
                    }

                    // Join fragments of the same balloon before translation so a
                    // split balloon is never translated piece by piece.
                    val mergedCandidates = mergeSameBubbleFragments(candidates)

                    // Metinleri çevir
                    if (mergedCandidates.isNotEmpty()) {
                        val candidateRequests = mergedCandidates.map { cand ->
                            val r = cand.textRect
                            val id = "$pageIdx:${r.left},${r.top},${r.right},${r.bottom}:${cand.text.hashCode()}"
                            TranslationRequest(id, cand.text)
                        }
                        val detectedLang = if (forcedSourceCode != null) {
                            originLang
                        } else {
                            translationService.detectRealLanguage(ocrBlocks, originLang)
                        }
                        val gemModel = preferences.geminiModel.first()
                        val engineIsMlKit = prefEngine.trim().let {
                            it.equals("ML Kit", ignoreCase = true) ||
                                it.equals("MLKit", ignoreCase = true) ||
                                it.equals("Offline", ignoreCase = true)
                        }
                        if (engineIsMlKit) {
                            emitTranslationNotice("ML Kit dil modeli indiriliyor; ilk çeviri model hazır olana kadar sürebilir.")
                        }
                        Log.d("TR", "Çeviri başladı: engine=$prefEngine, aday=${candidateRequests.size} metin, $detectedLang->$targetLangCode")
                        val outcome = translationService.translateBlocks(
                            requests = candidateRequests,
                            engine = prefEngine,
                            userGeminiApiKey = apiK,
                            userDeepLApiKey = deepLK,
                            originLang = detectedLang,
                            targetLangCode = targetLangCode,
                            turkishConvertEnabled = tC,
                            geminiModel = gemModel
                        )
                        emitTranslationNotice(outcome.warning)
                        val translationResults = outcome.results
                        val resultsById = translationResults.associateBy { it.id }
                        Log.d("TR", "Çeviri bitti: ${translationResults.count { it.succeeded }}/${candidateRequests.size} başarılı")
                        if (translationResults.isNotEmpty() && translationResults.none { it.succeeded }) {
                            emitTranslationNotice("Çeviri başarısız (motor: $prefEngine). İnternet bağlantısını ve API anahtarını kontrol edin.")
                        }

                        mergedCandidates.forEachIndexed { idx, cand ->
                            val request = candidateRequests[idx]
                            val result = resultsById[request.id]
                            if (result == null) {
                                Log.e("TR", "Çeviri sonucu eşleşmedi: id=${request.id}, sayfa=$pageIdx")
                            } else if (!result.succeeded) {
                                Log.e("TR", "Çeviri başarısız: id=${result.id}, sayfa=$pageIdx, hata=${result.error}")
                            }
                            val translated = result?.translatedText.orEmpty()
                            val normL = (cand.targetBox.left / bmpW).coerceIn(0f, 1f)
                            val normT = (cand.targetBox.top / bmpH).coerceIn(0f, 1f)
                            val normW = (cand.targetBox.width() / bmpW).coerceIn(0f, 1f)
                            val normH = (cand.targetBox.height() / bmpH).coerceIn(0f, 1f)
                            val sampledColor = translationService.sampleSpeechBubbleColor(
                                bitmap,
                                cand.textRect,
                                cand.isDark
                            )
                            finalBlocks.add(
                                TextBlockObj(
                                    originalText = cand.text,
                                    translatedText = translated,
                                    boundingBox = cand.textRect,
                                    bubbleBox = cand.bubbleBounds,
                                    isBubble = cand.isBubble,
                                    isDarkBubble = cand.isDark,
                                    normalizedLeft = normL,
                                    normalizedTop = normT,
                                    normalizedWidth = normW,
                                    normalizedHeight = normH,
                                    backgroundColor = sampledColor,
                                    layoutBox = cand.targetBox,
                                    translationSucceeded = result?.succeeded == true,
                                    coordinateImageWidth = bitmap.width,
                                    coordinateImageHeight = bitmap.height
                                )
                            )
                        }
                        translationCanBeCached = finalBlocks.size == mergedCandidates.size &&
                            finalBlocks.all { it.translationSucceeded }
                    } else {
                        Log.d("TR", "Çevrilecek aday bulunamadı (candidates=0).")
                        translationCanBeCached = true
                    }

                    // 4. Sayfadaki yabancı metinleri temizleyip maskele
                    val timingAfterTranslate = System.currentTimeMillis()
                    val succeededBlocks = finalBlocks.filter { it.translationSucceeded }
                    val failedBlocks = finalBlocks.filter { !it.translationSucceeded }
                    Log.d("TR", "Maskeleme başladı: finalBlocks=${finalBlocks.size}")
                    val maskResult = translationService.maskOriginalTextOnBitmap(
                        bitmap,
                        succeededBlocks,
                        pageAuxBoxes,
                        failedBlocks
                    )
                    val maskedBitmap = maskResult.bitmap

                    Log.d("TR", "Maskeleme bitti sayfa=$pageIdx (${System.currentTimeMillis() - timingAfterTranslate}ms)")
                    // Show successful bubbles immediately, even when another block on
                    // the page failed. Failed blocks keep their source text visible.
                    if (finalBlocks.isNotEmpty() || mergedCandidates.isEmpty()) {
                        storeMaskedPage(pageIdx, maskedBitmap, generation)
                        // Keep translation geometry for the whole chapter. The masked bitmap
                        // cache is intentionally smaller, so tying overlays to it made older
                        // translated pages disappear as the reader scrolled forward.
                        _translatedPagesMap.update { current ->
                            if (readerGeneration.get() != generation) current else
                                current + (pageIdx to finalBlocks)
                        }
                    }
                    if (translationCanBeCached) {
                        // Kalıcı önbellek: uygulama yeniden açıldığında beklemeden gösterilir.
                        // Kısmi sonuçlar kalıcı cache'e yazılmaz; sonraki açılışta sayfa yeniden denenir.
                        persistTranslatedPage(pageIdx, finalBlocks, maskedBitmap)
                    }
                    if (translationCanBeCached) {
                        Log.d("TR", "Sayfa $pageIdx çeviri akışıyla tamamlandı.")
                    } else {
                        Log.w("TR", "Sayfa $pageIdx çevirilerinden en az biri başarısız; sonuç yeniden denenebilmesi için cache'lenmedi.")
                    }
                    Log.d(
                        "TR",
                        "Sayfa $pageIdx süre özeti: toplam=${System.currentTimeMillis() - timingStart}ms " +
                            "(tespit=${timingAfterDetect - timingStart}ms, ocr=${timingAfterOcr - timingAfterDetect}ms, " +
                            "çeviri=${timingAfterTranslate - timingAfterOcr}ms, maske=${System.currentTimeMillis() - timingAfterTranslate}ms)"
                    )
                }
            } catch (e: CancellationException) {
                Log.w("TR", "Çeviri iptal edildi (CancellationException) sayfa=$pageIdx")
                throw e
            } catch (t: Throwable) {
                Log.e("TR", "Çeviri sürecinde hata yakalandı: ${t.message}", t)
                Log.w("BabelRead_TL", "Overlay translation note: ${t.message}")
            } finally {
                finishTranslationProgress(progressKey, generation, currentCoroutineContext()[Job])
                Log.d("TR", "translatePage sonlandı (finally) sayfa=$pageIdx, kalan=$inProgressPages")
            }
        }
        translationJobs[progressKey] = translationJob
        translationJob.invokeOnCompletion {
            finishTranslationProgress(progressKey, generation, translationJob)
        }
        translationJob.start()
    }

    private fun finishTranslationProgress(progressKey: String, generation: Long, job: Job?) {
        inProgressPages.remove(progressKey)
        if (job != null) translationJobs.remove(progressKey, job)
        if (readerGeneration.get() == generation) {
            _isTranslationLoading.value = inProgressPages.any { it.startsWith("$generation:") }
        }
    }

    suspend fun submitCorrection(originalText: String, turkishText: String): Result<Unit> {
        if (originalText.isBlank() || turkishText.isBlank()) {
            return Result.failure(IllegalArgumentException("Metinler boş olamaz."))
        }
        return try {
            val targetLangCode = preferences.translationTargetLang.first()
            val entity = TranslationCorrectionEntity(
                originalText = translationService.normalizeForCache(originalText),
                turkishText = turkishText,
                sourceLang = _readerManga.value?.originLanguage ?: "Bilinmiyor",
                targetLang = targetLangCode
            )
            correctionDao.insertCorrection(entity)
            val currentIdx = _currentPageIndex.value
            _translatedPagesMap.update { it - currentIdx }
            _maskedPagesMap.update { it - currentIdx }
            translatePageIfNeeded(currentIdx)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 6. DOWNLOADS & OFFLINE SYSTEM STATE
    private val _downloadQueue = MutableStateFlow<List<DownloadQueueItemEntity>>(emptyList())
    val downloadQueue = _downloadQueue.asStateFlow()

    fun queueChapterDownload(mangaId: String, chapterId: String) {
        viewModelScope.launch {
            val chapter = chapterDao.getChapterById(chapterId) ?: return@launch
            if (chapter.isDownloaded) return@launch
            val manga = mangaDao.getMangaById(mangaId) ?: return@launch
            val queueItem = DownloadQueueItemEntity(
                chapterId = chapterId,
                mangaId = mangaId,
                chapterTitle = chapter.title,
                mangaTitle = manga.title,
                mangaCover = manga.coverUrl
            )
            queueDao.insertQueueItem(queueItem)
        }
    }

    fun retryDownload(chapterId: String) {
        viewModelScope.launch {
            val item = queueDao.getQueueItem(chapterId) ?: return@launch
            if (item.status == "Başarısız" || item.status == "CFBekliyor") {
                queueDao.updateQueueStatus(chapterId, "Bekliyor", 0f)
            }
        }
    }

    fun queueAllChaptersFromHere(mangaId: String, chapterNumber: Double) {
        viewModelScope.launch {
            val allChapters = chapterDao.getChaptersByMangaIdSync(mangaId)
            val toDownload = allChapters.filter { it.chapterNumber >= chapterNumber && !it.isDownloaded }
            enqueueChapters(mangaId, toDownload)
        }
    }

    fun queueAllChapters(mangaId: String, language: String? = null) {
        viewModelScope.launch {
            val allChapters = chapterDao.getChaptersByMangaIdSync(mangaId)
            val toDownload = allChapters.filter {
                !it.isDownloaded && (language == null || it.language.equals(language, ignoreCase = true))
            }
            enqueueChapters(mangaId, toDownload)
        }
    }

    private suspend fun enqueueChapters(mangaId: String, chapters: List<ChapterEntity>) {
        if (chapters.isEmpty()) return
        val manga = mangaDao.getMangaById(mangaId) ?: return
        val queuedChapterIds = queueDao.getAllItemsSync().asSequence()
            .filter { it.mangaId == mangaId }
            .map { it.chapterId }
            .toSet()
        val queueItems = chapters.asSequence()
            .filterNot { it.isDownloaded || it.id in queuedChapterIds }
            .map { chapter ->
                DownloadQueueItemEntity(
                    chapterId = chapter.id,
                    mangaId = mangaId,
                    chapterTitle = chapter.title,
                    mangaTitle = manga.title,
                    mangaCover = manga.coverUrl
                )
            }
            .toList()
        if (queueItems.isNotEmpty()) queueDao.insertQueueItems(queueItems)
    }

    private val downloadStateLock = Any()
    private val activeDownloadIds = mutableSetOf<String>()
    private val cancelledDownloadIds = mutableSetOf<String>()
    private val activeDownloadCalls = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Call>>()

    private class DownloadCancelledException : Exception("Download cancelled by user")

    private fun throwIfDownloadCancelled(chapterId: String) {
        if (synchronized(downloadStateLock) { chapterId in cancelledDownloadIds }) {
            throw DownloadCancelledException()
        }
    }

    private fun registerDownloadCall(chapterId: String, call: Call) {
        val calls = activeDownloadCalls.computeIfAbsent(chapterId) {
            java.util.Collections.synchronizedSet(mutableSetOf<Call>())
        }
        calls.add(call)
        if (synchronized(downloadStateLock) { chapterId in cancelledDownloadIds }) {
            runCatching { call.cancel() }
        }
    }

    private fun unregisterDownloadCall(chapterId: String, call: Call) {
        activeDownloadCalls[chapterId]?.remove(call)
    }

    private fun cancelDownloadCalls(chapterId: String) {
        val calls = activeDownloadCalls[chapterId]?.toList().orEmpty()
        calls.forEach { runCatching { it.cancel() } }
    }

    fun cancelDownload(chapterId: String) {
        synchronized(downloadStateLock) {
            cancelledDownloadIds.add(chapterId)
            cancelDownloadCalls(chapterId)
        }
        viewModelScope.launch {
            queueDao.deleteQueueItem(chapterId)
            synchronized(downloadStateLock) {
                if (chapterId !in activeDownloadIds) cancelledDownloadIds.remove(chapterId)
            }
        }
    }

    /** All supported roots, so cleanup still finds downloads if the storage preference changed. */
    private fun downloadBaseDirs(): List<File> {
        val roots = mutableListOf(File(context.filesDir, "downloads"))
        context.getExternalFilesDir("downloads")?.let(roots::add)
        return roots.distinctBy { it.absoluteFile.toPath().normalize().toString() }
    }

    private suspend fun downloadBaseDir(): File {
        return try {
            val loc = preferences.downloadLocation.first()
            if (loc.equals("Harici", ignoreCase = true)) {
                context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")
            } else {
                File(context.filesDir, "downloads")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            File(context.filesDir, "downloads")
        }
    }

    private suspend fun enforceCacheLimit(exceptChapterId: String? = null) {
        try {
            val limitMb = preferences.cacheSizeLimit.first()
            if (limitMb <= 0) return
            val bases = downloadBaseDirs().filter { it.exists() }
            if (bases.isEmpty()) return

            fun dirSize(f: File): Long {
                if (!f.exists()) return 0L
                if (f.isFile) return f.length()
                return f.listFiles()?.sumOf { dirSize(it) } ?: 0L
            }

            var total = bases.sumOf(::dirSize)
            val maxBytes = limitMb * 1024L * 1024L
            if (total <= maxBytes) return

            data class ChapterCacheCandidate(val chapterId: String, val dirs: List<File>, val size: Long, val oldestWrite: Long)
            val protectedChapterIds = setOfNotNull(exceptChapterId, _activeChapter.value?.id)
            val candidates = bases
                .flatMap { base -> base.listFiles()?.filter { it.isDirectory }.orEmpty() }
                .filter { it.name !in protectedChapterIds }
                .groupBy { it.name }
                .map { (chapterId, dirs) ->
                    ChapterCacheCandidate(chapterId, dirs, dirs.sumOf(::dirSize), dirs.minOf { it.lastModified() })
                }
                .sortedBy { it.oldestWrite }
            if (candidates.isEmpty()) return

            for (candidate in candidates) {
                if (total <= maxBytes) break
                val deletionResults = candidate.dirs.map { dir -> !dir.exists() || dir.deleteRecursively() }
                val deleted = deletionResults.all { it }
                if (deleted) {
                    total -= candidate.size
                    chapterDao.updateDownloadStatus(candidate.chapterId, false, "")
                } else {
                    total = bases.sumOf(::dirSize)
                    if (deletionResults.any { it }) {
                        chapterDao.updateDownloadStatus(candidate.chapterId, false, "")
                    }
                    Log.w("BabelRead_Cache", "Could not completely remove cached chapter ${candidate.chapterId}")
                }
            }
        } catch (e: Exception) {
            Log.e("BabelRead_Cache", "Failed to enforce download cache limit", e)
        }
    }

    private fun startDownloadProcessor() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val next = queueDao.getNextPendingItem()
                if (next != null) {
                    val claimed = synchronized(downloadStateLock) {
                        if (next.chapterId in cancelledDownloadIds) false
                        else activeDownloadIds.add(next.chapterId)
                    }
                    if (!claimed) {
                        queueDao.deleteQueueItem(next.chapterId)
                        synchronized(downloadStateLock) { cancelledDownloadIds.remove(next.chapterId) }
                        continue
                    }
                    var downloadDir: File? = null
                    try {
                        if (queueDao.getQueueItem(next.chapterId) == null) continue
                        throwIfDownloadCancelled(next.chapterId)
                        queueDao.updateQueueStatus(next.chapterId, "İndiriliyor", 0f)
                        val manga = mangaDao.getMangaById(next.mangaId)
                        val sourceName = manga?.sourceName
                            ?: throw IOException("İndirme için manga kaydı bulunamadı: ${next.mangaId}")
                        val src = MangaSourceRegistry.getSource(sourceName)
                        val pageUrls = src.getChapterPages(next.chapterId)
                        if (pageUrls.isEmpty()) throw IOException("Bölümde indirilecek sayfa bulunamadı: ${next.chapterId}")

                        enforceCacheLimit(next.chapterId)
                        throwIfDownloadCancelled(next.chapterId)
                        val targetDownloadDir = File(downloadBaseDir(), next.chapterId).apply { mkdirs() }
                        downloadDir = targetDownloadDir

                        val totalPages = pageUrls.size
                        val savedPaths = java.util.concurrent.ConcurrentHashMap<Int, String>()
                        val pendingPages = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, String>>()
                        var alreadyDone = 0
                        for (i in pageUrls.indices) {
                            val file = File(targetDownloadDir, "page_$i.jpg")
                            if (file.exists() && file.length() > 1024) {
                                savedPaths[i] = "file://${file.absolutePath}"
                                alreadyDone++
                            } else {
                                pendingPages.add(i to pageUrls[i])
                            }
                        }
                        val completedCount = java.util.concurrent.atomic.AtomicInteger(alreadyDone)
                        if (alreadyDone > 0) {
                            queueDao.updateQueueStatus(next.chapterId, "İndiriliyor", alreadyDone.toFloat() / totalPages)
                        }
                        val workerCount = minOf(3, pendingPages.size)
                        if (workerCount > 0) {
                            coroutineScope {
                                repeat(workerCount) {
                                    launch(Dispatchers.IO) {
                                        while (isActive) {
                                            val page = pendingPages.poll() ?: break
                                            throwIfDownloadCancelled(next.chapterId)
                                            downloadPageWithResume(next.chapterId, page.first, page.second, targetDownloadDir)
                                            savedPaths[page.first] = "file://${File(targetDownloadDir, "page_${page.first}.jpg").absolutePath}"
                                            val done = completedCount.incrementAndGet()
                                            queueDao.updateQueueStatus(next.chapterId, "İndiriliyor", done.toFloat() / totalPages)
                                        }
                                    }
                                }
                            }
                        }
                        val orderedPaths = (0 until totalPages).mapNotNull { savedPaths[it] }
                        if (orderedPaths.size != totalPages) {
                            throw IOException("Eksik sayfa indirildi: ${orderedPaths.size}/$totalPages (${next.chapterId})")
                        }

                        val localPathJoined = PagePathCodec.encode(orderedPaths)
                        throwIfDownloadCancelled(next.chapterId)
                        chapterDao.updateDownloadStatus(next.chapterId, true, localPathJoined)
                        queueDao.deleteQueueItem(next.chapterId)
                    } catch (e: DownloadCancelledException) {
                        downloadDir?.deleteRecursively()
                        queueDao.deleteQueueItem(next.chapterId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CloudflareChallengeException) {
                        Log.w("BabelRead_Download", "Cloudflare doğrulaması gerekiyor: ${e.host}")
                        val stillQueued = runCatching {
                            if (synchronized(downloadStateLock) { next.chapterId in cancelledDownloadIds }) false
                            else queueDao.getQueueItem(next.chapterId) != null
                        }.getOrDefault(false)
                        if (stillQueued) {
                            runCatching {
                                queueDao.updateQueueStatus(next.chapterId, "CFBekliyor", 0f)
                            }
                            beginCloudflareVerification(e.host, e.challengedUrl) {
                                viewModelScope.launch {
                                    runCatching { queueDao.requeueCfPendingDownloads() }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        if (synchronized(downloadStateLock) { next.chapterId in cancelledDownloadIds }) {
                            downloadDir?.deleteRecursively()
                            queueDao.deleteQueueItem(next.chapterId)
                        } else {
                            Log.e("BabelRead_Download", "Failed downloading chapter: ${next.chapterId}", t)
                            runCatching {
                                queueDao.updateQueueStatus(next.chapterId, "Başarısız", 0f)
                            }.onFailure { statusError ->
                                Log.w("BabelRead_Download", "Could not persist failed status", statusError)
                            }
                            delay(5000)
                        }
                    } finally {
                        cancelDownloadCalls(next.chapterId)
                        activeDownloadCalls.remove(next.chapterId)
                        synchronized(downloadStateLock) {
                            activeDownloadIds.remove(next.chapterId)
                            cancelledDownloadIds.remove(next.chapterId)
                        }
                    }
                } else {
                    delay(3000)
                }
            }
        }
    }

    private suspend fun downloadPageWithResume(chapterId: String, index: Int, urlString: String, targetDir: File) {
        val file = File(targetDir, "page_$index.jpg")
        val tempFile = File(targetDir, ".page_$index.jpg.tmp")
        var lastErr: Throwable? = null
        var attempt = 0
        val maxAttempts = 4
        while (attempt < maxAttempts) {
            throwIfDownloadCancelled(chapterId)
            try {
                if (urlString.startsWith("extpg:")) {
                    val bytes = com.example.extensions.ExtensionPageFetcher.fetch(urlString)
                        ?: throw IOException("Eklenti sayfası alınamadı: $urlString")
                    tempFile.writeBytes(bytes)
                    if (!tempFile.renameTo(file)) {
                        tempFile.copyTo(file, overwrite = true)
                        tempFile.delete()
                    }
                    return
                }
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                val reqBuilder = Request.Builder().url(urlString)
                if (existingBytes > 0) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }
                val call = NetworkManager.client.newCall(reqBuilder.build())
                registerDownloadCall(chapterId, call)
                try {
                    call.execute().use { response ->
                        if (response.code == 416) {
                            tempFile.delete()
                            throw IOException("Aralık isteği reddedildi, sayfa baştan indirilecek: $urlString")
                        }
                        if (!response.isSuccessful) {
                            if (NetworkManager.isCloudflareChallenge(response)) {
                                throw CloudflareChallengeException(response.request.url.host, urlString, "İndirme")
                            }
                            throw IOException("Page download failed: $urlString (${response.code})")
                        }
                        val body = response.body ?: throw IOException("Empty page response: $urlString")
                        val append = response.code == 206 && existingBytes > 0
                        FileOutputStream(tempFile, append).use { out ->
                            body.byteStream().use { input ->
                                input.copyTo(out)
                            }
                            out.flush()
                        }
                    }
                } finally {
                    unregisterDownloadCall(chapterId, call)
                }
                if (!tempFile.renameTo(file)) {
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                throw e
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (t: Throwable) {
                if (synchronized(downloadStateLock) { chapterId in cancelledDownloadIds }) {
                    throw DownloadCancelledException()
                }
                lastErr = t
                attempt++
                if (attempt < maxAttempts) delay(1200L * attempt)
            }
        }
        throw lastErr ?: IOException("Page download failed: $urlString")
    }

    fun importLocalArchive(uri: Uri, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = "Arşiv taranıyor..."
            try {
                val res = localMangaImporter.importArchiveOrDocument(uri) { status ->
                    _importStatusText.value = status
                }
                onResult(res.success, res.message)
            } catch (e: Exception) {
                onResult(false, "İçe aktarma hatası: ${e.localizedMessage ?: e.message}")
            } finally {
                _isImporting.value = false
                _importStatusText.value = ""
            }
        }
    }

    fun importLocalFolder(treeUri: Uri, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = "Klasör taranıyor..."
            try {
                val res = localMangaImporter.importFolderTree(treeUri) { status ->
                    _importStatusText.value = status
                }
                onResult(res.success, res.message)
            } catch (e: Exception) {
                onResult(false, "Klasör aktarma hatası: ${e.localizedMessage ?: e.message}")
            } finally {
                _isImporting.value = false
                _importStatusText.value = ""
            }
        }
    }

    fun importLocalFolderBulk(treeUri: Uri, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = "Toplu klasör taranıyor..."
            try {
                val res: BulkLocalImportResult = localMangaImporter.importFolderLibrary(treeUri) { status ->
                    _importStatusText.value = status
                }
                onResult(res.success, res.message)
            } catch (e: Exception) {
                onResult(false, "Toplu klasör aktarma hatası: ${e.localizedMessage ?: e.message}")
            } finally {
                _isImporting.value = false
                _importStatusText.value = ""
            }
        }
    }

    fun importLocalArchives(
        uris: List<Uri>,
        title: String? = null,
        asSingleManga: Boolean,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = if (asSingleManga) {
                "Arşivler tek manga olarak hazırlanıyor..."
            } else {
                "Arşivler ayrı ayrı aktarılıyor..."
            }
            try {
                val res: BulkLocalImportResult = localMangaImporter.importMultipleArchives(
                    uris = uris,
                    customTitle = title,
                    asSingleManga = asSingleManga
                ) { status ->
                    _importStatusText.value = status
                }
                onResult(res.success, res.message)
            } catch (e: Exception) {
                onResult(false, "Arşiv aktarma hatası: ${e.localizedMessage ?: e.message}")
            } finally {
                _isImporting.value = false
                _importStatusText.value = ""
            }
        }
    }

    fun importLocalImages(uris: List<Uri>, title: String? = null, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = "Resimler aktarılıyor..."
            try {
                val res = localMangaImporter.importMultipleImages(uris, title) { status ->
                    _importStatusText.value = status
                }
                onResult(res.success, res.message)
            } catch (e: Exception) {
                onResult(false, "Resim aktarma hatası: ${e.localizedMessage ?: e.message}")
            } finally {
                _isImporting.value = false
                _importStatusText.value = ""
            }
        }
    }

    fun importCbzOrZip(uri: Uri, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        importLocalArchive(uri, onResult)
    }

    fun importImagesFromUris(uris: List<Uri>, title: String? = null, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        importLocalImages(uris, title, onResult)
    }

    fun clearPersonalGrammarCorrections() {
        viewModelScope.launch {
            correctionDao.clearAll()
        }
    }

    fun mergeMangas(
        orderedMangaIds: List<String>,
        targetTitle: String,
        onFinished: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ids = orderedMangaIds.distinct()
                if (ids.size < 2) {
                    withContext(Dispatchers.Main) {
                        onFinished(false, "Birleştirmek için en az 2 manga seçin.")
                    }
                    return@launch
                }
                val mangas = ids.mapNotNull { mangaDao.getMangaById(it) }
                if (mangas.size != ids.size) {
                    withContext(Dispatchers.Main) {
                        onFinished(false, "Seçilen manga kayıtlarından bazıları bulunamadı.")
                    }
                    return@launch
                }
                val chapterLists = mangas.associate { manga ->
                    manga.id to chapterDao.getChaptersByMangaIdSync(manga.id).sortedWith(
                        compareBy<ChapterEntity> { it.chapterNumber }.thenBy { it.title }
                    )
                }
                val emptyTitles = mangas
                    .filter { chapterLists[it.id].orEmpty().isEmpty() }
                    .map { it.title }
                if (emptyTitles.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onFinished(
                            false,
                            "Bölümü olmayan manga birleştirilemez: ${emptyTitles.joinToString(", ").take(160)}"
                        )
                    }
                    return@launch
                }

                val first = mangas.first()
                val mergedId = "local_merged_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                val mergedTitle = targetTitle.trim().ifBlank { first.title }
                val mergedCover = mangas.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
                val mergedSource = first.sourceName.ifBlank { "Cihaz / Yerel Mangalar" }
                val existingHistory = preferences.readingHistory.first()
                val latestHistory = existingHistory
                    .filter { it.mangaId in ids }
                    .maxByOrNull { it.updatedAt }

                val mergedManga = MangaEntity(
                    id = mergedId,
                    title = mergedTitle,
                    coverUrl = mergedCover,
                    author = first.author.ifBlank { "Koleksiyon" },
                    artist = first.artist.ifBlank { "Koleksiyon" },
                    description = "$mergedTitle - ${mangas.size} eserin tek bir manga altında birleştirilmiş hâli.",
                    status = first.status,
                    rating = first.rating,
                    sourceName = mergedSource,
                    originLanguage = first.originLanguage,
                    availableLanguages = first.availableLanguages,
                    isFavorite = true,
                    addedAt = System.currentTimeMillis()
                )

                val mergedChapters = mutableListOf<ChapterEntity>()
                var nextChapterNumber = 1.0
                mangas.forEach { manga ->
                    chapterLists[manga.id].orEmpty().forEach { chapter ->
                        mergedChapters += chapter.copy(
                            mangaId = mergedId,
                            chapterNumber = nextChapterNumber
                        )
                        nextChapterNumber += 1.0
                    }
                }

                database.withTransaction {
                    mangaDao.insertManga(mergedManga)
                    chapterDao.upsertChapters(mergedChapters)
                    mangas.forEach { manga ->
                        queueDao.reassignMangaForQueue(
                            oldMangaId = manga.id,
                            newMangaId = mergedId,
                            newTitle = mergedTitle,
                            newCover = mergedCover
                        )
                        chapterDao.deleteChaptersForManga(manga.id)
                        mangaDao.deleteMangaById(manga.id)
                    }
                }

                val sourceIds = ids.toSet()
                val mergedHistory = latestHistory?.copy(
                    mangaId = mergedId,
                    title = mergedTitle,
                    coverUrl = mergedCover,
                    sourceName = mergedSource
                )
                runCatching { preferences.replaceHistoryEntries(sourceIds, mergedHistory) }
                runCatching {
                    val hidden = preferences.hiddenMangaIds.first()
                    if (hidden.any { it in sourceIds }) {
                        preferences.setHiddenMangaIds(hidden - sourceIds)
                    }
                }
                if (mergedSource.startsWith("Web: ")) {
                    runCatching {
                        val page = preferences.webMangaPages.first()[first.id]
                        if (!page.isNullOrBlank()) {
                            preferences.setWebMangaPage(mergedId, page)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    onFinished(
                        true,
                        "'$mergedTitle' oluşturuldu: ${mangas.size} manga, ${mergedChapters.size} bölüm birleştirildi."
                    )
                }
            } catch (e: Exception) {
                Log.e("BabelRead_Merge", "Manga merge failed", e)
                withContext(Dispatchers.Main) {
                    onFinished(false, "Manga birleştirilemedi: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }

    fun deleteMangaCompletely(manga: MangaEntity, onFinished: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.IO) {
            data class StagedFile(val original: File, val staged: File)
            val stagedFiles = mutableListOf<StagedFile>()
            val chapterIdsToCancel = mutableSetOf<String>()
            try {
                val chapters = chapterDao.getChaptersByMangaIdSync(manga.id)
                val queuedChapterIds = queueDao.getAllItemsSync()
                    .filter { it.mangaId == manga.id }
                    .map { it.chapterId }
                chapterIdsToCancel.addAll(chapters.map { it.id } + queuedChapterIds)
                synchronized(downloadStateLock) {
                    chapterIdsToCancel.forEach { chapterId ->
                        cancelledDownloadIds.add(chapterId)
                        cancelDownloadCalls(chapterId)
                    }
                }
                while (synchronized(downloadStateLock) {
                        chapterIdsToCancel.any { it in activeDownloadIds }
                    }
                ) {
                    delay(25)
                }

                val isLocal = manga.sourceName.contains("Yerel", ignoreCase = true) || manga.sourceName.equals("Local", ignoreCase = true) || manga.id.startsWith("local_")
                val pathsToStage = linkedSetOf<File>()
                if (isLocal) {
                    pathsToStage += File(context.filesDir, "local_imports/${manga.id}")
                    pathsToStage += File(context.filesDir, "local_imports/${manga.id.removePrefix("local_")}")
                }

                if (manga.coverUrl.startsWith("/") || manga.coverUrl.startsWith("file://")) {
                    pathsToStage += File(manga.coverUrl.removePrefix("file://"))
                }

                val downloadRoots = downloadBaseDirs()
                // Stage both legacy manga folders and chapter folders across all storage roots.
                downloadRoots.forEach { root -> pathsToStage += File(root, manga.id) }
                chapters.forEach { ch ->
                    if (ch.localImagePathList.isNotBlank()) {
                        PagePathCodec.decode(ch.localImagePathList).forEach { path ->
                            pathsToStage += File(path.trim().removePrefix("file://"))
                        }
                    }
                    downloadRoots.forEach { root -> pathsToStage += File(root, ch.id) }
                }

                val normalizedStagePaths = pathsToStage
                    .map { it.absoluteFile.toPath().normalize().toFile() }
                    .distinctBy { it.absolutePath }
                val topLevelStagePaths = normalizedStagePaths.filterNot { candidate ->
                    normalizedStagePaths.any { parent ->
                        parent != candidate && parent.isDirectory &&
                            candidate.toPath().startsWith(parent.toPath())
                    }
                }

                // Rename first so filesystem cleanup can be rolled back if the Room transaction fails.
                for (original in topLevelStagePaths) {
                    if (!original.exists()) continue
                    val staged = File(original.parentFile, ".delete_${UUID.randomUUID()}")
                    if (!original.renameTo(staged)) {
                        throw IOException("Could not stage file for deletion: ${original.absolutePath}")
                    }
                    stagedFiles += StagedFile(original, staged)
                }

                database.withTransaction {
                    queueDao.deleteQueueItemsForManga(manga.id)
                    chapterDao.deleteChaptersForManga(manga.id)
                    mangaDao.deleteMangaById(manga.id)
                }
                synchronized(downloadStateLock) {
                    chapterIdsToCancel.forEach(cancelledDownloadIds::remove)
                }

                val undeletedPaths = stagedFiles.filterNot { entry ->
                    if (entry.staged.isDirectory) entry.staged.deleteRecursively() else entry.staged.delete()
                }

                withContext(Dispatchers.Main) {
                    val message = if (undeletedPaths.isEmpty()) {
                        "\"${manga.title}\" ve tüm dosyaları cihazdan tamamen silindi."
                    } else {
                        "\"${manga.title}\" kayıtları silindi; bazı dosyalar depolamadan kaldırılamadı."
                    }
                    onFinished(true, message)
                }
            } catch (e: Exception) {
                stagedFiles.asReversed().forEach { entry ->
                    if (entry.staged.exists() && !entry.original.exists() && !entry.staged.renameTo(entry.original)) {
                        Log.e("BabelRead_Delete", "Could not restore staged path ${entry.original.absolutePath}", e)
                    }
                }
                withContext(Dispatchers.Main) {
                    onFinished(false, "Silinirken hata oluştu: ${e.localizedMessage ?: e.message}")
                }
            } finally {
                synchronized(downloadStateLock) {
                    chapterIdsToCancel
                        .filterNot { it in activeDownloadIds }
                        .forEach(cancelledDownloadIds::remove)
                }
            }
        }
    }

    fun clearDownloadedCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val queueItems = queueDao.getAllItemsSync()
                synchronized(downloadStateLock) {
                    queueItems.forEach { item ->
                        cancelledDownloadIds.add(item.chapterId)
                        cancelDownloadCalls(item.chapterId)
                    }
                }
                queueDao.clearAllItems()

                val bases = downloadBaseDirs()
                val downloadedChapters = chapterDao.getDownloadedChapters()
                bases.forEach { base ->
                    if (base.exists() && !base.deleteRecursively()) {
                        Log.w("BabelRead_Cache", "Could not completely clear ${base.absolutePath}")
                    }
                }

                downloadedChapters.forEach { chapter ->
                    val manga = mangaDao.getMangaById(chapter.mangaId)
                    val localImport = manga?.id?.startsWith("local_") == true ||
                        manga?.sourceName?.contains("yerel", ignoreCase = true) == true ||
                        chapter.localImagePathList.contains("${File.separator}local_imports${File.separator}")
                    if (!localImport) {
                        chapterDao.updateDownloadStatus(chapter.id, false, "")
                    }
                }

                synchronized(downloadStateLock) {
                    queueItems.forEach { item ->
                        if (item.chapterId !in activeDownloadIds) cancelledDownloadIds.remove(item.chapterId)
                    }
                }
            } catch (e: Exception) {
                Log.e("BabelRead_Cache", "Failed to clear downloaded cache", e)
            }
        }
    }

    fun importYoloModel(uri: Uri, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val (success, errorMsg) = bubbleDetector.yoloDetector.importModelFromUri(uri)
            onResult(success, errorMsg)
        }
    }

    fun isYoloModelLoaded(): Boolean = bubbleDetector.yoloDetector.isModelLoaded()

    fun getYoloModelInfo(): Map<String, String> = bubbleDetector.yoloDetector.getModelInfo()

    init {
        MangaSourceRegistry.webSourceResolver = { sourceName ->
            webSiteStore.siteBySourceName(sourceName)?.let { site ->
                WebSiteSource(site, context)
            }
        }

        viewModelScope.launch {
            // Eski sürümlerin ayıklanmamış sayfa listeleri önbellekte kalmasın;
            // web sayfa önbelleği yalnızca bu oturumda güvenilir sayılır.
            runCatching { database.webPageCacheDao().deleteOlderThan(Long.MAX_VALUE) }
        }

        viewModelScope.launch {
            preferences.pinnedSources.collect { pinned ->
                _pinnedSources.value = pinned
            }
        }

        viewModelScope.launch {
            preferences.recentSources.collect { recents ->
                _recentSources.value = recents
            }
        }

        viewModelScope.launch {
            preferences.readingHistory.collect { history ->
                _readingHistory.value = history
            }
        }

        @OptIn(FlowPreview::class)
        val debouncedQuery = discoverQuery.debounce(350L)
        viewModelScope.launch {
            combine(
                selectedSource,
                discoverTab,
                debouncedQuery,
                filterGenre,
                filterStatus,
                filterLanguage
            ) { array: Array<String?> ->
                val source = array[0] ?: "Cihaz / Yerel Mangalar"
                val tab = array[1] ?: "Popüler"
                val q = array[2] ?: ""
                val genre = array[3]
                val status = array[4]
                val lang = array[5]
                MangaFilterCriteria(source, tab, q, genre, status, lang)
            }.collect { criteria ->
                currentDiscoverPage.value = 1
                _isDiscoverEndReached.value = false
                fetchDiscoverItems(criteria, isNextPage = false, pageToFetch = 1)
            }
        }

        viewModelScope.launch {
            combine(
                preferences.updateCheckInterval,
                preferences.notificationNewChapter
            ) { hours, enabled ->
                Pair(hours, enabled)
            }.collect { (hours, enabled) ->
                if (enabled) {
                    NewChapterWorker.scheduleCheck(context, hours)
                } else {
                    runCatching {
                        androidx.work.WorkManager.getInstance(context)
                            .cancelUniqueWork(NewChapterWorker.UNIQUE_WORK_NAME)
                    }
                }
            }
        }

        viewModelScope.launch {
            queueDao.getQueueFlow().collect {
                _downloadQueue.value = it
            }
        }

        viewModelScope.launch {
            runCatching { queueDao.requeueFailedDownloads() }
        }

        optimizeTranslationCacheStorage()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { purgeExpiredTranslationCache() }
        }
        startDownloadProcessor()
    }

    override fun onCleared() {
        viewModelScope.coroutineContext.cancelChildren()
        prefetchJobMap.values.forEach { it.cancel() }
        prefetchJobMap.clear()
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
        activePageJobs.values.forEach { it.cancel() }
        activePageJobs.clear()
        inProgressPages.clear()
        _isTranslationLoading.value = false
        pageBitmapCache.evictAll()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { bubbleDetector.yoloDetector.closeSession() }
        }
        runCatching { translationService.closeAllTranslators() }
        super.onCleared()
    }
}
