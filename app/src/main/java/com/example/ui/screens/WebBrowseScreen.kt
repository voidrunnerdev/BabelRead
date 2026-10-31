package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.data.net.PageRefererRegistry
import com.example.data.net.WebViewIdentity
import com.example.data.web.CaptureChapter
import com.example.data.web.CaptureResult
import com.example.data.web.CaptureSeries
import com.example.data.web.WebCaptureMode
import com.example.data.web.WebCaptureParser
import com.example.data.web.WebScripts
import com.example.i18n.Text
import com.example.ui.components.AppScaffold
import com.example.ui.components.AppTopBar
import com.example.ui.theme.AppElevation
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowseScreen(
    viewModel: MangaViewModel,
    siteId: String,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String, String) -> Unit,
    onNavigateToReader: (String) -> Unit,
    startUrl: String? = null
) {
    val webSites by viewModel.webSites.collectAsState()
    val site = remember(siteId, webSites) {
        webSites.firstOrNull { it.id == siteId || it.name == siteId }
    }
    val scope = rememberCoroutineScope()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf("") }
    var captureResult by remember { mutableStateOf<CaptureResult?>(null) }
    var showCaptureDialog by remember { mutableStateOf(false) }
    var listingSeries by remember { mutableStateOf<List<CaptureSeries>?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var quickReading by remember { mutableStateOf(false) }
    var quickReadViaTranslate by remember { mutableStateOf(false) }
    var quickReadRequest by remember { mutableStateOf<QuickReadRequest?>(null) }

    LaunchedEffect(webSites, siteId) {
        if (siteId.isNotBlank() && site == null && webSites.isNotEmpty()) {
            onNavigateBack()
        }
    }

    if (site == null && startUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val baseUrl = startUrl ?: site?.origin.orEmpty()
    val sourceName = remember(site, baseUrl) {
        val host = if (site != null) {
            site.name
        } else {
            baseUrl.substringAfter("://", baseUrl).substringBefore('/').substringBefore('?')
        }
        "Web: $host"
    }

    fun startQuickRead(withTranslate: Boolean, showPicker: Boolean = true) {
        val web = webViewRef ?: return
        if (quickReading) return
        quickReading = true
        quickReadViaTranslate = !showPicker
        scope.launch {
            try {
                val info = evalJsValue(web, WebScripts.PAGE_INFO)?.let { raw ->
                    runCatching { JSONObject(raw) }.getOrNull()
                }
                val pageTitle = info?.optString("t", "").orEmpty()
                val coverUrl = info?.optString("c", "").orEmpty()
                val pageUrl = currentUrl
                    .ifBlank { info?.optString("u", "").orEmpty() }
                    .ifBlank { web.url.orEmpty() }

                var lastCount = -1
                var stable = 0
                for (i in 0 until 80) {
                    val raw = evalJsValue(web, WebScripts.SCROLL_STEP)
                    val state = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (state != null) {
                        val count = state.optInt("c", 0)
                        val y = state.optDouble("y", 0.0)
                        val h = state.optDouble("h", 0.0)
                        val vh = state.optDouble("vh", 0.0)
                        if (count == lastCount) stable++ else stable = 0
                        lastCount = count
                        if (h > 0 && y + vh >= h - 64 && stable >= 2) break
                    }
                    delay(300)
                }

                val entries = WebCaptureParser.parseImages(evalJsValue(web, WebScripts.COLLECT_IMAGES))
                val pages = WebCaptureParser.pickReadablePages(entries)
                web.evaluateJavascript("window.scrollTo(0, 0)", null)

                if (pages.isEmpty()) {
                    feedback = "Bu sayfada okunacak görsel bulunamadı. Bir bölüm (okuma) sayfası açıp tekrar deneyin."
                } else {
                    PageRefererRegistry.registerForUrls(pages, baseUrl.ifBlank { currentUrl })
                    if (showPicker) {
                        quickReadRequest = QuickReadRequest(
                            title = pageTitle.ifBlank { "Hızlı Okuma" },
                            cover = coverUrl,
                            pageUrl = pageUrl,
                            pages = pages,
                            translateDefault = withTranslate
                        )
                    } else {
                        // Çeviri butonu: diyalog yok; doğrudan çeviriyle açılır.
                        val chapterId = viewModel.startQuickRead(
                            sourceName = sourceName,
                            pageTitle = pageTitle.ifBlank { "Hızlı Okuma" },
                            pageUrl = pageUrl,
                            coverUrl = coverUrl,
                            pages = pages,
                            translate = withTranslate
                        )
                        if (chapterId != null) {
                            onNavigateToReader(chapterId)
                        } else {
                            feedback = "Hızlı okuma başlatılamadı."
                        }
                    }
                }
            } finally {
                quickReading = false
            }
        }
    }

    BackHandler {
        val web = webViewRef
        if (web != null && web.canGoBack()) web.goBack() else onNavigateBack()
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = sourceName,
                subtitle = currentUrl.ifBlank { baseUrl },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { startQuickRead(false) }, enabled = !quickReading) {
                        if (quickReading && !quickReadViaTranslate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(AppSizes.icon),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Default.MenuBook,
                                contentDescription = "Oku (eklemeden)",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Yenile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Okuyucudaki çeviri butonuyla aynı görünüm ve davranış.
                FloatingActionButton(
                    onClick = { startQuickRead(true, showPicker = false) },
                    modifier = Modifier
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            shape = FloatingActionButtonDefaults.shape
                        )
                        .testTag("browser_translate_fab"),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = AppElevation.low,
                        pressedElevation = AppElevation.medium
                    )
                ) {
                    if (quickReading && quickReadViaTranslate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AppSizes.iconLarge),
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Türkçe Çeviri",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.md))
                ExtendedFloatingActionButton(
                    onClick = {
                    webViewRef?.evaluateJavascript(WebScripts.CAPTURE) { value ->
                        val parsed = WebCaptureParser.parse(value)
                        when (val mode = parsed?.let { WebCaptureParser.decide(it) }) {
                            is WebCaptureMode.Series -> {
                                captureResult = parsed
                                showCaptureDialog = true
                            }
                            is WebCaptureMode.Listing -> {
                                listingSeries = mode.series
                            }
                            null -> {
                                feedback = "Bu sayfada seri ya da bölüm bağlantısı bulunamadı. Seri sayfasında ya da seri listesinde deneyin."
                            }
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Text("Seri Ekle")
                }
            }
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    WebViewIdentity.apply(this)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null) currentUrl = url
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) currentUrl = url
                        }
                    }
                    loadUrl(baseUrl)
                    webViewRef = this
                }
            }
        )
    }

    val result = captureResult
    if (showCaptureDialog && result != null) {
        CaptureDialog(
            result = result,
            onDismiss = {
                showCaptureDialog = false
                captureResult = null
            },
            onConfirm = { selected ->
                scope.launch {
                    val mangaId = viewModel.captureWebManga(
                        sourceName = sourceName,
                        pageUrl = result.pageUrl.ifBlank { currentUrl.ifBlank { baseUrl } },
                        title = result.title,
                        coverUrl = result.cover,
                        description = result.description,
                        chapters = selected.map { Triple(it.title, it.url, it.number) }
                    )
                    showCaptureDialog = false
                    captureResult = null
                    if (mangaId != null) {
                        onNavigateToDetail(mangaId, sourceName)
                    } else {
                        feedback = "Seri eklenemedi. En az bir bölüm seçtiğinizden emin olun."
                    }
                }
            }
        )
    }

    val listing = listingSeries
    if (listing != null) {
        SeriesListDialog(
            series = listing,
            onDismiss = { listingSeries = null },
            onConfirm = { selected ->
                scope.launch {
                    val count = viewModel.captureWebSeries(
                        sourceName = sourceName,
                        items = selected.map { Triple(it.title, it.url, it.cover) },
                        favorite = true
                    )
                    listingSeries = null
                    feedback = if (count > 0) {
                        "$count seri kütüphaneye eklendi. Bölümler, seri açıldığında otomatik yüklenecek."
                    } else {
                        "Seri eklenemedi."
                    }
                }
            }
        )
    }

    val quickRequest = quickReadRequest
    if (quickRequest != null) {
        QuickReadDialog(
            pages = quickRequest.pages,
            translateDefault = quickRequest.translateDefault,
            onDismiss = { quickReadRequest = null },
            onConfirm = { selectedPages, translate ->
                quickReadRequest = null
                if (selectedPages.isNotEmpty()) {
                    PageRefererRegistry.registerForUrls(selectedPages, baseUrl.ifBlank { currentUrl })
                    val chapterId = viewModel.startQuickRead(
                        sourceName = sourceName,
                        pageTitle = quickRequest.title,
                        pageUrl = quickRequest.pageUrl,
                        coverUrl = quickRequest.cover,
                        pages = selectedPages,
                        translate = translate
                    )
                    if (chapterId != null) {
                        onNavigateToReader(chapterId)
                    } else {
                        feedback = "Hızlı okuma başlatılamadı."
                    }
                }
            }
        )
    }

    if (feedback != null) {
        AlertDialog(
            onDismissRequest = { feedback = null },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(onClick = { feedback = null }) {
                    Text("Tamam")
                }
            },
            title = {
                Text(
                    "Bilgi",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    feedback ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

@Composable
private fun CaptureDialog(
    result: CaptureResult,
    onDismiss: () -> Unit,
    onConfirm: (List<CaptureChapter>) -> Unit
) {
    val selected = remember {
        mutableStateMapOf<String, Boolean>().apply {
            result.chapters.forEach { put(it.url, true) }
        }
    }
    val selectedCount = result.chapters.count { selected[it.url] == true }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Seriyi Ekle",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    result.title.ifBlank { "Başlıksız" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    "${result.chapters.size} bölüm bulundu. Eklemek istediklerinizi seçin:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Text(
                    "Bölümler okurken internetten yayınlanır; cihaza indirilmez.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Box(modifier = Modifier.heightIn(max = 360.dp)) {
                    LazyColumn {
                        itemsIndexed(result.chapters) { _, chapter ->
                            val checked = selected[chapter.url] == true
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected[chapter.url] = !checked }
                                    .padding(vertical = AppSpacing.xxs)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selected[chapter.url] = it }
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xs))
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(result.chapters.filter { selected[it.url] == true })
                },
                enabled = selectedCount > 0
            ) {
                Text(if (selectedCount > 0) "Ekle ($selectedCount)" else "Ekle")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "İptal",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun SeriesListDialog(
    series: List<CaptureSeries>,
    onDismiss: () -> Unit,
    onConfirm: (List<CaptureSeries>) -> Unit
) {
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val selectedCount = series.count { selected[it.url] == true }
    val allSelected = selectedCount == series.size && series.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Seri Listesi Algılandı",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    "${series.size} seri bulundu. Eklemek istediklerinizi seçin; her seri ayrı ayrı kütüphaneye eklenir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Text(
                    "Mangalar cihaza indirilmez; bölümler okurken internetten yayınlanır.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                TextButton(onClick = {
                    if (allSelected) selected.clear()
                    else series.forEach { selected[it.url] = true }
                }) {
                    Text(
                        if (allSelected) "Seçimi Kaldır" else "Tümünü Seç",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Box(modifier = Modifier.heightIn(max = 340.dp)) {
                    LazyColumn {
                        itemsIndexed(series) { _, item ->
                            val checked = selected[item.url] == true
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected[item.url] = !checked }
                                    .padding(vertical = AppSpacing.xxs)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selected[item.url] = it }
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xs))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        item.url.removePrefix("https://").removePrefix("http://"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(series.filter { selected[it.url] == true })
                },
                enabled = selectedCount > 0
            ) {
                Text(if (selectedCount > 0) "Ekle ($selectedCount)" else "Ekle")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "İptal",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

private data class QuickReadRequest(
    val title: String,
    val cover: String,
    val pageUrl: String,
    val pages: List<String>,
    val translateDefault: Boolean
)

@Composable
private fun QuickReadDialog(
    pages: List<String>,
    translateDefault: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<String>, Boolean) -> Unit
) {
    val selected = remember {
        mutableStateMapOf<String, Boolean>().apply {
            pages.forEach { put(it, true) }
        }
    }
    var translate by remember { mutableStateOf(translateDefault) }
    val selectedCount = pages.count { selected[it] == true }
    val allSelected = selectedCount == pages.size && pages.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Okunacak Görseller (${pages.size})",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    "Tümü seçili. İstemediğiniz görsellerin işaretini kaldırabilirsiniz; sadece seçili olanlar açılır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Text(
                    "Görseller cihaza indirilmez; okurken internetten yayınlanır.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                TextButton(onClick = {
                    if (allSelected) selected.clear()
                    else pages.forEach { selected[it] = true }
                }) {
                    Text(
                        if (allSelected) "Seçimi Kaldır" else "Tümünü Seç",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Box(modifier = Modifier.heightIn(max = 320.dp)) {
                    LazyColumn {
                        itemsIndexed(pages) { index, url ->
                            val checked = selected[url] == true
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected[url] = !checked }
                                    .padding(vertical = AppSpacing.xs)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selected[url] = it }
                                )
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(56.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Text(
                                    "${index + 1}. görsel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { translate = !translate }
                ) {
                    Checkbox(checked = translate, onCheckedChange = { translate = it })
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        "Çeviri ile oku",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pages.filter { selected[it] == true }, translate) },
                enabled = selectedCount > 0
            ) {
                Text(if (selectedCount > 0) "Oku ($selectedCount)" else "Oku")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "İptal",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

private suspend fun evalJsValue(webView: WebView, script: String): String? =
    withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine { cont ->
            try {
                webView.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
