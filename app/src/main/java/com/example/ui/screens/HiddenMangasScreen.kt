package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.db.MangaEntity
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

@Composable
fun HiddenMangasScreen(
    viewModel: MangaViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String, String) -> Unit
) {
    val allMangas by viewModel.allFavoriteMangas.collectAsState()
    val hiddenIds by viewModel.hiddenMangaIds.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    val hiddenMangas = remember(allMangas, hiddenIds) { allMangas.filter { it.id in hiddenIds } }
    val selectableMangas = remember(allMangas, hiddenIds) { allMangas.filter { it.id !in hiddenIds } }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = "Gizli Mangalar",
                titleContent = {
                    Text(
                        text = "Gizli Mangalar",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("hidden_mangas_back")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.testTag("hidden_mangas_menu")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menü")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Gizlenecek mangaları seç",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(AppSizes.icon)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showPicker = true
                                },
                                modifier = Modifier.testTag("menu_select_hidden_mangas")
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = AppSpacing.lg)
        ) {
            if (hiddenMangas.isEmpty()) {
                StateView(
                    title = "Gizli manga yok",
                    message = "Sağ üstteki ⋮ menüsünden gizlenecek mangaları seçebilirsiniz. Gizlenen mangalar kütüphanede görünmez.",
                    icon = Icons.Default.VisibilityOff,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                SectionHeader(title = "Gizli Mangalar (${hiddenMangas.size})")
                Spacer(modifier = Modifier.height(AppSpacing.md))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    contentPadding = PaddingValues(bottom = AppSpacing.lg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(hiddenMangas, key = { it.id }) { manga ->
                        HiddenMangaCard(
                            manga = manga,
                            onOpen = { onNavigateToDetail(manga.id, manga.sourceName) },
                            onUnhide = { viewModel.unhideManga(manga.id) }
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        HideMangaPickerDialog(
            mangas = selectableMangas,
            onDismiss = { showPicker = false },
            onConfirm = { selected ->
                viewModel.hideMangas(selected)
                showPicker = false
            }
        )
    }
}

@Composable
private fun HiddenMangaCard(
    manga: MangaEntity,
    onOpen: () -> Unit,
    onUnhide: () -> Unit
) {
    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hidden_manga_card_${manga.id}"),
        onClick = onOpen,
        contentPadding = PaddingValues(AppSpacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MangaCover(
                imageUrl = manga.coverUrl,
                title = manga.title,
                modifier = Modifier
                    .width(56.dp)
                    .height(78.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Text(
                    text = manga.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(
                onClick = onUnhide,
                modifier = Modifier
                    .heightIn(min = AppSizes.minTouchTarget)
                    .testTag("unhide_${manga.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AppSizes.iconSmall)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(
                    text = "Göster",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HideMangaPickerDialog(
    mangas: List<MangaEntity>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val selected = remember { mutableStateListOf<String>() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.lg)
            ) {
                Text(
                    text = "Gizlenecek Mangaları Seç",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = "Kütüphanede görünmesini istemediğiniz mangaları işaretleyin. Birden fazla manga seçebilirsiniz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.md))

                if (mangas.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Gizlenebilecek manga yok",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        items(mangas, key = { it.id }) { manga ->
                            val checked = selected.contains(manga.id)
                            val toggleSelection: () -> Unit = {
                                if (checked) selected.remove(manga.id) else selected.add(manga.id)
                            }
                            SurfaceCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pick_manga_${manga.id}"),
                                onClick = toggleSelection,
                                color = if (checked) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (checked) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (checked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                ),
                                contentPadding = PaddingValues(
                                    horizontal = AppSpacing.sm,
                                    vertical = AppSpacing.xs
                                )
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { toggleSelection() },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = MaterialTheme.colorScheme.primary,
                                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                    MangaCover(
                                        imageUrl = manga.coverUrl,
                                        title = manga.title,
                                        modifier = Modifier
                                            .width(36.dp)
                                            .height(50.dp)
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = manga.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(AppSpacing.xxs))
                                        Text(
                                            text = manga.sourceName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = AppSizes.minTouchTarget)
                            .testTag("hide_picker_cancel")
                    ) {
                        Text(
                            text = "Vazgeç",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Button(
                        onClick = { onConfirm(selected.toSet()) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier
                            .heightIn(min = AppSizes.minTouchTarget)
                            .testTag("hide_picker_confirm")
                    ) {
                        Text(
                            text = if (selected.isEmpty()) "Gizle" else "Gizle (${selected.size})",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
