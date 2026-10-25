package com.example.data.pref

import android.content.Context
import android.content.SharedPreferences
import com.example.data.web.WebSite
import com.example.i18n.AppLanguages
import com.example.i18n.TranslationLanguages
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "manga_settings")

data class ReadingHistoryEntry(
    val mangaId: String,
    val title: String,
    val coverUrl: String,
    val sourceName: String,
    val chapterId: String,
    val chapterTitle: String,
    val pageIndex: Int,
    val totalPages: Int,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mangaId", mangaId)
        put("title", title)
        put("coverUrl", coverUrl)
        put("sourceName", sourceName)
        put("chapterId", chapterId)
        put("chapterTitle", chapterTitle)
        put("pageIndex", pageIndex)
        put("totalPages", totalPages)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): ReadingHistoryEntry? {
            val mangaId = obj.optString("mangaId")
            if (mangaId.isBlank()) return null
            return ReadingHistoryEntry(
                mangaId = mangaId,
                title = obj.optString("title"),
                coverUrl = obj.optString("coverUrl"),
                sourceName = obj.optString("sourceName"),
                chapterId = obj.optString("chapterId"),
                chapterTitle = obj.optString("chapterTitle"),
                pageIndex = obj.optInt("pageIndex"),
                totalPages = obj.optInt("totalPages"),
                updatedAt = obj.optLong("updatedAt")
            )
        }
    }
}

private fun parseExtensionSourceSelection(raw: String?): Map<String, Set<Long>> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = JSONObject(raw)
        buildMap {
            obj.keys().forEach { pkg ->
                val arr = obj.optJSONArray(pkg) ?: return@forEach
                val ids = buildSet {
                    for (i in 0 until arr.length()) {
                        val id = arr.optLong(i)
                        if (id > 0L) add(id)
                    }
                }
                if (ids.isNotEmpty()) put(pkg, ids)
            }
        }
    }.getOrDefault(emptyMap())
}

private fun encodeExtensionSourceSelection(selection: Map<String, Set<Long>>): String {
    val obj = JSONObject()
    selection.forEach { (pkg, ids) ->
        if (ids.isNotEmpty()) {
            val arr = JSONArray()
            ids.sorted().forEach { arr.put(it) }
            obj.put(pkg, arr)
        }
    }
    return obj.toString()
}

private fun parseHistory(raw: String?): List<ReadingHistoryEntry> {
    val value = raw.orEmpty()
    if (value.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(value)
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(ReadingHistoryEntry::fromJson) }
    }.getOrDefault(emptyList())
}

class MangaPreferences(private val context: Context) {
    companion object {
        val READING_MODE = stringPreferencesKey("reading_mode") // "Dikey", "Yatay", "Çift Sayfa"
        val PAGE_QUALITY = stringPreferencesKey("page_quality") // "Düşük", "Orta", "Yüksek"
        val BRIGHTNESS_LOCK = booleanPreferencesKey("brightness_lock")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val TRANSLATION_ENGINE = stringPreferencesKey("translation_engine") // "Otomatik", "Gemini", "DeepL", "ML Kit", "Google", "MyMemory"
        val TRANSLATION_TARGET_LANG = stringPreferencesKey("translation_target_lang")
        val TRANSLATION_SOURCE_LANG = stringPreferencesKey("translation_source_lang")
        val RECENT_TRANSLATION_LANGS = stringPreferencesKey("recent_translation_langs")
        val TRANSLATION_BUBBLE_STYLE = stringPreferencesKey("translation_bubble_style") // "Doğal Balon", "Alt Yazı (Şeffaf)", "Koyu Zemin"
        val TRANSLATION_OPACITY = intPreferencesKey("translation_opacity") // 0 - 100
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model") // "gemini-3.5-flash", "gemini-flash-latest"
        val DEEPL_API_KEY = stringPreferencesKey("deepl_api_key")
        val AUTO_TRANSLATE = booleanPreferencesKey("auto_translate")
        val TURKISH_CHAR_CONVERT = booleanPreferencesKey("turkish_char_convert")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location") // "Dahili", "Harici"
        val CACHE_SIZE_LIMIT = intPreferencesKey("cache_size_limit") // in MB
        val NOTIFICATION_NEW_CHAPTER = booleanPreferencesKey("notification_new_chapter")
        val UPDATE_CHECK_INTERVAL = intPreferencesKey("update_check_interval") // 6, 12, 24 saat
        val YOLO_AI_ENABLED = booleanPreferencesKey("yolo_ai_enabled")
        val CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val UI_LANGUAGE = stringPreferencesKey("ui_language")
        val HIDDEN_MANGA_IDS = stringSetPreferencesKey("hidden_manga_ids")
        val WEB_SITES = stringPreferencesKey("web_sites")
        val WEB_MANGA_PAGES = stringPreferencesKey("web_manga_pages")
        val PINNED_SOURCES = stringSetPreferencesKey("pinned_sources")
        val RECENT_SOURCES = stringPreferencesKey("recent_sources")
        val READING_HISTORY = stringPreferencesKey("reading_history")
        val EXTENSION_REPOS = stringSetPreferencesKey("extension_repos")
        val EXTENSION_SOURCE_SELECTION = stringPreferencesKey("extension_source_selection")
    }

