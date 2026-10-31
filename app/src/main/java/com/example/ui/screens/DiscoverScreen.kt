package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.parser.MangaModel
import com.example.data.parser.MangaSourceRegistry
import com.example.data.web.WebSite
import com.example.i18n.Text
import com.example.ui.components.AddWebSiteDialog
import com.example.ui.components.AppScaffold
import com.example.ui.components.AppTopBar
import com.example.ui.components.MangaCover
import com.example.ui.components.ManageWebSitesDialog
import com.example.ui.components.SourceFilterSheet
import com.example.ui.components.StateView
import com.example.ui.components.SurfaceCard
import com.example.ui.theme.AppRadius
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel

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
    val sourceFilters by viewModel.sourceFilters.collectAsState()
    val loading by viewModel.discoverLoading.collectAsState()
    val items by viewModel.discoverItems.collectAsState()
    val sourceEndReached by viewModel.isEndReached.collectAsState()
    val error by viewModel.discoverError.collectAsState()
    val webSites by viewModel.webSites.collectAsState()
    val webCatalogScanning by viewModel.webCatalogScanning.collectAsState()
    val webCatalogStatus by viewModel.webCatalogStatus.collectAsState()
    val webSitePlan by viewModel.webSitePlan.collectAsState()

    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var showManageSitesDialog by remember { mutableStateOf(false) }

    val sourcesList = MangaSourceRegistry.sources.map { it.name }
    val tabsList = listOf("Popüler", "En Beğenilen", "Yeni", "Güncellenen")
    val isWebSource = viewModel.isWebSource(selectedSource)
    val isLocalSource = selectedSource.contains("Yerel", ignoreCase = true) ||
        selectedSource.contains("Cihaz", ignoreCase = true)
    val showFilterAction = !isWebSource && !isLocalSource &&
        sourceFilters.sourceName == selectedSource &&
        (sourceFilters.loading || sourceFilters.hasFilters || sourceFilters.error != null)

    LaunchedEffect(selectedSource) {
        showFilterSheet = false
    }

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

    AppScaffold(
        topBar = {
            AppTopBar(
                title = selectedSource,
                navigationIcon = if (onNavigateBack != null) {
                    {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Geri",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    null
                },
                titleContent = {
                    Box(modifier = Modifier.wrapContentSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable { sourceMenuExpanded = true }
                                .heightIn(min = AppSizes.minTouchTarget)
                                .padding(horizontal = AppSpacing.sm)
                                .testTag("source_selector_trigger")
                        ) {
                            Text(
                                text = selectedSource,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Kaynak Değiştir",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = sourceMenuExpanded,
                            onDismissRequest = { sourceMenuExpanded = false }
                        ) {
                            sourcesList.forEach { src ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            src,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        viewModel.selectedSource.value = src
                                        sourceMenuExpanded = false
                                    }
                                )
                            }
                            if (webSites.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                webSites.forEach { site ->
                                    val sourceName = "Web: ${site.name}"
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                sourceName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
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
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Yeni Web Sitesi Ekle…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
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
                                    text = {
                                        Text(
                                            "Web Sitelerini Yönet",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        sourceMenuExpanded = false
                                        showManageSitesDialog = true
                                    }
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Eklenti Mağazası (Mihon)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Extension,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
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
                    if (showFilterAction) {
                        IconButton(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier.testTag("filter_menu_trigger")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (sourceFilters.activeCount > 0) {
                                        Badge {
                                            Text(sourceFilters.activeCount.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filtreler",
                                    tint = if (sourceFilters.activeCount > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
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
        ) {
            TextField(
                value = discoverQuery,
                onValueChange = { viewModel.discoverQuery.value = it },
                placeholder = {
                    Text(
                        "Tüm kaynaklarda ara...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (discoverQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.discoverQuery.value = "" }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Temizle",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                modifier = Modifier
                    .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .testTag("discover_search_input"),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            if (!isWebSource) {
                ScrollableTabRow(
                    selectedTabIndex = tabsList.indexOf(discoverTab).coerceAtLeast(0),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = AppSpacing.lg,
                    divider = {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                ) {
                    tabsList.forEach { tab ->
                        Tab(
                            selected = discoverTab == tab,
                            onClick = { viewModel.discoverTab.value = tab },
                            text = {
                                Text(
                                    text = tab,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

            if (!isWebSource &&
                sourceFilters.sourceName == selectedSource &&
                sourceFilters.activeFilters.isNotEmpty()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sourceFilters.activeFilters.forEach { filter ->
                        FilterChip(
                            selected = true,
                            onClick = {
                                viewModel.clearSourceFilter(selectedSource, filter.key)
                            },
                            label = {
                                Text(
                                    text = filter.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Filtreyi kaldır",
                                    modifier = Modifier.size(AppSizes.iconSmall)
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = AppSizes.minTouchTarget)
                                .widthIn(max = 220.dp)
                        )
                    }
                    TextButton(
                        onClick = { viewModel.clearSourceFilters(selectedSource) },
                        modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                    ) {
                        Text(
                            "Tümünü temizle",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            if (loading && items.isEmpty()) {
                StateView(
                    title = "Yükleniyor…",
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    loading = true
                )
            } else if (error != null && items.isEmpty()) {
                StateView(
                    title = error ?: "Bilinmeyen bir hata oluştu",
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    icon = Icons.Default.ErrorOutline
                )
            } else if (items.isEmpty()) {
                StateView(
                    title = "Sonuç bulunamadı.",
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    icon = Icons.Default.Search,
                    actionLabel = if (!sourceEndReached) "Daha fazla sonuç ara" else null,
                    onAction = if (!sourceEndReached) {
                        { viewModel.loadNextDiscoverPage() }
                    } else {
                        null
                    }
                )
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
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(
                        start = AppSpacing.lg,
                        end = AppSpacing.lg,
                        bottom = AppSpacing.xl
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
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
                                    .padding(AppSpacing.lg),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (showFilterSheet && sourceFilters.sourceName == selectedSource) {
                SourceFilterSheet(
                    state = sourceFilters,
                    onDismiss = { showFilterSheet = false },
                    onApply = { nodes ->
                        viewModel.applySourceFilters(selectedSource, nodes)
                        showFilterSheet = false
                    },
                    onRetry = { viewModel.loadSourceFilters(selectedSource) }
                )
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
private fun FilterChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        },
        modifier = Modifier
            .padding(end = AppSpacing.xs)
            .heightIn(min = AppSizes.minTouchTarget),
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
fun DiscoverItemCard(item: MangaModel, onClick: () -> Unit) {
    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("discover_card_${item.id}"),
        onClick = onClick,
        contentPadding = PaddingValues(AppSpacing.xs)
    ) {
        MangaCover(
            imageUrl = item.coverUrl,
            title = item.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(AppSizes.coverAspectRatio)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Türler: ${item.genres.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(AppRadius.xs)
                        )
                        .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs)
                ) {
                    Text(
                        text = langBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(AppSizes.iconSmall)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xxs))
                    Text(
                        text = if (item.rating > 0.0) item.rating.toString() else "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
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

    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        contentPadding = PaddingValues(AppSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Web: ${site.name}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenBrowser) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = "Tarayıcıda aç",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                FilterChipButton(
                    label = label,
                    selected = selected,
                    onClick = {
                        selectedCategory = null
                        onSection(label)
                    }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    listUrl = site.origin
                    showUrlDialog = true
                },
                enabled = !loading,
                modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
            ) {
                Text(
                    "Adresle tara",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.xs))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text(
                        "Sitede ara…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconSmall)
                    )
                }
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                        selectedCategory = null
                        onSearch(query.trim())
                    }
                },
                enabled = query.isNotBlank() && !loading,
                modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
            ) {
                Text("Ara", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (!plan?.categories.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                val categories = plan?.categories.orEmpty()
                categories.forEach { (title, url) ->
                    val selected = selectedCategory == title
                    FilterChipButton(
                        label = title,
                        selected = selected,
                        onClick = {
                            selectedCategory = title
                            onCategory(url)
                        }
                    )
                }
            }
        }

        if (status != null) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (loading) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
        if (loading) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }

    if (showUrlDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Katalog Adresi",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        "Sitenin manga listesi sayfası adresini girin (örn. site.com/manga-list). Listeleme sayfası, ana sayfadan daha fazla manga verir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
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
                    Text(
                        "İptal",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}
