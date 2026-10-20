package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.parser.LocalDeviceSource
import com.example.data.parser.MangaModel
import com.example.data.parser.MangaSource
import com.example.data.parser.MangaSourceRegistry
import com.example.data.web.WebSite
import com.example.extensions.ExtensionMangaSource
import com.example.extensions.ExtensionManager
import com.example.extensions.languageLabel
import com.example.ui.components.AddWebSiteDialog
import com.example.ui.components.ExtensionsSection
import com.example.ui.components.ManageWebSitesDialog
import com.example.ui.components.rememberExtensionIcon
import com.example.viewmodel.MangaViewModel

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Göz At",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
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
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF161616),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Kaynaklar", fontWeight = FontWeight.Medium) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            if (installedExtensions.isEmpty()) "Uzantılar"
                            else "Uzantılar (${installedExtensions.size})",
                            fontWeight = FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Global Arama", fontWeight = FontWeight.Medium) }
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
        contentPadding = PaddingValues(16.dp)
    ) {
        if (visibleRecent.isNotEmpty()) {
            item { SectionHeader("Son kullanılan") }
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
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (pinned.isNotEmpty()) {
            item { SectionHeader("Sabitlenmiş") }
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
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        item { SectionHeader("Tüm Kaynaklar") }
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
            Spacer(modifier = Modifier.height(8.dp))
        }

        item { SectionHeader("Web Sitelerim") }
        if (webSites.isEmpty()) {
            item {
                Text(
                    "Henüz web sitesi eklenmedi. Kendi manga sitenizi ekleyip kataloğunu taratabilirsiniz.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
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
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onAddSite) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Site Ekle")
                }
                if (webSites.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onManageSites) {
                        Text("Yönet", color = Color(0xFF9E9E9E))
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier.padding(bottom = 10.dp, top = 6.dp)
    )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isWeb) Color(0xFF2A2A2A)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
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
                    tint = if (isWeb) Color(0xFF9E9E9E) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = if (pinned) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (pinned) "Sabitlemeyi kaldır" else "Sabitle",
                tint = if (pinned) MaterialTheme.colorScheme.primary else Color.Gray
            )
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
            .padding(16.dp)
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
                placeholder = { Text("Manga adı ara…", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF424242)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.runGlobalSearch(query) },
                enabled = query.isNotBlank() && !loading
            ) {
                Text("Ara")
            }
        }
        Text(
            "Yüklü tüm kaynaklarda eşzamanlı arar.",
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            if (!done && groups.isEmpty() && !loading) {
                item {
                    Text(
                        "Aramak istediğiniz manga adını yazın; sonuçlar kaynak bazında listelenir.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            groups.forEach { group ->
                item(key = "header_${group.sourceName}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = group.sourceName,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (group.error == null) "${group.items.size} sonuç" else "Hata",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    if (group.error != null) {
                        Text(
                            text = group.error,
                            color = Color(0xFFEF5350),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
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
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            if (done && groups.isNotEmpty() && groups.all { it.items.isEmpty() }) {
                item {
                    Text(
                        "Sonuç bulunamadı.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .width(48.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.status.isNotBlank()) {
                Text(
                    text = item.status,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onAdd) {
            Icon(
                imageVector = if (added) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Kütüphaneye ekle",
                tint = if (added) MaterialTheme.colorScheme.primary else Color.Gray
            )
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