    private val apiKeyRevision = MutableStateFlow(0)
    private val apiKeyStorageMutex = Mutex()

    val readingMode: Flow<String> = context.dataStore.data.map { it[READING_MODE] ?: "Dikey" }
    val pageQuality: Flow<String> = context.dataStore.data.map { it[PAGE_QUALITY] ?: "Orta" }
    val brightnessLock: Flow<Boolean> = context.dataStore.data.map { it[BRIGHTNESS_LOCK] ?: false }
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[KEEP_SCREEN_ON] ?: true }
    val translationEngine: Flow<String> = context.dataStore.data.map { it[TRANSLATION_ENGINE] ?: "Otomatik" }
    val translationTargetLang: Flow<String> = context.dataStore.data.map {
        it[TRANSLATION_TARGET_LANG] ?: TranslationLanguages.defaultTargetCode()
    }
    val translationSourceLang: Flow<String> = context.dataStore.data.map {
        it[TRANSLATION_SOURCE_LANG] ?: TranslationLanguages.AUTO
    }
    val recentTranslationLangs: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[RECENT_TRANSLATION_LANGS].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
    val translationBubbleStyle: Flow<String> = context.dataStore.data.map { it[TRANSLATION_BUBBLE_STYLE] ?: "Doğal Balon" }
    val translationOpacity: Flow<Int> = context.dataStore.data.map { it[TRANSLATION_OPACITY] ?: 90 }
    val geminiApiKey: Flow<String> = apiKeyFlow("gemini_api_key", GEMINI_API_KEY)
    val geminiModel: Flow<String> = context.dataStore.data.map { it[GEMINI_MODEL] ?: "gemini-flash-latest" }
    val deeplApiKey: Flow<String> = apiKeyFlow("deepl_api_key", DEEPL_API_KEY)
    val autoTranslate: Flow<Boolean> = context.dataStore.data.map { it[AUTO_TRANSLATE] ?: false }
    val turkishCharConvert: Flow<Boolean> = context.dataStore.data.map { it[TURKISH_CHAR_CONVERT] ?: false }
    val downloadLocation: Flow<String> = context.dataStore.data.map { it[DOWNLOAD_LOCATION] ?: "Dahili" }
    val cacheSizeLimit: Flow<Int> = context.dataStore.data.map { it[CACHE_SIZE_LIMIT] ?: 500 }
    val notificationNewChapter: Flow<Boolean> = context.dataStore.data.map { it[NOTIFICATION_NEW_CHAPTER] ?: true }
    val updateCheckInterval: Flow<Int> = context.dataStore.data.map { it[UPDATE_CHECK_INTERVAL] ?: 12 }
    val yoloAiEnabled: Flow<Boolean> = context.dataStore.data.map { it[YOLO_AI_ENABLED] ?: true }
    val confidenceThreshold: Flow<Float> = context.dataStore.data.map { it[CONFIDENCE_THRESHOLD] ?: 0.35f }
    val debugMode: Flow<Boolean> = context.dataStore.data.map { it[DEBUG_MODE] ?: false }
    val uiLanguage: Flow<String> = context.dataStore.data.map { it[UI_LANGUAGE] ?: AppLanguages.SYSTEM }
    val hiddenMangaIds: Flow<Set<String>> = context.dataStore.data.map { it[HIDDEN_MANGA_IDS] ?: emptySet() }
    val pinnedSources: Flow<Set<String>> = context.dataStore.data.map { it[PINNED_SOURCES] ?: emptySet() }
    val extensionRepos: Flow<Set<String>> = context.dataStore.data.map { it[EXTENSION_REPOS] ?: emptySet() }
    val extensionSourceSelection: Flow<Map<String, Set<Long>>> = context.dataStore.data.map {
        parseExtensionSourceSelection(it[EXTENSION_SOURCE_SELECTION])
    }
    val recentSources: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[RECENT_SOURCES].orEmpty()
        if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }
    }
    val readingHistory: Flow<List<ReadingHistoryEntry>> = context.dataStore.data.map { parseHistory(it[READING_HISTORY]) }
    val webSites: Flow<List<WebSite>> = context.dataStore.data.map { WebSite.parseList(it[WEB_SITES]) }
    val webMangaPages: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[WEB_MANGA_PAGES].orEmpty()
        if (raw.isBlank()) emptyMap()
        else runCatching {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.optString(key) }
            map
        }.getOrDefault(emptyMap())
    }

    suspend fun setReadingMode(value: String) {
        context.dataStore.edit { it[READING_MODE] = value }
    }

    suspend fun setPageQuality(value: String) {
        context.dataStore.edit { it[PAGE_QUALITY] = value }
    }

    suspend fun setBrightnessLock(value: Boolean) {
        context.dataStore.edit { it[BRIGHTNESS_LOCK] = value }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = value }
    }

    suspend fun setTranslationEngine(value: String) {
        context.dataStore.edit { it[TRANSLATION_ENGINE] = value }
    }

    suspend fun setTranslationBubbleStyle(value: String) {
        context.dataStore.edit { it[TRANSLATION_BUBBLE_STYLE] = value }
    }

    suspend fun setTranslationOpacity(value: Int) {
        context.dataStore.edit { it[TRANSLATION_OPACITY] = value }
    }

    suspend fun setGeminiApiKey(value: String): Boolean = saveApiKey("gemini_api_key", GEMINI_API_KEY, value)

    suspend fun setGeminiModel(value: String) {
        context.dataStore.edit { it[GEMINI_MODEL] = value }
    }

    suspend fun setDeepLApiKey(value: String): Boolean = saveApiKey("deepl_api_key", DEEPL_API_KEY, value)

    suspend fun setAutoTranslate(value: Boolean) {
        context.dataStore.edit { it[AUTO_TRANSLATE] = value }
    }

    suspend fun setTurkishCharConvert(value: Boolean) {
        context.dataStore.edit { it[TURKISH_CHAR_CONVERT] = value }
    }

    suspend fun setTranslationTargetLang(code: String) {
        context.dataStore.edit { it[TRANSLATION_TARGET_LANG] = code }
    }

    suspend fun setTranslationSourceLang(code: String) {
        context.dataStore.edit { it[TRANSLATION_SOURCE_LANG] = code }
    }

    suspend fun addRecentTranslationLang(code: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[RECENT_TRANSLATION_LANGS].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            prefs[RECENT_TRANSLATION_LANGS] =
                (listOf(code) + current.filterNot { it == code }).take(6).joinToString(",")
        }
    }

    suspend fun setDownloadLocation(value: String) {
        context.dataStore.edit { it[DOWNLOAD_LOCATION] = value }
    }

    suspend fun setCacheSizeLimit(value: Int) {
        context.dataStore.edit { it[CACHE_SIZE_LIMIT] = value }
    }

    suspend fun setNotificationNewChapter(value: Boolean) {
        context.dataStore.edit { it[NOTIFICATION_NEW_CHAPTER] = value }
    }

    suspend fun setUpdateCheckInterval(value: Int) {
        context.dataStore.edit { it[UPDATE_CHECK_INTERVAL] = value }
    }

    suspend fun addExtensionRepo(url: String) {
        context.dataStore.edit { prefs ->
            prefs[EXTENSION_REPOS] = (prefs[EXTENSION_REPOS] ?: emptySet()) + url
        }
    }

    suspend fun removeExtensionRepo(url: String) {
        context.dataStore.edit { prefs ->
            prefs[EXTENSION_REPOS] = (prefs[EXTENSION_REPOS] ?: emptySet()) - url
        }
    }

    suspend fun setExtensionSourceSelection(pkgName: String, sourceIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            val selection = parseExtensionSourceSelection(prefs[EXTENSION_SOURCE_SELECTION]).toMutableMap()
            selection[pkgName] = sourceIds
            prefs[EXTENSION_SOURCE_SELECTION] = encodeExtensionSourceSelection(selection)
        }
    }

    suspend fun setExtensionSourceSelectionMap(selection: Map<String, Set<Long>>) {
        context.dataStore.edit { prefs ->
            prefs[EXTENSION_SOURCE_SELECTION] = encodeExtensionSourceSelection(selection)
        }
    }

    suspend fun clearExtensionSourceSelection(pkgName: String) {
        context.dataStore.edit { prefs ->
            val selection = parseExtensionSourceSelection(prefs[EXTENSION_SOURCE_SELECTION]).toMutableMap()
            if (selection.remove(pkgName) != null) {
                prefs[EXTENSION_SOURCE_SELECTION] = encodeExtensionSourceSelection(selection)
            }
        }
    }

    suspend fun setYoloAiEnabled(value: Boolean) {
        context.dataStore.edit { it[YOLO_AI_ENABLED] = value }
    }

    suspend fun setConfidenceThreshold(value: Float) {
        context.dataStore.edit { it[CONFIDENCE_THRESHOLD] = value }
    }

    suspend fun setDebugMode(value: Boolean) {
        context.dataStore.edit { it[DEBUG_MODE] = value }
    }

    suspend fun setUiLanguage(value: String) {
        context.dataStore.edit { it[UI_LANGUAGE] = value }
    }

    suspend fun setHiddenMangaIds(value: Set<String>) {
        context.dataStore.edit { it[HIDDEN_MANGA_IDS] = value.toSet() }
    }

    suspend fun setPinnedSources(value: Set<String>) {
        context.dataStore.edit { it[PINNED_SOURCES] = value.toSet() }
    }

    suspend fun setRecentSources(value: List<String>) {
        context.dataStore.edit { it[RECENT_SOURCES] = JSONArray(value).toString() }
    }

    suspend fun upsertHistoryEntry(entry: ReadingHistoryEntry) {
        context.dataStore.edit { prefs ->
            val existing = parseHistory(prefs[READING_HISTORY])
            val updated = (listOf(entry) + existing.filterNot { it.mangaId == entry.mangaId }).take(100)
            prefs[READING_HISTORY] = JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString()
        }
    }

    suspend fun removeHistoryEntry(mangaId: String) {
        context.dataStore.edit { prefs ->
            val updated = parseHistory(prefs[READING_HISTORY]).filterNot { it.mangaId == mangaId }
            if (updated.isEmpty()) {
                prefs.remove(READING_HISTORY)
            } else {
                prefs[READING_HISTORY] = JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString()
            }
        }
    }

    suspend fun replaceHistoryEntries(
        sourceMangaIds: Set<String>,
        target: ReadingHistoryEntry?
    ) {
        if (sourceMangaIds.isEmpty()) return
        context.dataStore.edit { prefs ->
            val existing = parseHistory(prefs[READING_HISTORY])
            val updated = buildList {
                if (target != null) add(target)
                addAll(
                    existing.filterNot {
                        it.mangaId in sourceMangaIds || (target != null && it.mangaId == target.mangaId)
                    }
                )
            }.take(100)
            if (updated.isEmpty()) {
                prefs.remove(READING_HISTORY)
            } else {
                prefs[READING_HISTORY] = JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString()
            }
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { it.remove(READING_HISTORY) }
    }

    suspend fun setWebSites(sites: List<WebSite>) {
        context.dataStore.edit { it[WEB_SITES] = WebSite.encodeList(sites) }
    }

    suspend fun setWebMangaPage(mangaId: String, pageUrl: String) {
        context.dataStore.edit { prefs ->
            val json = runCatching {
                JSONObject(prefs[WEB_MANGA_PAGES].orEmpty().ifBlank { "{}" })
            }.getOrDefault(JSONObject())
            json.put(mangaId, pageUrl)
            prefs[WEB_MANGA_PAGES] = json.toString()
        }
    }

    /** API keys are stored only in EncryptedSharedPreferences backed by Android Keystore. */
    private val securePrefs: SharedPreferences by lazy {
        val appCtx = context.applicationContext
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "secure_api_keys",
            masterKeyAlias,
            appCtx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun apiKeyFlow(key: String, legacyKey: Preferences.Key<String>): Flow<String> =
        combine(context.dataStore.data, apiKeyRevision) { data, _ ->
            apiKeyStorageMutex.lock()
            try {
                val encryptedValue = secureGet(key)
                if (encryptedValue != null) {
                    if (data[legacyKey] != null) {
                        runCatching { context.dataStore.edit { it.remove(legacyKey) } }
                    }
                    encryptedValue
                } else {
                    val legacyValue = data[legacyKey].orEmpty()
                    if (legacyValue.isBlank()) {
                        ""
                    } else {
                        val migrated = try {
                            securePut(key, legacyValue)
                            true
                        } catch (_: Exception) {
                            false
                        }
                        // Never use or retain an unencrypted key when Keystore storage is unavailable.
                        try {
                            context.dataStore.edit { it.remove(legacyKey) }
                        } catch (_: Exception) {
                        }
                        if (migrated) legacyValue else ""
                    }
                }
            } finally {
                apiKeyStorageMutex.unlock()
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun saveApiKey(key: String, legacyKey: Preferences.Key<String>, value: String): Boolean =
        withContext(Dispatchers.IO) {
            apiKeyStorageMutex.lock()
            try {
                securePut(key, value)
                context.dataStore.edit { it.remove(legacyKey) }
                apiKeyRevision.update { it + 1 }
                true
            } catch (_: Exception) {
                false
            } finally {
                apiKeyStorageMutex.unlock()
            }
        }

    private fun secureGet(key: String): String? {
        val encryptedPrefs = try {
            securePrefs
        } catch (_: Exception) {
            discardPlaintextFallback()
            return null
        }
        val encryptedValue = runCatching { encryptedPrefs.getString(key, null) }.getOrNull()?.ifBlank { null }
        if (encryptedValue != null) return encryptedValue

        val fallbackPrefs = context.applicationContext
            .getSharedPreferences("secure_api_keys_fallback", Context.MODE_PRIVATE)
        val legacyValue = runCatching { fallbackPrefs.getString(key, null) }.getOrNull()?.ifBlank { null }
            ?: return null
        val migrated = runCatching {
            encryptedPrefs.edit().putString(key, legacyValue).commit()
        }.getOrDefault(false)
        val cleared = fallbackPrefs.edit().remove(key).commit()
        if (!cleared) discardPlaintextFallback()
        return legacyValue.takeIf { migrated }
    }

    private fun securePut(key: String, value: String) {
        val editor = securePrefs.edit()
        if (value.isBlank()) editor.remove(key) else editor.putString(key, value)
        check(editor.commit()) { "Encrypted API key could not be saved" }
    }

    private fun discardPlaintextFallback() {
        runCatching { context.applicationContext.deleteSharedPreferences("secure_api_keys_fallback") }
    }
}
