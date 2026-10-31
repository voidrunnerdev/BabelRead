package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import com.example.data.db.ChapterEntity
import com.example.data.db.DownloadQueueItemEntity
import com.example.data.db.MangaEntity
import com.example.extensions.languageLabel
import com.example.i18n.Text
import com.example.ui.components.AppScaffold
import com.example.ui.components.AppTopBar
import com.example.ui.components.MangaCover
import com.example.ui.components.SectionHeader
import com.example.ui.components.StateView
import com.example.ui.components.SurfaceCard
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel
import kotlin.math.roundToInt

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

    AppScaffold(
        topBar = {
            AppTopBar(
                title = manga?.title ?: "Manga Detay",
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("detail_back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    StateView(
                        title = "Yükleniyor…",
                        loading = true
                    )
                }
            }

            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    StateView(
                        title = error ?: "Bilinmeyen bir hata oluştu.",
                        icon = Icons.Default.ErrorOutline,
                        actionLabel = "Yeniden Dene",
                        onAction = {
                            viewModel.loadMangaDetail(mangaId, sourceName)
                        }
                    )
                }
            }

            manga != null -> {
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
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = {
                            Text(
                                act.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                "Seçtiğiniz bölüm için yapmak istediğiniz eylemi belirtin:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
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
                        }
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    item {
                        DetailHero(manga = m)
                    }

                    item {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = AppSpacing.lg,
                                vertical = AppSpacing.lg
                            ),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                            ) {
                                Button(
                                    onClick = { viewModel.toggleFavorite(m.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = AppSizes.minTouchTarget)
                                        .testTag("add_to_library_btn"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isFavorite) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        contentColor = if (isFavorite) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onPrimary
                                        }
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                    Text(
                                        text = if (isFavorite) "Kütüphanede" else "Kütüphaneye Ekle",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(
                                    onClick = {
                                        val targetCh = filteredChapters.lastOrNull { !it.isRead }
                                            ?: filteredChapters.lastOrNull()
                                        targetCh?.let { onNavigateToReader(it.id) }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = AppSizes.minTouchTarget)
                                        .testTag("start_reading_btn"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                                    Text(
                                        text = "Okumaya Başla",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.queueAllChapters(
                                        m.id,
                                        selectedChapterLang.takeUnless { it == "Hepsi" }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = AppSizes.minTouchTarget)
                                    .testTag("download_all_btn"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                if (aggregateDownloadProgress != null) {
                                    DownloadProgressIcon(
                                        progress = aggregateDownloadProgress,
                                        modifier = Modifier.size(AppSizes.iconLarge)
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                    Text(
                                        text = "İndiriliyor %${(aggregateDownloadProgress.coerceIn(0f, 1f) * 100).roundToInt()}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                    Text(
                                        text = "Tümünü İndir",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = AppSpacing.lg)
                        ) {
                            SectionHeader(title = "Özet Açıklama")
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            SurfaceCard {
                                Text(
                                    text = m.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Column(
                            modifier = Modifier.padding(
                                start = AppSpacing.lg,
                                end = AppSpacing.lg,
                                top = AppSpacing.xl,
                                bottom = AppSpacing.md
                            )
                        ) {
                            SectionHeader(
                                title = "Bölümler (${filteredChapters.size}/${chapters.size})",
                                subtitle = if (availableChapterLangs.size > 1) "Dile Göre Filtrele" else null
                            )
                            if (availableChapterLangs.size > 1) {
                                Spacer(modifier = Modifier.height(AppSpacing.sm))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                                ) {
                                    availableChapterLangs.forEach { l ->
                                        val isSel = selectedChapterLang == l
                                        FilterChip(
                                            selected = isSel,
                                            onClick = { selectedChapterLang = l },
                                            label = {
                                                Text(
                                                    text = l,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
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

                    item {
                        Spacer(modifier = Modifier.height(AppSpacing.xl))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHero(manga: MangaEntity) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.sm)
            .height(300.dp)
            .clip(MaterialTheme.shapes.medium)
    ) {
        MangaCover(
            imageUrl = manga.coverUrl,
            title = manga.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.scrim,
                            Color.Transparent,
                            MaterialTheme.colorScheme.background
                        ),
                        startY = 0f,
                        endY = 900f
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "Diller: ${manga.availableLanguages.ifBlank { manga.originLanguage }}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs)
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = manga.status,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs)
                    )
                }
            }
            Text(
                text = manga.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Orijinal: ${manga.originLanguage}  |  Yazar: ${manga.author}  |  Kaynak: ${manga.sourceName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    val arcColor = MaterialTheme.colorScheme.primary
    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
    val clickableModifier = if (onClick != null) {
        Modifier
            .sizeIn(minWidth = AppSizes.minTouchTarget, minHeight = AppSizes.minTouchTarget)
            .clickable(onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier.then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            if (fraction <= 0f) {
                drawCircle(color = arcColor.copy(alpha = 0.24f))
            } else {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = true
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.Download,
            contentDescription = "İndiriliyor",
            tint = iconColor,
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
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("chapter_row_${chapter.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (chapter.isRead) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chapter.isRead) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Okundu",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(AppSizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                    }
                    val langLabel = languageLabel(chapter.language).ifBlank { "Türkçe" }
                    val badgeColor = when (langLabel) {
                        "Türkçe" -> MaterialTheme.colorScheme.error
                        "İngilizce" -> MaterialTheme.colorScheme.primary
                        "Fransızca" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.secondary
                    }
                    Surface(
                        color = badgeColor.copy(alpha = 0.16f),
                        contentColor = badgeColor,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = langLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs)
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        text = chapter.title.replace(Regex("^\\[[A-Z-]+\\]\\s*"), ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (chapter.isRead) FontWeight.Normal else FontWeight.Bold,
                        color = if (chapter.isRead) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Text(
                    text = "Yayınlanma: ${chapter.uploadDate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                modifier = Modifier.size(AppSizes.iconLarge)
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
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(AppSizes.iconLarge)
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(AppSizes.iconLarge)
                            )
                        }
                    }
                }
            }
        }
    }
}
