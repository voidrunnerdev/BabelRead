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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.data.net.PageRefererRegistry
import com.example.data.web.CaptureChapter
import com.example.data.web.CaptureResult
import com.example.data.web.CaptureSeries
import com.example.data.web.WebCaptureMode
import com.example.data.web.WebCaptureParser
import com.example.data.web.WebScripts
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
                .background(Color(0xFF121212)),
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

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, start = 4.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Geri",
                        tint = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sourceName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                    Text(
                        text = currentUrl.ifBlank { baseUrl },
                        color = Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                IconButton(onClick = { startQuickRead(false) }, enabled = !quickReading) {
                    if (quickReading && !quickReadViaTranslate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = "Oku (eklemeden)",
                            tint = Color.White
                        )
                    }
                }
                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = Color.White)
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Okuyucudaki çeviri butonuyla aynı görünüm ve davranış.
                FloatingActionButton(
                    onClick = { startQuickRead(true, showPicker = false) },
                    modifier = Modifier
                        .border(1.dp, Color.White.copy(alpha = 0.25f), shape = FloatingActionButtonDefaults.shape)
                        .testTag("browser_translate_fab"),
                    containerColor = Color.DarkGray.copy(alpha = 0.45f),
                    contentColor = Color.White.copy(alpha = 0.90f),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp)
                ) {
                    if (quickReading && quickReadViaTranslate) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Türkçe Çeviri",
                            tint = Color.White.copy(alpha = 0.90f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
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
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
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
            containerColor = Color(0xFF1E1E1E),
            confirmButton = {
                TextButton(onClick = { feedback = null }) {
                    Text("Tamam")
                }
            },
            title = { Text("Bilgi", color = Color.White) },
            text = { Text(feedback ?: "", color = Color(0xFFBDBDBD)) }
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
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Seriyi Ekle", color = Color.White) },
        text = {
            Column {
                Text(
                    result.title.ifBlank { "Başlıksız" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${result.chapters.size} bölüm bulundu. Eklemek istediklerinizi seçin:",
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Bölümler okurken internetten yayınlanır; cihaza indirilmez.",
                    color = Color(0xFF757575),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.heightIn(max = 360.dp)) {
                    LazyColumn {
                        itemsIndexed(result.chapters) { _, chapter ->
                            val checked = selected[chapter.url] == true
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected[chapter.url] = !checked }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selected[chapter.url] = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    chapter.title,
                                    color = Color(0xFFE0E0E0),
                                    fontSize = 13.sp,
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
                Text("İptal", color = Color(0xFF9E9E9E))
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
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Seri Listesi Algılandı", color = Color.White) },
        text = {
            Column {
                Text(
                    "${series.size} seri bulundu. Eklemek istediklerinizi seçin; her seri ayrı ayrı kütüphaneye eklenir.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Mangalar cihaza indirilmez; bölümler okurken internetten yayınlanır.",
                    color = Color(0xFF757575),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        if (allSelected) selected.clear()
                        else series.forEach { selected[it.url] = true }
                    }
                ) {
                    Text(
                        if (allSelected) "Seçimi Kaldır" else "Tümünü Seç",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
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
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selected[item.url] = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        color = Color(0xFFE0E0E0),
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        item.url.removePrefix("https://").removePrefix("http://"),
                                        color = Color(0xFF757575),
                                        fontSize = 10.sp,
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
                Text("İptal", color = Color(0xFF9E9E9E))
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
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Okunacak Görseller (${pages.size})", color = Color.White) },
        text = {
            Column {
                Text(
                    "Tümü seçili. İstemediğiniz görsellerin işaretini kaldırabilirsiniz; sadece seçili olanlar açılır.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Görseller cihaza indirilmez; okurken internetten yayınlanır.",
                    color = Color(0xFF757575),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        if (allSelected) selected.clear()
                        else pages.forEach { selected[it] = true }
                    }
                ) {
                    Text(
                        if (allSelected) "Seçimi Kaldır" else "Tümünü Seç",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
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
                                    .padding(vertical = 3.dp)
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
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF262626))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "${index + 1}. görsel",
                                    color = Color(0xFFE0E0E0),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { translate = !translate }
                ) {
                    Checkbox(checked = translate, onCheckedChange = { translate = it })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Çeviri ile oku", color = Color(0xFFE0E0E0), fontSize = 13.sp)
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
                Text("İptal", color = Color(0xFF9E9E9E))
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
