package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.extensions.ExtensionIndex
import com.example.extensions.ExtensionManager
import com.example.extensions.languageLabel
import com.example.i18n.Text
import com.example.i18n.TranslationLanguages
import com.example.ui.components.AppScaffold
import com.example.ui.components.AppTopBar
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.LanguagePickerOption
import com.example.ui.components.SectionHeader
import com.example.ui.components.StateView
import com.example.ui.components.SurfaceCard
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class ItemStatus(val running: Boolean = false, val message: String? = null)

private data class StoreVariant(
    val item: ExtensionIndex.StoreItem,
    val source: ExtensionIndex.SourceRef?,
    val isMulti: Boolean,
) {
    val key: String = "${item.pkgName}|${source?.id ?: 0L}"
    val language: String? = source?.language
    val title: String = when {
        isMulti -> "${item.name} (Çok Dilli)"
        source != null && item.sources.size > 1 -> {
            val duplicateLanguage = item.sources.count { it.language.equals(source.language, ignoreCase = true) } > 1
            val base = if (duplicateLanguage) source.name else item.name
            "$base (${languageLabel(source.language)})"
        }
        else -> item.name
    }
    val enableIds: Set<Long> = if (isMulti) {
        item.sources.map { it.id }.toSet()
    } else {
        setOfNotNull(source?.id)
    }
    val languages: Set<String> = source?.language
        ?.takeIf { it.isNotBlank() }
        ?.let { setOf(it) }
        .orEmpty()
}

private fun languageRank(language: String): Int = when {
    language.equals("tr", ignoreCase = true) -> 0
    language.equals("en", ignoreCase = true) -> 1
    else -> 2
}

private fun variantRank(variant: StoreVariant): Int = when {
    variant.language.equals("tr", ignoreCase = true) -> 0
    variant.language.equals("en", ignoreCase = true) -> 1
    variant.language.equals("all", ignoreCase = true) -> 2
    variant.isMulti -> 4
    else -> 3
}

