package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import com.example.i18n.Text
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
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.db.MangaEntity
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showSelectionMode) {
                        Text(
                            text = "Seçili manga sayısı: ${selectedMangaIds.size}",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "BabelRead Kütüphane",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onNavigateToHiddenMangas() }
                                .testTag("library_logo_title")
                        )
                    }
                },
                navigationIcon = {
                    if (showSelectionMode) {
                        IconButton(
                            onClick = {
                                showSelectionMode = false
                                selectedMangaIds = emptySet()
                            },
                            modifier = Modifier.testTag("clear_library_selection")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Seçimi Kapat", tint = Color.White)
                        }
                    }
                },
                actions = {
                    if (showSelectionMode) {
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
                            Icon(Icons.Default.SelectAll, contentDescription = "Tümünü Seç", tint = Color.White)
                        }
                    } else {
                        if (mangas.isNotEmpty()) {
                            IconButton(
                                onClick = { showSelectionMode = true },
                                modifier = Modifier.testTag("start_library_selection")
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = "Seç", tint = Color.White)
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
                                contentDescription = "Görünümü Değiştir",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.testTag("library_settings")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Ayarlar",
                                tint = Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161616),
                    titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            if (!showSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showImportSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Manga Ekle", fontWeight = FontWeight.Bold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("fab_import_manga")
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { viewModel.updateLibraryQuery(it) },
                placeholder = { Text("Manga ara...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateLibraryQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Temizle", tint = Color.Gray)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .testTag("library_search_input"),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (mangas.isEmpty()) {
                val allHidden = remember(allMangas, hiddenIds) {
                    allMangas.isNotEmpty() && allMangas.all { it.id in hiddenIds }
                }
                val showEmptyLibrary = allMangas.isEmpty() || allHidden
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(96.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (showEmptyLibrary) "Kütüphaneniz Boş" else "Sonuç Bulunamadı",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (showEmptyLibrary) {
                                "Henüz kütüphanenize manga eklemediniz. Keşfet ekranından serilerinizi bulup ekleyebilirsiniz."
                            } else {
                                "Aramanızla eşleşen manga bulunamadı."
                            },
                            fontSize = 14.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        if (showEmptyLibrary) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = onJumpToDiscover,
                                    modifier = Modifier
                                        .testTag("jump_to_discover_button")
                                        .height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(Icons.Default.Explore, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Keşfet", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { showImportSheet = true },
                                    modifier = Modifier
                                        .testTag("empty_library_import_btn")
                                        .height(48.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Cihazdan Ekle", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 145.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
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
    }

    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            containerColor = Color(0xFF1E1E1E),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Cihazınızdan Manga Yükleyin",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Telefonunuzdaki arşivleri, manga klasörlerini, toplu kütüphane klasörlerini veya galerinizdeki sayfaları içe aktarın.",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(20.dp))

                // Option 1: Archive
                Surface(
                    onClick = {
                        showImportSheet = false
                        archivePickerLauncher.launch(arrayOf("*/*"))
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF282828),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_archive_option")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Arşiv / Çizgi Roman Dosyası", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(".cbz, .zip, .cbr, .rar veya .pdf formatındaki tek dosya mangalar", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    onClick = {
                        showImportSheet = false
                        multipleArchivesPickerLauncher.launch(arrayOf("*/*"))
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF282828),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_multiple_archives_option")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFF29B6F6).copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.FileCopy, contentDescription = null, tint = Color(0xFF29B6F6))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Çoklu Arşiv / PDF", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Birden fazla dosyayı ayrı manga veya tek manganın bölümleri olarak ekleyin", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Option 2: Folder Tree
                Surface(
                    onClick = {
                        showImportSheet = false
                        folderPickerLauncher.launch(null)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF282828),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_folder_option")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFFFF9800).copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFFFF9800))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Manga Klasörü (Klasörden İçe Aktar)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Resimlerin veya alt bölümlerin bulunduğu klasörü doğrudan seçin", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    onClick = {
                        showImportSheet = false
                        bulkFolderPickerLauncher.launch(null)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF282828),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_bulk_folder_option")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFFAB47BC).copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.LibraryBooks, contentDescription = null, tint = Color(0xFFAB47BC))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Kütüphane Klasörü (Toplu)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Her doğrudan alt klasör ayrı manga olarak içe aktarılır", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Option 3: Multiple Images
                Surface(
                    onClick = {
                        showImportSheet = false
                        multipleImagesPickerLauncher.launch(arrayOf("image/*"))
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF282828),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_images_option")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFF00E676).copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFF00E676))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Resim Dosyaları (Çoklu Seçim)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Galeriden veya dosyalardan manga sayfalarını topluca seçin", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
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
                    tint = Color(0xFF29B6F6)
                )
            },
            title = {
                Text(
                    text = "Çoklu Arşiv İçe Aktarma",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Seçilen dosya sayısı: ${pendingArchiveUris.size}",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    Surface(
                        onClick = { importArchivesAsSingle = false },
                        shape = RoundedCornerShape(12.dp),
                        color = if (!importArchivesAsSingle) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            Color(0xFF282828)
                        },
                        border = if (!importArchivesAsSingle) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("archive_mode_separate")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !importArchivesAsSingle,
                                onClick = { importArchivesAsSingle = false }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Her Dosya Ayrı Manga", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Her dosya bağımsız bir manga olur", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                    Surface(
                        onClick = { importArchivesAsSingle = true },
                        shape = RoundedCornerShape(12.dp),
                        color = if (importArchivesAsSingle) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            Color(0xFF282828)
                        },
                        border = if (importArchivesAsSingle) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("archive_mode_single")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = importArchivesAsSingle,
                                onClick = { importArchivesAsSingle = true }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Tek Manga, Sıralı Bölümler", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Tüm dosyalar tek manganın bölümleri olur", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
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
                        color = Color.Gray,
                        fontSize = 11.sp
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
                    modifier = Modifier.testTag("confirm_multi_archive_import")
                ) {
                    Text("İçe Aktar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveImportDialog = false }) {
                    Text("İptal", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF202020),
            shape = RoundedCornerShape(16.dp)
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
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Seçili manga sayısı: ${orderedMergeMangas.size}",
                        color = Color.LightGray,
                        fontSize = 13.sp
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
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        orderedMergeMangas.forEachIndexed { index, manga ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = manga.title,
                                        color = Color.White,
                                        fontSize = 13.sp,
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
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("merge_move_up_${manga.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = "Yukarı Taşı",
                                            modifier = Modifier.size(18.dp)
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
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("merge_move_down_${manga.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            contentDescription = "Aşağı Taşı",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = "Kaynak manga kayıtları kütüphaneden kaldırılır. Bölüm dosyaları yeni manga silinene kadar korunur. Bu işlem geri alınamaz.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "İndirilmemiş çevrimiçi bölümler farklı kaynaklardan birleştirildiyse açılamayabilir.",
                        color = Color.Gray,
                        fontSize = 11.sp
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
                    modifier = Modifier.testTag("confirm_merge_mangas")
                ) {
                    Text("Birleştir", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }) {
                    Text("İptal", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF202020),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (isImporting) {
        Dialog(onDismissRequest = { /* Aktarma bitene kadar bekle */ }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E1E1E),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Manga İçe Aktarılıyor...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = importStatusText.ifBlank { "Sayfalar ayıklanıyor ve sıralanıyor..." },
                        color = Color.LightGray,
                        fontSize = 13.sp,
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
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Mangayı Kalıcı Olarak Sil?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "\"${targetManga.title}\" kütüphaneden ve cihazınıza kaydedilmiş tüm bölümlerinden/dosyalarından tamamen silinecektir. Arka planda hiçbir dosya veya kalıntı kalmayacaktır.\n\nBu işlem geri alınamaz. Onaylıyor musunuz?",
                    color = Color.LightGray,
                    fontSize = 14.sp
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    modifier = Modifier.testTag("confirm_delete_manga_btn")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Tamamen Sil", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { mangaToDelete = null },
                    modifier = Modifier.testTag("cancel_delete_manga_btn")
                ) {
                    Text("İptal", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF202020),
            shape = RoundedCornerShape(16.dp)
        )
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("manga_card_${manga.id}"),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.70f)
        ) {
            AsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f)),
                            startY = 220f
                        )
                    )
            )

            // Sol üst köşe: Dil etiketi
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopStart)
                    .background(
                        Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = manga.availableLanguages.ifBlank { manga.originLanguage },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .padding(10.dp)
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.78f), CircleShape)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = if (isSelected) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = if (isSelected) "Seçili" else "Seçili değil",
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .align(Alignment.TopEnd)
                ) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                            .testTag("manga_menu_${manga.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Seçenekler",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(Color(0xFF262626))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Kalıcı Olarak Sil",
                                    color = Color(0xFFFF5252),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(20.dp)
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
                    .padding(10.dp)
            ) {
                Text(
                    text = manga.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Rating",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (manga.rating > 0.0) manga.rating.toString() else "—",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = manga.sourceName,
                        fontSize = 11.sp,
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("manga_list_card_${manga.id}"),
        shape = RoundedCornerShape(8.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(85.dp)
                    .fillMaxHeight()
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(12.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = manga.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Yazar: ${manga.author}",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = manga.availableLanguages.ifBlank { manga.originLanguage },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = manga.sourceName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = if (isSelected) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = if (isSelected) "Seçili" else "Seçili değil",
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                ) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("list_menu_${manga.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Seçenekler",
                            tint = Color.LightGray
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(Color(0xFF262626))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Kalıcı Olarak Sil",
                                    color = Color(0xFFFF5252),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(20.dp)
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
