package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.db.ChapterEntity
import com.example.data.db.DownloadQueueItemEntity
import com.example.extensions.languageLabel
import com.example.viewmodel.MangaViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: MangaViewModel,
    mangaId: String,
    sourceName: String,
    onNavigateBack: () -> Unit,
    onNavigateToReader: (String) -> Unit
) {
    LaunchedEffect(mangaId, sourceName) {
        viewModel.loadMangaDetail(mangaId, sourceName)
    }

    val loading by viewModel.detailLoading.collectAsState()
    val manga by viewModel.detailManga.collectAsState()
    val chapters by viewModel.detailChapters.collectAsState()
    val error by viewModel.detailError.collectAsState()
    val isFavorite by viewModel.isFavoriteManga.collectAsState()
    val downloadQueue by viewModel.downloadQueue.collectAsState()
    val queueByChapterId = remember(downloadQueue) { downloadQueue.associateBy { it.chapterId } }

    var activeContextChapter by remember { mutableStateOf<ChapterEntity?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }

    var selectedChapterLang by remember(mangaId) { mutableStateOf("Hepsi") }
    val availableChapterLangs = remember(chapters) {
        val list = chapters.map { languageLabel(it.language) }.distinct().filter { it.isNotBlank() }
        if (list.size > 1) listOf("Hepsi") + list else list
    }

    val filteredChapters = remember(chapters, selectedChapterLang) {
        if (selectedChapterLang == "Hepsi") chapters
        else chapters.filter { languageLabel(it.language) == selectedChapterLang }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = manga?.title ?: "Manga Detay",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("detail_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161616),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (error != null) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color.Red, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(error ?: "Bilinmeyen bir hata oluştu.", color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadMangaDetail(mangaId, sourceName) }) {
                        Text("Yeniden Dene")
                    }
                }
            }
        } else if (manga != null) {
            val m = manga!!

            val mangaQueueItems = downloadQueue.filter { it.mangaId == m.id }
            val activeDownloadItems = mangaQueueItems.filter {
                it.status == "Bekliyor" || it.status == "İndiriliyor" || it.status == "CFBekliyor"
            }
            val aggregateDownloadProgress = if (activeDownloadItems.isEmpty()) null else {
                activeDownloadItems.map { if (it.status == "İndiriliyor") it.progress else 0f }.average().toFloat()
            }

            if (showContextMenu && activeContextChapter != null) {
                val act = activeContextChapter!!
                AlertDialog(
                    onDismissRequest = { showContextMenu = false },
                    title = { Text(act.title, color = Color.White) },
                    text = { Text("Seçtiğiniz bölüm için yapmak istediğiniz eylemi belirtin:") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.markChaptersReadFromHere(m.id, act.chapterNumber)
                            showContextMenu = false
                        }) {
                            Text("Buradan İtibaren Okundu İşaretle")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.queueAllChaptersFromHere(m.id, act.chapterNumber)
                            showContextMenu = false
                        }) {
                            Text("Buradan İtibaren İndir")
                        }
                    },
                    containerColor = Color(0xFF1E1E1E),
                    textContentColor = Color.White,
                    titleContentColor = Color.White
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    ) {
                        AsyncImage(
                            model = m.coverUrl,
                            contentDescription = m.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent, Color(0xFF121212)),
                                        startY = 0f,
                                        endY = 900f
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Diller: ${m.availableLanguages.ifBlank { m.originLanguage }}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            Color.DarkGray,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(m.status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(m.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Orijinal: ${m.originLanguage}  |  Yazar: ${m.author}  |  Kaynak: ${m.sourceName}", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                }

                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.toggleFavorite(m.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("add_to_library_btn"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isFavorite) Color.DarkGray else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isFavorite) Color.Red else Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isFavorite) "Kütüphanede" else "Kütüphaneye Ekle", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    val targetCh = filteredChapters.lastOrNull { !it.isRead } ?: filteredChapters.lastOrNull()
                                    targetCh?.let { onNavigateToReader(it.id) }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("start_reading_btn"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Okumaya Başla", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                viewModel.queueAllChapters(
                                    m.id,
                                    selectedChapterLang.takeUnless { it == "Hepsi" }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("download_all_btn"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (aggregateDownloadProgress != null) {
                                DownloadProgressIcon(
                                    progress = aggregateDownloadProgress,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "İndiriliyor %${(aggregateDownloadProgress.coerceIn(0f, 1f) * 100).roundToInt()}",
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Tümünü İndir", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Özet Açıklama", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(m.description, color = Color.Gray, fontSize = 13.sp, lineHeight = 18.sp)

                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Bölümler (${filteredChapters.size}/${chapters.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (availableChapterLangs.size > 1) {
                                Text("Dile Göre Filtrele", color = Color.Gray, fontSize = 12.sp)
                            }
                        }

                        if (availableChapterLangs.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                availableChapterLangs.forEach { l ->
                                    val isSel = selectedChapterLang == l
                                    FilterChip(
                                        selected = isSel,
                                        onClick = { selectedChapterLang = l },
                                        label = { Text(l, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = Color.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                items(filteredChapters, key = { it.id }) { chapter ->
                    ChapterRow(
                        chapter = chapter,
                        queueItem = queueByChapterId[chapter.id],
                        onClick = { onNavigateToReader(chapter.id) },
                        onLongClick = {
                            activeContextChapter = chapter
                            showContextMenu = true
                        },
                        onDownloadClick = {
                            viewModel.queueChapterDownload(m.id, chapter.id)
                        },
                        onRetryClick = {
                            viewModel.retryDownload(chapter.id)
                        },
                        onCancelClick = {
                            viewModel.cancelDownload(chapter.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressIcon(
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .size(40.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            if (fraction <= 0f) {
                drawCircle(color = Color(0xFFE0E0E0).copy(alpha = 0.28f))
            } else {
                drawArc(
                    color = Color(0xFFE0E0E0).copy(alpha = 0.92f),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = true
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.Download,
            contentDescription = "İndiriliyor",
            tint = Color(0xFF9E9E9E),
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChapterRow(
    chapter: ChapterEntity,
    queueItem: DownloadQueueItemEntity? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRetryClick: () -> Unit = {},
    onCancelClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("chapter_row_${chapter.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (chapter.isRead) Color(0xFF161616) else Color(0xFF1E1E1E)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chapter.isRead) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Okundu",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    val langLabel = languageLabel(chapter.language).ifBlank { "Türkçe" }
                    Surface(
                        color = when (langLabel) {
                            "Türkçe" -> Color(0xFFE53935).copy(alpha = 0.2f)
                            "İngilizce" -> Color(0xFF1E88E5).copy(alpha = 0.2f)
                            "Fransızca" -> Color(0xFF8E24AA).copy(alpha = 0.2f)
                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = langLabel,
                            color = when (langLabel) {
                                "Türkçe" -> Color(0xFFFF5252)
                                "İngilizce" -> Color(0xFF64B5F6)
                                "Fransızca" -> Color(0xFFBA68C8)
                                else -> MaterialTheme.colorScheme.primary
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = chapter.title.replace(Regex("^\\[[A-Z-]+\\]\\s*"), ""),
                        fontWeight = if (chapter.isRead) FontWeight.Normal else FontWeight.Bold,
                        color = if (chapter.isRead) Color.Gray else Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Yayınlanma: ${chapter.uploadDate}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    chapter.isDownloaded -> {
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = "İndirildi",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    queueItem != null && queueItem.status == "İndiriliyor" -> {
                        DownloadProgressIcon(
                            progress = queueItem.progress,
                            modifier = Modifier.testTag("download_progress_${chapter.id}"),
                            onClick = onCancelClick
                        )
                    }
                    queueItem != null && queueItem.status == "Başarısız" -> {
                        IconButton(
                            onClick = onRetryClick,
                            modifier = Modifier.testTag("download_retry_${chapter.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "İndirme başarısız, tekrar dene",
                                tint = Color(0xFFEF5350),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    queueItem != null -> {
                        DownloadProgressIcon(
                            progress = 0f,
                            modifier = Modifier.testTag("download_queued_${chapter.id}"),
                            onClick = onCancelClick
                        )
                    }
                    else -> {
                        IconButton(
                            onClick = onDownloadClick,
                            modifier = Modifier.testTag("download_chapter_${chapter.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = "İndir",
                                tint = Color.LightGray,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