@Composable
fun ExtensionStoreScreen(
    onNavigateBack: () -> Unit,
    viewModel: MangaViewModel,
) {
    val scope = rememberCoroutineScope()
    val installed by ExtensionManager.installed.collectAsState()
    val enabledSourceIds by ExtensionManager.enabledSourceIds.collectAsState()
    val repos by viewModel.preferences.extensionRepos.collectAsState(initial = emptySet())

    var storeItems by remember { mutableStateOf<List<ExtensionIndex.StoreItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var repoErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showRepoDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var languageFilter by remember { mutableStateOf<String?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    val statuses = remember { mutableStateMapOf<String, ItemStatus>() }

    fun loadStore() {
        scope.launch {
            loading = true
            loadError = null
            val merged = LinkedHashMap<String, ExtensionIndex.StoreItem>()
            val errors = mutableMapOf<String, String>()
            val results = coroutineScope {
                repos.sorted().map { repo ->
                    async { repo to runCatching { ExtensionIndex.fetch(repo) } }
                }.awaitAll()
            }
            for ((repo, result) in results) {
                result.onSuccess { items ->
                    for (item in items) {
                        val existing = merged[item.pkgName]
                        if (existing == null || item.versionCode > existing.versionCode) {
                            merged[item.pkgName] = item
                        }
                    }
                }.onFailure { errors[repo] = it.message ?: "Depo yüklenemedi" }
            }
            storeItems = merged.values.sortedBy { it.name.lowercase() }
            repoErrors = errors
            loading = false
        }
    }

    LaunchedEffect(repos) { loadStore() }

    val installedByPkg = remember(installed) { installed.associateBy { it.meta.pkgName } }

    val availableLanguages = remember(storeItems) {
        storeItems.flatMap { it.languages }
            .filterNot { it.equals("all", ignoreCase = true) }
            .distinct()
            .sortedWith(compareBy({ it != "tr" }, { languageLabel(it) }))
    }

    val storeVariants = remember(storeItems) {
        storeItems.flatMap { item ->
            if (item.sources.size <= 1) {
                listOf(StoreVariant(item, item.sources.firstOrNull(), isMulti = false))
            } else {
                buildList {
                    // "all" kaynağı olan eklentilerde o kaynak zaten çok dilli;
                    // olmayanlarda tüm dilleri birlikte kuran bir seçenek ekle.
                    if (item.sources.none { it.language.equals("all", ignoreCase = true) }) {
                        add(StoreVariant(item, null, isMulti = true))
                    }
                    item.sources
                        .sortedWith(compareBy({ languageRank(it.language) }, { languageLabel(it.language) }))
                        .forEach { source -> add(StoreVariant(item, source, isMulti = false)) }
                }
            }
        }.sortedWith(
            compareBy(
                { it.item.name.lowercase() },
                { variantRank(it) },
                { it.language?.let(::languageLabel).orEmpty() },
            )
        )
    }

    val filtered = remember(storeVariants, query, languageFilter) {
        storeVariants.filter { variant ->
            val matchesQuery = query.isBlank() ||
                variant.title.contains(query, ignoreCase = true) ||
                variant.item.name.contains(query, ignoreCase = true) ||
                variant.item.pkgName.contains(query, ignoreCase = true)
            val matchesLanguage = languageFilter == null ||
                (!variant.isMulti && variant.language.equals(languageFilter, ignoreCase = true))
            matchesQuery && matchesLanguage
        }
    }

    fun installVariant(variant: StoreVariant) {
        if (statuses[variant.key]?.running == true) return
        scope.launch {
            statuses[variant.key] = ItemStatus(running = true)
            val result = runCatching {
                val pkg = variant.item.pkgName
                val installedExt = ExtensionManager.installed.value.firstOrNull { it.meta.pkgName == pkg }
                val needsApk = installedExt == null || variant.item.versionCode > installedExt.meta.versionCode
                if (needsApk) {
                    val bytes = ExtensionIndex.downloadApk(variant.item)
                    ExtensionManager.install(
                        apkBytes = bytes,
                        expectedPkg = pkg,
                        enableSourceIds = variant.enableIds,
                        enableLanguages = variant.languages,
                        enableAllSources = variant.isMulti,
                    ).getOrThrow()
                } else if (
                    !ExtensionManager.enableSources(
                        pkgName = pkg,
                        sourceIds = variant.enableIds,
                        sourceLanguages = variant.languages,
                        enableAllSources = variant.isMulti,
                    )
                ) {
                    throw IllegalStateException("Kaynak eklenemedi")
                }
            }
            statuses[variant.key] = result.fold(
                onSuccess = { ItemStatus(message = "Kuruldu") },
                onFailure = { ItemStatus(message = it.message ?: "Kurulum başarısız") },
            )
        }
    }

    fun updateVariant(variant: StoreVariant) {
        if (statuses[variant.key]?.running == true) return
        scope.launch {
            statuses[variant.key] = ItemStatus(running = true)
            val result = runCatching {
                val pkg = variant.item.pkgName
                val bytes = ExtensionIndex.downloadApk(variant.item)
                ExtensionManager.install(bytes, pkg, enableSourceIds = null).getOrThrow()
            }
            statuses[variant.key] = result.fold(
                onSuccess = { ItemStatus(message = "Güncellendi") },
                onFailure = { ItemStatus(message = it.message ?: "Güncelleme başarısız") },
            )
        }
    }

    fun removeVariant(variant: StoreVariant) {
        if (statuses[variant.key]?.running == true) return
        scope.launch {
            statuses[variant.key] = ItemStatus(running = true)
            val removed = if (variant.isMulti || variant.item.sources.size <= 1) {
                ExtensionManager.uninstall(variant.item.pkgName)
            } else {
                ExtensionManager.disableSources(
                    pkgName = variant.item.pkgName,
                    sourceIds = variant.enableIds,
                    sourceLanguages = variant.languages,
                )
            }
            statuses[variant.key] = ItemStatus(message = if (removed) "Kaldırıldı" else "Kaldırılamadı")
        }
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = "Eklenti Mağazası",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showLanguagePicker = true },
                        modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                    ) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Dil filtresi",
                            tint = if (languageFilter != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(AppSizes.iconSmall),
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text(
                            languageFilter?.let { languageLabel(it) } ?: "Tümü",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (languageFilter != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { loadStore() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Yenile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (repos.isEmpty()) {
                RepoEmptyState(onAddRepo = { showRepoDialog = true })
            } else {
                RepoManager(
                    repos = repos,
                    errors = repoErrors,
                    onRemove = { repo -> scope.launch { viewModel.preferences.removeExtensionRepo(repo) } },
                    onAddRepo = { showRepoDialog = true },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)
                        .testTag("extension_search"),
                    placeholder = {
                        Text(
                            "Eklenti ara…",
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
                    singleLine = true,
                )

                Text(
                    "${installed.size} kurulu · ${filtered.size} seçenek",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
                )

                if (loadError != null) {
                    StateView(
                        title = "Hata",
                        message = loadError,
                        icon = Icons.Default.ErrorOutline
                    )
                }

                if (loading) {
                    StateView(
                        title = "Yükleniyor…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.xl),
                        loading = true,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = AppSpacing.lg,
                            end = AppSpacing.lg,
                            bottom = AppSpacing.xl
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        if (filtered.isEmpty()) {
                            item {
                                StateView(
                                    title = "Eklenti bulunamadı.",
                                    icon = Icons.Default.Extension
                                )
                            }
                        }
                        items(filtered, key = { it.key }) { variant ->
                            val installedExt = installedByPkg[variant.item.pkgName]
                            val enabledIds = enabledSourceIds[variant.item.pkgName]
                                ?: installedExt?.let { ext ->
                                    setOfNotNull(ExtensionManager.defaultSource(ext)?.id)
                                }.orEmpty()
                            val allSourceIds = installedExt?.sources?.map { it.id }?.toSet().orEmpty()
                            val isVariantInstalled = installedExt != null && when {
                                variant.isMulti ->
                                    allSourceIds.isNotEmpty() && enabledIds.containsAll(allSourceIds)
                                variant.source != null -> ExtensionManager.isSourceEnabled(
                                    ext = installedExt,
                                    enabledIds = enabledIds,
                                    sourceId = variant.source.id,
                                    language = variant.source.language,
                                )
                                else -> true
                            }
                            val status = statuses[variant.key]
                            val hasUpdate = installedExt != null &&
                                variant.item.versionCode > installedExt.meta.versionCode
                            val showUpdate = hasUpdate && (
                                variant.isMulti ||
                                    variant.item.sources.size <= 1 ||
                                    isVariantInstalled
                                )
                            ExtensionRow(
                                variant = variant,
                                packageInstalled = installedExt != null,
                                isVariantInstalled = isVariantInstalled,
                                showUpdate = showUpdate,
                                status = status,
                                onInstall = { installVariant(variant) },
                                onUpdate = { updateVariant(variant) },
                                onRemove = { removeVariant(variant) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRepoDialog) {
        AddRepoDialog(
            onDismiss = { showRepoDialog = false },
            onConfirm = { normalized ->
                scope.launch { viewModel.preferences.addExtensionRepo(normalized) }
                showRepoDialog = false
            },
        )
    }

    if (showLanguagePicker) {
        val filterOptions = buildList {
            add(LanguagePickerOption(code = "all", title = "Tümü"))
            availableLanguages.forEach { lang ->
                add(
                    LanguagePickerOption(
                        code = lang,
                        title = languageLabel(lang),
                        nativeName = TranslationLanguages.nativeName(lang)
                            ?.takeIf { it != languageLabel(lang) },
                    )
                )
            }
        }
        LanguagePickerDialog(
            title = "Dil Filtresi",
            options = filterOptions,
            selectedCode = languageFilter ?: "all",
            onSelect = { code ->
                languageFilter = if (code == "all") null else code
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }
}

@Composable
private fun ExtensionRow(
    variant: StoreVariant,
    packageInstalled: Boolean,
    isVariantInstalled: Boolean,
    showUpdate: Boolean,
    status: ItemStatus?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    val item = variant.item
    SurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(AppSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!item.iconUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.iconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(AppSizes.icon),
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        variant.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.isNsfw) {
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text(
                            "18+",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs),
                        )
                    }
                }
                if (variant.isMulti) {
                    Text(
                        "Tüm dil kaynakları birlikte",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status?.message != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.xxs))
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (
                            status.message == "Kuruldu" ||
                            status.message == "Güncellendi" ||
                            status.message == "Kaldırıldı"
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            if (status?.running == true) {
                CircularProgressIndicator(
                    modifier = Modifier.size(AppSizes.icon),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    if (showUpdate) {
                        Button(
                            onClick = onUpdate,
                            contentPadding = PaddingValues(horizontal = AppSpacing.md),
                            modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(AppSizes.iconSmall)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text("Güncelle", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (isVariantInstalled) {
                        OutlinedButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = AppSpacing.md),
                            modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(AppSizes.iconSmall),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text(
                                if (variant.isMulti) "Tümünü Kaldır" else "Kaldır",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        TextButton(
                            onClick = onInstall,
                            modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(AppSizes.iconSmall),
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text(
                                when {
                                    !packageInstalled -> "Kur"
                                    variant.isMulti -> "Tümünü Ekle"
                                    else -> "Ekle"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddRepoDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Depo Ekle",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "Depo kök adresini ya da doğrudan index.min.json / index.json bağlantısını yapıştırın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        inputError = null
                    },
                    placeholder = {
                        Text(
                            "https://ornek.com/eklenti-deposu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (inputError != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Text(
                        inputError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Text(
                    "Yalnızca güvendiğiniz depoları ekleyin.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = ExtensionIndex.normalizeRepoUrl(input)
                if (normalized == null) {
                    inputError = "Geçerli bir http(s) adresi girin."
                } else {
                    onConfirm(normalized)
                }
            }) { Text("Ekle", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "İptal",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
    )
}

@Composable
private fun RepoEmptyState(onAddRepo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(AppSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.lg))
        StateView(
            title = "Henüz depo eklenmedi",
            message = "Eklentileri görüntülemek için bir depo adresi ekleyin. Depo kök adresini, index.min.json / index.json bağlantısını veya GitHub sayfa adresini yapıştırabilirsiniz; adres otomatik olarak düzenlenir.",
            icon = Icons.Default.Extension,
            actionLabel = "Depo Ekle",
            onAction = onAddRepo
        )
        Text(
            "Depoları dilediğiniz zaman listeden kaldırabilirsiniz.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RepoManager(
    repos: Set<String>,
    errors: Map<String, String>,
    onRemove: (String) -> Unit,
    onAddRepo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg),
    ) {
        SectionHeader(
            title = "Depolar",
            modifier = Modifier.padding(vertical = AppSpacing.xs)
        )
        repos.sorted().forEach { repo ->
            val error = errors[repo]
            SurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.sm),
                contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconSmall)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            repoDisplayName(repo),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (error != null) {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(repo) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Depoyu kaldır",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(AppSizes.iconSmall)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onAddRepo,
                contentPadding = PaddingValues(horizontal = AppSpacing.md),
                modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(AppSizes.iconSmall)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text("Depo Ekle", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Text(
                "Kaldırma, kurulu eklentileri silmez.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.sm))
    }
}

private fun repoDisplayName(repoUrl: String): String =
    repoUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
