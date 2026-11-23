package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.DownloadQueueItemEntity
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
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    viewModel: MangaViewModel,
    onNavigateToDetail: (String, String) -> Unit
) {
    val downloadQueue by viewModel.downloadQueue.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val cbzPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importLocalArchive(it) { success, msg ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    val imgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importLocalImages(uris) { success, msg ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = "İndirilenler & Kuyruk",
                actions = {
                    IconButton(
                        onClick = {
                            imgPickerLauncher.launch(arrayOf("image/*"))
                        },
                        modifier = Modifier.testTag("import_img_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Resimler Yükle"
                        )
                    }
                    Button(
                        onClick = {
                            cbzPickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier
                            .padding(end = AppSpacing.sm)
                            .heightIn(min = AppSizes.minTouchTarget)
                            .testTag("import_cbz_btn")
                    ) {
                        Icon(
                            Icons.Default.FileOpen,
                            contentDescription = null,
                            modifier = Modifier.size(AppSizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text(
                            text = "CBZ/ZIP/RAR Yükle",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(AppSpacing.lg)
        ) {
            SectionHeader(title = "Aktif İndirme Kuyruğu (${downloadQueue.size} Bölüm)")
            Spacer(modifier = Modifier.height(AppSpacing.md))

            if (downloadQueue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    StateView(
                        title = "Aktif İndirme Yok",
                        message = "Detay ekranından istediğiniz bölümleri çevrimdışı okumak için indirebilirsiniz.",
                        icon = Icons.Default.DownloadForOffline
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    contentPadding = PaddingValues(bottom = AppSpacing.lg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(downloadQueue, key = { it.chapterId }) { item ->
                        DownloadQueueRow(
                            item = item,
                            onCancelClick = { viewModel.cancelDownload(item.chapterId) },
                            onRetryClick = { viewModel.retryDownload(item.chapterId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadQueueRow(
    item: DownloadQueueItemEntity,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit
) {
    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_row_${item.chapterId}"),
        contentPadding = PaddingValues(AppSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MangaCover(
                imageUrl = item.mangaCover,
                title = item.mangaTitle,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.chapterTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.mangaTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(MaterialTheme.shapes.extraSmall),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.md))
                    Text(
                        text = when (item.status) {
                            "İndiriliyor" -> "${(item.progress * 100).toInt()}%"
                            "CFBekliyor" -> "Cloudflare bekliyor"
                            else -> item.status
                        },
                        color = when (item.status) {
                            "Başarısız" -> MaterialTheme.colorScheme.error
                            "CFBekliyor" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            if (item.status == "Başarısız" || item.status == "CFBekliyor") {
                TextButton(
                    onClick = onRetryClick,
                    modifier = Modifier
                        .heightIn(min = AppSizes.minTouchTarget)
                        .testTag("retry_download_${item.chapterId}")
                ) {
                    Text("Tekrar Dene", style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(
                onClick = onCancelClick,
                modifier = Modifier
                    .size(AppSizes.minTouchTarget)
                    .testTag("cancel_download_${item.chapterId}")
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "İptal Et",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
