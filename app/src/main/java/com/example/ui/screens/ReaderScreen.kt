package com.example.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.i18n.TranslationLanguages
import com.example.service.BubbleBox
import com.example.service.TextBlockObj
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.LanguagePickerOption
import com.example.util.StaticTextLayoutOptimizer
import com.example.viewmodel.MangaViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
private fun LanguageSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2C2C2C))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

data class PlacedOverlayBox(
    val block: TextBlockObj,
    val leftDp: Dp,
    val topDp: Dp,
    val widthDp: Dp,
    val heightDp: Dp,
    val layoutResult: StaticTextLayoutOptimizer.OptimizedLayoutResult,
    val paddingHDp: Dp,
    val paddingVDp: Dp,
    val showOriginalFallback: Boolean = false,
    val fallbackSourceBounds: Rect? = null,
    val forceTextToFitBox: Boolean = false,
    val boldBoost: Boolean = false
)

// Birbirine yakın/değen iki çeviri kutusu varsa, iki kutuyu da kesişim
// bölgesinin orta hattına kadar geri çekerek birbirlerinin üzerine
// çökmelerini engeller. Kutular orijinal boyutlarının %35'inden fazla
// küçültülmez. Küçültülen bloklar işaretlenir; bu blokların yazıları
// kalın çizilerek küçülen alanda okunabilirlik telafi edilir.
private fun separateNearbyBoxes(rects: List<Rect>): BooleanArray {
    val boosted = BooleanArray(rects.size)
    if (rects.size < 2) return boosted
    val margin = 3
    val originalWidths = IntArray(rects.size) { rects[it].width() }
    val originalHeights = IntArray(rects.size) { rects[it].height() }
    for (i in rects.indices) {
        for (j in i + 1 until rects.size) {
            val a = rects[i]
            val b = rects[j]
            if (a.isEmpty || b.isEmpty) continue
            val interLeft = max(a.left, b.left)
            val interTop = max(a.top, b.top)
            val interRight = min(a.right, b.right)
            val interBottom = min(a.bottom, b.bottom)
            val interW = interRight - interLeft
            val interH = interBottom - interTop
            if (interW <= 0 || interH <= 0) continue
            if (interW <= interH) {
                // Dikey bölme: soldaki kutunun sağ kenarı, sağdakinin sol kenarı
                // kesişimin orta hattına çekilir.
                val li = if (a.centerX() <= b.centerX()) i else j
                val ri = if (li == i) j else i
                val leftBox = rects[li]
                val rightBox = rects[ri]
                val mid = interLeft + interW / 2
                val newLeftRight = mid - margin
                val newRightLeft = mid + margin
                val minLeftWidth = max(28, (originalWidths[li] * 0.35f).toInt())
                val minRightWidth = max(28, (originalWidths[ri] * 0.35f).toInt())
                if (newLeftRight - leftBox.left >= minLeftWidth &&
                    rightBox.right - newRightLeft >= minRightWidth
                ) {
                    leftBox.right = newLeftRight
                    rightBox.left = newRightLeft
                    boosted[li] = true
                    boosted[ri] = true
                }
            } else {
                // Yatay bölme: üstteki kutunun alt kenarı, alttakinin üst kenarı
                // kesişimin orta hattına çekilir.
                val ti = if (a.centerY() <= b.centerY()) i else j
                val bi = if (ti == i) j else i
                val topBox = rects[ti]
                val bottomBox = rects[bi]
                val mid = interTop + interH / 2
                val newTopBottom = mid - margin
                val newBottomTop = mid + margin
                val minTopHeight = max(28, (originalHeights[ti] * 0.35f).toInt())
                val minBottomHeight = max(28, (originalHeights[bi] * 0.35f).toInt())
                if (newTopBottom - topBox.top >= minTopHeight &&
                    bottomBox.bottom - newBottomTop >= minBottomHeight
                ) {
                    topBox.bottom = newTopBottom
                    bottomBox.top = newBottomTop
                    boosted[ti] = true
                    boosted[bi] = true
                }
            }
        }
    }
    return boosted
}

private data class BoxGeometry(
    val leftDp: Dp,
    val topDp: Dp,
    val widthDp: Dp,
    val heightDp: Dp,
    val padH: Dp,
    val padV: Dp
)

