package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.example.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import coil.compose.AsyncImage
import com.example.data.parser.MangaModel
import com.example.data.parser.MangaSourceRegistry
import com.example.data.web.WebSite
import com.example.ui.components.AddWebSiteDialog
import com.example.ui.components.ManageWebSitesDialog
import com.example.viewmodel.MangaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: MangaViewModel,
    onNavigateToDetail: (String, String) -> Unit,
    onNavigateToWebBrowser: (String) -> Unit,
    presetSource: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onOpenExtensions: (() -> Unit)? = null
) {
    val selectedSource by viewModel.selectedSource.collectAsState()
    val discoverTab by viewModel.discoverTab.collectAsState()
    val discoverQuery by viewModel.discoverQuery.collectAsState()
    val filterGenre by viewModel.filterGenre.collectAsState()
    val filterStatus by viewModel.filterStatus.collectAsState()
    val filterLanguage by viewModel.filterLanguage.collectAsState()
    val loading by viewModel.discoverLoading.collectAsState()
    val items by viewModel.discoverItems.collectAsState()
    val sourceEndReached by viewModel.isEndReached.collectAsState()
    val error by viewModel.discoverError.collectAsState()
    val webSites by viewModel.webSites.collectAsState()
    val webCatalogScanning by viewModel.webCatalogScanning.collectAsState()
    val webCatalogStatus by viewModel.webCatalogStatus.collectAsState()
    val webSitePlan by viewModel.webSitePlan.collectAsState()

    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var showManageSitesDialog by remember { mutableStateOf(false) }

    val sourcesList = MangaSourceRegistry.sources.map { it.name }
    val tabsList = listOf("Popüler", "En Beğenilen", "Yeni", "Güncellenen")
    val isWebSource = viewModel.isWebSource(selectedSource)

    LaunchedEffect(webSites, selectedSource, sourcesList) {
        val missingWebSource = viewModel.isWebSource(selectedSource) &&
            webSites.none { "Web: ${it.name}" == selectedSource }
        val missingSource = !viewModel.isWebSource(selectedSource) &&
            sourcesList.none { it == selectedSource }
        if (missingWebSource || missingSource) {
            viewModel.selectedSource.value = sourcesList.firstOrNull() ?: "Cihaz / Yerel Mangalar"
        }
    }

    LaunchedEffect(presetSource) {
        if (!presetSource.isNullOrBlank() && viewModel.selectedSource.value != presetSource) {
            viewModel.selectedSource.value = presetSource
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Geri",
                                tint = Color.White
                            )
                        }
                    }
                },
                title = {
                    Box(modifier = Modifier.wrapContentSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { sourceMenuExpanded = true }
                                .padding(8.dp)
                                .testTag("source_selector_trigger")
                        ) {
                            Text(
                                text = selectedSource,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 18.sp
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Kaynak Değiştir",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = sourceMenuExpanded,
                            onDismissRequest = { sourceMenuExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E1E1E))
                        ) {
                            sourcesList.forEach { src ->
                                DropdownMenuItem(
                                    text = { Text(src, color = Color.White) },
                                    onClick = {
                                        viewModel.selectedSource.value = src
                                        sourceMenuExpanded = false
                                    }
                                )
                            }
                            if (webSites.isNotEmpty()) {
                                HorizontalDivider(color = Color(0xFF2C2C2C))
                                webSites.forEach { site ->
                                    val sourceName = "Web: ${site.name}"
                                    DropdownMenuItem(
                                        text = { Text(sourceName, color = Color.White) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Language,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        onClick = {
                                            viewModel.selectedSource.value = sourceName
                                            sourceMenuExpanded = false
                                        }
                                    )
                                }
                            }
                            HorizontalDivider(color = Color(0xFF2C2C2C))
                            DropdownMenuItem(
                                text = { Text("Yeni Web Sitesi Ekle…", color = MaterialTheme.colorScheme.primary) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = {
                                    sourceMenuExpanded = false
                                    showAddSiteDialog = true
                                }
                            )
                            if (webSites.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Web Sitelerini Yönet", color = Color.White) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.Gray)
                                    },
                                    onClick = {
                                        sourceMenuExpanded = false
                                        showManageSitesDialog = true
                                    }
                                )
                            }
                            HorizontalDivider(color = Color(0xFF2C2C2C))
                            DropdownMenuItem(
                                text = { Text("Eklenti Mağazası (Mihon)", color = MaterialTheme.colorScheme.primary) },
                                leadingIcon = {
                                    Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                onClick = {
                                    sourceMenuExpanded = false
                                    onOpenExtensions?.invoke()
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { filterMenuExpanded = !filterMenuExpanded },
                        modifier = Modifier.testTag("filter_menu_trigger")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Filtreler",
                            tint = if (filterGenre != null || filterStatus != null || filterLanguage != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White
                            }
                        )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TextField(
                value = discoverQuery,
                onValueChange = { viewModel.discoverQuery.value = it },
                placeholder = { Text("Tüm kaynaklarda ara...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                trailingIcon = {
                    if (discoverQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.discoverQuery.value = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Temizle", tint = Color.Gray)
                        }
                    }
                },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .testTag("discover_search_input"),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (!isWebSource) {
                ScrollableTabRow(
                    selectedTabIndex = tabsList.indexOf(discoverTab).coerceAtLeast(0),
                    containerColor = Color(0xFF121212),
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = 16.dp,
                    divider = {}
                ) {
                    tabsList.forEach { tab ->
                        Tab(
                            selected = discoverTab == tab,
                            onClick = { viewModel.discoverTab.value = tab },
                            text = {
                                Text(
                                    text = tab,
                                    fontWeight = if (discoverTab == tab) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            unselectedContentColor = Color.Gray,
                            selectedContentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                val activeSite = webSites.firstOrNull { "Web: ${it.name}" == selectedSource }
                if (activeSite != null) {
                    WebSourcePanel(
                        site = activeSite,
                        plan = webSitePlan,
                        loading = webCatalogScanning || loading,
                        status = webCatalogStatus,
                        selectedSection = if (discoverTab == "Yeni") 1 else 0,
                        onSection = { tab ->
                            val vm = viewModel
                            if (vm.discoverTab.value != tab) {
                                vm.discoverTab.value = tab
                                if (vm.discoverQuery.value.isNotBlank()) {
                                    vm.discoverQuery.value = ""
                                }
                            } else if (vm.discoverQuery.value.isNotBlank()) {
                                vm.discoverQuery.value = ""
                            } else {
                                vm.refreshDiscover()
                            }
                        },
                        onCategory = { url -> viewModel.loadWebCategory(selectedSource, url) },
                        onSearch = { q -> viewModel.discoverQuery.value = q },
                        onRefresh = { viewModel.refreshDiscover() },
                        onOpenBrowser = { onNavigateToWebBrowser(activeSite.id) },
                        onScanUrl = { url -> viewModel.scanWebSiteCatalog(selectedSource, url) }
                    )
                }
            }

            if (filterMenuExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A))
                        .padding(16.dp)
                ) {
                    Text("Detaylı Filtreleme", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Text("Tür: ", color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        val genres = listOf(
                            "Hepsi", "Aksiyon", "Macera", "Komedi", "Dram", "Fantezi", "Isekai", "Romantik",
                            "Bilim Kurgu", "Gizem", "Korku", "Doğaüstü", "Webtoon", "Psikolojik", "Gerilim",
                            "Dövüş Sanatları", "Spor", "Tarihi", "Günlük Yaşam", "Okul", "Büyü", "Zombiler"
                        )
                        genres.forEach { g ->
                            val isSel = (g == "Hepsi" && filterGenre == null) || (g == filterGenre)
                            Button(
                                onClick = { viewModel.filterGenre.value = if (g == "Hepsi") null else g },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .height(30.dp)
                            ) {
                                Text(g, fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Text("Durum: ", color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        val statuses = listOf("Hepsi", "Devam Ediyor", "Tamamlandı")
                        statuses.forEach { s ->
                            val isSel = (s == "Hepsi" && filterStatus == null) || (s == filterStatus)
                            Button(
                                onClick = { viewModel.filterStatus.value = if (s == "Hepsi") null else s },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .height(30.dp)
                            ) {
                                Text(s, fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Text("Çeviri Dili: ", color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        val languages = listOf(
                            "Hepsi", "Türkçe", "İngilizce", "Fransızca", "İspanyolca",
                            "Japonca", "Korece", "Çince", "Almanca", "İtalyanca", "Rusça", "Portekizce", "Arapça"
                        )
                        languages.forEach { l ->
                            val isSel = (l == "Hepsi" && filterLanguage == null) || (l == filterLanguage)
                            Button(
                                onClick = { viewModel.filterLanguage.value = if (l == "Hepsi") null else l },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .height(30.dp)
                            ) {
                                Text(l, fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = {
                            viewModel.filterGenre.value = null
                            viewModel.filterStatus.value = null
                            viewModel.filterLanguage.value = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Filtreleri Temizle", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (loading && items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (error != null && items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color.Red, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(error ?: "Bilinmeyen bir hata oluştu", color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sonuç bulunamadı.", color = Color.Gray)
                        if (!sourceEndReached) {
                            TextButton(onClick = { viewModel.loadNextDiscoverPage() }) {
                                Text("Daha fazla sonuç ara")
                            }
                        }
                    }
                }
            } else {
                val gridState = rememberLazyGridState()
                val isEndReached by remember {
                    derivedStateOf {
                        val layoutInfo = gridState.layoutInfo
                        val visibleItemsInfo = layoutInfo.visibleItemsInfo
                        if (layoutInfo.totalItemsCount == 0) {
                            false
                        } else {
                            val lastVisibleItem = visibleItemsInfo.lastOrNull()
                            lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 2
                        }
                    }
                }
                LaunchedEffect(isEndReached) {
                    if (isEndReached) {
                        viewModel.loadNextDiscoverPage()
                    }
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 145.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    items(items.distinctBy { "${it.sourceName}:${it.id}" }, key = { "${it.sourceName}:${it.id}" }) { item ->
                        DiscoverItemCard(item = item, onClick = {
                            onNavigateToDetail(item.id, item.sourceName)
                        })
                    }
                    if (loading) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }

            if (showAddSiteDialog) {
                AddWebSiteDialog(
                    viewModel = viewModel,
                    onDismiss = { showAddSiteDialog = false },
                    onAdded = { site ->
                        showAddSiteDialog = false
                        viewModel.selectedSource.value = "Web: ${site.name}"
                        viewModel.clearWebCatalogStatus()
                    }
                )
            }
            if (showManageSitesDialog) {
                ManageWebSitesDialog(
                    webSites = webSites,
                    onRemove = { viewModel.removeWebSite(it) },
                    onDismiss = { showManageSitesDialog = false }
                )
            }
        }
    }
}

@Composable
fun DiscoverItemCard(item: MangaModel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("discover_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.70f)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Türler: ${item.genres.joinToString(", ")}",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val langBadge = if (item.availableLanguages.isNotEmpty()) {
                        item.availableLanguages.take(3).joinToString(", ")
                    } else if (item.sourceName.contains("Yerel", ignoreCase = true) || item.sourceName.contains("Cihaz", ignoreCase = true)) {
                        "Yerel"
                    } else {
                        "TR, EN"
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = langBadge,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (item.rating > 0.0) item.rating.toString() else "—",
                            fontSize = 11.sp,
                            color = Color.LightGray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebSourcePanel(
    site: WebSite,
    plan: MangaViewModel.WebSitePlan?,
    loading: Boolean,
    status: String?,
    selectedSection: Int,
    onSection: (String) -> Unit,
    onCategory: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenBrowser: () -> Unit,
    onScanUrl: (String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var listUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Web: ${site.name}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenBrowser) {
                Icon(Icons.Default.Language, contentDescription = "Tarayıcıda aç", tint = Color.Gray)
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Yenile",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("Popüler", "Yeni").forEachIndexed { index, label ->
                val selected = selectedSection == index && selectedCategory == null
                Button(
                    onClick = {
                        selectedCategory = null
                        onSection(label)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .height(32.dp)
                ) {
                    Text(label, fontSize = 12.sp, color = Color.White)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    listUrl = site.origin
                    showUrlDialog = true
                },
                enabled = !loading
            ) {
                Text("Adresle tara", fontSize = 11.sp, color = Color(0xFF9E9E9E))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                placeholder = { Text("Sitede ara…", color = Color.Gray, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF424242)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                        selectedCategory = null
                        onSearch(query.trim())
                    }
                },
                enabled = query.isNotBlank() && !loading
            ) {
                Text("Ara", fontSize = 13.sp)
            }
        }

        if (!plan?.categories.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                val categories = plan?.categories.orEmpty()
                categories.forEach { (title, url) ->
                    val selected = selectedCategory == title
                    Button(
                        onClick = {
                            selectedCategory = title
                            onCategory(url)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .height(28.dp)
                    ) {
                        Text(title, fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }

        if (status != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                status,
                color = if (loading) Color(0xFFBDBDBD) else MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
        }
        if (loading) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.DarkGray
            )
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Katalog Adresi", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Sitenin manga listesi sayfası adresini girin (örn. site.com/manga-list). Listeleme sayfası, ana sayfadan daha fazla manga verir.",
                        color = Color(0xFFBDBDBD),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = listUrl,
                        onValueChange = { listUrl = it },
                        singleLine = true,
                        label = { Text("Liste adresi") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUrlDialog = false
                        onScanUrl(listUrl)
                    },
                    enabled = listUrl.trim().startsWith("http")
                ) {
                    Text("Tara")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("İptal", color = Color(0xFF9E9E9E))
                }
            }
        )
    }
}

