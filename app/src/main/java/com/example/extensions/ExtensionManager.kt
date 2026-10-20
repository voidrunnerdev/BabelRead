package com.example.extensions

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.data.parser.MangaSourceRegistry
import com.example.data.pref.MangaPreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object ExtensionManager {

    data class InstalledExtension(
        val meta: ExtensionMeta,
        val sources: List<CatalogueSource>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val reloadMutex = Mutex()
    private var appContext: Context? = null
    private var preferences: MangaPreferences? = null

    private val _installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installed: StateFlow<List<InstalledExtension>> = _installed.asStateFlow()

    private val _loadErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val loadErrors: StateFlow<Map<String, String>> = _loadErrors.asStateFlow()

    private val _enabledSourceIds = MutableStateFlow<Map<String, Set<Long>>>(emptyMap())
    val enabledSourceIds: StateFlow<Map<String, Set<Long>>> = _enabledSourceIds.asStateFlow()

    val extensionsDir: File
        get() = File(requiredContext().filesDir, "extensions")

    fun init(context: Context) {
        appContext = context.applicationContext
        preferences = MangaPreferences(context.applicationContext)
        scope.launch { reload() }
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        reloadMutex.withLock {
            val context = requiredContext()
            val dir = extensionsDir
            dir.mkdirs()
            val loader = ExtensionLoader(context)
            val loadedByPkg = LinkedHashMap<String, Pair<File, InstalledExtension>>()
            val errors = mutableMapOf<String, String>()
            dir.listFiles { f -> f.isFile && f.extension == "apk" }?.sortedBy { it.name }?.forEach { file ->
                try {
                    val result = loader.load(file)
                    val ext = InstalledExtension(result.meta, result.sources)
                    val existing = loadedByPkg[result.meta.pkgName]
                    when {
                        existing == null -> loadedByPkg[result.meta.pkgName] = file to ext
                        result.meta.versionCode > existing.second.meta.versionCode -> {
                            // Aynı paketin eski sürümü: çift kayıt çökmelere yol açmasın diye dosyasını temizle
                            runCatching { existing.first.delete() }
                                .onSuccess {
                                    Log.w("ExtensionManager", "Yinelenen eski eklenti dosyası silindi: ${existing.first.name}")
                                }
                            loadedByPkg[result.meta.pkgName] = file to ext
                        }
                        else -> {
                            runCatching { file.delete() }
                                .onSuccess {
                                    Log.w("ExtensionManager", "Yinelenen eklenti dosyası silindi: ${file.name}")
                                }
                        }
                    }
                } catch (t: Throwable) {
                    errors[file.name] = "${t.javaClass.simpleName}: ${t.message ?: "bilinmeyen hata"}"
                    Log.w("ExtensionManager", "Eklenti yüklenemedi: ${file.name}", t)
                }
            }
            val loaded = loadedByPkg.values.map { it.second }
            val prefs = preferences ?: MangaPreferences(context).also { preferences = it }
            val storedSelections = prefs.extensionSourceSelection.first().toMutableMap()
            val loadedPkgs = loaded.map { it.meta.pkgName }.toSet()
            val selections = storedSelections.filterKeys { it in loadedPkgs }.toMutableMap()
            var selectionChanged = selections.size != storedSelections.size

            // Eski sürümden gelen kurulumlarda tüm dil kaynakları açıktı; ilk açılışta
            // varsayılan varyantı seçip kaynak listesinin 61 kaynakla dolmasını önle.
            loaded.forEach { ext ->
                val ids = ext.sources.map { it.id }.toSet()
                val existing = selections[ext.meta.pkgName]
                if (existing == null) {
                    chooseDefaultSource(ext.sources)?.let { source ->
                        selections[ext.meta.pkgName] = setOf(source.id)
                        selectionChanged = true
                    }
                } else {
                    val pruned = existing.intersect(ids)
                    val fixed = if (pruned.isEmpty()) {
                        setOfNotNull(chooseDefaultSource(ext.sources)?.id)
                    } else {
                        pruned
                    }
                    if (fixed != existing) {
                        selections[ext.meta.pkgName] = fixed
                        selectionChanged = true
                    }
                }
            }
            if (selectionChanged) {
                prefs.setExtensionSourceSelectionMap(selections)
            }

            _enabledSourceIds.value = selections
            _installed.value = loaded
            _loadErrors.value = errors
            val wrapped = loaded.flatMap { ext ->
                buildLanguageSources(ext, selections[ext.meta.pkgName].orEmpty())
            }
            MangaSourceRegistry.setExtensionSources(wrapped)
            Log.i("ExtensionManager", "Yüklenen eklenti: ${loaded.size}")
        }
    }

    private fun buildLanguageSources(
        ext: InstalledExtension,
        enabledIds: Set<Long>,
    ): List<ExtensionMangaSource> {
        val sources = ext.sources.distinctBy { it.id }
        if (sources.isEmpty()) return emptyList()
        val enabled = sources.filter { it.id in enabledIds }.ifEmpty {
            listOfNotNull(chooseDefaultSource(sources))
        }
        if (sources.size <= 1) {
            return enabled.map { ExtensionMangaSource(ext.meta, it) }
        }

        // Önceki sürümde tek kaynak görünüyordu; o varyantın adı değişmesin
        val legacyVisible = legacyVisibleSource(sources)
        val searchDefault = chooseDefaultSource(enabled)

        return enabled.map { source ->
            val displayName = if (source === legacyVisible) {
                null
            } else {
                "${source.name} (${languageLabel(source.lang)})"
            }
            ExtensionMangaSource(
                meta = ext.meta,
                ext = source,
                displayName = displayName,
                isDefaultForExtension = source === searchDefault,
            )
        }
    }

    private fun chooseDefaultSource(sources: List<CatalogueSource>): CatalogueSource? {
        val deviceLang = java.util.Locale.getDefault().language.ifBlank { "en" }
        return sources.firstOrNull { it.lang.equals(deviceLang, ignoreCase = true) }
            ?: sources.firstOrNull { it.lang.equals("en", ignoreCase = true) }
            ?: sources.firstOrNull { it.lang.equals("all", ignoreCase = true) }
            ?: sources.firstOrNull()
    }

    fun defaultSource(ext: InstalledExtension): CatalogueSource? = chooseDefaultSource(ext.sources)

    /**
     * Depodaki kaynak kimliklerini gerçekten yüklenen kaynaklarla eşler.
     * Depo ile APK sürümü uyuşmadığında kimlikler kayabilir; bu yüzden dil
     * bilgisi kimlikten daha güvenilir kabul edilir.
     */
    fun resolveSourceIds(
        sources: List<CatalogueSource>,
        sourceIds: Set<Long>,
        languages: Set<String> = emptySet(),
    ): Set<Long> {
        if (sources.isEmpty()) return emptySet()
        val languageKeys = languages.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val exact = sources.filter { it.id in sourceIds }
        if (languageKeys.isNotEmpty()) {
            val exactMatching = exact.filter { it.lang.trim().lowercase() in languageKeys }
            if (exactMatching.isNotEmpty()) return exactMatching.map { it.id }.toSet()
            val byLanguage = sources.filter { it.lang.trim().lowercase() in languageKeys }
            if (byLanguage.isNotEmpty()) return byLanguage.map { it.id }.toSet()
        }
        return exact.map { it.id }.toSet()
    }

    fun isSourceEnabled(
        ext: InstalledExtension,
        enabledIds: Set<Long>,
        sourceId: Long,
        language: String?,
    ): Boolean {
        val languageKey = language?.trim()?.lowercase().orEmpty()
        val exact = ext.sources.firstOrNull { it.id == sourceId }
        if (exact != null && (languageKey.isEmpty() || exact.lang.lowercase() == languageKey)) {
            return sourceId in enabledIds
        }
        if (languageKey.isEmpty()) return sourceId in enabledIds
        return ext.sources.any {
            it.id in enabledIds && it.lang.trim().lowercase() == languageKey
        }
    }

    private fun legacyVisibleSource(sources: List<CatalogueSource>): CatalogueSource? {
        val deviceLang = java.util.Locale.getDefault().language.ifBlank { "en" }
        val allSource = sources.firstOrNull { it.lang.equals("all", ignoreCase = true) }
        val deviceSource = sources.firstOrNull { it.lang.equals(deviceLang, ignoreCase = true) }
        return if (allSource != null && deviceSource != null) {
            null
        } else {
            allSource ?: deviceSource
                ?: sources.firstOrNull { it.lang.equals("en", ignoreCase = true) }
                ?: sources.first()
        }
    }

    suspend fun install(
        apkBytes: ByteArray,
        expectedPkg: String?,
        enableSourceIds: Set<Long>? = null,
        enableLanguages: Set<String> = emptySet(),
        enableAllSources: Boolean = false,
    ): Result<ExtensionMeta> =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                runCatching {
                    val context = requiredContext()
                    val prefs = preferences ?: MangaPreferences(context).also { preferences = it }
                    val tmp = File.createTempFile("ext_install_", ".apk", context.cacheDir)
                    tmp.writeBytes(apkBytes)
                    try {
                        @Suppress("DEPRECATION")
                        val pkgInfo = context.packageManager.getPackageArchiveInfo(
                            tmp.absolutePath,
                            PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES,
                        ) ?: throw IllegalStateException("APK okunamadı")
                        if (expectedPkg != null && pkgInfo.packageName != expectedPkg) {
                            throw SecurityException("Paket adı uyuşmuyor: ${pkgInfo.packageName}")
                        }
                        if (!ExtensionSignature.isTrusted(pkgInfo)) {
                            throw SecurityException("Eklenti imzası doğrulanamadı (Keiyoushi anahtarı bekleniyor)")
                        }
                        val dest = File(extensionsDir, "${pkgInfo.packageName}.apk")
                        val wasInstalled = dest.exists() ||
                            _installed.value.any { it.meta.pkgName == pkgInfo.packageName }
                        if (dest.exists()) dest.delete()
                        tmp.copyTo(dest, overwrite = true)
                        dest.setReadOnly()

                        val loaded = try {
                            ExtensionLoader(context).load(dest)
                        } catch (t: Throwable) {
                            reload()
                            throw IllegalStateException(
                                "Başlatılamadı — ${t.javaClass.simpleName}: ${t.message ?: "bilinmeyen hata"}",
                                t,
                            )
                        }

                        val allSourceIds = loaded.sources.map { it.id }.toSet()
                        val resolved = if (enableAllSources) {
                            allSourceIds
                        } else {
                            resolveSourceIds(
                                sources = loaded.sources,
                                sourceIds = enableSourceIds.orEmpty(),
                                languages = enableLanguages,
                            )
                        }
                        val hasRequest = enableAllSources || enableSourceIds != null || enableLanguages.isNotEmpty()
                        val stored = prefs.extensionSourceSelection.first()[pkgInfo.packageName].orEmpty()
                        val previous = _installed.value
                            .firstOrNull { it.meta.pkgName == pkgInfo.packageName }
                        val current = stored.intersect(allSourceIds)
                        // Güncellemede kaynak kimlikleri değişebilir; seçimi dil üzerinden taşı.
                        val preserved = if (current.isEmpty() && previous != null) {
                            val previousLanguages = previous.sources
                                .filter { it.id in stored }
                                .map { it.lang }
                                .filter { it.isNotBlank() }
                                .toSet()
                            if (previousLanguages.isEmpty()) {
                                emptySet()
                            } else {
                                resolveSourceIds(loaded.sources, emptySet(), previousLanguages)
                            }
                        } else {
                            current
                        }
                        val selection = when {
                            !hasRequest -> preserved.ifEmpty {
                                setOfNotNull(chooseDefaultSource(loaded.sources)?.id)
                            }
                            wasInstalled -> (preserved + resolved).ifEmpty {
                                setOfNotNull(chooseDefaultSource(loaded.sources)?.id)
                            }
                            else -> resolved.ifEmpty {
                                setOfNotNull(chooseDefaultSource(loaded.sources)?.id)
                            }
                        }
                        if (selection.isNotEmpty()) {
                            prefs.setExtensionSourceSelection(pkgInfo.packageName, selection)
                        }

                        reload()
                        _installed.value.firstOrNull { it.meta.pkgName == pkgInfo.packageName }?.meta
                            ?: throw IllegalStateException("Eklenti yüklendi ancak başlatılamadı")
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }

    suspend fun uninstall(pkgName: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(extensionsDir, "$pkgName.apk")
        var removed = file.exists() && file.delete()
        val actualPath = _installed.value.firstOrNull { it.meta.pkgName == pkgName }?.meta?.apkPath
        val actual = actualPath?.let { File(it) }
        if (actual != null && actual != file && actual.exists() && actual.delete()) {
            removed = true
        }
        if (removed) {
            preferences?.clearExtensionSourceSelection(pkgName)
            reload()
        }
        removed
    }

    suspend fun enableSources(
        pkgName: String,
        sourceIds: Set<Long>,
        sourceLanguages: Set<String> = emptySet(),
        enableAllSources: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!enableAllSources && sourceIds.isEmpty() && sourceLanguages.isEmpty()) {
            return@withContext false
        }
        val ext = _installed.value.firstOrNull { it.meta.pkgName == pkgName } ?: return@withContext false
        val prefs = preferences ?: return@withContext false
        val allSourceIds = ext.sources.map { it.id }.toSet()
        val current = (prefs.extensionSourceSelection.first()[pkgName]
            ?: setOfNotNull(chooseDefaultSource(ext.sources)?.id)).intersect(allSourceIds)
        val requested = if (enableAllSources) {
            allSourceIds
        } else {
            resolveSourceIds(ext.sources, sourceIds, sourceLanguages)
        }
        if (requested.isEmpty()) return@withContext false
        val updated = current + requested
        if (updated == current) return@withContext true
        prefs.setExtensionSourceSelection(pkgName, updated)
        reload()
        true
    }

    suspend fun disableSources(
        pkgName: String,
        sourceIds: Set<Long>,
        sourceLanguages: Set<String> = emptySet(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (sourceIds.isEmpty() && sourceLanguages.isEmpty()) return@withContext false
        val ext = _installed.value.firstOrNull { it.meta.pkgName == pkgName } ?: return@withContext false
        val prefs = preferences ?: return@withContext false
        val allSourceIds = ext.sources.map { it.id }.toSet()
        val current = (prefs.extensionSourceSelection.first()[pkgName]
            ?: setOfNotNull(chooseDefaultSource(ext.sources)?.id)).intersect(allSourceIds)
        val requested = resolveSourceIds(ext.sources, sourceIds, sourceLanguages)
        if (requested.isEmpty()) return@withContext false
        val remaining = current - requested
        if (remaining == current) return@withContext true
        if (remaining.isEmpty()) {
            uninstall(pkgName)
        } else {
            prefs.setExtensionSourceSelection(pkgName, remaining)
            reload()
            true
        }
    }

    private fun requiredContext(): Context =
        appContext ?: throw IllegalStateException("ExtensionManager başlatılmadı")
}
