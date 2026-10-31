package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.components.StateView
import com.example.ui.components.SurfaceCard
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MangaViewModel,
    onNavigateToDetail: (String, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHiddenMangas: () -> Unit,
    onJumpToDiscover: () -> Unit
) {
    val mangas by viewModel.favoriteMangas.collectAsState()
    val allMangas by viewModel.allFavoriteMangas.collectAsState()
    val hiddenIds by viewModel.hiddenMangaIds.collectAsState()
    val searchQuery by viewModel.librarySearchQuery.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importStatusText by viewModel.importStatusText.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showImportSheet by remember { mutableStateOf(false) }
    var mangaToDelete by remember { mutableStateOf<MangaEntity?>(null) }
    var showSelectionMode by remember { mutableStateOf(false) }
    var selectedMangaIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var mergeTargetTitle by remember { mutableStateOf("") }
    var orderedMergeMangas by remember { mutableStateOf<List<MangaEntity>>(emptyList()) }
    var showArchiveImportDialog by remember { mutableStateOf(false) }
    var pendingArchiveUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var importArchivesAsSingle by remember { mutableStateOf(false) }
    var combinedArchiveTitle by remember { mutableStateOf("") }

    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let {
            viewModel.importLocalArchive(it) { success, msg ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        uri?.let {
            viewModel.importLocalFolder(it) { success, msg ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    val multipleImagesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importLocalImages(uris) { success, msg ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    val multipleArchivesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) {
            pendingArchiveUris = uris
            importArchivesAsSingle = false
            combinedArchiveTitle = ""
            showArchiveImportDialog = true
        }
    }

    val bulkFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        uri?.let {
            viewModel.importLocalFolderBulk(it) { success, msg ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    LaunchedEffect(mangas) {
        val availableIds = mangas.mapTo(HashSet()) { it.id }
        if (selectedMangaIds.any { it !in availableIds }) {
            selectedMangaIds = selectedMangaIds.intersect(availableIds)
        }
    }

    val toggleSelection: (MangaEntity) -> Unit = { manga ->
        selectedMangaIds = if (manga.id in selectedMangaIds) {
            selectedMangaIds - manga.id
        } else {
            selectedMangaIds + manga.id
        }
    }
    val beginSelection: (MangaEntity) -> Unit = { manga ->
        showSelectionMode = true
        selectedMangaIds = selectedMangaIds + manga.id
    }

    AppScaffold(
        topBar = {
            if (showSelectionMode) {
                AppTopBar(
                    title = "Seçili manga sayısı: ${selectedMangaIds.size}",
                    titleContent = {
                        Text(
                            text = "Seçili manga sayısı: ${selectedMangaIds.size}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                showSelectionMode = false
                                selectedMangaIds = emptySet()
                            },
                            modifier = Modifier.testTag("clear_library_selection")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Seçimi Kapat")
                        }
                    },
                    actions = {
                        if (selectedMangaIds.size >= 2) {
                            IconButton(
                                onClick = {
                                    val selected = mangas.filter { it.id in selectedMangaIds }
                                    if (selected.size >= 2) {
                                        orderedMergeMangas = selected
                                        mergeTargetTitle = selected.first().title
                                        showMergeDialog = true
                                    }
                                },
                                modifier = Modifier.testTag("merge_selected_mangas")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallMerge,
                                    contentDescription = "Mangaları Birleştir",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(
                            onClick = { selectedMangaIds = mangas.mapTo(HashSet()) { it.id } },
                            modifier = Modifier.testTag("select_all_mangas")
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Tümünü Seç")
                        }
                    }
                )
            } else {
                AppTopBar(
                    title = "BabelRead Kütüphane",
                    titleContent = {
                        Box(
                            modifier = Modifier
                                .heightIn(min = AppSizes.minTouchTarget)
                                .clickable { onNavigateToHiddenMangas() }
                                .testTag("library_logo_title"),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = "BabelRead Kütüphane",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        if (mangas.isNotEmpty()) {
                            IconButton(
                                onClick = { showSelectionMode = true },
                                modifier = Modifier.testTag("start_library_selection")
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = "Seç")
                            }
                        }
                        IconButton(
                            onClick = { showImportSheet = true },
                            modifier = Modifier.testTag("library_import_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Cihazdan Manga Yükle",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleLayout() },
                            modifier = Modifier.testTag("toggle_layout_button")
                        ) {
                            Icon(
                                imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                                contentDescription = "Görünümü Değiştir"
                            )
                        }
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.testTag("library_settings")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Ayarlar"
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!showSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showImportSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Manga Ekle", fontWeight = FontWeight.Bold) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("fab_import_manga")
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = AppSpacing.lg)
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.md))

            LibrarySearchField(
                value = searchQuery,
                onValueChange = { viewModel.updateLibraryQuery(it) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(AppSpacing.lg))

            if (mangas.isEmpty()) {
                val allHidden = remember(allMangas, hiddenIds) {
                    allMangas.isNotEmpty() && allMangas.all { it.id in hiddenIds }
                }
                val showEmptyLibrary = allMangas.isEmpty() || allHidden
                LibraryEmptyState(
                    showEmptyLibrary = showEmptyLibrary,
                    onJumpToDiscover = onJumpToDiscover,
                    onImportFromDevice = { showImportSheet = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(bottom = AppSpacing.xxxl + AppSpacing.xxl),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    items(mangas, key = { it.id }) { manga ->
                        LibraryGridCard(
                            manga = manga,
                            isSelectionMode = showSelectionMode,
                            isSelected = manga.id in selectedMangaIds,
                            onClick = {
                                if (showSelectionMode) {
                                    toggleSelection(manga)
                                } else {
                                    onNavigateToDetail(manga.id, manga.sourceName)
                                }
                            },
                            onLongClick = {
                                if (showSelectionMode) {
                                    toggleSelection(manga)
                                } else {
                                    beginSelection(manga)
                                }
                            },
                            onDeleteClick = {
                                mangaToDelete = manga
                            }
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(bottom = AppSpacing.xxxl + AppSpacing.xxl),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    items(mangas, key = { it.id }) { manga ->
                        LibraryListCard(
                            manga = manga,
                            isSelectionMode = showSelectionMode,
                            isSelected = manga.id in selectedMangaIds,
                            onClick = {
                                if (showSelectionMode) {
                                    toggleSelection(manga)
                                } else {
                                    onNavigateToDetail(manga.id, manga.sourceName)
                                }
                            },
                            onLongClick = {
                                if (showSelectionMode) {
                                    toggleSelection(manga)
                                } else {
                                    beginSelection(manga)
                                }
                            },
                            onDeleteClick = {
                                mangaToDelete = manga
                            }
                        )
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.xl)
                    .padding(bottom = AppSpacing.xxl)
            ) {
                Text(
                    text = "Cihazınızdan Manga Yükleyin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = "Telefonunuzdaki arşivleri, manga klasörlerini, toplu kütüphane klasörlerini veya galerinizdeki sayfaları içe aktarın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.xl))

                ImportOption(
                    title = "Arşiv / Çizgi Roman Dosyası",
                    description = ".cbz, .zip, .cbr, .rar veya .pdf formatındaki tek dosya mangalar",
                    icon = Icons.Default.FolderZip,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        showImportSheet = false
                        archivePickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.testTag("import_archive_option")
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                ImportOption(
                    title = "Çoklu Arşiv / PDF",
                    description = "Birden fazla dosyayı ayrı manga veya tek manganın bölümleri olarak ekleyin",
                    icon = Icons.Default.FileCopy,
                    tint = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        showImportSheet = false
                        multipleArchivesPickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.testTag("import_multiple_archives_option")
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                ImportOption(
                    title = "Manga Klasörü (Klasörden İçe Aktar)",
                    description = "Resimlerin veya alt bölümlerin bulunduğu klasörü doğrudan seçin",
                    icon = Icons.Default.FolderOpen,
                    tint = MaterialTheme.colorScheme.tertiary,
                    onClick = {
                        showImportSheet = false
                        folderPickerLauncher.launch(null)
                    },
                    modifier = Modifier.testTag("import_folder_option")
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                ImportOption(
                    title = "Kütüphane Klasörü (CBZ)",
                    description = "Yalnızca CBZ dosyaları içe aktarılır; her doğrudan alt klasör ayrı manga olur",
                    icon = Icons.Default.LibraryBooks,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        showImportSheet = false
                        bulkFolderPickerLauncher.launch(null)
                    },
                    modifier = Modifier.testTag("import_bulk_folder_option")
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                ImportOption(
                    title = "Resim Dosyaları (Çoklu Seçim)",
                    description = "Galeriden veya dosyalardan manga sayfalarını topluca seçin",
                    icon = Icons.Default.PhotoLibrary,
                    tint = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        showImportSheet = false
                        multipleImagesPickerLauncher.launch(arrayOf("image/*"))
                    },
                    modifier = Modifier.testTag("import_images_option")
                )
            }
        }
    }

    if (showArchiveImportDialog && pendingArchiveUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showArchiveImportDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.FileCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
            title = {
                Text(
                    text = "Çoklu Arşiv İçe Aktarma",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Text(
                        text = "Seçilen dosya sayısı: ${pendingArchiveUris.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ArchiveModeOption(
                        selected = !importArchivesAsSingle,
                        title = "Her Dosya Ayrı Manga",
                        description = "Her dosya bağımsız bir manga olur",
                        onClick = { importArchivesAsSingle = false },
                        modifier = Modifier.testTag("archive_mode_separate")
                    )
                    ArchiveModeOption(
                        selected = importArchivesAsSingle,
                        title = "Tek Manga, Sıralı Bölümler",
                        description = "Tüm dosyalar tek manganın bölümleri olur",
                        onClick = { importArchivesAsSingle = true },
                        modifier = Modifier.testTag("archive_mode_single")
                    )
                    if (importArchivesAsSingle) {
                        OutlinedTextField(
                            value = combinedArchiveTitle,
                            onValueChange = { combinedArchiveTitle = it },
                            label = { Text("Manga Adı") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("combined_archive_title")
                        )
                    }
                    Text(
                        text = "Bölüm sırası dosya adına göre doğal olarak sıralanır.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uris = pendingArchiveUris
                        val single = importArchivesAsSingle
                        val title = combinedArchiveTitle
                        showArchiveImportDialog = false
                        pendingArchiveUris = emptyList()
                        viewModel.importLocalArchives(
                            uris = uris,
                            title = if (single) title else null,
                            asSingleManga = single
                        ) { _, msg ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = AppSizes.minTouchTarget)
                        .testTag("confirm_multi_archive_import")
                ) {
                    Text("İçe Aktar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showArchiveImportDialog = false },
                    modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                ) {
                    Text("İptal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    if (showMergeDialog && orderedMergeMangas.size >= 2) {
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.CallMerge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Mangaları Birleştir",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    Text(
                        text = "Seçili manga sayısı: ${orderedMergeMangas.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = mergeTargetTitle,
                        onValueChange = { mergeTargetTitle = it },
                        label = { Text("Birleştirilmiş Manga Adı") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("merge_target_title")
                    )
                    Text(
                        text = "Bölüm Sıralaması",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        orderedMergeMangas.forEachIndexed { index, manga ->
                            SurfaceCard(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                contentPadding = PaddingValues(
                                    horizontal = AppSpacing.sm,
                                    vertical = AppSpacing.xs
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        shape = CircleShape,
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                    Text(
                                        text = manga.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val mutable = orderedMergeMangas.toMutableList()
                                                val previous = mutable[index - 1]
                                                mutable[index - 1] = mutable[index]
                                                mutable[index] = previous
                                                orderedMergeMangas = mutable
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.testTag("merge_move_up_${manga.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = "Yukarı Taşı",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(AppSizes.iconSmall)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index < orderedMergeMangas.lastIndex) {
                                                val mutable = orderedMergeMangas.toMutableList()
                                                val next = mutable[index + 1]
                                                mutable[index + 1] = mutable[index]
                                                mutable[index] = next
                                                orderedMergeMangas = mutable
                                            }
                                        },
                                        enabled = index < orderedMergeMangas.lastIndex,
                                        modifier = Modifier.testTag("merge_move_down_${manga.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            contentDescription = "Aşağı Taşı",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(AppSizes.iconSmall)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = "Kaynak manga kayıtları kütüphaneden kaldırılır. Bölüm dosyaları yeni manga silinene kadar korunur. Bu işlem geri alınamaz.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "İndirilmemiş çevrimiçi bölümler farklı kaynaklardan birleştirildiyse açılamayabilir.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = orderedMergeMangas.map { it.id }
                        val title = mergeTargetTitle.trim().ifBlank { orderedMergeMangas.first().title }
                        showMergeDialog = false
                        viewModel.mergeMangas(ids, title) { success, msg ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                            if (success) {
                                showSelectionMode = false
                                selectedMangaIds = emptySet()
                            }
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = AppSizes.minTouchTarget)
                        .testTag("confirm_merge_mangas")
                ) {
                    Text("Birleştir", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showMergeDialog = false },
                    modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                ) {
                    Text("İptal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    if (isImporting) {
        Dialog(onDismissRequest = { /* Aktarma bitene kadar bekle */ }) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.lg)
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                    Text(
                        text = "Manga İçe Aktarılıyor...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        text = importStatusText.ifBlank { "Sayfalar ayıklanıyor ve sıralanıyor..." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }

    if (mangaToDelete != null) {
        val targetManga = mangaToDelete!!
        AlertDialog(
            onDismissRequest = { mangaToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Mangayı Kalıcı Olarak Sil?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "\"${targetManga.title}\" kütüphaneden ve cihazınıza kaydedilmiş tüm bölümlerinden/dosyalarından tamamen silinecektir. Arka planda hiçbir dosya veya kalıntı kalmayacaktır.\n\nBu işlem geri alınamaz. Onaylıyor musunuz?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val m = targetManga
                        mangaToDelete = null
                        viewModel.deleteMangaCompletely(m) { success, msg ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .heightIn(min = AppSizes.minTouchTarget)
                        .testTag("confirm_delete_manga_btn")
                ) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(AppSizes.iconSmall)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("Tamamen Sil", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { mangaToDelete = null },
                    modifier = Modifier
                        .heightIn(min = AppSizes.minTouchTarget)
                        .testTag("cancel_delete_manga_btn")
                ) {
                    Text("İptal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge
        )
    }
}

@Composable
private fun LibrarySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Surface(
        modifier = modifier.height(AppSizes.minTouchTarget),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (focused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = AppSpacing.lg, end = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppSizes.icon)
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "Manga ara...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .testTag("library_search_input"),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    interactionSource = interactionSource,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            innerTextField()
                        }
                    }
                )
            }
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(AppSizes.minTouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Temizle",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    showEmptyLibrary: Boolean,
    onJumpToDiscover: () -> Unit,
    onImportFromDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StateView(
                title = if (showEmptyLibrary) "Kütüphaneniz Boş" else "Sonuç Bulunamadı",
                message = if (showEmptyLibrary) {
                    "Henüz kütüphanenize manga eklemediniz. Keşfet ekranından serilerinizi bulup ekleyebilirsiniz."
                } else {
                    "Aramanızla eşleşen manga bulunamadı."
                },
                icon = Icons.Outlined.MenuBook,
                modifier = Modifier.fillMaxWidth()
            )
            if (showEmptyLibrary) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onJumpToDiscover,
                        modifier = Modifier
                            .heightIn(min = AppSizes.minTouchTarget)
                            .testTag("jump_to_discover_button")
                    ) {
                        Icon(
                            Icons.Default.Explore,
                            contentDescription = null,
                            modifier = Modifier.size(AppSizes.icon)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text("Keşfet", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onImportFromDevice,
                        modifier = Modifier
                            .heightIn(min = AppSizes.minTouchTarget)
                            .testTag("empty_library_import_btn")
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(AppSizes.icon)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text("Cihazdan Ekle", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportOption(
    title: String,
    description: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SurfaceCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(AppSpacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tint.copy(alpha = 0.16f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(AppSizes.iconLarge)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ArchiveModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SurfaceCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        contentPadding = PaddingValues(AppSpacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridCard(
    manga: MangaEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("manga_card_${manga.id}"),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(AppSizes.coverAspectRatio)
        ) {
            MangaCover(
                imageUrl = manga.coverUrl,
                title = manga.title,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)),
                            startY = 200f
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(AppSpacing.sm)
                    .background(
                        Color.Black.copy(alpha = 0.72f),
                        shape = MaterialTheme.shapes.extraSmall
                    )
                    .padding(
                        horizontal = AppSpacing.xs + AppSpacing.xxs,
                        vertical = AppSpacing.xxs
                    )
            ) {
                Text(
                    text = manga.availableLanguages.ifBlank { manga.originLanguage },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(AppSpacing.sm)
                        .background(Color.Black.copy(alpha = 0.72f), CircleShape)
                        .padding(AppSpacing.xxs)
                ) {
                    Icon(
                        imageVector = if (isSelected) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = if (isSelected) "Seçili" else "Seçili değil",
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(AppSizes.iconLarge)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(AppSpacing.xxs)
                ) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(AppSizes.minTouchTarget)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .testTag("manga_menu_${manga.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Seçenekler",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(AppSizes.icon)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Kalıcı Olarak Sil",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(AppSizes.icon)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteClick()
                            },
                            modifier = Modifier.testTag("menu_delete_${manga.id}")
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(AppSpacing.sm)
            ) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(AppSizes.iconSmall)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xxs))
                    Text(
                        text = if (manga.rating > 0.0) manga.rating.toString() else "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        text = manga.sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryListCard(
    manga: MangaEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("manga_list_card_${manga.id}"),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
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
                    .width(72.dp)
                    .height(100.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = "Yazar: ${manga.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                                shape = MaterialTheme.shapes.extraSmall
                            )
                            .padding(
                                horizontal = AppSpacing.xs + AppSpacing.xxs,
                                vertical = AppSpacing.xxs
                            )
                    ) {
                        Text(
                            text = manga.availableLanguages.ifBlank { manga.originLanguage },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text(
                        text = manga.sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .padding(end = AppSpacing.md)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .padding(AppSpacing.xs)
                ) {
                    Icon(
                        imageVector = if (isSelected) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = if (isSelected) "Seçili" else "Seçili değil",
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(AppSizes.iconLarge)
                    )
                }
            } else {
                Box(modifier = Modifier.padding(end = AppSpacing.xxs)) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("list_menu_${manga.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Seçenekler",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Kalıcı Olarak Sil",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(AppSizes.icon)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteClick()
                            },
                            modifier = Modifier.testTag("list_menu_delete_${manga.id}")
                        )
                    }
                }
            }
        }
    }
}
