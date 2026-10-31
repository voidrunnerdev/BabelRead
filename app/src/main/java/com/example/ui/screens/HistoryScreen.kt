package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.pref.ReadingHistoryEntry
import com.example.i18n.Text
import com.example.ui.components.AppScaffold
import com.example.ui.components.AppTopBar
import com.example.ui.components.MangaCover
import com.example.ui.components.StateView
import com.example.ui.components.SurfaceCard
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: MangaViewModel,
    onNavigateToDetail: (String, String) -> Unit
) {
    val history by viewModel.readingHistory.collectAsState()

    AppScaffold(
        topBar = {
            AppTopBar(
                title = "Okuma Geçmişi",
                actions = {
                    if (history.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.clearReadingHistory() },
                            modifier = Modifier
                                .heightIn(min = AppSizes.minTouchTarget)
                                .testTag("history_clear_all")
                        ) {
                            Text(
                                text = "Temizle",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (history.isEmpty()) {
            StateView(
                title = "Henüz okuma geçmişi yok.",
                message = "Okumaya başladığın bölümler burada görünecek.",
                icon = Icons.Outlined.History,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    horizontal = AppSpacing.lg,
                    vertical = AppSpacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                items(history, key = { it.mangaId }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onClick = { onNavigateToDetail(entry.mangaId, entry.sourceName) },
                        onDelete = { viewModel.removeHistoryEntry(entry.mangaId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: ReadingHistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_row_${entry.mangaId}"),
        onClick = onClick,
        contentPadding = PaddingValues(AppSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MangaCover(
                imageUrl = entry.coverUrl,
                title = entry.title,
                modifier = Modifier
                    .width(48.dp)
                    .height(68.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Text(
                    text = entry.chapterTitle.replace(Regex("^\\[[A-Z-]+\\]\\s*"), ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                val pageText = if (entry.totalPages > 1) {
                    "Sayfa ${(entry.pageIndex + 1).coerceAtMost(entry.totalPages)}/${entry.totalPages} · "
                } else {
                    ""
                }
                Text(
                    text = pageText + historyRelativeTime(entry.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(AppSizes.minTouchTarget)
                    .testTag("history_delete_${entry.mangaId}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Geçmişten kaldır",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppSizes.icon)
                )
            }
        }
    }
}

private fun historyRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "az önce"
        diff < 3_600_000L -> "${diff / 60_000L} dk önce"
        diff < 86_400_000L -> "${diff / 3_600_000L} saat önce"
        diff < 2 * 86_400_000L -> "Dün"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} gün önce"
        else -> SimpleDateFormat("d MMM yyyy", Locale("tr", "TR")).format(Date(timestamp))
    }
}
