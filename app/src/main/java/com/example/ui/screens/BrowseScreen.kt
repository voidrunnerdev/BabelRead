package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.parser.LocalDeviceSource
import com.example.data.parser.MangaModel
import com.example.data.parser.MangaSource
import com.example.data.parser.MangaSourceRegistry
import com.example.data.web.WebSite
import com.example.extensions.ExtensionManager
import com.example.extensions.ExtensionMangaSource
import com.example.extensions.languageLabel
import com.example.i18n.Text
import com.example.ui.components.AddWebSiteDialog
import com.example.ui.components.AppScaffold
import com.example.ui.components.AppTopBar
import com.example.ui.components.ExtensionsSection
import com.example.ui.components.MangaCover
import com.example.ui.components.ManageWebSitesDialog
import com.example.ui.components.SectionHeader
import com.example.ui.components.StateView
import com.example.ui.components.SurfaceCard
import com.example.ui.components.rememberExtensionIcon
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel

@Composable
fun BrowseScreen(
    viewModel: MangaViewModel,
    onOpenSource: (String) -> Unit,
    onNavigateToDetail: (String, String) -> Unit,
    onOpenExtensions: () -> Unit
) {
    val webSites by viewModel.webSites.collectAsState()
    val pinnedSources by viewModel.pinnedSources.collectAsState()
    val recentSources by viewModel.recentSources.collectAsState()
    val installedExtensions by ExtensionManager.installed.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var showManageSitesDialog by remember { mutableStateOf(false) }

    val builtInSources = MangaSourceRegistry.sources.filter { it !is LocalDeviceSource }

    val validPinned = remember(pinnedSources, webSites, builtInSources) {
        pinnedSources.filter { name ->
            builtInSources.any { it.name == name } || webSites.any { "Web: ${it.name}" == name }
        }
    }

    AppScaffold(
        topBar = {
            AppTopBar(title = "Göz At")
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "Kaynaklar",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            if (installedExtensions.isEmpty()) "Uzantılar"
                            else "Uzantılar (${installedExtensions.size})",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            "Global Arama",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }

            when (selectedTab) {
                0 -> SourcesTab(
                    pinned = validPinned,
                    recent = recentSources,
                    builtIn = builtInSources,
                    webSites = webSites,
                    onOpenSource = onOpenSource,
                    onTogglePin = { viewModel.toggleSourcePin(it) },
                    onAddSite = { showAddSiteDialog = true },
                    onManageSites = { showManageSitesDialog = true }
                )
                1 -> ExtensionsSection(onOpenStore = onOpenExtensions)
                else -> GlobalSearchTab(
                    viewModel = viewModel,
                    onNavigateToDetail = onNavigateToDetail
                )
            }
        }
    }

    if (showAddSiteDialog) {
        AddWebSiteDialog(
            viewModel = viewModel,
            onDismiss = { showAddSiteDialog = false },
            onAdded = { site ->
                showAddSiteDialog = false
                viewModel.clearWebCatalogStatus()
                onOpenSource("Web: ${site.name}")
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

@Composable
private fun SourcesTab(
    pinned: List<String>,
    recent: List<String>,
    builtIn: List<MangaSource>,
    webSites: List<WebSite>,
    onOpenSource: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onAddSite: () -> Unit,
    onManageSites: () -> Unit
) {
    val visibleRecent = recent.filter { name ->
        name !in pinned && (
            builtIn.any { it.name == name } || webSites.any { "Web: ${it.name}" == name }
            )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        if (visibleRecent.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Son kullanılan",
                    modifier = Modifier.padding(top = AppSpacing.sm)
                )
            }
            items(visibleRecent, key = { "recent_$it" }) { name ->
                SourceRow(
                    name = name,
                    subtitle = subtitleFor(name, builtIn, webSites),
                    isWeb = false,
                    pinned = false,
                    apkPath = (builtIn.firstOrNull { src -> src.name == name } as? ExtensionMangaSource)?.meta?.apkPath,
                    onClick = { onOpenSource(name) },
                    onTogglePin = { onTogglePin(name) }
                )
            }
        }

        if (pinned.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Sabitlenmiş",
                    modifier = Modifier.padding(top = AppSpacing.sm)
                )
            }
            items(pinned, key = { "pin_$it" }) { name ->
                SourceRow(
                    name = name,
                    subtitle = subtitleFor(name, builtIn, webSites),
                    isWeb = name.startsWith("Web: "),
                    pinned = true,
                    apkPath = (builtIn.firstOrNull { src -> src.name == name } as? ExtensionMangaSource)?.meta?.apkPath,
                    onClick = { onOpenSource(name) },
                    onTogglePin = { onTogglePin(name) }
                )
            }
        }

        item {
            SectionHeader(
                title = "Tüm Kaynaklar",
                modifier = Modifier.padding(top = AppSpacing.sm)
            )
        }
        items(
            builtIn,
            key = { src ->
                if (src is ExtensionMangaSource) {
                    "ext_${src.meta.pkgName}_${src.ext.id}"
                } else {
                    "src_${src.name}"
                }
            },
        ) { source ->
            SourceRow(
                name = source.name,
                subtitle = if (source is ExtensionMangaSource) {
                    "${languageLabel(source.ext.lang)} eklentisi"
                } else {
                    "Yerleşik kaynak"
                },
                isWeb = false,
                pinned = source.name in pinned,
                apkPath = (source as? ExtensionMangaSource)?.meta?.apkPath,
                onClick = { onOpenSource(source.name) },
                onTogglePin = { onTogglePin(source.name) }
            )
        }

        item {
            SectionHeader(
                title = "Web Sitelerim",
                modifier = Modifier.padding(top = AppSpacing.sm)
            )
        }
        if (webSites.isEmpty()) {
            item {
                StateView(
                    title = "Henüz web sitesi eklenmedi.",
                    message = "Kendi manga sitenizi ekleyip kataloğunu taratabilirsiniz.",
                    icon = Icons.Default.Language
                )
            }
        } else {
            items(webSites, key = { it.id }) { site ->
                val sourceName = "Web: ${site.name}"
                SourceRow(
                    name = sourceName,
                    subtitle = site.origin,
                    isWeb = true,
                    pinned = sourceName in pinned,
                    onClick = { onOpenSource(sourceName) },
                    onTogglePin = { onTogglePin(sourceName) }
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                OutlinedButton(
                    onClick = onAddSite,
                    modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(AppSizes.iconSmall)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("Site Ekle", style = MaterialTheme.typography.labelLarge)
                }
                if (webSites.isNotEmpty()) {
                    TextButton(
                        onClick = onManageSites,
                        modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                    ) {
                        Text(
                            "Yönet",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.xl))
        }
    }
}

@Composable
private fun SourceRow(
    name: String,
    subtitle: String,
    isWeb: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    apkPath: String? = null
) {
    val extensionIcon = rememberExtensionIcon(apkPath)
    SurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (isWeb) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (extensionIcon != null) {
                    Image(
                        bitmap = extensionIcon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = when {
                            isWeb -> Icons.Default.Language
                            apkPath != null -> Icons.Default.Extension
                            else -> Icons.Default.MenuBook
                        },
                        contentDescription = null,
                        tint = if (isWeb) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(AppSizes.icon)
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (pinned) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (pinned) "Sabitlemeyi kaldır" else "Sabitle",
                    tint = if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun GlobalSearchTab(
    viewModel: MangaViewModel,
    onNavigateToDetail: (String, String) -> Unit
) {
    val groups by viewModel.globalSearchGroups.collectAsState()
    val loading by viewModel.globalSearchLoading.collectAsState()
    val done by viewModel.globalSearchDone.collectAsState()
    var query by remember { mutableStateOf("") }
    val addedIds = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { newValue ->
                    query = newValue
                    if (newValue.isBlank() && (done || groups.isNotEmpty())) {
                        viewModel.clearGlobalSearch()
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        "Manga adı ara…",
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
                }
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Button(
                onClick = { viewModel.runGlobalSearch(query) },
                enabled = query.isNotBlank() && !loading,
                modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
            ) {
                Text("Ara", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            "Yüklü tüm kaynaklarda eşzamanlı arar.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppSpacing.xs)
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(AppSpacing.sm))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            if (loading && groups.isEmpty()) {
                item {
                    StateView(
                        title = "Yükleniyor…",
                        loading = true
                    )
                }
            }
            if (!done && groups.isEmpty() && !loading) {
                item {
                    StateView(
                        title = "Aramak istediğiniz manga adını yazın; sonuçlar kaynak bazında listelenir.",
                        icon = Icons.Outlined.Search
                    )
                }
            }

            groups.forEach { group ->
                item(key = "header_${group.sourceName}") {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AppSpacing.sm, bottom = AppSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                        ) {
                            Text(
                                text = group.sourceName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (group.error == null) "${group.items.size} sonuç" else "Hata",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (group.error != null) {
                            Text(
                                text = group.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = AppSpacing.xs)
                            )
                        }
                    }
                }
                items(group.items, key = { "${group.sourceName}_${it.id}" }) { item ->
                    GlobalSearchResultRow(
                        item = item,
                        added = addedIds[item.id] == true,
                        onAdd = {
                            if (addedIds[item.id] != true) {
                                viewModel.addMangaToLibrary(item)
                                addedIds[item.id] = true
                            }
                        },
                        onClick = { onNavigateToDetail(item.id, item.sourceName) }
                    )
                }
            }

            if (done && groups.isNotEmpty() && groups.all { it.items.isEmpty() }) {
                item {
                    StateView(
                        title = "Sonuç bulunamadı.",
                        icon = Icons.Outlined.Search
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalSearchResultRow(
    item: MangaModel,
    added: Boolean,
    onAdd: () -> Unit,
    onClick: () -> Unit
) {
    SurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(AppSpacing.sm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            MangaCover(
                imageUrl = item.coverUrl,
                title = item.title,
                modifier = Modifier
                    .width(48.dp)
                    .height(68.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.status.isNotBlank()) {
                    Text(
                        text = item.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onAdd) {
                Icon(
                    imageVector = if (added) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Kütüphaneye ekle",
                    tint = if (added) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

private fun subtitleFor(name: String, builtIn: List<MangaSource>, webSites: List<WebSite>): String {
    (builtIn.firstOrNull { it.name == name } as? ExtensionMangaSource)?.let {
        return "${languageLabel(it.ext.lang)} eklentisi"
    }
    builtIn.firstOrNull { it.name == name }?.let { return "Yerleşik kaynak" }
    webSites.firstOrNull { "Web: ${it.name}" == name }?.let { return it.origin }
    return ""
}