private fun contrastingTextColor(backgroundColor: Int): Color {
    val alpha = android.graphics.Color.alpha(backgroundColor) / 255f
    fun linearChannel(channel: Int): Float {
        val srgb = (channel * alpha + 255f * (1f - alpha)) / 255f
        return if (srgb <= 0.04045f) srgb / 12.92f
        else ((srgb + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    val luminance = 0.2126f * linearChannel(android.graphics.Color.red(backgroundColor)) +
        0.7152f * linearChannel(android.graphics.Color.green(backgroundColor)) +
        0.0722f * linearChannel(android.graphics.Color.blue(backgroundColor))
    return if (luminance >= 0.179f) Color.Black else Color.White
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    viewModel: MangaViewModel,
    chapterId: String,
    onNavigateBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var bottomHudHeightPx by remember { mutableIntStateOf(0) }
    val bottomHudHeightDp = with(density) { bottomHudHeightPx.toDp() }

    var retainedReaderChapterId by rememberSaveable(chapterId) {
        mutableStateOf(chapterId)
    }
    LaunchedEffect(chapterId, viewModel) {
        val chapterToRestore = retainedReaderChapterId.ifBlank { chapterId }
        if (viewModel.activeChapter.value?.id != chapterToRestore) {
            viewModel.loadChapterInReader(chapterToRestore)
        } else if (viewModel.translationActive.value) {
            viewModel.resumeReaderTranslation()
        }
    }
    LaunchedEffect(viewModel) {
        // Navigation actions load chapters through the ViewModel; this collector only
        // remembers the latest requested id so it can be restored after recreation.
        viewModel.requestedReaderChapterId.collect { requestedId ->
            if (requestedId.isNotBlank()) retainedReaderChapterId = requestedId
        }
    }

    val loading by viewModel.readerLoading.collectAsState()
    val readerError by viewModel.readerError.collectAsState()
    val manga by viewModel.readerManga.collectAsState()
    val activeCh by viewModel.activeChapter.collectAsState()
    val chapters by viewModel.readerChapters.collectAsState()
    val pages by viewModel.readerPages.collectAsState()
    val webChapterUrl = remember(activeCh?.id, chapterId) {
        viewModel.webChapterUrl(activeCh?.id ?: chapterId)
    }
    val currentPageIdx by viewModel.currentPageIndex.collectAsState()
    val translationActive by viewModel.translationActive.collectAsState()
    val translatedPagesMap by viewModel.translatedPagesMap.collectAsState()
    val isTranslationLoading by viewModel.isTranslationLoading.collectAsState()
    val translationNotice by viewModel.translationNotice.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(translationNotice) {
        val message = translationNotice
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            viewModel.clearTranslationNotice()
        }
    }
    val translationBubbleStyle by viewModel.translationBubbleStyle.collectAsState()
    val translationOpacity by viewModel.translationOpacity.collectAsState()
    val translationEngine by viewModel.preferences.translationEngine.collectAsState(initial = "")
    val translationTargetLanguage by viewModel.translationTargetLanguage.collectAsState()
    val translationSourceLanguage by viewModel.translationSourceLanguage.collectAsState()
    val recentTranslationLanguages by viewModel.recentTranslationLanguages.collectAsState()
    val autoTranslateEnabled by viewModel.preferences.autoTranslate.collectAsState(initial = false)
    var showTargetLanguagePicker by remember { mutableStateOf(false) }
    var showSourceLanguagePicker by remember { mutableStateOf(false) }
    val resolvedSourceLabel = if (translationSourceLanguage == TranslationLanguages.AUTO) {
        "(Otomatik) ${manga?.originLanguage ?: "İngilizce"}"
    } else {
        TranslationLanguages.turkishName(translationSourceLanguage) ?: translationSourceLanguage
    }
    val targetLanguageLabel =
        TranslationLanguages.turkishName(translationTargetLanguage) ?: translationTargetLanguage
    val readingDirection by viewModel.readingDirection.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val brightnessLock by viewModel.brightnessLock.collectAsState()
    val isDebugMode by viewModel.isDebugMode.collectAsState()

    var showHud by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showCorrectionDialog by remember { mutableStateOf(false) }
    var correctionOriginal by remember { mutableStateOf("") }
    var correctionTranslated by remember { mutableStateOf("") }
    var correctionSaving by remember { mutableStateOf(false) }
    var correctionSaveError by remember { mutableStateOf<String?>(null) }

    val readerWindow = activity?.window
    DisposableEffect(readerWindow, keepScreenOn, brightnessLock) {
        if (readerWindow == null) {
            onDispose { }
        } else {
            val originalKeepScreenOn =
                readerWindow.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            val originalBrightness = readerWindow.attributes.screenBrightness

            if (keepScreenOn) {
                readerWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                readerWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            readerWindow.attributes = readerWindow.attributes.apply {
                screenBrightness = if (brightnessLock) {
                    1.0f
                } else {
                    originalBrightness
                }
            }

            onDispose {
                if (originalKeepScreenOn) {
                    readerWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    readerWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                readerWindow.attributes = readerWindow.attributes.apply {
                    screenBrightness = originalBrightness
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val errorMsg = readerError
        if (errorMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Hata",
                        tint = Color.Red,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Bölüm Yüklenemedi",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMsg,
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = onNavigateBack,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("Geri Git")
                        }
                        Button(
                            onClick = {
                                retainedReaderChapterId.ifBlank { chapterId }
                                    .let(viewModel::loadChapterInReader)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Yeniden Dene")
                        }
                    }
                    if (webChapterUrl != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onOpenInBrowser(webChapterUrl) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text("Tarayıcıda Aç")
                        }
                    }
                }
            }
        } else if (loading || activeCh == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (pages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "Bu bölümde gösterilecek sayfa bulunamadı.",
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onNavigateBack) { Text("Geri") }
                        Button(
                            onClick = {
                                retainedReaderChapterId.ifBlank { chapterId }
                                    .let(viewModel::loadChapterInReader)
                            }
                        ) {
                            Text("Yeniden Dene")
                        }
                    }
                }
            }
        } else {
            val ch = activeCh!!
            val totalPages = pages.size
            val safeCurrentPageIdx = if (totalPages > 0) {
                currentPageIdx.coerceIn(0, totalPages - 1)
            } else {
                0
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Box(
                    modifier = Modifier
                    .fillMaxSize()
                        .clickable(interactionSource = null, indication = null) { showHud = !showHud }
                ) {
                    key(ch.id, readingDirection) {
                    if (readingDirection == "Dikey") {
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeCurrentPageIdx)
                        LaunchedEffect(listState, totalPages) {
                            if (totalPages > 0) {
                                snapshotFlow {
                                    val layoutInfo = listState.layoutInfo
                                    val viewportStart = layoutInfo.viewportStartOffset
                                    val viewportEnd = layoutInfo.viewportEndOffset
                                    val mostVisibleIndex = layoutInfo.visibleItemsInfo
                                        .maxByOrNull { item ->
                                            val visibleStart = maxOf(item.offset, viewportStart)
                                            val visibleEnd = minOf(item.offset + item.size, viewportEnd)
                                            (visibleEnd - visibleStart).coerceAtLeast(0)
                                        }
                                        ?.index
                                        ?: listState.firstVisibleItemIndex
                                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                    if (
                                        !listState.canScrollForward &&
                                        lastVisibleIndex == totalPages - 1 &&
                                        (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                                    ) {
                                        totalPages - 1
                                    } else {
                                        mostVisibleIndex
                                    }
                                }
                                    .distinctUntilChanged()
                                    .collect { index ->
                                        viewModel.onPageIndexChanged(index.coerceIn(0, totalPages - 1))
                                    }
                            }
                        }
                        LaunchedEffect(currentPageIdx, totalPages) {
                            if (totalPages > 0 && listState.firstVisibleItemIndex != safeCurrentPageIdx && !listState.isScrollInProgress) {
                                listState.scrollToItem(safeCurrentPageIdx)
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(pages.size, key = { "${ch.id}_$it" }) { idx ->
                                ReaderPageContainer(
                                    chapterId = ch.id,
                                    pageIndex = idx,
                                    viewModel = viewModel,
                                    translationActive = translationActive,
                                    translatedBlocks = translatedPagesMap[idx] ?: emptyList(),
                                    bubbleStyle = translationBubbleStyle,
                                    opacity = translationOpacity,
                                    isDebugMode = isDebugMode,
                                    onCorrectionClick = { orig, trans ->
                                        correctionOriginal = orig
                                        correctionTranslated = trans
                                        showCorrectionDialog = true
                                    }
                                )
                            }
                        }
                    } else {
                        val pagerState = rememberPagerState(initialPage = safeCurrentPageIdx) { totalPages }
                        LaunchedEffect(currentPageIdx, totalPages) {
                            if (totalPages > 0 && pagerState.currentPage != safeCurrentPageIdx) {
                                pagerState.scrollToPage(safeCurrentPageIdx)
                            }
                        }
                        LaunchedEffect(pagerState.currentPage, totalPages) {
                            if (totalPages > 0) {
                                viewModel.onPageIndexChanged(pagerState.currentPage.coerceIn(0, totalPages - 1))
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            key = { "${ch.id}_$it" }
                        ) { index ->
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ReaderPageContainer(
                                    chapterId = ch.id,
                                    pageIndex = index,
                                    viewModel = viewModel,
                                    translationActive = translationActive,
                                    translatedBlocks = translatedPagesMap[index] ?: emptyList(),
                                    bubbleStyle = translationBubbleStyle,
                                    opacity = translationOpacity,
                                    isDebugMode = isDebugMode,
                                    onCorrectionClick = { orig, trans ->
                                        correctionOriginal = orig
                                        correctionTranslated = trans
                                        showCorrectionDialog = true
                                    }
                                )
                            }
                        }
                    }
                    }
                }

                // TOP TOOLBAR HUD
                if (showHud) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        color = Color.Black.copy(alpha = 0.85f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("reader_back")) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
                                }
                                Column {
                                    Text(
                                        text = manga?.title ?: "Manga",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = ch.title,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (webChapterUrl != null) {
                                    IconButton(
                                        onClick = { onOpenInBrowser(webChapterUrl) },
                                        modifier = Modifier.testTag("reader_open_browser")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Public,
                                            contentDescription = "Tarayıcıda Aç",
                                            tint = Color.White
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.toggleDebugMode() },
                                    modifier = Modifier.testTag("reader_debug_toggle")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = "RT-DETR AI Balon Debug Modu",
                                        tint = if (isDebugMode) Color(0xFF00E676) else Color.White
                                    )
                                }
                                IconButton(onClick = { showSettingsSheet = true }, modifier = Modifier.testTag("reader_settings")) {
                                    Icon(Icons.Default.Tune, contentDescription = "Okuyucu Ayarları", tint = Color.White)
                                }
                            }
                        }
                    }

                    // BOTTOM BAR STATUS HUD
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .onSizeChanged { bottomHudHeightPx = it.height },
                        color = Color.Black.copy(alpha = 0.85f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Sayfa ${currentPageIdx + 1} / $totalPages",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val lastPageIndex = (totalPages - 1).coerceAtLeast(0)
                            var sliderPage by remember(currentPageIdx) {
                                mutableStateOf(currentPageIdx.toFloat())
                            }
                            Slider(
                                value = sliderPage.coerceIn(0f, lastPageIndex.toFloat()),
                                onValueChange = { sliderPage = it.coerceIn(0f, lastPageIndex.toFloat()) },
                                onValueChangeFinished = {
                                    viewModel.onPageIndexChanged(sliderPage.roundToInt().coerceIn(0, lastPageIndex))
                                },
                                valueRange = 0f..lastPageIndex.toFloat(),
                                steps = (totalPages - 2).coerceAtLeast(0),
                                enabled = totalPages > 1,
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val currentIdx = chapters.indexOfFirst { it.id == ch.id }
                                fun adjacentChapter(step: Int): com.example.data.db.ChapterEntity? {
                                    if (currentIdx == -1) return null
                                    var index = currentIdx + step
                                    while (index in chapters.indices) {
                                        val candidate = chapters[index]
                                        val sameNumberTranslation = ch.chapterNumber != 0.0 &&
                                            candidate.chapterNumber == ch.chapterNumber
                                        if (!sameNumberTranslation) return candidate
                                        index += step
                                    }
                                    return null
                                }
                                val prevCh = adjacentChapter(1)
                                val nextCh = adjacentChapter(-1)

                                Button(
                                    onClick = { prevCh?.let { viewModel.loadChapterInReader(it.id) } },
                                    enabled = prevCh != null,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222))
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Önceki")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Önceki Bölüm", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { nextCh?.let { viewModel.loadChapterInReader(it.id) } },
                                    enabled = nextCh != null,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222))
                                ) {
                                    Text("Sonraki Bölüm", fontSize = 11.sp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Sonraki")
                                }
                            }
                        }
                    }
                }

                // FLOATING AUTO-TRANSLATOR CONTROL BUTTON
                FloatingActionButton(
                    onClick = { viewModel.toggleLiveTranslation(!translationActive) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = if (showHud) bottomHudHeightDp + 16.dp else 24.dp, end = 24.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), shape = FloatingActionButtonDefaults.shape)
                        .testTag("translate_toggle_fab"),
                    containerColor = if (translationActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else Color.DarkGray.copy(alpha = 0.45f),
                    contentColor = Color.White.copy(alpha = 0.90f),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp)
                ) {
                    if (isTranslationLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Çeviri",
                            tint = Color.White.copy(alpha = 0.90f)
                        )
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = Color(0xFF1E1E1E)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Okuyucu & Çeviri Tercihleri",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Okuma Yönü:", color = Color.Gray, fontSize = 13.sp)
                val directions = listOf("Dikey", "Yatay")
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    directions.forEach { d ->
                        val isSel = d == readingDirection
                        Button(
                            onClick = { coroutineScope.launch { viewModel.preferences.setReadingMode(d) } },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(d, color = Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Çeviri Motoru:", color = Color.Gray, fontSize = 13.sp)
                val engines = listOf("Otomatik", "Gemini", "DeepL", "ML Kit", "Google")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    engines.forEach { eng ->
                        val isSel = eng == translationEngine
                        Button(
                            onClick = { viewModel.setTranslationEngine(eng) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                            ),
                            modifier = Modifier.widthIn(min = 84.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                eng,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Çeviri Dilleri:", color = Color.Gray, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                LanguageSettingRow(
                    label = "Kaynak Dil",
                    value = resolvedSourceLabel,
                    onClick = { showSourceLanguagePicker = true },
                )
                Spacer(modifier = Modifier.height(6.dp))
                LanguageSettingRow(
                    label = "Hedef Dil",
                    value = targetLanguageLabel,
                    onClick = { showTargetLanguagePicker = true },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Otomatik Çeviri", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Sayfa açıldığında çeviri kendiliğinden başlar", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoTranslateEnabled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch { viewModel.preferences.setAutoTranslate(checked) }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Balon & Yazı Stili:", color = Color.Gray, fontSize = 13.sp)
                val styles = listOf("Doğal Balon", "Alt Yazı (Şeffaf)", "Koyu Zemin")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    styles.forEach { st ->
                        val isSel = st == translationBubbleStyle
                        Button(
                            onClick = { coroutineScope.launch { viewModel.preferences.setTranslationBubbleStyle(st) } },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                            ),
                            modifier = Modifier.widthIn(min = 112.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                st,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                var opacitySliderValue by remember(translationOpacity) {
                    mutableFloatStateOf(translationOpacity.toFloat())
                }
                Text("Balon Opaklığı: %${opacitySliderValue.roundToInt()}", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = opacitySliderValue,
                    onValueChange = { opacitySliderValue = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            viewModel.preferences.setTranslationOpacity(opacitySliderValue.roundToInt())
                        }
                    },
                    valueRange = 20f..100f,
                    steps = 15,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = if (isDebugMode) Color(0xFF00E676) else Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("RT-DETR AI Balon Debug Modu", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("Balon ve OCR kutularını ekranda dikdörtgenlerle görselleştirir", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = isDebugMode,
                        onCheckedChange = { checked -> viewModel.setDebugMode(checked) }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showSourceLanguagePicker) {
        val options = buildList {
            add(LanguagePickerOption(code = TranslationLanguages.AUTO, title = "Otomatik", nativeName = "Manga bilgisinden algıla"))
            TranslationLanguages.sources.forEach { lang ->
                add(LanguagePickerOption(code = lang.code, title = lang.turkishName, nativeName = lang.nativeName))
            }
        }
        LanguagePickerDialog(
            title = "Kaynak Dil",
            options = options,
            selectedCode = translationSourceLanguage,
            recentCodes = recentTranslationLanguages,
            onSelect = {
                viewModel.setTranslationSourceLanguage(it)
                showSourceLanguagePicker = false
            },
            onDismiss = { showSourceLanguagePicker = false },
        )
    }

    if (showTargetLanguagePicker) {
        val options = TranslationLanguages.targets.map { lang ->
            LanguagePickerOption(code = lang.code, title = lang.turkishName, nativeName = lang.nativeName)
        }
        LanguagePickerDialog(
            title = "Hedef Dil",
            options = options,
            selectedCode = translationTargetLanguage,
            recentCodes = recentTranslationLanguages,
            onSelect = {
                viewModel.setTranslationTargetLanguage(it)
                showTargetLanguagePicker = false
            },
            onDismiss = { showTargetLanguagePicker = false },
        )
    }

    if (showCorrectionDialog) {
        var newText by remember(correctionOriginal, correctionTranslated) {
            mutableStateOf(correctionTranslated)
        }
        AlertDialog(
            onDismissRequest = {
                if (!correctionSaving) showCorrectionDialog = false
            },
            title = { Text("Çeviriyi Düzelt", color = Color.White) },
            text = {
                Column {
                    Text("Orijinal Metin:", color = Color.Gray, fontSize = 12.sp)
                    Text(correctionOriginal, color = Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Çeviri:", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newText,
                        onValueChange = {
                            newText = it
                            correctionSaveError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    correctionSaveError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (!correctionSaving) {
                        correctionSaving = true
                        correctionSaveError = null
                        coroutineScope.launch {
                            try {
                                val result = viewModel.submitCorrection(correctionOriginal, newText)
                                result.fold(
                                    onSuccess = {
                                        showCorrectionDialog = false
                                        correctionSaveError = null
                                    },
                                    onFailure = { error ->
                                        correctionSaveError = error.localizedMessage
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { "Düzeltme kaydedilemedi: $it" }
                                            ?: "Düzeltme kaydedilemedi. Tekrar deneyin."
                                    }
                                )
                            } finally {
                                correctionSaving = false
                            }
                        }
                    }
                }, enabled = newText.isNotBlank() && !correctionSaving) {
                    if (correctionSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Kaydet")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCorrectionDialog = false }, enabled = !correctionSaving) {
                    Text("İptal")
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderPageContainer(
    chapterId: String,
    pageIndex: Int,
    viewModel: MangaViewModel,
    translationActive: Boolean,
    translatedBlocks: List<TextBlockObj>,
    bubbleStyle: String,
    opacity: Int,
    isDebugMode: Boolean = false,
    onCorrectionClick: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    val readerLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val rawBitmapState = remember(chapterId, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var rawBitmap by rawBitmapState
    DisposableEffect(rawBitmapState) {
        onDispose {
            // getPageBitmap returns a borrowed bitmap owned by the ViewModel cache.
            // Drop this screen's reference, but never recycle shared cache data here.
            rawBitmapState.value = null
        }
    }
    var bitmapLoadFailed by remember(chapterId, pageIndex) { mutableStateOf(false) }
    var bitmapLoadAttempt by remember(chapterId, pageIndex) { mutableIntStateOf(0) }
    val rawBitmapIsRecycled = rawBitmap?.isRecycled == true
    val maskedPagesMap by viewModel.maskedPagesMap.collectAsState()
    val readerPageRetryTick by viewModel.readerPageRetryTick.collectAsState()

    LaunchedEffect(chapterId, pageIndex, rawBitmapIsRecycled, bitmapLoadAttempt, readerPageRetryTick) {
        if (rawBitmap == null || rawBitmapIsRecycled) {
            rawBitmap = null
            bitmapLoadFailed = false
            try {
                val loaded = viewModel.getPageBitmap(chapterId, pageIndex)
                    ?.takeUnless { it.isRecycled }
                rawBitmap = loaded
                bitmapLoadFailed = loaded == null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                rawBitmap = null
                bitmapLoadFailed = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (rawBitmap != null && !rawBitmapIsRecycled) {
            val bmp = rawBitmap!!
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                val containerWidth = constraints.maxWidth
                val density = LocalDensity.current

                val maskedBmp = maskedPagesMap[pageIndex]
                val validMasked = if (translationActive && maskedBmp != null && !maskedBmp.isRecycled) {
                    maskedBmp
                } else null
                val displayBitmap = when {
                    validMasked != null -> validMasked
                    !bmp.isRecycled -> bmp
                    else -> null
                }

                val measureTypeface = remember(ctx) {
                    try {
                        val base = androidx.core.content.res.ResourcesCompat.getFont(
                            ctx, com.example.R.font.comic_neue
                        ) ?: Typeface.DEFAULT
                        Typeface.create(base, Typeface.BOLD)
                    } catch (_: Exception) {
                        Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                }

                // OCR rectangles belong to the bitmap used during translation. Keep that
                // coordinate space separate from whichever bitmap is currently displayed.
                val coordinateBlock = translatedBlocks.firstOrNull {
                    it.coordinateImageWidth > 0 && it.coordinateImageHeight > 0
                }
                val pageOriginalWidth = (
                    coordinateBlock?.coordinateImageWidth ?: validMasked?.width ?: bmp.width
                ).toFloat().coerceAtLeast(1f)
                val pageOriginalHeight = (
                    coordinateBlock?.coordinateImageHeight ?: validMasked?.height ?: bmp.height
                ).toFloat().coerceAtLeast(1f)
                val renderedBitmap = displayBitmap ?: bmp
                val bitmapScaleX = renderedBitmap.width.toFloat() / pageOriginalWidth
                val bitmapScaleY = renderedBitmap.height.toFloat() / pageOriginalHeight
                val widthScale = containerWidth.coerceAtLeast(1).toFloat() /
                    renderedBitmap.width.coerceAtLeast(1)
                val coordinateScaleX = bitmapScaleX * widthScale
                val coordinateScaleY = bitmapScaleY * widthScale

                if (displayBitmap != null && !displayBitmap.isRecycled) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = "Sayfa ${pageIndex + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (translationActive && translatedBlocks.isNotEmpty()) {
                    val userAlpha = (opacity / 100f).coerceIn(0.20f, 1.0f)
                    val placedOverlays by produceState(
                        initialValue = emptyList<PlacedOverlayBox>(),
                        translatedBlocks,
                        widthScale,
                        coordinateScaleX,
                        coordinateScaleY,
                        density,
                        containerWidth,
                        bubbleStyle,
                        pageOriginalWidth,
                        pageOriginalHeight,
                        measureTypeface
                    ) {
                        value = withContext(Dispatchers.Default) {
                        val screenWidthDp = with(density) { containerWidth.toFloat().toDp() }
                        // Çeviri hedef kutuları: birbirine değen/çakışan kutular
                        // metin yerleşiminden önce ayrıştırılır ki iki çeviri
                        // birbirinin üzerine çökmesin.
                        val preparedBoxes = translatedBlocks.map { block ->
                            if (block.isBubble) Rect(block.layoutBox) else Rect(block.boundingBox)
                        }
                        val boldBoostFlags = separateNearbyBoxes(preparedBoxes)
                        val placements = translatedBlocks.withIndex().mapNotNull { (blockIndex, block) ->
                            val targetBox = preparedBoxes[blockIndex]
                            val boldBoost = boldBoostFlags[blockIndex]
                            val cleanText = block.translatedText.trim()
                            val isSub = bubbleStyle == "Alt Yazı (Şeffaf)" || !block.isBubble

                            fun geometryFor(box: Rect): BoxGeometry {
                                val rawLeftDp = with(density) { (box.left * coordinateScaleX).toDp() }
                                val rawTopDp = with(density) { (box.top * coordinateScaleY).toDp() }
                                val rawWidthDp = with(density) { (box.width() * coordinateScaleX).toDp() }
                                val rawHeightDp = with(density) { (box.height() * coordinateScaleY).toDp() }
                                return if (isSub) {
                                    // Subtitle text uses a page-wide line area; source glyph width
                                    // should not force a long translation into a narrow box.
                                    val horizontalMargin = 16.dp
                                    val subtitleWidth = (screenWidthDp - horizontalMargin * 2)
                                        .coerceAtLeast(1.dp)
                                        .coerceAtMost(screenWidthDp)
                                    val subtitleLeft = ((screenWidthDp - subtitleWidth) / 2)
                                        .coerceAtLeast(0.dp)
                                    BoxGeometry(
                                        subtitleLeft,
                                        rawTopDp,
                                        subtitleWidth,
                                        maxOf(24.dp, rawHeightDp),
                                        6.dp,
                                        4.dp
                                    )
                                } else {
                                    // Keep the overlay on the detected source bounds. Moving or shrinking
                                    // it at the screen edge can expose the original glyphs underneath.
                                    val bubbleW = rawWidthDp.coerceAtLeast(1.dp)
                                    val bubbleH = rawHeightDp.coerceAtLeast(1.dp)
                                    val pH = minOf(bubbleW * 0.045f, 5.dp)
                                    val pV = minOf(bubbleH * 0.045f, 5.dp)
                                    BoxGeometry(rawLeftDp, rawTopDp, bubbleW, bubbleH, pH, pV)
                                }
                            }

                            fun maxSafeBubbleBounds(): Rect {
                                val bubble = Rect(block.bubbleBox)
                                if (bubble.isEmpty) return Rect(targetBox)
                                val pageBounds = Rect(
                                    0,
                                    0,
                                    pageOriginalWidth.toInt().coerceAtLeast(1),
                                    pageOriginalHeight.toInt().coerceAtLeast(1)
                                )
                                if (!bubble.intersect(pageBounds)) return Rect(targetBox)
                                val textAnchorX = block.boundingBox.exactCenterX()
                                val textAnchorY = block.boundingBox.exactCenterY()
                                if (textAnchorX < bubble.left || textAnchorX >= bubble.right ||
                                    textAnchorY < bubble.top || textAnchorY >= bubble.bottom
                                ) {
                                    // A detector box that misses the OCR center is not a safe
                                    // container for expansion; retain the OCR-derived layout box.
                                    return Rect(targetBox)
                                }
                                val minSide = minOf(bubble.width(), bubble.height()).coerceAtLeast(0)
                                val margin = (minSide * 0.04f).toInt()
                                    .coerceAtMost(minSide / 4)
                                    .coerceAtLeast(0)
                                val inset = Rect(
                                    bubble.left + margin,
                                    bubble.top + margin,
                                    bubble.right - margin,
                                    bubble.bottom - margin
                                )
                                if (inset.isEmpty) return Rect(targetBox)

                                // Match the 80% oval budget used when the initial box is
                                // created (MangaViewModel.centeredLayoutBox), but allow the
                                // text to grow into that whole area.
                                val centerX = bubble.exactCenterX()
                                val centerY = bubble.exactCenterY()
                                val ovalSafe = Rect(
                                    (centerX - bubble.width() * 0.40f).toInt().coerceAtLeast(bubble.left),
                                    (centerY - bubble.height() * 0.40f).toInt().coerceAtLeast(bubble.top),
                                    (centerX + bubble.width() * 0.40f).toInt().coerceAtMost(bubble.right),
                                    (centerY + bubble.height() * 0.40f).toInt().coerceAtMost(bubble.bottom)
                                )
                                if (ovalSafe.intersect(inset)) return ovalSafe
                                return inset
                            }

                            fun optimize(
                                geo: BoxGeometry,
                                minSp: Float = 8f,
                                allowBrokenWords: Boolean = false,
                                textOverride: String? = null
                            ) = StaticTextLayoutOptimizer.optimizeLayout(
                                text = textOverride ?: cleanText,
                                bounds = RectF(
                                    0f,
                                    0f,
                                    (geo.widthDp - geo.padH * 2).value.coerceAtLeast(1f),
                                    (geo.heightDp - geo.padV * 2).value.coerceAtLeast(1f)
                                ),
                                minSp = minSp,
                                maxSp = 30f,
                                density = density.density,
                                spToPx = { fontSizeSp -> with(density) { fontSizeSp.sp.toPx() } },
                                layoutLocale = readerLocale,
                                isOvalBubble = !isSub,
                                measureTypeface = measureTypeface,
                                allowBrokenWords = allowBrokenWords
                            )

                            // Android'de Türkçe heceleme desenleri bulunmadığı için uzun
                            // kelimeler tirelenemiyor ve yazı okunaksız boyutlara kadar
                            // küçülüyordu. Son çare denemelerinde uzun kelimelere görünmez
                            // bir kırılma noktası (ZWSP) ekleyip büyük boyutta sığdır.
                            fun hyphenateForFit(source: String): String {
                                val builder = StringBuilder(source.length + 8)
                                var index = 0
                                while (index < source.length) {
                                    if (source[index].isLetter()) {
                                        var end = index
                                        while (end < source.length && source[end].isLetter()) end++
                                        val length = end - index
                                        if (length >= 8) {
                                            val split = index +
                                                (length * 0.55f).roundToInt().coerceIn(3, length - 3)
                                            builder.append(source, index, split)
                                            builder.append('\u200B')
                                            builder.append(source, split, end)
                                        } else {
                                            builder.append(source, index, end)
                                        }
                                        index = end
                                    } else {
                                        builder.append(source[index])
                                        index++
                                    }
                                }
                                return builder.toString()
                            }

                            var geo = geometryFor(targetBox)
                            var opt: StaticTextLayoutOptimizer.OptimizedLayoutResult
                            if (isSub) {
                                val pageHeightDp = with(density) {
                                    (renderedBitmap.height * widthScale).toDp()
                                }
                                // Measure subtitle text with the available page height first. If we
                                // measure only in the source glyph's one-line box, the optimizer can
                                // settle on 4sp (which technically fits) and never request expansion.
                                val probeGeo = geo.copy(
                                    topDp = 0.dp,
                                    heightDp = pageHeightDp.coerceAtLeast(1.dp)
                                )
                                val probe = optimize(probeGeo)
                                opt = probe
                                val probeFontPx = with(density) { probe.fontSizeSp.sp.toPx() }
                                val safePaddingPx = max(1.5f, probeFontPx * 0.10f)
                                val requiredHeightDp = (
                                    probe.totalHeightPx + safePaddingPx * 2f
                                ) / density.density + geo.padV.value * 2f
                                if (requiredHeightDp > geo.heightDp.value) {
                                    val expandedHeight = minOf(requiredHeightDp.dp, pageHeightDp)
                                    val centeredTop = geo.topDp - (expandedHeight - geo.heightDp) / 2
                                    geo = geo.copy(
                                        topDp = centeredTop.coerceIn(
                                            0.dp,
                                            (pageHeightDp - expandedHeight).coerceAtLeast(0.dp)
                                        ),
                                        heightDp = expandedHeight
                                    )
                                    opt = optimize(geo)
                                }
                            } else {
                                opt = optimize(geo)
                            }
                            if (!opt.fitsInside) {
                                if (!isSub) {
                                    val safeBubble = maxSafeBubbleBounds()
                                    if (!safeBubble.isEmpty) {
                                        val anchorX = block.boundingBox.exactCenterX()
                                            .coerceIn(safeBubble.left.toFloat(), safeBubble.right.toFloat())
                                        val anchorY = block.boundingBox.exactCenterY()
                                            .coerceIn(safeBubble.top.toFloat(), safeBubble.bottom.toFloat())
                                        var expanded = Rect(targetBox).apply { union(block.boundingBox) }
                                        var attempts = 0
                                        while (!opt.fitsInside && attempts < 5) {
                                            val requiredWidthPx = ceil(
                                                (opt.requiredWidthDp + geo.padH.value * 2f) * density.density /
                                                    coordinateScaleX.coerceAtLeast(0.0001f)
                                            ).toInt()
                                            val fontPx = with(density) { opt.fontSizeSp.sp.toPx() }
                                            val safeTextPaddingPx = max(1.5f, fontPx * 0.10f)
                                            val requiredHeightPx = ceil(
                                                (
                                                    opt.totalHeightPx + safeTextPaddingPx * 2f +
                                                        geo.padV.value * density.density * 2f
                                                    ) / coordinateScaleY.coerceAtLeast(0.0001f)
                                            ).toInt()

                                            // Exact width can underestimate emergency-wrapped tokens;
                                            // bounded geometric growth gives the next measurement more
                                            // room without immediately filling the whole speech bubble.
                                            val nextWidth = maxOf(
                                                expanded.width(),
                                                requiredWidthPx,
                                                ceil(expanded.width() * 1.30f).toInt()
                                            ).coerceAtMost(safeBubble.width())
                                            val nextHeight = maxOf(
                                                expanded.height(),
                                                requiredHeightPx,
                                                ceil(expanded.height() * 1.30f).toInt()
                                            ).coerceAtMost(safeBubble.height())
                                            val left = (anchorX - nextWidth / 2f).roundToInt()
                                                .coerceIn(safeBubble.left, safeBubble.right - nextWidth)
                                            val top = (anchorY - nextHeight / 2f).roundToInt()
                                                .coerceIn(safeBubble.top, safeBubble.bottom - nextHeight)
                                            val next = Rect(left, top, left + nextWidth, top + nextHeight)
                                            if (next == expanded) break
                                            expanded = next
                                            geo = geometryFor(expanded)
                                            opt = optimize(geo)
                                            attempts++
                                        }

                                        // If the OCR-anchored expansion still cannot fit the
                                        // translation, try the complete safe balloon area. The
                                        // OCR box is an anchor, not a hard size limit for translated text.
                                        if (!opt.fitsInside) {
                                            geo = geometryFor(safeBubble)
                                            opt = optimize(geo)
                                        }

                                        // Long translations can need a smaller font even when the
                                        // whole safe balloon is available. Keep this as a last
                                        // readable-size attempt before showing the source fallback.
                                        if (!opt.fitsInside) {
                                            opt = optimize(geo, minSp = 6f)
                                        }
                                    }
                                }
                                // If even the readable minimum cannot fit without splitting
                                // words, allow hyphenated/broken words so the translation can
                                // grow instead of collapsing to an unreadable size.
                                if (!opt.fitsInside) {
                                    val brokenFit = optimize(
                                        geo,
                                        minSp = 6f,
                                        allowBrokenWords = true,
                                        textOverride = hyphenateForFit(cleanText)
                                    )
                                    if (brokenFit.fitsInside && brokenFit.fontSizeSp >= 8f) {
                                        opt = brokenFit
                                    }
                                }
                                if (!opt.fitsInside) {
                                    // Keep speech-bubble translations inside their safe
                                    // balloon area. If normal font fitting fails, scale the
                                    // complete layout down in the draw pass instead of
                                    // replacing it with an OCR crop and a floating TR badge.
                                    if (!isSub) {
                                        val safeBubble = maxSafeBubbleBounds().let { detectedSafe ->
                                            if (!detectedSafe.isEmpty) {
                                                detectedSafe
                                            } else {
                                                Rect(block.bubbleBox).takeUnless { it.isEmpty }
                                                    ?: Rect(block.boundingBox)
                                            }
                                        }.apply {
                                            val pageBounds = Rect(
                                                0,
                                                0,
                                                pageOriginalWidth.toInt().coerceAtLeast(1),
                                                pageOriginalHeight.toInt().coerceAtLeast(1)
                                            )
                                            if (!intersect(pageBounds)) setEmpty()
                                        }
                                        if (!safeBubble.isEmpty) {
                                            geo = geometryFor(safeBubble)
                                            opt = optimize(geo, minSp = 1f)
                                            return@mapNotNull PlacedOverlayBox(
                                                block = block,
                                                leftDp = geo.leftDp,
                                                topDp = geo.topDp,
                                                widthDp = geo.widthDp,
                                                heightDp = geo.heightDp,
                                                layoutResult = opt,
                                                paddingHDp = geo.padH,
                                                paddingVDp = geo.padV,
                                                forceTextToFitBox = true,
                                                boldBoost = boldBoost
                                            )
                                        }
                                    }
                                    // This is a layout fallback only. Keep the successful translation
                                    // available and do not change the block's translation status.
                                    // Include both OCR text and its measured layout area so a
                                    // clipped OCR rectangle cannot crop off the original glyphs.
                                    val sourceBox = Rect(block.boundingBox).apply { union(block.layoutBox) }
                                    val pageBounds = Rect(
                                        0,
                                        0,
                                        pageOriginalWidth.toInt(),
                                        pageOriginalHeight.toInt()
                                    )
                                    if (!sourceBox.intersect(pageBounds)) {
                                        sourceBox.set(block.boundingBox)
                                        sourceBox.intersect(pageBounds)
                                    }
                                    if (sourceBox.isEmpty) return@mapNotNull null
                                    val fallbackGeo = geometryFor(sourceBox)
                                    return@mapNotNull PlacedOverlayBox(
                                        block = block,
                                        leftDp = fallbackGeo.leftDp,
                                        topDp = fallbackGeo.topDp,
                                        widthDp = fallbackGeo.widthDp,
                                        heightDp = fallbackGeo.heightDp,
                                        layoutResult = opt,
                                        paddingHDp = 0.dp,
                                        paddingVDp = 0.dp,
                                        showOriginalFallback = true,
                                        fallbackSourceBounds = Rect(sourceBox)
                                    )
                                }
                            }

                            // Translations that only fit at very small sizes leave most of
                            // the balloon empty. Retry allowing hyphenated/broken words so
                            // the text can grow into the available space instead of staying
                            // tiny with empty space above and below.
                            if (!isSub && opt.fitsInside && opt.fontSizeSp < 10f) {
                                val brokenFit = optimize(
                                    geo,
                                    allowBrokenWords = true,
                                    textOverride = hyphenateForFit(cleanText)
                                )
                                if (brokenFit.fitsInside && brokenFit.fontSizeSp >= opt.fontSizeSp * 1.2f) {
                                    opt = brokenFit
                                }
                            }

                            PlacedOverlayBox(
                                block = block,
                                leftDp = geo.leftDp,
                                topDp = geo.topDp,
                                widthDp = geo.widthDp,
                                // Keep the rendered bounds identical to the geometry that was
                                // measured and fit-checked; do not grow downward after placement.
                                heightDp = geo.heightDp,
                                layoutResult = opt,
                                paddingHDp = geo.padH,
                                paddingVDp = geo.padV,
                                boldBoost = boldBoost
                            )
                        }
                        placements
                        }
                    }

                    // Katman 1: balon/alt yazı zeminleri. Tüm zeminler bütün
                    // metinlerden ÖNCE çizilir; böylece hiçbir zemin komşu
                    // bir metni örtemez ve zeminlerin kırpılmasına gerek kalmaz.
                    val peekHiddenStates = remember { mutableStateMapOf<String, Boolean>() }
                    placedOverlays.forEach { placed ->
                        val block = placed.block
                        val overlayKey = "$pageIndex:${block.boundingBox}:${block.originalText}:${placed.showOriginalFallback}"
                        if (placed.showOriginalFallback || peekHiddenStates[overlayKey] == true) {
                            return@forEach
                        }
                        val isSubtitleMode = bubbleStyle == "Alt Yazı (Şeffaf)" || !block.isBubble

                        val boxBackground = when {
                            !block.isBubble -> Color.Black.copy(alpha = 0.65f)
                            isSubtitleMode -> Color.Black.copy(alpha = 0.75f)
                            bubbleStyle == "Koyu Zemin" -> Color(0xFF111111).copy(alpha = userAlpha)
                            block.backgroundColor != null -> Color(block.backgroundColor).copy(alpha = userAlpha)
                            block.isDarkBubble -> Color(0xFF111111).copy(alpha = userAlpha)
                            else -> Color.White.copy(alpha = userAlpha)
                        }

                        // Metin kutusu, çakışan kutuları ayrıştırmak için
                        // küçültülmüş olsa bile zemin kaynak metin alanının
                        // tamamını kaplar; zemin kenarları kırpılmaz.
                        val boxLeft: Dp
                        val boxTop: Dp
                        val boxRight: Dp
                        val boxBottom: Dp
                        val sourceRect = if (isSubtitleMode) null else Rect(block.layoutBox)
                        if (sourceRect != null && !sourceRect.isEmpty) {
                            val srcLeft = with(density) { (sourceRect.left * coordinateScaleX).toDp() }
                            val srcTop = with(density) { (sourceRect.top * coordinateScaleY).toDp() }
                            val srcWidth = with(density) { (sourceRect.width() * coordinateScaleX).toDp() }
                            val srcHeight = with(density) { (sourceRect.height() * coordinateScaleY).toDp() }
                            boxLeft = minOf(placed.leftDp, srcLeft)
                            boxTop = minOf(placed.topDp, srcTop)
                            boxRight = maxOf(placed.leftDp + placed.widthDp, srcLeft + srcWidth)
                            boxBottom = maxOf(placed.topDp + placed.heightDp, srcTop + srcHeight)
                        } else {
                            boxLeft = placed.leftDp
                            boxTop = placed.topDp
                            boxRight = placed.leftDp + placed.widthDp
                            boxBottom = placed.topDp + placed.heightDp
                        }
                        val boxWidth = (boxRight - boxLeft).coerceAtLeast(1.dp)
                        val boxHeight = (boxBottom - boxTop).coerceAtLeast(1.dp)
                        val cornerRadius = if (isSubtitleMode) 6.dp else {
                            (minOf(boxWidth.value, boxHeight.value) * 0.45f).coerceIn(4f, 26f).dp
                        }
                        Box(
                            modifier = Modifier
                                .absoluteOffset(x = boxLeft, y = boxTop)
                                .width(boxWidth)
                                .height(boxHeight)
                                .drawBehind {
                                    if (size.width <= 0f || size.height <= 0f) return@drawBehind
                                    drawRoundRect(
                                        color = boxBackground,
                                        size = size,
                                        cornerRadius = CornerRadius(cornerRadius.toPx())
                                    )
                                }
                                .combinedClickable(
                                    onClick = { peekHiddenStates[overlayKey] = true },
                                    onLongClick = { onCorrectionClick(block.originalText, block.translatedText) }
                                )
                        )
                    }

                    // Katman 2: metinler ve kırpılmış orijinal görseller,
                    // zeminlerin üstünde.
                    placedOverlays.forEach { placed ->
                        val block = placed.block
                        key("$pageIndex:${block.boundingBox}:${block.originalText}") {
                        val overlayKey = "$pageIndex:${block.boundingBox}:${block.originalText}:${placed.showOriginalFallback}"
                        val isPeekHidden = peekHiddenStates[overlayKey] ?: placed.showOriginalFallback
                        var showFallbackTranslation by remember(
                            block.boundingBox,
                            block.originalText,
                            block.translatedText
                        ) {
                            mutableStateOf(false)
                        }
                        val isSubtitleMode = bubbleStyle == "Alt Yazı (Şeffaf)" || !block.isBubble

                        val boxWidth = placed.widthDp
                        val boxHeight = placed.heightDp
                        val boxLeft = placed.leftDp
                        val boxTop = placed.topDp
                        val paddingH = placed.paddingHDp
                        val paddingV = placed.paddingVDp
                        val layoutResult = placed.layoutResult

                        if (placed.showOriginalFallback || isPeekHidden) {
                            val cropBounds = placed.fallbackSourceBounds ?: remember(
                                block.boundingBox,
                                block.layoutBox,
                                pageOriginalWidth,
                                pageOriginalHeight
                            ) {
                                val expandedBounds = Rect(block.boundingBox).apply { union(block.layoutBox) }
                                val imageBounds = Rect(
                                    0,
                                    0,
                                    pageOriginalWidth.toInt(),
                                    pageOriginalHeight.toInt()
                                )
                                if (!expandedBounds.intersect(imageBounds)) {
                                    expandedBounds.set(block.boundingBox)
                                    expandedBounds.intersect(imageBounds)
                                }
                                expandedBounds
                            }
                            val cropRect = remember(cropBounds, bmp, pageOriginalWidth, pageOriginalHeight) {
                                val b = cropBounds
                                val scaleX = bmp.width.toFloat() / pageOriginalWidth
                                val scaleY = bmp.height.toFloat() / pageOriginalHeight
                                val cLeft = (b.left * scaleX).toInt().coerceIn(0, bmp.width - 1)
                                val cTop = (b.top * scaleY).toInt().coerceIn(0, bmp.height - 1)
                                val cRight = (b.right * scaleX).toInt().coerceIn(cLeft + 1, bmp.width)
                                val cBottom = (b.bottom * scaleY).toInt().coerceIn(cTop + 1, bmp.height)
                                Rect(cLeft, cTop, cRight, cBottom)
                            }
                            val cropBitmap = remember(cropRect, bmp, boxWidth, boxHeight, density) {
                                try {
                                    val sourceWidth = cropRect.width()
                                    val sourceHeight = cropRect.height()
                                    if (sourceWidth <= 0 || sourceHeight <= 0) {
                                        null
                                    } else {
                                        // The crop is only displayed inside this overlay. Avoid
                                        // keeping a full-resolution copy when the on-screen box
                                        // needs far fewer pixels; draw directly into the smaller
                                        // bitmap so no full-size intermediate is allocated.
                                        val displayEdgePx = with(density) {
                                            maxOf(boxWidth, boxHeight).roundToPx().coerceAtLeast(1)
                                        }
                                        val maxOutputEdge = (displayEdgePx.toLong() * 2L)
                                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                        val scale = minOf(
                                            1f,
                                            maxOutputEdge.toFloat() / maxOf(sourceWidth, sourceHeight)
                                        )
                                        val outputWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
                                        val outputHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                                        if (scale >= 0.999f) {
                                            Bitmap.createBitmap(
                                                bmp,
                                                cropRect.left,
                                                cropRect.top,
                                                sourceWidth,
                                                sourceHeight
                                            )
                                        } else {
                                            Bitmap.createBitmap(
                                                outputWidth,
                                                outputHeight,
                                                Bitmap.Config.ARGB_8888
                                            ).also { output ->
                                                android.graphics.Canvas(output).drawBitmap(
                                                    bmp,
                                                    cropRect,
                                                    Rect(0, 0, outputWidth, outputHeight),
                                                    android.graphics.Paint(
                                                        android.graphics.Paint.ANTI_ALIAS_FLAG or
                                                            android.graphics.Paint.FILTER_BITMAP_FLAG
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            DisposableEffect(cropBitmap, bmp) {
                                onDispose {
                                    if (cropBitmap != null && cropBitmap !== bmp && !cropBitmap.isRecycled) {
                                        cropBitmap.recycle()
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .absoluteOffset(x = boxLeft, y = boxTop)
                                    .width(boxWidth)
                                    .height(boxHeight)
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        if (placed.showOriginalFallback) {
                                            Modifier.clickable(
                                                interactionSource = null,
                                                indication = null
                                            ) { showFallbackTranslation = true }
                                        } else {
                                            Modifier.clickable { peekHiddenStates[overlayKey] = false }
                                        }
                                    )
                            ) {
                                if (cropBitmap != null) {
                                    Image(
                                        bitmap = cropBitmap.asImageBitmap(),
                                        contentDescription = "Orijinal Metin",
                                        modifier = Modifier.matchParentSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                if (!placed.showOriginalFallback) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("TR", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .background(Color(0xFF008577).copy(alpha = 0.88f), RoundedCornerShape(3.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            "TR",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        } else {
                            val textColor = when {
                                isSubtitleMode || !block.isBubble -> Color.White
                                bubbleStyle == "Koyu Zemin" -> Color.White
                                block.backgroundColor != null -> {
                                    contrastingTextColor(block.backgroundColor)
                                }
                                block.isDarkBubble -> Color.White
                                else -> Color.Black
                            }

                            val textShadowColor = when {
                                block.backgroundColor != null -> {
                                    if (textColor == Color.Black) Color.White.copy(alpha = 0.55f)
                                    else Color.Black.copy(alpha = 0.55f)
                                }
                                !block.isBubble || isSubtitleMode -> {
                                    if (block.isDarkBubble) Color.White.copy(alpha = 0.4f)
                                    else Color.Black.copy(alpha = 0.35f)
                                }
                                else -> null
                            }

                            Box(
                                modifier = Modifier
                                    .absoluteOffset(x = boxLeft, y = boxTop)
                                    .width(boxWidth)
                                    .height(boxHeight)
                                    .combinedClickable(
                                        onClick = { peekHiddenStates[overlayKey] = true },
                                        onLongClick = { onCorrectionClick(block.originalText, block.translatedText) }
                                    )
                                    .padding(horizontal = paddingH, vertical = paddingV)
                                    .semantics { contentDescription = block.translatedText },
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val staticLayout = layoutResult.staticLayout ?: return@Canvas
                                    // The placement pass normally routes non-fitting layouts to
                                    // the explicit fallback UI above. Guard again at draw time
                                    // so stale or rounded measurements can never clip text.
                                    if (!layoutResult.fitsInside && !placed.forceTextToFitBox) return@Canvas
                                    if (size.width <= 0f || size.height <= 0f) return@Canvas

                                    val layoutWidth = staticLayout.width.toFloat()
                                    val layoutHeight = staticLayout.height.toFloat()
                                    if (layoutWidth <= 0f || layoutHeight <= 0f) return@Canvas

                                    // Compose's Canvas and StaticLayout can differ by a
                                    // fractional pixel after dp/px conversion. Shrink only
                                    // when necessary, then center the complete layout.
                                    val drawScale = minOf(
                                        1f,
                                        size.width / layoutWidth,
                                        size.height / layoutHeight
                                    )
                                    val drawnWidth = layoutWidth * drawScale
                                    val drawnHeight = layoutHeight * drawScale
                                    val nativeCanvas = drawContext.canvas.nativeCanvas
                                    // StaticTextLayoutOptimizer creates an independent TextPaint
                                    // for each returned StaticLayout, so styling this paint cannot
                                    // alter another block's layout.
                                    val textPaint = staticLayout.paint
                                    textPaint.color = textColor.toArgb()
                                    if (textShadowColor != null) {
                                        textPaint.setShadowLayer(
                                            3f * density.density,
                                            1.5f * density.density,
                                            1.5f * density.density,
                                            textShadowColor.toArgb()
                                        )
                                    } else {
                                        textPaint.clearShadowLayer()
                                    }
                                    // Çakışma nedeniyle daraltılan kutularda yazı
                                    // kalınlaştırılarak okunabilirlik korunur.
                                    textPaint.isFakeBoldText = placed.boldBoost
                                    val saveCount = nativeCanvas.save()
                                    nativeCanvas.translate(
                                        (size.width - drawnWidth) / 2f,
                                        (size.height - drawnHeight) / 2f
                                    )
                                    nativeCanvas.scale(drawScale, drawScale)
                                    staticLayout.draw(nativeCanvas)
                                    nativeCanvas.restoreToCount(saveCount)
                                }
                                if (!block.translationSucceeded) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                            .background(Color(0xFFD32F2F), RoundedCornerShape(50)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("!", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        if (placed.showOriginalFallback && showFallbackTranslation) {
                            AlertDialog(
                                onDismissRequest = { showFallbackTranslation = false },
                                title = { Text("Yerleşime sığmayan çeviri") },
                                text = {
                                    Text(
                                        text = block.translatedText,
                                        modifier = Modifier
                                            .heightIn(max = 280.dp)
                                            .verticalScroll(rememberScrollState())
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = { showFallbackTranslation = false }) {
                                        Text("Kapat")
                                    }
                                }
                            )
                        }
                        }
                    }
                }

                // ==================== DEBUG MOD GÖRSELLEŞTİRME ====================
                if (isDebugMode) {
                    val detectedBubblesMap by viewModel.detectedBubblesMap.collectAsState()
                    val detectedBubbles: List<BubbleBox> = detectedBubblesMap[pageIndex] ?: emptyList()

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        val scale = renderedBitmap.width * widthScale
                        val strokePx = with(density) { 1.5.dp.toPx() }
                        val cornerPx = with(density) { 4.dp.toPx() }
                        val horizontalPadding = with(density) { 3.dp.toPx() }
                        val verticalPadding = with(density) { 1.dp.toPx() }
                        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.BLACK
                            textSize = with(density) { 8.sp.toPx() }
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }
                        val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(0, 229, 255)
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = strokePx
                        }
                        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(0, 229, 255)
                            style = android.graphics.Paint.Style.FILL
                        }

                        fun drawDebugBox(
                            rect: Rect,
                            scaleX: Float,
                            scaleY: Float,
                            color: Int,
                            label: String,
                            strokeWidth: Float,
                            radius: Float
                        ) {
                            if (rect.isEmpty) return
                            val left = rect.left * scaleX
                            val top = rect.top * scaleY
                            val right = rect.right * scaleX
                            val bottom = rect.bottom * scaleY
                            boxPaint.color = color
                            boxPaint.strokeWidth = strokeWidth
                            nativeCanvas.drawRoundRect(
                                RectF(left, top, right, bottom),
                                radius,
                                radius,
                                boxPaint
                            )

                            val metrics = textPaint.fontMetrics
                            val labelWidth = textPaint.measureText(label)
                            val labelHeight = metrics.descent - metrics.ascent
                            val labelLeft = left.coerceIn(
                                0f,
                                (size.width - labelWidth - horizontalPadding * 2).coerceAtLeast(0f)
                            )
                            val labelTop = (top - labelHeight - verticalPadding * 2)
                                .coerceIn(0f, (size.height - labelHeight - verticalPadding * 2).coerceAtLeast(0f))
                            labelPaint.color = color
                            nativeCanvas.drawRect(
                                labelLeft,
                                labelTop,
                                labelLeft + labelWidth + horizontalPadding * 2,
                                labelTop + labelHeight + verticalPadding * 2,
                                labelPaint
                            )
                            textPaint.color = android.graphics.Color.BLACK
                            nativeCanvas.drawText(
                                label,
                                labelLeft + horizontalPadding,
                                labelTop + verticalPadding - metrics.ascent,
                                textPaint
                            )
                        }

                        detectedBubbles.forEach { bubble ->
                            val normalizedRect = Rect(
                                (bubble.bounds.left * renderedBitmap.width).toInt(),
                                (bubble.bounds.top * renderedBitmap.height).toInt(),
                                (bubble.bounds.right * renderedBitmap.width).toInt(),
                                (bubble.bounds.bottom * renderedBitmap.height).toInt()
                            )
                            drawDebugBox(
                                normalizedRect,
                                widthScale,
                                widthScale,
                                android.graphics.Color.rgb(0, 229, 255),
                                "Aday ${bubble.label} #${bubble.id} (${(bubble.confidence * 100).toInt()}%)",
                                strokePx,
                                cornerPx
                            )
                        }

                        translatedBlocks.forEachIndexed { idx, block ->
                            drawDebugBox(
                                block.bubbleBox,
                                coordinateScaleX,
                                coordinateScaleY,
                                android.graphics.Color.rgb(224, 64, 251),
                                "Ham balon #${idx + 1} [${block.bubbleBox.width()}x${block.bubbleBox.height()}]",
                                with(density) { 2.dp.toPx() },
                                cornerPx
                            )
                            drawDebugBox(
                                block.layoutBox,
                                coordinateScaleX,
                                coordinateScaleY,
                                android.graphics.Color.rgb(0, 230, 118),
                                "Dizgi alanı",
                                with(density) { 1.dp.toPx() },
                                with(density) { 2.dp.toPx() }
                            )
                            drawDebugBox(
                                block.boundingBox,
                                coordinateScaleX,
                                coordinateScaleY,
                                android.graphics.Color.rgb(255, 214, 0),
                                "OCR #${idx + 1}",
                                with(density) { 1.dp.toPx() },
                                with(density) { 2.dp.toPx() }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF00E676))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Debug: ${translatedBlocks.size} Balon | Auto-Scale Aktif",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(550.dp),
                contentAlignment = Alignment.Center
            ) {
                if (bitmapLoadFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sayfa yüklenemedi.", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { bitmapLoadAttempt++ }) {
                            Text("Yeniden Dene")
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Sayfa yükleniyor...", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
