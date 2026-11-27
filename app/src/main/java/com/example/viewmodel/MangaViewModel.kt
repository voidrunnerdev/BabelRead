package com.example.viewmodel

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.db.*
import com.example.data.linked.LinkedFolderManager
import com.example.data.linked.LinkedPageLocator
import com.example.data.linked.LinkedPageResolver
import com.example.data.linked.LinkedScanResult
import com.example.data.linked.LinkedSourceInfo
import com.example.data.net.CloudflareChallengeException
import com.example.data.net.toSafeWebViewHeaders
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
import com.example.i18n.TranslationLanguages
import eu.kanade.tachiyomi.source.model.FilterList
import com.example.service.BubbleBox
import com.example.service.BubbleDetectionOutput
import com.example.service.BubbleDetector
import com.example.service.DetectorModel
import com.example.service.BulkLocalImportResult
import com.example.service.LocalMangaImporter
import com.example.service.TextBlockObj
import com.example.service.TranslationRequest
import com.example.service.TranslationService
import com.example.util.BubbleContourExtractor
import com.example.util.BubbleLayoutGeometry
import com.example.util.ContourScanlineProfile
import com.example.util.ReaderBitmapPolicy
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
    private val linkedFolderDao = database.linkedFolderDao()
    private val linkedPageResolver = LinkedPageResolver(context)
    private val linkedFolderManager = LinkedFolderManager(context, database, linkedPageResolver)

    val preferences = MangaPreferences(context)
    val translationService = TranslationService(correctionDao, context)
    val bubbleDetector = BubbleDetector(context)
    val localMangaImporter = LocalMangaImporter(context, mangaDao, chapterDao)
    val webSiteStore = WebSiteStore(preferences, viewModelScope)

    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _importStatusText = MutableStateFlow("")
    val importStatusText = _importStatusText.asStateFlow()

    private val _linkedSources = MutableStateFlow<List<LinkedSourceInfo>>(emptyList())
    val linkedSources = _linkedSources.asStateFlow()

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

    private val _sourceFilters = MutableStateFlow(DiscoverFilterState())
    val sourceFilters = _sourceFilters.asStateFlow()
    private val filterSessions = mutableMapOf<String, SourceFilterSession>()
    private var filterLoadGeneration = 0L

    private data class SourceFilterSession(
        val filterList: FilterList,
        val defaultNodes: List<SourceFilterNode>,
        var nodes: List<SourceFilterNode>,
        var version: Int = 0
    )

    private val _discoverLoading = MutableStateFlow(false)
    val discoverLoading = _discoverLoading.asStateFlow()

    private val _discoverItems = MutableStateFlow<List<MangaModel>>(emptyList())
    val discoverItems = _discoverItems.asStateFlow()

    private val _discoverError = MutableStateFlow<String?>(null)
    val discoverError = _discoverError.asStateFlow()

    val currentDiscoverPage = MutableStateFlow(1)
    private val _isDiscoverEndReached = MutableStateFlow(false)
    val isEndReached = _isDiscoverEndReached.asStateFlow()

    fun loadSourceFilters(sourceName: String) {
        val generation = ++filterLoadGeneration
        filterSessions[sourceName]?.let { session ->
            publishSourceFilters(sourceName, session)
            return
        }
        _sourceFilters.value = DiscoverFilterState(sourceName = sourceName, loading = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    MangaSourceRegistry.getSource(sourceName).getFilterList()
                }
            }
            if (generation != filterLoadGeneration) return@launch
            result.onSuccess { filters ->
                val nodes = SourceFilterAdapter.build(filters)
                val session = SourceFilterSession(
                    filterList = filters,
                    defaultNodes = nodes,
                    nodes = nodes
                )
                filterSessions[sourceName] = session
                publishSourceFilters(sourceName, session)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (selectedSource.value == sourceName) {
                    _sourceFilters.value = DiscoverFilterState(
                        sourceName = sourceName,
                        error = error.detailedMessage()
                    )
                }
            }
        }
    }

    fun applySourceFilters(sourceName: String, nodes: List<SourceFilterNode>) {
        val session = filterSessions[sourceName] ?: return
        SourceFilterAdapter.apply(session.filterList, nodes)
        session.nodes = SourceFilterAdapter.build(session.filterList)
        session.version++
        publishSourceFilters(sourceName, session)
        if (selectedSource.value == sourceName) {
            fetchDiscoverItems(discoverCriteria(), isNextPage = false, pageToFetch = 1)
        }
    }

    fun clearSourceFilters(sourceName: String) {
        val session = filterSessions[sourceName] ?: return
        SourceFilterAdapter.apply(session.filterList, session.defaultNodes)
        session.nodes = session.defaultNodes
        session.version++
        publishSourceFilters(sourceName, session)
        if (selectedSource.value == sourceName) {
            fetchDiscoverItems(discoverCriteria(), isNextPage = false, pageToFetch = 1)
        }
    }

    fun clearSourceFilter(sourceName: String, key: String) {
        val session = filterSessions[sourceName] ?: return
        val replacement = SourceFilterAdapter.findNode(session.defaultNodes, key) ?: return
        val nodes = SourceFilterAdapter.replaceNode(session.nodes, key, replacement)
        applySourceFilters(sourceName, nodes)
    }

    private fun publishSourceFilters(sourceName: String, session: SourceFilterSession) {
        if (selectedSource.value != sourceName) return
        _sourceFilters.value = DiscoverFilterState(
            sourceName = sourceName,
            nodes = session.nodes,
            defaultNodes = session.defaultNodes,
            activeFilters = SourceFilterAdapter.activeFilters(
                session.defaultNodes,
                session.nodes
            ),
            version = session.version
        )
    }

    private fun appliedFilters(sourceName: String): FilterList {
        val session = filterSessions[sourceName] ?: return FilterList()
        val active = SourceFilterAdapter.activeFilters(session.defaultNodes, session.nodes)
        return if (active.isEmpty()) FilterList() else session.filterList
    }

    private fun discoverCriteria(): MangaFilterCriteria = MangaFilterCriteria(
        source = selectedSource.value,
        tab = discoverTab.value,
        query = discoverQuery.value,
        filters = appliedFilters(selectedSource.value)
    )

    fun loadNextDiscoverPage() {
        if (_discoverLoading.value || _isDiscoverEndReached.value) return
        val nextPage = currentDiscoverPage.value + 1
        fetchDiscoverItems(discoverCriteria(), isNextPage = true, pageToFetch = nextPage)
    }

    fun refreshDiscover() {
        if (_discoverLoading.value) return
        fetchDiscoverItems(discoverCriteria(), isNextPage = false, pageToFetch = 1)
    }

    private data class MangaFilterCriteria(
        val source: String,
        val tab: String,
        val query: String,
        val filters: FilterList = FilterList()
    )

    private var discoverJob: Job? = null
    private val discoverGeneration = AtomicLong(0L)

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
                            beginCloudflareVerification(e) {
                                runGlobalSearch(rawQuery)
                            }
                            GlobalSearchGroup(source.name, emptyList(), "Cloudflare doğrulaması gerekiyor. Doğrulayıp yeniden deneyin.")
                        } catch (t: Throwable) {
                            GlobalSearchGroup(source.name, emptyList(), t.detailedMessage())
                        }
                        _globalSearchGroups.update { it + group }
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
    data class CfChallenge(
        val host: String,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
    )

    private val _cfChallenge = MutableStateFlow<CfChallenge?>(null)
    val cfChallenge = _cfChallenge.asStateFlow()

    private var cfRetryAction: (() -> Unit)? = null

    fun beginCloudflareVerification(e: CloudflareChallengeException, retry: () -> Unit) {
        cfRetryAction = retry
        _cfChallenge.value = CfChallenge(e.host, e.challengedUrl, e.method, e.headers)
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
            fetchDiscoverItems(discoverCriteria(), isNextPage = false, pageToFetch = 1)
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
                beginCloudflareVerification(e) {
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
                beginCloudflareVerification(e) {
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
        readerGeneration.incrementAndGet()
        translationSequenceJob?.cancel()
        translationSequenceJob = null
        detectionPrefetchJob?.cancel()
        detectionPrefetchJob = null
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
        inProgressPages.clear()
        _isTranslationLoading.value = false
        prefetchJobMap.values.forEach { it.cancel() }
        prefetchJobMap.clear()
        activePageJobs.values.forEach { it.cancel() }
        activePageJobs.clear()
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
        _detectedPanelsMap.value = emptyMap()
        _maskedPagesMap.value = emptyMap()
        _translationActive.value = false
        translationPipelineCursor = 0
        evictReaderBitmapCaches()
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
        }
        val requestId = discoverGeneration.incrementAndGet()
        _discoverLoading.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _discoverError.value = null
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
                } else if (criteria.query.isNotBlank() || criteria.filters.isNotEmpty()) {
                    src.searchMangas(
                        query = criteria.query,
                        page = pageToFetch,
                        filters = criteria.filters
                    )
                } else {
                    src.getMangas(
                        page = pageToFetch,
                        tab = criteria.tab,
                        filters = criteria.filters
                    )
                }

                if (requestId != discoverGeneration.get()) return@launch
                if (isLocalSource && criteria.query.isNotBlank()) {
                    items = items.filter { it.title.contains(criteria.query.trim(), ignoreCase = true) }
                }

                if (requestId != discoverGeneration.get()) return@launch
                currentDiscoverPage.value = pageToFetch
                if (items.isEmpty()) {
                    _isDiscoverEndReached.value = true
                } else {
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
                    beginCloudflareVerification(e) {
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
                    beginCloudflareVerification(e) {
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

    private val _detectedPanelsMap = MutableStateFlow<Map<Int, List<RectF>>>(emptyMap())
    private val foregroundDetectingPages = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

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

    private val pageCacheBudgetBytes = ReaderBitmapPolicy.displayCacheBytes(Runtime.getRuntime().maxMemory())
    private val fullResCacheBudgetBytes = ReaderBitmapPolicy.fullResCacheBytes(Runtime.getRuntime().maxMemory())
    private val maskedPageBudgetBytes = (Runtime.getRuntime().maxMemory() / 12L)
        .coerceIn(6L * 1024L * 1024L, 40L * 1024L * 1024L)
    private val pageBitmapCache = object : LruCache<String, Bitmap>(pageCacheBudgetBytes.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount.coerceAtLeast(1)
        }
    }
    private val fullResBitmapCache = object : LruCache<String, Bitmap>(fullResCacheBudgetBytes.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount.coerceAtLeast(1)
        }
    }
    private val pageMemoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = evictReaderBitmapCaches()
        override fun onTrimMemory(level: Int) {
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
            ) {
                evictReaderBitmapCaches()
            }
        }
    }

    private fun evictReaderBitmapCaches() {
        pageBitmapCache.evictAll()
        fullResBitmapCache.evictAll()
    }

    private fun putBitmapIfFits(cache: LruCache<String, Bitmap>, key: String, bitmap: Bitmap) {
        val entryBytes = bitmap.allocationByteCount.toLong().coerceAtLeast(1L)
        if (!ReaderBitmapPolicy.cacheEntryFits(entryBytes, cache.maxSize().toLong())) return
        cache.put(key, bitmap)
    }

    @Volatile private var lastObservedPageQuality: String? = null

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
            _detectedPanelsMap.value = emptyMap()
            _maskedPagesMap.value = emptyMap()
            _currentPageIndex.value = 0
            evictReaderBitmapCaches()

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
                    if (_translationActive.value || preferences.autoTranslate.first()) {
                        toggleLiveTranslation(true)
                    }
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
                if (dbChapter.linkedFolderId != null) {
                    val linkedFolder = linkedFolderDao.getLinkedFolderById(dbChapter.linkedFolderId)
                    if (linkedFolder == null) {
                        throw IOException("Bağlı klasör kaydı bulunamadı. Kaynağı yeniden bağlayın.")
                    }
                    if (!LinkedFolderManager.hasReadPermission(context, linkedFolder.treeUri)) {
                        throw IOException(
                            "Bağlı klasör izni kayboldu. Kütüphaneden 'Yeniden Bağla' ile klasörü tekrar seçin."
                        )
                    }
                    pages = LinkedPageLocator.decodeList(dbChapter.linkedPageLocator)
                } else if (dbChapter.isDownloaded && dbChapter.localImagePathList.isNotBlank()) {
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
                beginCloudflareVerification(e) {
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
        val previousIndex = _currentPageIndex.value
        _currentPageIndex.value = index
        recordHistory(index)
        prefetchNeighborPages(index, previousIndex)
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
        const val DEFAULT_TRANSLATION_CACHE_TTL_DAYS = 30
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
                val activeOcr = preferences.ocrEngine.first()
                if (translationService.isRegionOcrEngine(activeOcr)) {
                    val ready = translationService.prewarmOcrEngine(activeOcr)
                    if (ready) {
                        Log.d("TR", "$activeOcr prewarm tamamlandı")
                    } else {
                        Log.w("TR", "$activeOcr prewarm tamamlanamadı")
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
                    // Yield to active foreground translation pages so background prefetch
                    // never locks ONNX Runtime behind a future page.
                    var yieldCount = 0
                    while (readerGeneration.get() == generation &&
                        _translationActive.value &&
                        (foregroundDetectingPages.isNotEmpty() ||
                            (pageIndex != _currentPageIndex.value &&
                                !_detectedBubblesMap.value.containsKey(_currentPageIndex.value) &&
                                inProgressPages.contains("$generation:${_currentPageIndex.value}"))) &&
                        yieldCount < 25
                    ) {
                        delay(60)
                        yieldCount++
                    }
                    if (readerGeneration.get() != generation || !_translationActive.value) break
                    if (_detectedBubblesMap.value.containsKey(pageIndex)) continue

                    val bitmap = getPageBitmap(pageIndex, fullRes = true) ?: getPageBitmap(pageIndex) ?: continue
                    val confThreshold = preferences.confidenceThreshold.first()
                    val output: BubbleDetectionOutput = try {
                        bubbleDetector.detectBubblesWithPanels(bitmap, pageIndex, confThreshold)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        BubbleDetectionOutput(emptyList(), emptyList())
                    }
                    _detectedBubblesMap.update { current ->
                        if (readerGeneration.get() == generation && !current.containsKey(pageIndex)) {
                            current + (pageIndex to output.bubbles)
                        } else {
                            current
                        }
                    }
                    _detectedPanelsMap.update { current ->
                        if (readerGeneration.get() == generation && !current.containsKey(pageIndex)) {
                            current + (pageIndex to output.panels)
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

    private fun prefetchNeighborPages(currentIndex: Int, previousIndex: Int = currentIndex) {
        val pages = _readerPages.value
        val movingForward = currentIndex > previousIndex
        val movingBackward = currentIndex < previousIndex
        val behind = if (movingBackward) 3 else 2
        val ahead = if (movingForward) 3 else 2
        val range = (currentIndex - behind)..(currentIndex + ahead)
        val validIndices = range.filter { it in pages.indices }.toSet()

        prefetchJobMap.entries.toList()
            .filter { it.key !in validIndices }
            .forEach { (index, job) ->
                job.cancel()
                prefetchJobMap.remove(index)
            }

        val chapterId = _activeChapter.value?.id ?: return
        val quality = pageQuality.value
        for (i in range) {
            if (i !in validIndices) continue
            if (peekDisplayBitmap(chapterId, i, quality) != null) continue
            if (prefetchJobMap[i]?.isActive == true) continue
            prefetchJobMap[i] = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val currentQuality = preferences.pageQuality.first()
                    if (peekDisplayBitmap(chapterId, i, currentQuality) != null) return@launch
                    val path = pages[i]
                    prefetchDecodeSemaphore.withPermit {
                        loadCachedPageBitmap(
                            pageCacheKey(chapterId, i, currentQuality, fullRes = false),
                            path,
                            fullRes = false,
                            quality = currentQuality
                        )
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

    private val activePageJobs = java.util.concurrent.ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val pageJobsMutex = Mutex()

    private fun pageCacheKey(chapterId: String, index: Int, quality: String, fullRes: Boolean): String {
        val profile = if (fullRes) {
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

    private fun bitmapCacheFor(fullRes: Boolean): LruCache<String, Bitmap> =
        if (fullRes) fullResBitmapCache else pageBitmapCache

    fun peekPageBitmap(chapterId: String, index: Int): Bitmap? {
        if (_activeChapter.value?.id != chapterId) return null
        if (index !in _readerPages.value.indices) return null
        return peekDisplayBitmap(chapterId, index, pageQuality.value)
    }

    private fun peekDisplayBitmap(chapterId: String, index: Int, quality: String): Bitmap? {
        val cacheKey = pageCacheKey(chapterId, index, quality, fullRes = false)
        val cached = pageBitmapCache.get(cacheKey) ?: return null
        if (cached.isRecycled) {
            pageBitmapCache.remove(cacheKey)
            return null
        }
        return cached
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
        val cacheKey = pageCacheKey(chapterId, index, quality, fullRes)
        val bitmap = loadCachedPageBitmap(cacheKey, path, fullRes, quality)
        return bitmap?.takeIf {
            !it.isRecycled &&
                readerGeneration.get() == generation &&
                _activeChapter.value?.id == chapterId
        }
    }

    private suspend fun loadCachedPageBitmap(cacheKey: String, path: String, fullRes: Boolean, quality: String): Bitmap? {
        val cache = bitmapCacheFor(fullRes)
        val cached = cache.get(cacheKey)
        if (cached != null) {
            if (!cached.isRecycled) return cached
            cache.remove(cacheKey)
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
                            putBitmapIfFits(cache, cacheKey, bmp)
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
            val maxPixels = if (fullRes) {
                ReaderBitmapPolicy.FULL_RES_MAX_PIXELS
            } else {
                ReaderBitmapPolicy.DISPLAY_MAX_PIXELS
            }
            val targetWidth = if (fullRes) {
                0
            } else {
                ReaderBitmapPolicy.displayTargetWidthPx(
                    context.resources.displayMetrics.widthPixels,
                    quality
                )
            }
            if (LinkedPageLocator.isEncodedLocator(path)) {
                return@withContext linkedPageResolver.decodeSampled(path, maxPixels, targetWidth)
            }
            fun sampleFor(width: Int, height: Int): Int {
                return ReaderBitmapPolicy.chooseSampleSize(width, height, targetWidth, maxPixels)
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
            beginCloudflareVerification(e) {
                _readerPageRetryTick.update { it + 1 }
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
        inProgressPages.clear()
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

    private suspend fun resetActiveChapterTranslationState(
        clearDetections: Boolean = false,
        restartIfActive: Boolean = true
    ) {
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
        if (clearDetections) {
            detectionPrefetchJob?.cancel()
            detectionPrefetchJob = null
            _detectedBubblesMap.value = emptyMap()
            _detectedPanelsMap.value = emptyMap()
        }
        _isTranslationLoading.value = false

        if (restartIfActive && _translationActive.value && _activeChapter.value != null) {
            startSequentialTranslation()
        }
    }

    fun setTranslationEngine(engine: String) {
        viewModelScope.launch {
            if (preferences.translationEngine.first() == engine) return@launch
            preferences.setTranslationEngine(engine)
            resetActiveChapterTranslationState()
        }
    }

    data class GeminiModelsUiState(
        val models: List<com.example.service.GeminiModelInfo> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val loadedAtMs: Long = 0L,
        val apiKeyFingerprint: String = ""
    )

    private val _geminiModelsState = MutableStateFlow(GeminiModelsUiState())
    val geminiModelsState = _geminiModelsState.asStateFlow()

    fun refreshGeminiModels(force: Boolean = false, apiKeyOverride: String? = null) {
        viewModelScope.launch {
            val current = _geminiModelsState.value
            if (current.isLoading) return@launch
            val apiKey = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: preferences.geminiApiKey.first().trim()
            if (apiKey.isEmpty()) {
                _geminiModelsState.value = GeminiModelsUiState(
                    models = emptyList(),
                    isLoading = false,
                    error = "Modelleri listelemek için önce Gemini API anahtarını girin."
                )
                return@launch
            }
            val fingerprint = sha1Hex(apiKey).take(16)
            val now = System.currentTimeMillis()
            val sameKeyAndFresh = current.apiKeyFingerprint == fingerprint &&
                current.models.isNotEmpty() &&
                now - current.loadedAtMs < 5 * 60_000L
            if (!force && sameKeyAndFresh) {
                return@launch
            }
            _geminiModelsState.value = current.copy(
                models = if (current.apiKeyFingerprint == fingerprint) current.models else emptyList(),
                isLoading = true,
                error = null,
                apiKeyFingerprint = fingerprint
            )
            val result = translationService.fetchAvailableGeminiModels(apiKey)
            result.onSuccess { list ->
                _geminiModelsState.value = GeminiModelsUiState(
                    models = list,
                    isLoading = false,
                    error = if (list.isEmpty()) "Uygun çeviri modeli bulunamadı." else null,
                    loadedAtMs = System.currentTimeMillis(),
                    apiKeyFingerprint = fingerprint
                )
            }.onFailure { err ->
                _geminiModelsState.value = _geminiModelsState.value.copy(
                    isLoading = false,
                    error = err.localizedMessage ?: err.message ?: "Modeller yüklenemedi."
                )
            }
        }
    }

    fun setGeminiModel(model: String) {
        val trimmed = model.trim().removePrefix("models/").trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (preferences.geminiModel.first() == trimmed) return@launch
            preferences.setGeminiModel(trimmed)
            if (preferences.translationEngine.first().equals("Gemini", ignoreCase = true)) {
                resetActiveChapterTranslationState()
            }
        }
    }

    fun setYoloAiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (preferences.yoloAiEnabled.first() == enabled) return@launch
            preferences.setYoloAiEnabled(enabled)
            resetActiveChapterTranslationState(clearDetections = true)
        }
    }

    fun setTranslationCacheTtlDays(days: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.setTranslationCacheTtlDays(days)
            if (days > 0) {
                purgeExpiredTranslationCache()
            }
        }
    }

    fun isPpOcrEnglishModelInstalled(): Boolean =
        translationService.isPpOcrEnglishModelInstalled()

    fun setOcrEngine(engine: String) {
        viewModelScope.launch {
            if (preferences.ocrEngine.first() == engine) return@launch
            preferences.setOcrEngine(engine)
            if (translationService.isRegionOcrEngine(engine)) {
                prewarmPpOcrIfNeeded()
            }
            resetActiveChapterTranslationState()
        }
    }

    fun setTranslationTargetLanguage(code: String) {
        val resolved = TranslationLanguages.find(code)?.code ?: return
        viewModelScope.launch {
            val previous = preferences.translationTargetLang.first()
            preferences.setTranslationTargetLang(resolved)
            preferences.addRecentTranslationLang(resolved)
            if (previous != resolved) resetActiveChapterTranslationState()
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
            if (previous != resolved) resetActiveChapterTranslationState()
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

    private fun isCacheableSource(chapterId: String, sourceName: String): Boolean =
        chapterId.isNotBlank() && sourceName.isNotBlank()

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
                val affectsActive = sourceName == null || _readerManga.value?.sourceName == sourceName
                if (affectsActive) {
                    withContext(Dispatchers.Main) {
                        resetActiveChapterTranslationState(restartIfActive = true)
                    }
                }
                Log.d("TR", "Çeviri önbelleği temizlendi: ${sourceName ?: "TÜMÜ"} (${paths.size} sayfa)")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w("TR", "Çeviri önbelleği temizlenemedi: ${t.message}")
            }
        }
    }

    private suspend fun clearTranslationCacheForChapters(chapterIds: Collection<String>) {
        if (chapterIds.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            for (chapterId in chapterIds.distinct()) {
                if (chapterId.isBlank()) continue
                val paths = runCatching { translationCacheDao.pathsByChapterPrefix(chapterId) }
                    .getOrDefault(emptyList())
                for (path in paths) {
                    runCatching { File(path).delete() }
                }
                runCatching { translationCacheDao.deleteByChapterPrefix(chapterId) }
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

    /** Çeviri önbelleği dil, OCR motoru, çeviri motoru/modeli ve balon algılama ayarına göre ayrılır. */
    private suspend fun translationCacheId(chapterId: String): Pair<String, String> {
        val target = preferences.translationTargetLang.first()
        val ocrEngine = preferences.ocrEngine.first()
        val translationEngine = preferences.translationEngine.first()
        val engineKey = if (translationEngine.equals("Gemini", ignoreCase = true)) {
            val geminiModel = preferences.geminiModel.first().trim().ifEmpty { "default" }
            "Gemini:$geminiModel"
        } else {
            translationEngine
        }
        val yoloSuffix = if (preferences.yoloAiEnabled.first()) "" else "|nodet"
        val sourceDisplay = resolveSourceDisplayForCache()
        val sourceCode = TranslationLanguages.codeForDisplayName(sourceDisplay) ?: "en"
        return "$chapterId|$sourceCode>$target|$ocrEngine|$engineKey$yoloSuffix" to target
    }

    private suspend fun activeTranslationCacheTtlMs(): Long? {
        val days = preferences.translationCacheTtlDays.first()
        if (days <= 0) return null
        return days.toLong() * 24L * 60L * 60L * 1000L
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
        val ttlMs = activeTranslationCacheTtlMs()
        if (ttlMs != null && System.currentTimeMillis() - row.createdAt > ttlMs) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { File(row.maskedPath).delete() }
                runCatching { translationCacheDao.deletePage(cacheId, pageIdx) }
            }
            return false
        }
        if (!TranslationCacheUrlMatcher.matches(row.pageUrl, pageUrl)) return false
        val file = File(row.maskedPath)
        if (!file.exists()) {
            withContext(NonCancellable + Dispatchers.IO) {
                translationCacheDao.deletePage(cacheId, pageIdx)
            }
            return false
        }
        val cachedBlocks = withContext(Dispatchers.IO) {
            runCatching {
                org.json.JSONArray(row.blocksJson)
                TranslationCacheCodec.deserializeBlocks(row.blocksJson)
            }.getOrNull()
        } ?: return false
        val loadedBitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(row.maskedPath) }.getOrNull()
        }
        if (loadedBitmap == null) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { file.delete() }
                translationCacheDao.deletePage(cacheId, pageIdx)
            }
            return false
        }
        if (readerGeneration.get() != generation) {
            if (!loadedBitmap.isRecycled) loadedBitmap.recycle()
            return false
        }

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
        val reconstructedBubbles = blocks.filter { it.isBubble && !it.bubbleBox.isEmpty }.mapIndexed { idx, block ->
            val bmpW = loadedBitmap.width.toFloat().coerceAtLeast(1f)
            val bmpH = loadedBitmap.height.toFloat().coerceAtLeast(1f)
            BubbleBox(
                id = idx + 1,
                bounds = RectF(
                    block.bubbleBox.left / bmpW,
                    block.bubbleBox.top / bmpH,
                    block.bubbleBox.right / bmpW,
                    block.bubbleBox.bottom / bmpH
                ),
                confidence = 1.0f,
                label = "bubble",
                originalText = block.originalText,
                translatedText = block.translatedText,
                contourProfile = block.contourProfile
            )
        }
        if (reconstructedBubbles.isNotEmpty()) {
            _detectedBubblesMap.update { current ->
                if (!current.containsKey(pageIdx)) current + (pageIdx to reconstructedBubbles) else current
            }
        }
        storeMaskedPage(pageIdx, loadedBitmap, generation)
        // LRU / kayan TTL: yakın zamanda okunan sayfalar bütçe budamasında korunur.
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { translationCacheDao.touchPage(cacheId, pageIdx, System.currentTimeMillis()) }
        }
        Log.d("TR", "Sayfa $pageIdx önbellekten yüklendi ($cacheId)")
        return true
    }

    private suspend fun persistTranslatedPage(
        pageIdx: Int,
        blocks: List<TextBlockObj>,
        masked: Bitmap,
        chapterId: String? = _activeChapter.value?.id,
        sourceName: String? = _readerManga.value?.sourceName,
        pageUrl: String? = _readerPages.value.getOrNull(pageIdx)
    ) {
        if (chapterId.isNullOrBlank() || sourceName.isNullOrBlank() || pageUrl.isNullOrBlank()) return
        if (!isCacheableSource(chapterId, sourceName)) return
        val (cacheId, _) = translationCacheId(chapterId)
        withContext(NonCancellable + Dispatchers.IO) {
            if (masked.isRecycled) return@withContext
            val dir = File(context.filesDir, "translation_cache").apply { mkdirs() }
            val file = File(dir, sha1Hex(cacheId).take(16) + "_" + pageIdx + ".webp")
            val tmpFile = File(dir, sha1Hex(cacheId).take(16) + "_" + pageIdx + ".webp.tmp")
            try {
                val compressed = FileOutputStream(tmpFile).use { out ->
                    masked.compress(translationCacheFormat(), 80, out)
                }
                if (!compressed || tmpFile.length() <= 0L) {
                    tmpFile.delete()
                    return@withContext
                }
                if (file.exists() && !file.delete()) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                } else if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
                translationCacheDao.put(
                    TranslatedPageCacheEntity(
                        chapterId = cacheId,
                        pageIndex = pageIdx,
                        pageUrl = TranslationCacheUrlMatcher.sanitizeForStorage(pageUrl),
                        sourceName = sourceName,
                        blocksJson = TranslationCacheCodec.serializeBlocks(blocks),
                        maskedPath = file.absolutePath,
                        byteSize = file.length(),
                        createdAt = System.currentTimeMillis()
                    )
                )
                pruneTranslationCache()
            } catch (t: Throwable) {
                runCatching { tmpFile.delete() }
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

    /** Süresi dolan çeviri önbelleği kayıtlarını ve görsellerini siler (0 = süresiz). */
    private suspend fun purgeExpiredTranslationCache() {
        val ttlMs = activeTranslationCacheTtlMs() ?: return
        val cutoff = System.currentTimeMillis() - ttlMs
        val expired = withContext(Dispatchers.IO) {
            runCatching { translationCacheDao.expired(cutoff) }.getOrDefault(emptyList())
        }
        if (expired.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            for (row in expired) {
                runCatching { File(row.maskedPath).delete() }
            }
            runCatching { translationCacheDao.deleteExpired(cutoff) }
        }
        Log.d("TR", "Çeviri önbelleğinden ${expired.size} eski sayfa silindi")
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
                val failedKeys = HashSet<Pair<String, Int>>()
                while (processed < maxPerLaunch) {
                    val batch = translationCacheDao.cachedPagesBySuffix(".jpg", 60)
                        .filter { (it.chapterId to it.pageIndex) !in failedKeys }
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
                        val webpFile = File(file.parentFile, file.nameWithoutExtension + ".webp")
                        val tmp = File(file.parentFile, file.nameWithoutExtension + ".webp.tmp")
                        val ok = runCatching {
                            FileOutputStream(tmp).use { out ->
                                bitmap.compress(translationCacheFormat(), 80, out)
                            }
                            tmp.length() > 0
                        }.getOrDefault(false)
                        bitmap.recycle()
                        if (ok && (!webpFile.exists() || webpFile.delete()) && tmp.renameTo(webpFile)) {
                            translationCacheDao.updateMaskedPathAndSize(
                                row.chapterId,
                                row.pageIndex,
                                webpFile.absolutePath,
                                webpFile.length()
                            )
                            if (file.absolutePath != webpFile.absolutePath) {
                                runCatching { file.delete() }
                            }
                            converted++
                        } else {
                            failedKeys.add(row.chapterId to row.pageIndex)
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
                val anchorIdx = _currentPageIndex.value
                val next = linkedMapOf<Int, Bitmap>()
                var retainedBytes = 0L
                val candidates = (current + (pageIdx to bitmap)).entries
                    .sortedBy { kotlin.math.abs(it.key - anchorIdx) }
                for ((index, candidate) in candidates) {
                    if (candidate.isRecycled) continue
                    if (kotlin.math.abs(index - anchorIdx) > 2 && index != pageIdx) continue
                    val bytes = candidate.allocationByteCount.toLong()
                    if (index == anchorIdx || index == pageIdx || retainedBytes + bytes <= maskedPageBudgetBytes) {
                        next[index] = candidate
                        retainedBytes += bytes
                    }
                }
                next
            }
        }
    }

    private fun rectArea(rect: Rect): Long = rect.width().toLong() * rect.height()

    private fun rectOverlapArea(a: Rect, b: Rect): Long {
        val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return w.toLong() * h
    }

    private fun isDuplicateRegion(a: Rect, b: Rect): Boolean {
        if (a.isEmpty || b.isEmpty) return false
        val intersection = rectOverlapArea(a, b).toFloat()
        val areaA = rectArea(a).toFloat()
        val areaB = rectArea(b).toFloat()
        val minArea = minOf(areaA, areaB).coerceAtLeast(1f)
        val unionArea = (areaA + areaB - intersection).coerceAtLeast(1f)
        return intersection / unionArea >= 0.55f || intersection / minArea >= 0.85f
    }

    private fun dedupeRegions(rects: List<Rect>): List<Rect> {
        val result = mutableListOf<Rect>()
        for (rect in rects) {
            if (rect.isEmpty) continue
            val duplicateIndex = result.indexOfFirst { existing -> isDuplicateRegion(existing, rect) }
            if (duplicateIndex < 0) {
                result.add(Rect(rect))
            } else if (rectArea(rect) > rectArea(result[duplicateIndex])) {
                result[duplicateIndex] = Rect(rect)
            }
        }
        return result
    }

    /**
     * Bölge OCR için YOLO kutularını sadeleştirir: balon kutuları esas alınır,
     * balonla örtüşen metin kutuları ve başka bir bölgeyle çok örtüşen kopyalar
     * elenir. Yalnızca balon içi metin bölgeleri döner; text_free hariç tutulur.
     */
    private fun buildBubbleOcrRegions(bubbles: List<BubbleBox>, width: Int, height: Int): List<Rect> {
        if (bubbles.isEmpty() || width <= 0 || height <= 0) return emptyList()

        val nonPanelBubbles = bubbles.filterNot { it.label.equals("panel", ignoreCase = true) }
        val balloonLabels = setOf("bubble", "balloon")
        val textLabels = setOf("text_bubble", "dialogue_text", "text")
        val balloonRects = nonPanelBubbles.filter { it.label in balloonLabels }
            .map { it.toPixelRect(width, height) }
            .filter { !it.isEmpty }
        val textRects = nonPanelBubbles.filter { it.label in textLabels }
            .map { it.toPixelRect(width, height) }
            .filter { !it.isEmpty }

        val candidates = mutableListOf<Rect>()
        candidates.addAll(balloonRects)
        for (textRect in textRects) {
            val alreadyCovered = candidates.any { rectOverlapArea(it, textRect) >= rectArea(textRect) * 0.5f }
            if (!alreadyCovered) candidates.add(textRect)
        }
        return dedupeRegions(candidates)
    }

    /**
     * PP-OCRv5 recognition is line-oriented, so prefer the detector's
     * text_bubble boxes over the larger balloon boxes. The line segmenter
     * splits each text block into line crops. When the text boxes do not cover
     * most of a balloon, use that balloon as the OCR region instead.
     */
    private fun buildPpOcrV5Regions(bubbles: List<BubbleBox>, width: Int, height: Int): List<Rect> {
        if (bubbles.isEmpty() || width <= 0 || height <= 0) return emptyList()

        val nonPanelBubbles = bubbles.filterNot { it.label.equals("panel", ignoreCase = true) }
        val textLabels = setOf("text_bubble", "dialogue_text", "text")
        val balloonLabels = setOf("bubble", "balloon")
        val textRects = nonPanelBubbles.filter { it.label in textLabels }
            .map { it.toPixelRect(width, height) }
            .filter { !it.isEmpty }
        val balloonRects = nonPanelBubbles.filter { it.label in balloonLabels }
            .map { it.toPixelRect(width, height) }
            .filter { !it.isEmpty }

        // Prefer tight text boxes, but never drop a balloon whose text boxes are
        // too small to cover it. When the balloon is used as the OCR region, its
        // contained text boxes must be removed; otherwise PP-OCRv5 would read the
        // same glyphs twice (tight box and full balloon) and emit duplicates.
        val fallbackBalloons = balloonRects.filter { balloon ->
            textRects.none { text ->
                rectOverlapArea(balloon, text) >= rectArea(balloon) * 0.5f
            }
        }
        val candidates = textRects.filterNot { text ->
            fallbackBalloons.any { balloon ->
                balloon.contains(text.centerX(), text.centerY()) ||
                    rectOverlapArea(balloon, text) >= rectArea(text) * 0.5f
            }
        }.toMutableList()
        candidates.addAll(fallbackBalloons)
        return dedupeRegions(candidates)
    }

    fun translatePageIfNeeded(pageIdx: Int, forceReload: Boolean = false) {
        Log.d("TR", "translatePageIfNeeded çağrıldı: sayfa=$pageIdx, forceReload=$forceReload")
        val generation = readerGeneration.get()
        val progressKey = "$generation:$pageIdx"
        val cachedBlocksInRam = _translatedPagesMap.value[pageIdx]
        val hasCompleteRamEntry = cachedBlocksInRam != null &&
            (cachedBlocksInRam.isEmpty() || _maskedPagesMap.value.containsKey(pageIdx))
        if (!forceReload && hasCompleteRamEntry) {
            Log.d("TR", "Sayfa $pageIdx zaten çevrili, işlem atlandı")
            return
        }
        if (!inProgressPages.add(progressKey)) {
            Log.d("TR", "Sayfa $pageIdx şu an zaten işleniyor (inProgressPages).")
            return
        }
        val snapshotChapterId = _activeChapter.value?.id
        val snapshotSourceName = _readerManga.value?.sourceName
        val snapshotPageUrl = _readerPages.value.getOrNull(pageIdx)
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
                // Yerel arşivlerde kaynak dil "Yerel" olarak saklanır ve kod
                // karşılığı yoktur. PP-OCRv5 bu sayfaları Japonca varsayarak
                // dikey metin segmentasyonunu çalıştırır; gerçek dil OCR
                // sonrasında detectRealLanguage ile düzeltilir.
                val isPpOcrEnglish = translationService.isPpOcrEnglishEngine(ocrEngine)
                val isPpOcrAny = translationService.isRegionOcrEngine(ocrEngine)
                val localUnknownOrigin = originLang.equals("Yerel", ignoreCase = true) ||
                    originLang.equals("Bilinmiyor", ignoreCase = true)
                val ppOriginLang = when {
                    isPpOcrEnglish && (originCode == "en" || localUnknownOrigin) -> "İngilizce"
                    isPpOcrEnglish -> null
                    originCode == "en" || originCode == "ja" -> originLang
                    localUnknownOrigin -> "Japonca"
                    else -> null
                }
                if (isPpOcrAny) {
                    if (isPpOcrEnglish && ppOriginLang == null) {
                        emitTranslationNotice("PP-OCRv5 EN yalnızca İngilizce kaynakları destekler.")
                        return@launch
                    }
                    if (!isPpOcrEnglish && (originCode == "zh" || originCode == "ko")) {
                        Log.d("TR", "PP-OCRv5: $originLang kaynağı sessizce atlandı (sayfa=$pageIdx)")
                        return@launch
                    }
                    if (ppOriginLang == null) {
                        emitTranslationNotice("$ocrEngine yalnızca İngilizce ve Japonca kaynakları destekler.")
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
                    val detectionOutput = if (yoloEnabled) {
                        val prefetchedBubbles = _detectedBubblesMap.value[pageIdx]
                        if (prefetchedBubbles != null) {
                            Log.d("TR", "Sayfa $pageIdx balon tespiti ön-taramadan alındı (${prefetchedBubbles.size} kutu)")
                            BubbleDetectionOutput(
                                bubbles = prefetchedBubbles,
                                panels = _detectedPanelsMap.value[pageIdx].orEmpty()
                            )
                        } else {
                            foregroundDetectingPages.add(pageIdx)
                            try {
                                bubbleDetector.detectBubblesWithPanels(bitmap, pageIdx, confThreshold).also { output ->
                                    _detectedPanelsMap.update { current ->
                                        if (readerGeneration.get() == generation) current + (pageIdx to output.panels) else current
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                BubbleDetectionOutput(emptyList(), emptyList())
                            } finally {
                                foregroundDetectingPages.remove(pageIdx)
                            }
                        }
                    } else BubbleDetectionOutput(emptyList(), emptyList())
                    val yoloBubbles = detectionOutput.bubbles
                    val pagePanels = detectionOutput.panels.mapNotNull { norm ->
                        val left = (norm.left * bmpW).toInt().coerceIn(0, bitmap.width - 1)
                        val top = (norm.top * bmpH).toInt().coerceIn(0, bitmap.height - 1)
                        val right = (norm.right * bmpW).toInt().coerceIn(left + 1, bitmap.width)
                        val bottom = (norm.bottom * bmpH).toInt().coerceIn(top + 1, bitmap.height)
                        Rect(left, top, right, bottom).takeIf { !it.isEmpty }
                    }
                    val timingAfterDetect = System.currentTimeMillis()

                    // 2. Bölge OCR: yalnızca balon kutuları okunur. Balon dışı
                    // (text_free) yazılar şimdilik çevrilmez ve maskelenmez.
                    val regionMode = yoloEnabled && bubbleDetector.isAiModelReady()
                    val sfxBoxes = if (yoloBubbles.isNotEmpty()) {
                        yoloBubbles.filter {
                            it.label in setOf("text_free", "onomatopoeia_text", "sfx")
                        }.map { it.toPixelRect(bmpW.toInt(), bmpH.toInt()) }
                    } else {
                        emptyList()
                    }
                    val bubbleOcrRegions = if (regionMode) {
                        if (isPpOcrAny) {
                            buildPpOcrV5Regions(yoloBubbles, bitmap.width, bitmap.height)
                        } else {
                            buildBubbleOcrRegions(yoloBubbles, bitmap.width, bitmap.height)
                        }
                    } else {
                        emptyList()
                    }
                    if (isPpOcrAny && !regionMode) {
                        Log.w("TR", "$ocrEngine requires RT-DETR regions; page=$pageIdx skipped")
                        emitTranslationNotice("$ocrEngine için RT-DETR açık olmalı; tüm sayfa OCR'ı desteklenmiyor.")
                        return@launch
                    }
                    if (isPpOcrEnglish && !translationService.isOcrEngineAvailable(ocrEngine)) {
                        emitTranslationNotice("en_PP-OCRv5_mobile_rec.onnx bulunamadı. Dosyayı assets/models/ klasörüne ekleyin.")
                        return@launch
                    }

                    var ocrTimeoutMs = 120000L
                    val ocrResult: List<TranslationService.RectAndText>? = if (regionMode) {
                        var primaryTimedOut = false
                        val primaryBlocks: List<TranslationService.RectAndText> = if (bubbleOcrRegions.isEmpty()) {
                            Log.d("TR", "Bölge OCR: sayfa=$pageIdx için balon kutusu yok; kurtarma taramasına geçiliyor")
                            emptyList()
                        } else {
                            val regionArea = bubbleOcrRegions.sumOf { it.width().toLong() * it.height() }
                            val pageArea = (bitmap.width.toLong() * bitmap.height).coerceAtLeast(1L)
                            ocrTimeoutMs = (6000L + bubbleOcrRegions.size * 2000L).coerceAtMost(120000L)
                            Log.d("TR", "Bölge OCR başladı: sayfa=$pageIdx, ${bubbleOcrRegions.size} bölge (sayfa alanının %${regionArea * 100 / pageArea}'si)")
                            val regionResult = ocrExecutionMutex.withLock {
                                withTimeoutOrNull(ocrTimeoutMs) {
                                    translationService.performRegionOcr(
                                        bitmap,
                                        bubbleOcrRegions,
                                        ppOriginLang ?: originLang,
                                        ocrEngine
                                    )
                                }
                            }
                            if (regionResult == null) {
                                primaryTimedOut = true
                                emptyList()
                            } else {
                                regionResult
                            }
                        }
                        if (primaryTimedOut) {
                            null
                        } else if (FallbackBubbleSupport.shouldRunFallbackLineScan(
                                isPpOcrAny = isPpOcrAny,
                                regionCount = bubbleOcrRegions.size,
                                primaryBlockCount = primaryBlocks.size
                            )
                        ) {
                            val fallbackTimeoutMs = (20000L + ((bitmap.height / 1400).coerceAtLeast(1) * 10000L))
                                .coerceAtMost(120000L)
                            val fallbackBlocks = ocrExecutionMutex.withLock {
                                withTimeoutOrNull(fallbackTimeoutMs) {
                                    translationService.performFallbackLineScanAndOcr(
                                        bitmap = bitmap,
                                        existingBubbleRects = bubbleOcrRegions,
                                        excludeSfx = sfxBoxes,
                                        originLanguage = ppOriginLang ?: originLang,
                                        ocrEngine = ocrEngine
                                    )
                                }
                            }
                            if (fallbackBlocks == null) {
                                Log.w("TR", "Fallback satır OCR zaman aşımına uğradı: sayfa=$pageIdx, süre=${fallbackTimeoutMs}ms")
                                if (bubbleOcrRegions.isEmpty()) {
                                    ocrTimeoutMs = fallbackTimeoutMs
                                    null
                                } else {
                                    primaryBlocks
                                }
                            } else {
                                if (fallbackBlocks.isNotEmpty()) {
                                    Log.d("TR", "Fallback satır taraması ${fallbackBlocks.size} ek blok buldu (sayfa=$pageIdx)")
                                }
                                FallbackBubbleSupport.mergeOcrBlocksWithFallback(primaryBlocks, fallbackBlocks)
                            }
                        } else {
                            primaryBlocks
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
                        persistTranslatedPage(
                            pageIdx = pageIdx,
                            blocks = emptyList(),
                            masked = bitmap,
                            chapterId = snapshotChapterId,
                            sourceName = snapshotSourceName,
                            pageUrl = snapshotPageUrl
                        )
                        return@launch
                    }

                    val finalBlocks = mutableListOf<TextBlockObj>()
                    var translationCanBeCached = false

                    val candidates = mutableListOf<BubbleCandidate>()
                    // Modelin ham text_free/onomatopeya kutuları. Bunlar yalnızca
                    // maskeleme kapsamını genişletmek için kullanılır; balonun
                    // yerleşim alanını büyütmek balon dışına taşmaya yol açar.
                    val pageAuxBoxes = mutableListOf<Rect>()

                    // Safety net: when OCR splits one balloon into several blocks,
                    // join fragments that share the same balloon and prevent any
                    // remaining lobes from claiming the exact same bubble bounds.
                    fun mergeSameBubbleFragments(input: List<BubbleCandidate>): List<BubbleCandidate> =
                        BubbleCandidateMerger.mergeSameBubbleFragments(
                            input = input,
                            isJapanese = (ppOriginLang ?: originLang).equals("Japonca", ignoreCase = true),
                            joinText = { firstText, secondText, firstBeforeSecond ->
                                translationService.joinTextFragments(firstText, secondText, firstBeforeSecond)
                            }
                        )

                    // 3. Eşleştirme ve Çeviri Akışı
                    if (yoloBubbles.isNotEmpty()) {
                        pageAuxBoxes.addAll(sfxBoxes)
                        val nonPanelBubbles = yoloBubbles.filterNot {
                            it.label.equals("panel", ignoreCase = true)
                        }
                        val balloonLabels = setOf("bubble", "balloon")
                        val dialogueLabels = setOf("text_bubble", "dialogue_text", "text")
                        val balloonDetections = nonPanelBubbles.filter { it.label in balloonLabels }
                        val dialogueDetectionEntries = nonPanelBubbles
                            .filter { it.label in dialogueLabels }
                            .mapNotNull { det ->
                                val rect = det.toPixelRect(bmpW.toInt(), bmpH.toInt())
                                if (rect.isEmpty) null else rect to det.label
                            }
                        val balloonBoxes = dedupeRegions(
                            balloonDetections.map { it.toPixelRect(bmpW.toInt(), bmpH.toInt()) }
                        )
                        val dialogueBoxes = dedupeRegions(
                            dialogueDetectionEntries.map { it.first }
                        )

                        fun selectContainingBubble(textRect: Rect): Rect? =
                            FallbackBubbleSupport.selectContainingBalloon(textRect, balloonBoxes)

                        fun matchingTextDetectionEntry(textRect: Rect): Pair<Rect, String>? {
                            if (dialogueDetectionEntries.isEmpty() || textRect.isEmpty) return null
                            val textArea = (textRect.width().toLong() * textRect.height()).coerceAtLeast(1L)
                            return dialogueDetectionEntries.mapNotNull { (detection, label) ->
                                val intersectionWidth = (minOf(textRect.right, detection.right) - maxOf(textRect.left, detection.left))
                                    .coerceAtLeast(0)
                                val intersectionHeight = (minOf(textRect.bottom, detection.bottom) - maxOf(textRect.top, detection.top))
                                    .coerceAtLeast(0)
                                val overlap = (intersectionWidth.toLong() * intersectionHeight).toFloat() / textArea.toFloat()
                                val containsCenter = detection.contains(textRect.centerX(), textRect.centerY())
                                val score = overlap + if (containsCenter) 0.05f else 0f
                                if (score < 0.20f) null else Triple(detection, label, score)
                            }
                                .sortedWith(
                                    compareByDescending<Triple<Rect, String, Float>> { it.third }
                                        .thenByDescending { it.first.width().toLong() * it.first.height() }
                                )
                                .firstOrNull()
                                ?.let { Rect(it.first) to it.second }
                        }

                        fun matchingTextDetection(textRect: Rect): Rect? =
                            matchingTextDetectionEntry(textRect)?.first

                        // Keep tight text detections even when they lie inside a
                        // balloon; they are useful anchors for OCR assignment.
                        // Use the actual balloon detection as the stable parent for OCR
                        // grouping. text_bubble detections describe text regions and may
                        // split one sentence into several boxes inside the same balloon.
                        // Use them only when the model found no balloon at all.
                        val yoloBoxes = dedupeRegions(balloonBoxes + dialogueBoxes)

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

                        fun isBubbleInteriorDark(bubbleRect: Rect?): Boolean =
                            FallbackBubbleSupport.isBubbleInteriorDark(bitmap, bubbleRect)

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
                                val maxEdgeGap = minOf(yoloBox.height() * 0.14f, ocr.rect.height() * 0.75f, 28f)
                                val belongsToOtherBox = yoloBoxes.any { other ->
                                    other !== yoloBox && other.contains(ocr.rect.centerX(), ocr.rect.centerY())
                                }
                                val isNearVerticalEdge = !belongsToOtherBox &&
                                    overlap >= 0.05f &&
                                    interW >= ocr.rect.width() * 0.45f &&
                                    (vDistToTop <= maxEdgeGap || vDistToBottom <= maxEdgeGap) &&
                                    ocr.rect.centerX() in yoloBox.left..yoloBox.right
                                val score = when {
                                    isCenterIn -> overlap + 0.05f
                                    overlap >= 0.25f -> overlap
                                    isNearVerticalEdge -> 0.22f
                                    else -> 0f
                                }
                                val currentBestArea = yoloBoxes.getOrNull(bestYoloIdx)?.let {
                                    it.width().toLong() * it.height()
                                } ?: Long.MAX_VALUE
                                val candidateArea = yoloBox.width().toLong() * yoloBox.height()
                                // Eşit skorda en içteki (en küçük) balonu tercih et ki bileşik dış kutu komşu balonları yutmasın.
                                if (score >= 0.20f &&
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

                        fun buildYoloBubbleCandidate(
                            textBounds: Rect,
                            rawText: String
                        ): BubbleCandidate? {
                            val cleanedText = translationService.cleanOcrArtifacts(rawText, ppOriginLang ?: originLang)
                            val analysis = translationService.analyzeBubble(bitmap, textBounds)
                            val containingBubble = selectContainingBubble(textBounds)
                            val matchedDialogue = if (containingBubble == null) matchingTextDetectionEntry(textBounds) else null
                            val dialogueBounds = matchedDialogue?.first
                            val isDarkBubble = analysis.isDarkBubble ||
                                isBubbleInteriorDark(containingBubble ?: dialogueBounds) ||
                                (containingBubble == null && FallbackBubbleSupport.isColoredOrDarkWithLightText(bitmap, textBounds))
                            val finalIsBubble = containingBubble != null || analysis.isBubble || isDarkBubble
                            val dialogueValidationIsBubble = containingBubble != null ||
                                (matchedDialogue?.second != "text" && finalIsBubble)
                            if (cleanedText.isBlank() || !translationService.isValidDialogueText(cleanedText, isBubble = dialogueValidationIsBubble)) {
                                return null
                            }
                            val bubbleBounds = when {
                                containingBubble != null -> containingBubble
                                finalIsBubble && dialogueBounds != null -> {
                                    if (dialogueBounds.contains(textBounds)) Rect(dialogueBounds)
                                    else Rect(dialogueBounds).apply { union(textBounds) }
                                }
                                finalIsBubble -> FallbackBubbleSupport.boundFallbackRefinedBox(
                                    bitmap = bitmap,
                                    textRect = textBounds,
                                    rawRefined = translationService.refineBubbleBounds(bitmap, textBounds, isDarkBubble),
                                    isDarkBubble = isDarkBubble
                                )
                                else -> dialogueBounds ?: textBounds
                            }
                            val candidateContour = when {
                                containingBubble != null -> {
                                    (balloonDetections.firstOrNull { yb ->
                                        yb.toPixelRect(bitmap.width, bitmap.height) == bubbleBounds
                                    }?.contourProfile ?: balloonDetections.firstOrNull { yb ->
                                        yb.toPixelRect(bitmap.width, bitmap.height).contains(textBounds.centerX(), textBounds.centerY())
                                    }?.contourProfile)?.takeIf { it.isUsable }
                                        ?: BubbleContourExtractor.detectRectangularFrameFromBitmap(
                                            bitmap = bitmap,
                                            bubbleBox = bubbleBounds,
                                            pageOriginalWidth = bitmap.width.toFloat(),
                                            pageOriginalHeight = bitmap.height.toFloat()
                                        )
                                }
                                finalIsBubble -> {
                                    FallbackBubbleSupport.resolveNonBalloonContour(
                                        bitmap = bitmap,
                                        textBounds = textBounds,
                                        bubbleBounds = bubbleBounds,
                                        isDarkBubble = isDarkBubble,
                                        dialogueLabel = matchedDialogue?.second
                                    )
                                }
                                else -> null
                            }
                            // Size bubble text from the balloon interior. The tight
                            // text_bubble detection remains a fallback anchor, but
                            // fitting to it left longer translations needlessly tiny.
                            val isRectangularBox = !finalIsBubble ||
                                (candidateContour?.isRectangularLike == true) ||
                                (containingBubble == null && matchedDialogue?.second.equals("text", ignoreCase = true))
                            val targetBox = (if (finalIsBubble) {
                                BubbleLayoutGeometry.centeredLayoutBox(textBounds, bubbleBounds, isRectangular = isRectangularBox)
                            } else {
                                null
                            })
                                ?: dialogueBounds
                                ?: Rect(textBounds)
                            return BubbleCandidate(
                                targetBox = targetBox,
                                text = cleanedText,
                                textRect = textBounds,
                                isBubble = finalIsBubble,
                                isDark = isDarkBubble,
                                bubbleBounds = bubbleBounds,
                                contourProfile = candidateContour
                            )
                        }

                        val matchedOcrIndices = mutableSetOf<Int>()
                        val isJapanese = (ppOriginLang ?: originLang).equals("Japonca", ignoreCase = true)
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
                                    buildYoloBubbleCandidate(Rect(textGroup.rect), textGroup.text)?.let(candidates::add)
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
                            buildYoloBubbleCandidate(Rect(cluster.rect), cluster.text)?.let(candidates::add)
                        }

                        _detectedBubblesMap.update { current ->
                            if (readerGeneration.get() == generation) current + (pageIdx to yoloBubbles) else current
                        }
                    } else {
                        val filteredBlocks = ocrBlocks.filter { it.text.trim().isNotEmpty() }
                        val clusteredBlocks = translationService.clusterAdjacentBlocks(filteredBlocks, bitmap)
                        clusteredBlocks.forEach { raw ->
                            val cleanedText = translationService.cleanOcrArtifacts(raw.text, ppOriginLang ?: originLang)
                            val analysis = translationService.analyzeBubble(bitmap, raw.rect)
                            val isDarkBubble = analysis.isDarkBubble ||
                                FallbackBubbleSupport.isColoredOrDarkWithLightText(bitmap, raw.rect)
                            val finalIsBubble = analysis.isBubble || isDarkBubble
                            if (cleanedText.isNotBlank() && translationService.isValidDialogueText(cleanedText, isBubble = finalIsBubble)) {
                                val refinedBox = if (finalIsBubble) {
                                    FallbackBubbleSupport.boundFallbackRefinedBox(
                                        bitmap = bitmap,
                                        textRect = raw.rect,
                                        rawRefined = translationService.refineBubbleBounds(bitmap, raw.rect, isDarkBubble),
                                        isDarkBubble = isDarkBubble
                                    )
                                } else {
                                    raw.rect
                                }
                                val isRectangularBox = !finalIsBubble || (regionMode && isPpOcrAny) || isDarkBubble
                                val targetBox = (if (finalIsBubble) {
                                    BubbleLayoutGeometry.centeredLayoutBox(raw.rect, refinedBox, isRectangular = isRectangularBox)
                                } else {
                                    null
                                }) ?: raw.rect
                                candidates.add(
                                    BubbleCandidate(
                                        targetBox = targetBox,
                                        text = cleanedText,
                                        textRect = raw.rect,
                                        isBubble = finalIsBubble,
                                        isDark = isDarkBubble,
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
                        val detectedLang = if (forcedSourceCode != null) {
                            originLang
                        } else {
                            val ocrDefaultLang = if (isPpOcrAny) {
                                ppOriginLang ?: originLang
                            } else {
                                originLang
                            }
                            translationService.detectRealLanguage(ocrBlocks, ocrDefaultLang)
                        }
                        val orderedCandidates = FallbackBubbleSupport.sortCandidatesByPanelReadingOrder(
                            candidates = mergedCandidates,
                            pagePanels = pagePanels,
                            originLang = detectedLang
                        )
                        val pageWidthPx = bitmap.width.coerceAtLeast(1)
                        val dpPerPx = 380f / pageWidthPx.toFloat()
                        val candidateRequests = orderedCandidates.map { cand ->
                            val r = cand.textRect
                            val id = "$pageIdx:${r.left},${r.top},${r.right},${r.bottom}:${cand.text.hashCode()}"
                            if (cand.isBubble) {
                                val box = cand.bubbleBounds.takeUnless { it.isEmpty } ?: cand.targetBox
                                if (box.width() >= 24 && box.height() >= 24) {
                                    val wDp = box.width() * dpPerPx
                                    val hDp = box.height() * dpPerPx
                                    val maxChars = ((0.62f * wDp * hDp) / 71f).toInt().coerceIn(18, 180)
                                    val aspect = box.width().toFloat() / box.height().toFloat()
                                    val shape = when {
                                        aspect < 0.72f -> "narrow"
                                        aspect > 1.45f -> "wide"
                                        else -> "balanced"
                                    }
                                    TranslationRequest(id, cand.text, maxCharsHint = maxChars, shapeHint = shape)
                                } else {
                                    TranslationRequest(id, cand.text)
                                }
                            } else {
                                TranslationRequest(id, cand.text)
                            }
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

                        orderedCandidates.forEachIndexed { idx, cand ->
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
                                    // May intentionally exceed the 70% safe oval to keep
                                    // anchor readability; visual consumers clamp at draw time.
                                    layoutBox = cand.targetBox,
                                    translationSucceeded = result?.succeeded == true,
                                    coordinateImageWidth = bitmap.width,
                                    coordinateImageHeight = bitmap.height,
                                    contourProfile = cand.contourProfile
                                )
                            )
                        }
                        translationCanBeCached = finalBlocks.size == orderedCandidates.size &&
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
                        persistTranslatedPage(
                            pageIdx = pageIdx,
                            blocks = finalBlocks,
                            masked = maskedBitmap,
                            chapterId = snapshotChapterId,
                            sourceName = snapshotSourceName,
                            pageUrl = snapshotPageUrl
                        )
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
            val normalizedTarget = entity.originalText
            _translatedPagesMap.update { current ->
                current.mapValues { (_, blocks) ->
                    blocks.map { block ->
                        if (block.originalText.isNotBlank() &&
                            translationService.normalizeForCache(block.originalText) == normalizedTarget
                        ) {
                            block.copy(translatedText = turkishText)
                        } else {
                            block
                        }
                    }
                }
            }
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
            if (chapter.isDownloaded || chapter.linkedFolderId != null) return@launch
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
            .filterNot { it.isDownloaded || it.linkedFolderId != null || it.id in queuedChapterIds }
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
                            beginCloudflareVerification(e) {
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
                        if (NetworkManager.isCloudflareChallenge(response)) {
                            throw CloudflareChallengeException(
                                response.request.url.host,
                                urlString,
                                "İndirme",
                                response.request.method,
                                response.request.headers.toSafeWebViewHeaders(),
                            )
                        }
                        if (!response.isSuccessful) {
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

    fun refreshLinkedSources() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { linkedFolderManager.sourceInfos() }
                .onSuccess { _linkedSources.value = it }
        }
    }

    fun linkLocalFolder(
        treeUri: Uri,
        mode: String,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = if (mode == LinkedFolderEntity.MODE_LIBRARY_ROOT) {
                "Bağlı klasör kitaplığı taranıyor..."
            } else {
                "Bağlı klasör taranıyor..."
            }
            try {
                val res: LinkedScanResult = linkedFolderManager.linkFolder(treeUri, mode) { status ->
                    _importStatusText.value = status
                }
                onResult(res.success, res.message)
            } catch (e: Exception) {
                onResult(false, "Klasör bağlama hatası: ${e.localizedMessage ?: e.message}")
            } finally {
                refreshLinkedSources()
                _isImporting.value = false
                _importStatusText.value = ""
            }
        }
    }

    fun linkLocalFolderSingle(treeUri: Uri, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        linkLocalFolder(treeUri, LinkedFolderEntity.MODE_SINGLE_MANGA, onResult)
    }

    fun linkLocalFolderLibrary(treeUri: Uri, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        linkLocalFolder(treeUri, LinkedFolderEntity.MODE_LIBRARY_ROOT, onResult)
    }

    fun rescanLinkedFolder(folderId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = "Bağlı klasör yeniden taranıyor..."
            try {
                val res = linkedFolderManager.rescanFolder(folderId) { status ->
                    _importStatusText.value = status
                }
                onResult(res.success, res.message)
            } catch (e: Exception) {
                onResult(false, "Bağlı klasör taranamadı: ${e.localizedMessage ?: e.message}")
            } finally {
                refreshLinkedSources()
                _isImporting.value = false
                _importStatusText.value = ""
            }
        }
    }

    fun relinkLinkedFolder(
        folderId: String,
        treeUri: Uri,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = "Bağlı klasör yeniden bağlanıyor..."
            try {
                val res = linkedFolderManager.relinkFolder(folderId, treeUri) { status ->
                    _importStatusText.value = status
                }
                onResult(res.success, res.message)
            } catch (e: Exception) {
                onResult(false, "Klasör yeniden bağlanamadı: ${e.localizedMessage ?: e.message}")
            } finally {
                refreshLinkedSources()
                _isImporting.value = false
                _importStatusText.value = ""
            }
        }
    }

    fun detachLinkedFolder(folderId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                val (success, message) = linkedFolderManager.detachFolder(folderId)
                withContext(Dispatchers.Main) { onResult(success, message) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Bağlantı kaldırılamadı: ${e.localizedMessage ?: e.message}")
                }
            } finally {
                refreshLinkedSources()
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

    private fun repairMissingLinkedCovers() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val linkedMangas = mangaDao.getAllMangas().filter { it.linkedFolderId != null }
                for (manga in linkedMangas) {
                    if (linkedPageResolver.isCoverFileUsable(manga.coverUrl)) continue
                    val repairedUrl = when {
                        linkedPageResolver.hasCachedCover(manga.id) ->
                            linkedPageResolver.cachedCoverUrl(manga.id)
                        else -> linkedPageResolver.migrateLegacyCoverIfPresent(manga.id) ?: run {
                            val firstChapter = chapterDao.getChaptersByMangaIdSync(manga.id)
                                .minByOrNull { it.chapterNumber }
                            val firstLocator = firstChapter?.linkedPageLocator
                                ?.takeIf { it.isNotBlank() }
                                ?.let { PagePathCodec.decode(it).firstOrNull() }
                            if (firstLocator != null) {
                                linkedPageResolver.cacheCoverThumbnail(manga.id, firstLocator)
                            } else {
                                null
                            }
                        }
                    }
                    if (!repairedUrl.isNullOrBlank() && repairedUrl != manga.coverUrl) {
                        mangaDao.updateManga(manga.copy(coverUrl = repairedUrl))
                    }
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

                val isLinked = manga.linkedFolderId != null
                val isLocal = !isLinked && (
                    manga.sourceName.contains("Yerel", ignoreCase = true) ||
                        manga.sourceName.equals("Local", ignoreCase = true) ||
                        manga.id.startsWith("local_")
                )
                val pathsToStage = linkedSetOf<File>()
                if (isLocal) {
                    pathsToStage += File(context.filesDir, "local_imports/${manga.id}")
                    pathsToStage += File(context.filesDir, "local_imports/${manga.id.removePrefix("local_")}")
                }

                if (!isLinked && (manga.coverUrl.startsWith("/") || manga.coverUrl.startsWith("file://"))) {
                    pathsToStage += File(manga.coverUrl.removePrefix("file://"))
                }

                val downloadRoots = downloadBaseDirs()
                // Stage both legacy manga folders and chapter folders across all storage roots.
                downloadRoots.forEach { root -> pathsToStage += File(root, manga.id) }
                chapters.forEach { ch ->
                    if (ch.linkedFolderId == null && ch.localImagePathList.isNotBlank()) {
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
                clearTranslationCacheForChapters(chapterIdsToCancel)
                if (isLinked) linkedPageResolver.deleteCachedCover(manga.id)

                val undeletedPaths = stagedFiles.filterNot { entry ->
                    if (entry.staged.isDirectory) entry.staged.deleteRecursively() else entry.staged.delete()
                }

                withContext(Dispatchers.Main) {
                    val message = when {
                        isLinked -> "\"${manga.title}\" kütüphaneden kaldırıldı. " +
                            "Bağlı klasördeki kaynak dosyalarınıza dokunulmadı."
                        undeletedPaths.isEmpty() ->
                            "\"${manga.title}\" ve tüm dosyaları cihazdan tamamen silindi."
                        else ->
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
                    val localImport = chapter.linkedFolderId != null ||
                        manga?.id?.startsWith("local_") == true ||
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

    fun setDetectorModelType(type: DetectorModel) {
        viewModelScope.launch {
            preferences.setDetectorModelType(type.id)
            bubbleDetector.yoloDetector.switchModel(type)
        }
    }

    fun isYoloModelLoaded(): Boolean = bubbleDetector.yoloDetector.isModelLoaded()

    fun getYoloModelInfo(): Map<String, String> = bubbleDetector.yoloDetector.getModelInfo()

    private val webSourceResolverInstance: (String) -> MangaSource? = { sourceName ->
        webSiteStore.siteBySourceName(sourceName)?.let { site ->
            WebSiteSource(site, context)
        }
    }

    init {
        context.registerComponentCallbacks(pageMemoryCallbacks)
        MangaSourceRegistry.webSourceResolver = webSourceResolverInstance

        viewModelScope.launch {
            preferences.pageQuality.distinctUntilChanged().collect { quality ->
                val previous = lastObservedPageQuality
                lastObservedPageQuality = quality
                if (previous != null && previous != quality) {
                    pageBitmapCache.evictAll()
                    prefetchJobMap.values.toList().forEach { it.cancel() }
                    prefetchJobMap.clear()
                }
            }
        }

        viewModelScope.launch {
            // Eski sürümlerin ayıklanmamış sayfa listeleri önbellekte kalmasın;
            // web sayfa önbelleği yalnızca bu oturumda güvenilir sayılır.
            runCatching { database.webPageCacheDao().deleteOlderThan(Long.MAX_VALUE) }
        }

        refreshLinkedSources()
        repairMissingLinkedCovers()

        viewModelScope.launch {
            preferences.detectorModelType.distinctUntilChanged().collect { typeId ->
                val modelType = DetectorModel.fromId(typeId)
                bubbleDetector.yoloDetector.switchModel(modelType)
            }
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

        viewModelScope.launch {
            selectedSource.collectLatest { source ->
                loadSourceFilters(source)
            }
        }

        @OptIn(FlowPreview::class)
        val debouncedQuery = discoverQuery.debounce(350L)
        viewModelScope.launch {
            combine(
                selectedSource,
                discoverTab,
                debouncedQuery
            ) { source, tab, query ->
                Triple(source, tab, query)
            }.collect { (source, tab, query) ->
                currentDiscoverPage.value = 1
                _isDiscoverEndReached.value = false
                fetchDiscoverItems(
                    MangaFilterCriteria(
                        source = source,
                        tab = tab,
                        query = query,
                        filters = appliedFilters(source)
                    ),
                    isNextPage = false,
                    pageToFetch = 1
                )
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
        if (MangaSourceRegistry.webSourceResolver === webSourceResolverInstance) {
            MangaSourceRegistry.webSourceResolver = null
        }
        activeDownloadCalls.keys.toList().forEach(::cancelDownloadCalls)
        activeDownloadCalls.clear()
        viewModelScope.coroutineContext.cancelChildren()
        context.unregisterComponentCallbacks(pageMemoryCallbacks)
        prefetchJobMap.values.forEach { it.cancel() }
        prefetchJobMap.clear()
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
        activePageJobs.values.forEach { it.cancel() }
        activePageJobs.clear()
        inProgressPages.clear()
        _isTranslationLoading.value = false
        _maskedPagesMap.value = emptyMap()
        evictReaderBitmapCaches()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { bubbleDetector.yoloDetector.closeSession() }
        }
        runCatching { translationService.closeAllTranslators() }
        super.onCleared()
    }
}

internal data class BubbleCandidate(
    val targetBox: Rect,
    val text: String,
    val textRect: Rect,
    val isBubble: Boolean,
    val isDark: Boolean,
    val bubbleBounds: Rect = targetBox,
    val contourProfile: ContourScanlineProfile? = null
)

internal object BubbleCandidateMerger {

    private data class WorkingCandidate(
        val candidate: BubbleCandidate,
        val lineHeightPx: Float
    )

    fun areaOf(r: Rect): Long = if (r.isEmpty) 0L else r.width().toLong() * r.height().toLong()

    fun overlapArea(a: Rect, b: Rect): Long {
        val interW = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val interH = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return interW.toLong() * interH
    }

    fun sameBalloon(a: Rect, b: Rect): Boolean {
        if (a.isEmpty || b.isEmpty) return false
        if (a == b) return true
        val inter = overlapArea(a, b)
        val areaA = areaOf(a)
        val areaB = areaOf(b)
        val maxArea = maxOf(areaA, areaB).coerceAtLeast(1L)
        val unionArea = (areaA + areaB - inter).coerceAtLeast(1L)
        val iou = inter.toFloat() / unionArea
        return iou >= 0.55f || inter.toFloat() / maxArea >= 0.85f
    }

    fun defaultJoinTextFragments(t1: String, t2: String, isFirstBeforeSecond: Boolean): String {
        val first = if (isFirstBeforeSecond) t1.trim() else t2.trim()
        val second = if (isFirstBeforeSecond) t2.trim() else t1.trim()
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        if (first.equals(second, ignoreCase = true)) return first
        return if (first.endsWith("-") && first.length > 1 && first[first.length - 2].isLetter()) {
            first.dropLast(1) + second
        } else {
            "$first $second"
        }.replace("\\s+".toRegex(), " ")
    }

    /**
     * Drops stray 1-character ASCII artifacts (such as an isolated "O" from sweatdrops
     * or bubble borders) when the same balloon already contains a substantially longer
     * valid dialogue block. Preserves legitimate inline "I" / "A" words on the same row.
     */
    fun filterGhostSingleCharFragments(input: List<BubbleCandidate>): List<BubbleCandidate> {
        if (input.size <= 1) return input
        return input.filterIndexed { idx, cand ->
            val cleaned = cand.text.trim()
            val alnumCount = cleaned.count { it.isLetterOrDigit() }
            val isAsciiOnly = cleaned.all { it.code < 128 }
            if (!isAsciiOnly || alnumCount > 1) return@filterIndexed true

            val candArea = areaOf(cand.textRect)
            val bubbleArea = areaOf(cand.bubbleBounds).coerceAtLeast(1L)
            val isGhost = input.indices.any { otherIdx ->
                if (otherIdx == idx) return@any false
                val other = input[otherIdx]
                if (!sameBalloon(cand.bubbleBounds, other.bubbleBounds)) return@any false
                val otherAlnum = other.text.trim().count { it.isLetterOrDigit() }
                if (otherAlnum < 3) return@any false

                val inter = overlapArea(cand.textRect, other.textRect)
                if (inter > 0L) return@any true

                // Preserve legitimate inline "I" / "A" words that sit horizontally next to a word on the same line.
                val isInlineEnglishToken = cleaned == "I" || cleaned.equals("A", ignoreCase = true)
                if (isInlineEnglishToken) {
                    val vOverlap = minOf(cand.textRect.bottom, other.textRect.bottom) -
                        maxOf(cand.textRect.top, other.textRect.top)
                    val minH = minOf(cand.textRect.height(), other.textRect.height()).coerceAtLeast(1)
                    val maxH = maxOf(cand.textRect.height(), other.textRect.height()).coerceAtLeast(1)
                    val hGap = maxOf(
                        cand.textRect.left - other.textRect.right,
                        other.textRect.left - cand.textRect.right
                    ).toFloat()
                    if (vOverlap >= minH * 0.50f &&
                        minH >= maxH * 0.55f &&
                        hGap in (-minH * 0.5f)..(minH * 1.2f)
                    ) {
                        return@any false
                    }
                }

                val otherArea = areaOf(other.textRect).coerceAtLeast(1L)
                candArea <= otherArea * 0.65f || candArea <= bubbleArea * 0.30f
            }
            !isGhost
        }
    }

    fun mergeSameBubbleFragments(
        input: List<BubbleCandidate>,
        isJapanese: Boolean = false,
        joinText: (String, String, Boolean) -> String = ::defaultJoinTextFragments
    ): List<BubbleCandidate> {
        val filtered = filterGhostSingleCharFragments(input)
        if (filtered.size <= 1) return filtered

        val verticalCjkMode = isJapanese &&
            filtered.count { it.textRect.height() > it.textRect.width() * 1.3f } * 2 >= filtered.size

        fun joinFragmentText(firstText: String, secondText: String): String {
            if (firstText.length >= 3 && secondText.length >= 3 &&
                (firstText.contains(secondText, ignoreCase = true) ||
                    secondText.contains(firstText, ignoreCase = true))
            ) {
                return if (firstText.length >= secondText.length) firstText else secondText
            }
            return joinText(firstText, secondText, true)
        }

        fun buildMerged(
            first: WorkingCandidate,
            second: WorkingCandidate,
            joinedText: String
        ): WorkingCandidate {
            val a = first.candidate
            val b = second.candidate
            return WorkingCandidate(
                candidate = BubbleCandidate(
                    targetBox = Rect(
                        minOf(a.targetBox.left, b.targetBox.left),
                        minOf(a.targetBox.top, b.targetBox.top),
                        maxOf(a.targetBox.right, b.targetBox.right),
                        maxOf(a.targetBox.bottom, b.targetBox.bottom)
                    ),
                    text = joinedText,
                    textRect = Rect(
                        minOf(a.textRect.left, b.textRect.left),
                        minOf(a.textRect.top, b.textRect.top),
                        maxOf(a.textRect.right, b.textRect.right),
                        maxOf(a.textRect.bottom, b.textRect.bottom)
                    ),
                    isBubble = a.isBubble || b.isBubble,
                    isDark = a.isDark || b.isDark,
                    bubbleBounds = if (areaOf(a.bubbleBounds) >= areaOf(b.bubbleBounds)) {
                        a.bubbleBounds
                    } else {
                        b.bubbleBounds
                    },
                    contourProfile = if (areaOf(a.bubbleBounds) >= areaOf(b.bubbleBounds)) {
                        a.contourProfile
                    } else {
                        b.contourProfile
                    }
                ),
                lineHeightPx = minOf(first.lineHeightPx, second.lineHeightPx).coerceAtLeast(1f)
            )
        }

        fun fragmentContainedInBubble(inner: Rect, outerBubble: Rect): Boolean {
            if (inner.isEmpty || outerBubble.isEmpty) return false
            val interW = (minOf(inner.right, outerBubble.right) - maxOf(inner.left, outerBubble.left)).coerceAtLeast(0)
            val interH = (minOf(inner.bottom, outerBubble.bottom) - maxOf(inner.top, outerBubble.top)).coerceAtLeast(0)
            val interArea = interW.toLong() * interH.toLong()
            val innerArea = areaOf(inner).coerceAtLeast(1L)
            return interArea >= innerArea * 70L / 100L
        }

        fun tryMerge(firstW: WorkingCandidate, secondW: WorkingCandidate): WorkingCandidate? {
            val a = firstW.candidate
            val b = secondW.candidate
            val bHasOwnDistinctBubble = b.isBubble && b.bubbleBounds != b.textRect && !sameBalloon(a.bubbleBounds, b.bubbleBounds)
            val aHasOwnDistinctBubble = a.isBubble && a.bubbleBounds != a.textRect && !sameBalloon(a.bubbleBounds, b.bubbleBounds)
            val sharesBalloon = sameBalloon(a.bubbleBounds, b.bubbleBounds) ||
                (a.isBubble && !bHasOwnDistinctBubble && fragmentContainedInBubble(b.textRect, a.bubbleBounds)) ||
                (b.isBubble && !aHasOwnDistinctBubble && fragmentContainedInBubble(a.textRect, b.bubbleBounds))
            if (!sharesBalloon) return null
            val lineHeight = minOf(firstW.lineHeightPx, secondW.lineHeightPx).coerceAtLeast(1f)

            if (verticalCjkMode) {
                val minW = minOf(a.textRect.width(), b.textRect.width()).coerceAtLeast(1).toFloat()
                val sameColumn = kotlin.math.abs(a.textRect.exactCenterX() - b.textRect.exactCenterX()) < minW * 0.6f
                if (sameColumn) {
                    val upper = if (a.textRect.top <= b.textRect.top) firstW else secondW
                    val lower = if (upper === firstW) secondW else firstW
                    val gap = (lower.candidate.textRect.top - upper.candidate.textRect.bottom).toFloat()
                    if (gap <= lineHeight * 2.0f) {
                        return buildMerged(
                            upper,
                            lower,
                            joinFragmentText(upper.candidate.text.trim(), lower.candidate.text.trim())
                        )
                    }
                }
            }

            val left = if (a.textRect.left <= b.textRect.left) firstW else secondW
            val right = if (left === firstW) secondW else firstW
            val leftRect = left.candidate.textRect
            val rightRect = right.candidate.textRect
            val vOverlap = minOf(leftRect.bottom, rightRect.bottom) - maxOf(leftRect.top, rightRect.top)
            val minH = minOf(leftRect.height(), rightRect.height()).coerceAtLeast(1)
            val hGap = (rightRect.left - leftRect.right).toFloat()
            val vOverlapThreshold = if (verticalCjkMode) 0.35f else 0.5f
            val hGapLimit = if (verticalCjkMode) 1.5f else 1.0f
            if (vOverlap >= minH * vOverlapThreshold &&
                hGap <= lineHeight * hGapLimit &&
                hGap > -lineHeight * 0.5f
            ) {
                val first = if (verticalCjkMode) right else left
                val second = if (verticalCjkMode) left else right
                return buildMerged(
                    first,
                    second,
                    joinFragmentText(first.candidate.text.trim(), second.candidate.text.trim())
                )
            }

            if (!verticalCjkMode) {
                val upper = if (a.textRect.top <= b.textRect.top) firstW else secondW
                val lower = if (upper === firstW) secondW else firstW
                val gap = (lower.candidate.textRect.top - upper.candidate.textRect.bottom).toFloat()
                if (gap > lineHeight * 2.0f) return null
                val interW = minOf(upper.candidate.textRect.right, lower.candidate.textRect.right) -
                    maxOf(upper.candidate.textRect.left, lower.candidate.textRect.left)
                if (interW < minOf(upper.candidate.textRect.width(), lower.candidate.textRect.width()) * 0.30f) {
                    return null
                }
                return buildMerged(
                    upper,
                    lower,
                    joinFragmentText(upper.candidate.text.trim(), lower.candidate.text.trim())
                )
            }
            return null
        }

        val sortedInput = if (verticalCjkMode) {
            filtered.sortedWith(
                compareByDescending<BubbleCandidate> { it.textRect.right }
                    .thenBy { it.textRect.top }
            )
        } else {
            filtered.sortedWith(
                compareBy({ it.textRect.top }, { it.textRect.left })
            )
        }
        val working = sortedInput.map {
            WorkingCandidate(it, it.textRect.height().coerceAtLeast(1).toFloat())
        }.toMutableList()

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

        val consolidated = mutableListOf<WorkingCandidate>()
        for (item in working) {
            val candidate = item.candidate
            val sameTargetIndex = consolidated.indexOfFirst { existingW ->
                val existing = existingW.candidate
                if (!sameBalloon(existing.bubbleBounds, candidate.bubbleBounds)) {
                    return@indexOfFirst false
                }

                val lineHeight = minOf(existingW.lineHeightPx, item.lineHeightPx).coerceAtLeast(1f)
                val gapY = if (candidate.textRect.top > existing.textRect.bottom) {
                    (candidate.textRect.top - existing.textRect.bottom).toFloat()
                } else if (existing.textRect.top > candidate.textRect.bottom) {
                    (existing.textRect.top - candidate.textRect.bottom).toFloat()
                } else 0f

                val gapX = if (candidate.textRect.left > existing.textRect.right) {
                    (candidate.textRect.left - existing.textRect.right).toFloat()
                } else if (existing.textRect.left > candidate.textRect.right) {
                    (existing.textRect.left - candidate.textRect.right).toFloat()
                } else 0f

                val bubbleH = existing.bubbleBounds.height().toFloat()
                val bubbleW = existing.bubbleBounds.width().toFloat()
                val isTallTwinBubble = bubbleH > bubbleW * 1.30f

                // DİKEY AYRIK İKİZ BALON KORUMASI:
                // İki metin bloğu arasındaki boşluk normal satır aralığını (> 2.5 * lineHeight) aşıyorsa
                // ayrı loblar olarak korunur; aşağıda bubbleBounds bölünerek çakışma engellenir.
                if (isTallTwinBubble && gapY > lineHeight * 2.5f) {
                    return@indexOfFirst false
                }

                val horizThreshold = if (verticalCjkMode) {
                    minOf(existing.textRect.width(), candidate.textRect.width()).coerceAtLeast(1).toFloat() * 2.5f
                } else {
                    lineHeight * 2.5f
                }
                if (gapY <= lineHeight * 2.5f && gapX <= horizThreshold) {
                    return@indexOfFirst true
                }

                val inter = overlapArea(existing.targetBox, candidate.targetBox)
                val minArea = minOf(areaOf(existing.targetBox), areaOf(candidate.targetBox)).coerceAtLeast(1L)
                inter.toFloat() / minArea >= 0.55f &&
                    gapY <= lineHeight * 2.5f &&
                    gapX <= horizThreshold
            }
            if (sameTargetIndex < 0) {
                consolidated.add(item)
                continue
            }
            val existingW = consolidated[sameTargetIndex]
            val existing = existingW.candidate
            val firstBeforeSecond = if (verticalCjkMode) {
                existing.textRect.right >= candidate.textRect.right
            } else {
                existing.textRect.top < candidate.textRect.top ||
                    (existing.textRect.top == candidate.textRect.top && existing.textRect.left <= candidate.textRect.left)
            }
            val joinedText = when {
                existing.text.contains(candidate.text, ignoreCase = true) -> existing.text
                candidate.text.contains(existing.text, ignoreCase = true) -> candidate.text
                else -> joinText(existing.text, candidate.text, firstBeforeSecond)
            }
            consolidated[sameTargetIndex] = buildMerged(existingW, item, joinedText)
        }

        val cleaned = filterGhostSingleCharFragments(consolidated.map { it.candidate })
        return ensureNonOverlappingBubbleOwners(cleaned)
    }

    /**
     * Guarantees that two surviving candidates never share the same full `bubbleBounds`.
     * Twin-lobe balloons are split cleanly at the midpoint (`splitY` or `splitX`) so
     * ReaderScreen lays out and centers each translation inside its own half.
     */
    fun ensureNonOverlappingBubbleOwners(input: List<BubbleCandidate>): List<BubbleCandidate> {
        if (input.size <= 1) return input

        val parent = IntArray(input.size) { it }
        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in input.indices) {
            for (j in i + 1 until input.size) {
                if (sameBalloon(input[i].bubbleBounds, input[j].bubbleBounds)) {
                    union(i, j)
                }
            }
        }

        val groups = input.indices.groupBy { find(it) }
        val result = input.toMutableList()

        for ((_, memberIndices) in groups) {
            if (memberIndices.size <= 1) continue

            val canonicalEnvelope = memberIndices
                .map { input[it].bubbleBounds }
                .maxByOrNull { areaOf(it) }
                ?.let(::Rect) ?: continue
            if (canonicalEnvelope.isEmpty) continue

            val verticalSorted = memberIndices.sortedWith(
                compareBy<Int> { input[it].textRect.top }.thenBy { input[it].textRect.left }
            )
            val cleanlyVerticallySeparated = verticalSorted.zipWithNext().all { (aIdx, bIdx) ->
                input[aIdx].textRect.bottom <= input[bIdx].textRect.top
            }

            val horizontalSorted = memberIndices.sortedWith(
                compareBy<Int> { input[it].textRect.left }.thenBy { input[it].textRect.top }
            )
            val cleanlyHorizontallySeparated = horizontalSorted.zipWithNext().all { (aIdx, bIdx) ->
                input[aIdx].textRect.right <= input[bIdx].textRect.left
            }

            if (cleanlyVerticallySeparated || !cleanlyHorizontallySeparated) {
                val splitYs = IntArray(verticalSorted.size - 1)
                for (k in 0 until verticalSorted.size - 1) {
                    val prev = input[verticalSorted[k]].textRect
                    val next = input[verticalSorted[k + 1]].textRect
                    val rawSplit = if (prev.bottom <= next.top) {
                        (prev.bottom + next.top) / 2
                    } else {
                        (prev.centerY() + next.centerY()) / 2
                    }
                    val minSplit = if (k == 0) canonicalEnvelope.top + 1 else splitYs[k - 1] + 1
                    val maxSplit = (canonicalEnvelope.bottom - 1).coerceAtLeast(minSplit)
                    splitYs[k] = rawSplit.coerceIn(minSplit, maxSplit)
                }

                for (k in verticalSorted.indices) {
                    val idx = verticalSorted[k]
                    val cand = input[idx]
                    val segTop = if (k == 0) minOf(canonicalEnvelope.top, cand.textRect.top) else splitYs[k - 1]
                    val segBottom = if (k == verticalSorted.lastIndex) {
                        maxOf(canonicalEnvelope.bottom, cand.textRect.bottom)
                    } else {
                        splitYs[k]
                    }
                    val segLeft = minOf(canonicalEnvelope.left, cand.textRect.left)
                    val segRight = maxOf(canonicalEnvelope.right, cand.textRect.right)
                    val splitBounds = Rect(
                        segLeft,
                        segTop,
                        segRight.coerceAtLeast(segLeft + 1),
                        segBottom.coerceAtLeast(segTop + 1)
                    )
                    val slicedContour = sliceContourVertically(cand.contourProfile, canonicalEnvelope, splitBounds)
                    val updatedTarget = computeSplitTargetBox(cand, splitBounds, slicedContour)
                    result[idx] = cand.copy(
                        targetBox = updatedTarget,
                        bubbleBounds = splitBounds,
                        contourProfile = slicedContour
                    )
                }
            } else {
                val splitXs = IntArray(horizontalSorted.size - 1)
                for (k in 0 until horizontalSorted.size - 1) {
                    val prev = input[horizontalSorted[k]].textRect
                    val next = input[horizontalSorted[k + 1]].textRect
                    val rawSplit = (prev.right + next.left) / 2
                    val minSplit = if (k == 0) canonicalEnvelope.left + 1 else splitXs[k - 1] + 1
                    val maxSplit = (canonicalEnvelope.right - 1).coerceAtLeast(minSplit)
                    splitXs[k] = rawSplit.coerceIn(minSplit, maxSplit)
                }

                for (k in horizontalSorted.indices) {
                    val idx = horizontalSorted[k]
                    val cand = input[idx]
                    val segLeft = if (k == 0) minOf(canonicalEnvelope.left, cand.textRect.left) else splitXs[k - 1]
                    val segRight = if (k == horizontalSorted.lastIndex) {
                        maxOf(canonicalEnvelope.right, cand.textRect.right)
                    } else {
                        splitXs[k]
                    }
                    val segTop = minOf(canonicalEnvelope.top, cand.textRect.top)
                    val segBottom = maxOf(canonicalEnvelope.bottom, cand.textRect.bottom)
                    val splitBounds = Rect(
                        segLeft,
                        segTop,
                        segRight.coerceAtLeast(segLeft + 1),
                        segBottom.coerceAtLeast(segTop + 1)
                    )
                    val updatedTarget = computeSplitTargetBox(cand, splitBounds, null)
                    result[idx] = cand.copy(
                        targetBox = updatedTarget,
                        bubbleBounds = splitBounds,
                        contourProfile = null
                    )
                }
            }
        }
        return result
    }

    private fun computeSplitTargetBox(
        cand: BubbleCandidate,
        splitBounds: Rect,
        contourProfile: ContourScanlineProfile?
    ): Rect {
        val isRect = !cand.isBubble || (contourProfile?.isRectangularLike == true)
        val laidOut = if (cand.isBubble) {
            BubbleLayoutGeometry.centeredLayoutBox(cand.textRect, splitBounds, isRectangular = isRect)
        } else {
            null
        }
        if (laidOut != null && !laidOut.isEmpty) {
            val clamped = Rect(laidOut)
            if (clamped.intersect(splitBounds) && !clamped.isEmpty) {
                return clamped
            }
        }
        val textClamped = Rect(cand.textRect)
        if (textClamped.intersect(splitBounds) && !textClamped.isEmpty) {
            return textClamped
        }
        return Rect(splitBounds)
    }

    private fun sliceContourVertically(
        profile: ContourScanlineProfile?,
        origBounds: Rect,
        subBounds: Rect
    ): ContourScanlineProfile? {
        if (profile == null || !profile.isUsable) return null
        val origW = origBounds.width().toFloat()
        val origH = origBounds.height().toFloat()
        val subW = subBounds.width().toFloat()
        val subH = subBounds.height().toFloat()
        if (origW <= 0f || origH <= 0f || subW <= 0f || subH <= 0f || profile.rowCount <= 0) return null

        val outRows = profile.rowCount.coerceAtLeast(16)
        val lefts = FloatArray(outRows)
        val rights = FloatArray(outRows)
        var validCount = 0
        var centerSumX = 0f
        var centerSumY = 0f
        for (r in 0 until outRows) {
            val subFrac = (r + 0.5f) / outRows.toFloat()
            val y = subBounds.top + subFrac * subH
            val origFrac = ((y - origBounds.top) / origH).coerceIn(0f, 0.9999f)
            val srcRow = (origFrac * profile.rowCount).toInt().coerceIn(0, profile.rowCount - 1)
            val absL = origBounds.left + profile.rowLeftFractions[srcRow] * origW
            val absR = origBounds.left + profile.rowRightFractions[srcRow] * origW
            val l = ((absL - subBounds.left) / subW).coerceIn(0f, 1f)
            val rFrac = ((absR - subBounds.left) / subW).coerceIn(0f, 1f)
            lefts[r] = l
            rights[r] = rFrac
            if (rFrac > l) {
                validCount++
                centerSumX += (l + rFrac) * 0.5f
                centerSumY += subFrac
            }
        }
        if (validCount < 2) return null
        return ContourScanlineProfile(
            rowLeftFractions = lefts,
            rowRightFractions = rights,
            opticalCenterX = (centerSumX / validCount).coerceIn(0.1f, 0.9f),
            opticalCenterY = (centerSumY / validCount).coerceIn(0.1f, 0.9f)
        ).takeIf { it.isUsable }
    }
}

internal object FallbackBubbleSupport {

    fun shouldRunFallbackLineScan(
        isPpOcrAny: Boolean,
        regionCount: Int,
        primaryBlockCount: Int
    ): Boolean = isPpOcrAny && (regionCount == 0 || (primaryBlockCount == 0 && regionCount > 0))

    fun selectContainingBalloon(textRect: Rect, balloonBoxes: List<Rect>): Rect? {
        if (textRect.isEmpty || balloonBoxes.isEmpty()) return null
        val textArea = BubbleCandidateMerger.areaOf(textRect).coerceAtLeast(1L)
        val scored = balloonBoxes.filter { !it.isEmpty }.map { bBox ->
            val coverage = BubbleCandidateMerger.overlapArea(textRect, bBox).toFloat() / textArea
            val containsCenter = bBox.contains(textRect.centerX(), textRect.centerY())
            val score = coverage +
                (if (containsCenter) 0.08f else 0f) +
                (if (coverage >= 0.95f) 0.04f else 0f)
            Triple(bBox, score, BubbleCandidateMerger.areaOf(bBox))
        }
        val comparator = compareBy<Triple<Rect, Float, Long>> { it.second }
            .thenByDescending { it.third }
        val acceptable = scored.filter { it.second >= 0.35f }
        return acceptable.maxWithOrNull(comparator)?.first
    }

    fun rectangularContourProfile(rows: Int = 16): ContourScanlineProfile {
        val safeRows = rows.coerceAtLeast(4)
        return ContourScanlineProfile(
            rowLeftFractions = FloatArray(safeRows) { 0.02f },
            rowRightFractions = FloatArray(safeRows) { 0.98f },
            opticalCenterX = 0.5f,
            opticalCenterY = 0.5f
        )
    }

    fun resolveNonBalloonContour(
        bitmap: Bitmap,
        textBounds: Rect,
        bubbleBounds: Rect,
        isDarkBubble: Boolean,
        dialogueLabel: String?
    ): ContourScanlineProfile? {
        val isNarrationTextBox = dialogueLabel.equals("text", ignoreCase = true)
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 || bubbleBounds.isEmpty) {
            return if (isNarrationTextBox) rectangularContourProfile() else null
        }
        val extracted = BubbleContourExtractor.extractFromBitmap(
            bitmap = bitmap,
            bubbleBox = bubbleBounds,
            anchorBox = textBounds,
            pageOriginalWidth = bitmap.width.toFloat(),
            pageOriginalHeight = bitmap.height.toFloat(),
            isDarkBubble = isDarkBubble
        )?.takeIf { it.isUsable }
        if (isNarrationTextBox) {
            return extracted?.takeIf { it.isRectangularLike } ?: rectangularContourProfile()
        }
        return extracted ?: BubbleContourExtractor.detectRectangularFrameFromBitmap(
            bitmap = bitmap,
            bubbleBox = bubbleBounds,
            pageOriginalWidth = bitmap.width.toFloat(),
            pageOriginalHeight = bitmap.height.toFloat()
        )
    }

    fun mergeOcrBlocksWithFallback(
        primary: List<TranslationService.RectAndText>,
        fallback: List<TranslationService.RectAndText>
    ): List<TranslationService.RectAndText> {
        val merged = primary.filter { !it.rect.isEmpty && it.text.isNotBlank() }.toMutableList()
        if (fallback.isEmpty()) return merged

        for (fb in fallback) {
            if (fb.rect.isEmpty || fb.text.isBlank()) continue
            val fbArea = BubbleCandidateMerger.areaOf(fb.rect).coerceAtLeast(1L)
            val duplicatesExisting = merged.any { existing ->
                val inter = BubbleCandidateMerger.overlapArea(fb.rect, existing.rect)
                if (inter <= 0L) return@any false
                val existArea = BubbleCandidateMerger.areaOf(existing.rect).coerceAtLeast(1L)
                val unionArea = (fbArea + existArea - inter).coerceAtLeast(1L)
                val iou = inter.toFloat() / unionArea
                val fbCoverage = inter.toFloat() / fbArea
                val existCoverage = inter.toFloat() / existArea
                iou >= 0.30f ||
                    fbCoverage >= 0.45f ||
                    existCoverage >= 0.45f ||
                    existing.rect.contains(fb.rect.centerX(), fb.rect.centerY()) ||
                    fb.rect.contains(existing.rect.centerX(), existing.rect.centerY())
            }
            if (!duplicatesExisting) {
                merged.add(fb)
            }
        }
        return merged
    }

    fun isBubbleInteriorDark(bitmap: Bitmap, bubbleRect: Rect?): Boolean {
        if (bitmap.isRecycled || bubbleRect == null || bubbleRect.width() < 8 || bubbleRect.height() < 8) {
            return false
        }
        val insetX = (bubbleRect.width() * 0.12f).toInt().coerceAtLeast(1)
        val insetY = (bubbleRect.height() * 0.12f).toInt().coerceAtLeast(1)
        val sLeft = (bubbleRect.left + insetX).coerceIn(0, bitmap.width - 1)
        val sRight = (bubbleRect.right - insetX).coerceIn(sLeft, bitmap.width - 1)
        val sTop = (bubbleRect.top + insetY).coerceIn(0, bitmap.height - 1)
        val sBottom = (bubbleRect.bottom - insetY).coerceIn(sTop, bitmap.height - 1)
        val stepX = maxOf(1, (sRight - sLeft) / 6)
        val stepY = maxOf(1, (sBottom - sTop) / 6)
        val lums = ArrayList<Float>(49)
        var dark = 0
        for (sy in sTop..sBottom step stepY) {
            for (sx in sLeft..sRight step stepX) {
                val px = bitmap.getPixel(sx, sy)
                val lum = Color.red(px) * 0.299f + Color.green(px) * 0.587f + Color.blue(px) * 0.114f
                lums.add(lum)
                if (lum < 65f) dark++
            }
        }
        if (lums.isEmpty()) return false
        if (dark * 100 >= lums.size * 55) return true

        lums.sort()
        val median = lums[lums.size / 2]
        val p95 = lums[((lums.size - 1) * 0.95f).toInt().coerceIn(0, lums.size - 1)]
        return median < 198f && p95 >= 205f && (p95 - median) >= 42f
    }

    /**
     * Detects dark panels or colored/semi-transparent bubbles carrying white/light text
     * by comparing the immediate outer rim luma against bright interior text strokes.
     */
    fun isColoredOrDarkWithLightText(bitmap: Bitmap, textRect: Rect): Boolean {
        if (bitmap.isRecycled || textRect.isEmpty || bitmap.width < 8 || bitmap.height < 8) return false
        val rimMedian = sampleRimMedianLuma(bitmap, textRect) ?: return false
        if (rimMedian < 85f) return true
        if (rimMedian >= 198f) return false

        val iLeft = textRect.left.coerceIn(0, bitmap.width - 1)
        val iRight = textRect.right.coerceIn(iLeft, bitmap.width - 1)
        val iTop = textRect.top.coerceIn(0, bitmap.height - 1)
        val iBottom = textRect.bottom.coerceIn(iTop, bitmap.height - 1)
        val stepX = maxOf(1, (iRight - iLeft) / 8)
        val stepY = maxOf(1, (iBottom - iTop) / 8)
        val innerLums = ArrayList<Float>(81)
        for (sy in iTop..iBottom step stepY) {
            for (sx in iLeft..iRight step stepX) {
                val px = bitmap.getPixel(sx, sy)
                innerLums.add(Color.red(px) * 0.299f + Color.green(px) * 0.587f + Color.blue(px) * 0.114f)
            }
        }
        if (innerLums.isEmpty()) return false
        innerLums.sort()
        val p95 = innerLums[((innerLums.size - 1) * 0.95f).toInt().coerceIn(0, innerLums.size - 1)]
        return p95 >= 205f && (p95 - rimMedian) >= 42f
    }

    /**
     * Prevents `refineBubbleBounds` from spilling outside semi-transparent colored bubbles
     * or borderless dark panels when no YOLO container box exists.
     */
    fun boundFallbackRefinedBox(
        bitmap: Bitmap,
        textRect: Rect,
        rawRefined: Rect,
        isDarkBubble: Boolean
    ): Rect {
        if (textRect.isEmpty || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return rawRefined
        }
        val maxPadX = (textRect.width() * 0.14f).toInt().coerceIn(4, 16)
        val maxPadY = (textRect.height() * 0.14f).toInt().coerceIn(4, 14)
        val safeClamp = Rect(
            (textRect.left - maxPadX).coerceAtLeast(0),
            (textRect.top - maxPadY).coerceAtLeast(0),
            (textRect.right + maxPadX).coerceAtMost(bitmap.width),
            (textRect.bottom + maxPadY).coerceAtMost(bitmap.height)
        )
        val rimMedian = sampleRimMedianLuma(bitmap, textRect) ?: 255f
        val shouldTightClamp = isDarkBubble || rimMedian < 200f
        val innerLeft = textRect.left.coerceIn(safeClamp.left, safeClamp.right)
        val innerTop = textRect.top.coerceIn(safeClamp.top, safeClamp.bottom)
        val innerRight = textRect.right.coerceIn(innerLeft, safeClamp.right)
        val innerBottom = textRect.bottom.coerceIn(innerTop, safeClamp.bottom)
        val clampedRefined = Rect(
            rawRefined.left.coerceIn(safeClamp.left, innerLeft),
            rawRefined.top.coerceIn(safeClamp.top, innerTop),
            rawRefined.right.coerceIn(innerRight, safeClamp.right),
            rawRefined.bottom.coerceIn(innerBottom, safeClamp.bottom)
        )
        val withinControlledExpansion = rawRefined.width() <= (textRect.width() * 2.4f).toInt() &&
            rawRefined.height() <= (textRect.height() * 2.4f).toInt()
        if (shouldTightClamp) {
            if (withinControlledExpansion &&
                isMostlyUniformBubbleFill(bitmap, rawRefined, isDarkBubble, rimMedian)
            ) {
                return rawRefined
            }
            return clampedRefined
        }

        // For light bubbles, reject runaway expansion when the expanded area isn't uniform light paper.
        if (!withinControlledExpansion && !isMostlyUniformLightFill(bitmap, rawRefined)) {
            return clampedRefined
        }
        return rawRefined
    }

    /**
     * Orders translated candidates by panel-aware manga (RTL) or webtoon/English (LTR)
     * reading order so batched LLM translation sees coherent dialogue flow.
     */
    fun sortCandidatesByPanelReadingOrder(
        candidates: List<BubbleCandidate>,
        pagePanels: List<Rect>,
        originLang: String
    ): List<BubbleCandidate> {
        if (candidates.size <= 1) return candidates
        val isRtl = originLang.equals("Japonca", ignoreCase = true) ||
            originLang.equals("Japanese", ignoreCase = true)

        fun <T> orderByTierAndColumn(items: List<Pair<T, Rect>>): List<T> {
            if (items.size <= 1) return items.map { it.first }
            val byTop = items.sortedWith(compareBy<Pair<T, Rect>> { it.second.top }.thenBy { it.second.left })
            val tiers = ArrayList<MutableList<Pair<T, Rect>>>()
            var tierTop = byTop[0].second.top
            var tierBottom = byTop[0].second.bottom
            var currentTier = mutableListOf(byTop[0])
            for (i in 1 until byTop.size) {
                val item = byTop[i]
                val rect = item.second
                val minH = minOf(rect.height(), (tierBottom - tierTop).coerceAtLeast(1))
                val overlapsTierVertically = rect.top < tierBottom - (minH * 0.20f).toInt()
                if (overlapsTierVertically) {
                    currentTier.add(item)
                    if (rect.bottom > tierBottom) tierBottom = rect.bottom
                } else {
                    tiers.add(currentTier)
                    currentTier = mutableListOf(item)
                    tierTop = rect.top
                    tierBottom = rect.bottom
                }
            }
            tiers.add(currentTier)

            val ordered = ArrayList<T>(items.size)
            for (tier in tiers) {
                if (tier.size <= 1) {
                    ordered.add(tier[0].first)
                    continue
                }
                val sortedTier = if (isRtl) {
                    tier.sortedWith(compareByDescending<Pair<T, Rect>> { it.second.right }.thenBy { it.second.top })
                } else {
                    tier.sortedWith(compareBy<Pair<T, Rect>> { it.second.left }.thenBy { it.second.top })
                }
                val columns = ArrayList<MutableList<Pair<T, Rect>>>()
                var colLeft = sortedTier[0].second.left
                var colRight = sortedTier[0].second.right
                var currentCol = mutableListOf(sortedTier[0])
                for (i in 1 until sortedTier.size) {
                    val item = sortedTier[i]
                    val rect = item.second
                    val minW = minOf(rect.width(), (colRight - colLeft).coerceAtLeast(1))
                    val overlapsColumn = if (isRtl) {
                        rect.right > colLeft + (minW * 0.25f).toInt()
                    } else {
                        rect.left < colRight - (minW * 0.25f).toInt()
                    }
                    if (overlapsColumn) {
                        currentCol.add(item)
                        if (rect.left < colLeft) colLeft = rect.left
                        if (rect.right > colRight) colRight = rect.right
                    } else {
                        columns.add(currentCol)
                        currentCol = mutableListOf(item)
                        colLeft = rect.left
                        colRight = rect.right
                    }
                }
                columns.add(currentCol)

                for (col in columns) {
                    col.sortWith(
                        compareBy<Pair<T, Rect>> { it.second.top }
                            .thenBy { if (isRtl) -it.second.right else it.second.left }
                    )
                    for (entry in col) ordered.add(entry.first)
                }
            }
            return ordered
        }

        val validPanels = pagePanels.filter { !it.isEmpty }
        if (validPanels.isEmpty()) {
            return orderByTierAndColumn(
                candidates.map { cand ->
                    val box = cand.bubbleBounds.takeUnless { it.isEmpty } ?: cand.textRect
                    cand to box
                }
            )
        }

        val groupedByPanel = HashMap<Int, MutableList<BubbleCandidate>>()
        val unassigned = ArrayList<Pair<List<BubbleCandidate>, Rect>>()
        for (cand in candidates) {
            val box = cand.textRect.takeUnless { it.isEmpty } ?: cand.bubbleBounds
            val boxArea = BubbleCandidateMerger.areaOf(box).coerceAtLeast(1L)
            var bestPanelIdx = -1
            var bestScore = 0f
            var bestPanelArea = Long.MAX_VALUE
            for ((pIdx, panel) in validPanels.withIndex()) {
                val inter = BubbleCandidateMerger.overlapArea(box, panel)
                val coverage = inter.toFloat() / boxArea
                val containsCenter = panel.contains(box.centerX(), box.centerY())
                val score = coverage + if (containsCenter) 0.08f else 0f
                val panelArea = BubbleCandidateMerger.areaOf(panel)
                if (score >= 0.20f &&
                    (score > bestScore || (score == bestScore && panelArea < bestPanelArea))
                ) {
                    bestScore = score
                    bestPanelIdx = pIdx
                    bestPanelArea = panelArea
                }
            }
            if (bestPanelIdx >= 0) {
                groupedByPanel.getOrPut(bestPanelIdx) { ArrayList() }.add(cand)
            } else {
                unassigned.add(listOf(cand) to box)
            }
        }

        val readingUnits = ArrayList<Pair<List<BubbleCandidate>, Rect>>(groupedByPanel.size + unassigned.size)
        for ((pIdx, panelCands) in groupedByPanel) {
            val orderedInsidePanel = orderByTierAndColumn(
                panelCands.map { cand ->
                    val box = cand.bubbleBounds.takeUnless { it.isEmpty } ?: cand.textRect
                    cand to box
                }
            )
            readingUnits.add(orderedInsidePanel to validPanels[pIdx])
        }
        readingUnits.addAll(unassigned)
        return orderByTierAndColumn(readingUnits).flatten()
    }

    private fun isMostlyUniformBubbleFill(
        bitmap: Bitmap,
        rect: Rect,
        isDarkBubble: Boolean,
        targetLum: Float
    ): Boolean {
        if (targetLum >= 195f && !isDarkBubble) {
            return isMostlyUniformLightFill(bitmap, rect)
        }
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val right = (rect.right - 1).coerceIn(left, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val bottom = (rect.bottom - 1).coerceIn(top, bitmap.height - 1)
        val stepX = maxOf(1, (right - left) / 8)
        val stepY = maxOf(1, (bottom - top) / 8)
        var total = 0
        var matching = 0
        var whitePaper = 0
        for (sy in top..bottom step stepY) {
            for (sx in left..right step stepX) {
                val lum = pixelLuma(bitmap.getPixel(sx, sy))
                total++
                if (isDarkBubble && targetLum < 95f) {
                    if (lum < 105f) matching++
                    if (lum >= 210f) whitePaper++
                } else {
                    if (kotlin.math.abs(lum - targetLum) <= 35f || lum >= 205f) matching++
                    if (lum >= 235f) whitePaper++
                }
            }
        }
        if (total <= 0) return false
        return matching * 100 >= total * 68 && whitePaper * 100 <= total * 25
    }

    private fun sampleRimMedianLuma(bitmap: Bitmap, rect: Rect): Float? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || rect.isEmpty) return null
        val lums = ArrayList<Float>(64)
        for (dist in intArrayOf(3, 6)) {
            val left = (rect.left - dist).coerceIn(0, width - 1)
            val top = (rect.top - dist).coerceIn(0, height - 1)
            val right = (rect.right + dist).coerceIn(0, width - 1)
            val bottom = (rect.bottom + dist).coerceIn(0, height - 1)
            val stepX = maxOf(1, (right - left) / 6)
            for (x in left..right step stepX) {
                lums.add(pixelLuma(bitmap.getPixel(x, top)))
                lums.add(pixelLuma(bitmap.getPixel(x, bottom)))
            }
            val stepY = maxOf(1, (bottom - top) / 6)
            for (y in top..bottom step stepY) {
                lums.add(pixelLuma(bitmap.getPixel(left, y)))
                lums.add(pixelLuma(bitmap.getPixel(right, y)))
            }
        }
        if (lums.isEmpty()) return null
        lums.sort()
        return lums[lums.size / 2]
    }

    private fun isMostlyUniformLightFill(bitmap: Bitmap, rect: Rect): Boolean {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val right = rect.right.coerceIn(left, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val bottom = rect.bottom.coerceIn(top, bitmap.height - 1)
        val stepX = maxOf(1, (right - left) / 8)
        val stepY = maxOf(1, (bottom - top) / 8)
        var total = 0
        var light = 0
        for (sy in top..bottom step stepY) {
            for (sx in left..right step stepX) {
                total++
                if (pixelLuma(bitmap.getPixel(sx, sy)) >= 195f) light++
            }
        }
        return total > 0 && light * 100 >= total * 70
    }

    private fun pixelLuma(px: Int): Float =
        Color.red(px) * 0.299f + Color.green(px) * 0.587f + Color.blue(px) * 0.114f
}
