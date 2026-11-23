package com.example.data.db

import android.graphics.Rect
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.service.TextBlockObj
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(
    tableName = "translated_page_cache",
    primaryKeys = ["chapterId", "pageIndex"],
    indices = [Index(value = ["sourceName"])]
)
data class TranslatedPageCacheEntity(
    val chapterId: String,
    val pageIndex: Int,
    val pageUrl: String,
    val sourceName: String,
    val blocksJson: String,
    val maskedPath: String,
    val byteSize: Long,
    val createdAt: Long = System.currentTimeMillis()
)

data class TranslationCacheStat(
    val sourceName: String,
    val pageCount: Int,
    val totalBytes: Long
)

@Dao
interface TranslationCacheDao {
    @Query("SELECT * FROM translated_page_cache WHERE chapterId = :chapterId AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getCachedPage(chapterId: String, pageIndex: Int): TranslatedPageCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: TranslatedPageCacheEntity)

    @Query(
        "SELECT sourceName, COUNT(*) AS pageCount, COALESCE(SUM(byteSize), 0) AS totalBytes " +
            "FROM translated_page_cache GROUP BY sourceName ORDER BY sourceName"
    )
    fun statsBySource(): Flow<List<TranslationCacheStat>>

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM translated_page_cache")
    suspend fun totalBytes(): Long

    @Query("SELECT maskedPath FROM translated_page_cache WHERE sourceName = :sourceName")
    suspend fun pathsBySource(sourceName: String): List<String>

    @Query("SELECT maskedPath FROM translated_page_cache")
    suspend fun allPaths(): List<String>

    @Query("DELETE FROM translated_page_cache WHERE sourceName = :sourceName")
    suspend fun deleteBySource(sourceName: String)

    @Query("DELETE FROM translated_page_cache")
    suspend fun deleteAll()

    @Query("SELECT * FROM translated_page_cache ORDER BY createdAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<TranslatedPageCacheEntity>

    @Query("SELECT * FROM translated_page_cache WHERE maskedPath LIKE '%' || :suffix ORDER BY createdAt ASC LIMIT :limit")
    suspend fun cachedPagesBySuffix(suffix: String, limit: Int): List<TranslatedPageCacheEntity>

    @Query("UPDATE translated_page_cache SET byteSize = :size WHERE chapterId = :chapterId AND pageIndex = :pageIndex")
    suspend fun updateByteSize(chapterId: String, pageIndex: Int, size: Long)

    @Query("UPDATE translated_page_cache SET maskedPath = :maskedPath, byteSize = :size WHERE chapterId = :chapterId AND pageIndex = :pageIndex")
    suspend fun updateMaskedPathAndSize(chapterId: String, pageIndex: Int, maskedPath: String, size: Long)

    @Query("DELETE FROM translated_page_cache WHERE chapterId = :chapterId AND pageIndex = :pageIndex")
    suspend fun deletePage(chapterId: String, pageIndex: Int)

    @Query("SELECT * FROM translated_page_cache WHERE createdAt < :cutoff")
    suspend fun expired(cutoff: Long): List<TranslatedPageCacheEntity>

    @Query("DELETE FROM translated_page_cache WHERE createdAt < :cutoff")
    suspend fun deleteExpired(cutoff: Long): Int

    @Query("UPDATE translated_page_cache SET createdAt = :accessedAt WHERE chapterId = :chapterId AND pageIndex = :pageIndex")
    suspend fun touchPage(chapterId: String, pageIndex: Int, accessedAt: Long)

    @Query("SELECT maskedPath FROM translated_page_cache WHERE substr(chapterId, 1, length(:chapterIdPrefix) + 1) = :chapterIdPrefix || '|'")
    suspend fun pathsByChapterPrefix(chapterIdPrefix: String): List<String>

    @Query("DELETE FROM translated_page_cache WHERE substr(chapterId, 1, length(:chapterIdPrefix) + 1) = :chapterIdPrefix || '|'")
    suspend fun deleteByChapterPrefix(chapterIdPrefix: String)
}

object TranslationCacheUrlMatcher {

    private val genericPageStemRegex = Regex(
        "^(page|p|img|image|ch|chapter|scan)?[_-]?\\d{1,5}$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Compares cached and current page URLs while tolerating rotating CDN query tokens
     * (e.g. `?token=...`, `?expires=...`) and transient mirror hosts on remote HTTP(S) URLs.
     * Generic filenames (`01.jpg`, `page_05.webp`) require the full URL path to match so
     * re-uploaded chapters under a different directory do not collide with stale cache entries.
     */
    fun matches(cachedUrl: String, currentUrl: String): Boolean {
        if (cachedUrl == currentUrl) return true
        val normA = stripQueryAndFragment(cachedUrl)
        val normB = stripQueryAndFragment(currentUrl)
        if (normA == normB) return true
        if (isRemoteHttp(normA) && isRemoteHttp(normB)) {
            val pathA = extractPath(normA)
            val pathB = extractPath(normB)
            if (pathA.isNotBlank() && pathA == pathB) return true
            val fileA = pathA.substringAfterLast('/')
            val fileB = pathB.substringAfterLast('/')
            if (fileA.isNotBlank() && fileA == fileB && !isGenericPageFileName(fileA)) {
                return true
            }
        }
        return false
    }

    internal fun isGenericPageFileName(fileName: String): Boolean {
        val stem = fileName.substringBeforeLast('.', fileName).trim()
        return stem.isEmpty() || genericPageStemRegex.matches(stem)
    }

    private fun extractPath(urlWithoutQuery: String): String {
        val afterScheme = urlWithoutQuery.substringAfter("://", "")
        val slashIdx = afterScheme.indexOf('/')
        return if (slashIdx >= 0) afterScheme.substring(slashIdx) else ""
    }

    /**
     * Strips query strings and fragments from remote HTTP(S) URLs before persisting to SQLite
     * so signed CDN tokens are not stored in plaintext or synced via cloud backup.
     */
    fun sanitizeForStorage(url: String): String =
        if (isRemoteHttp(url)) stripQueryAndFragment(url) else url

    private fun stripQueryAndFragment(url: String): String {
        val noHash = url.substringBefore('#')
        return noHash.substringBefore('?')
    }

    private fun isRemoteHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}

object TranslationCacheCodec {

    fun serializeBlocks(blocks: List<TextBlockObj>): String {
        val array = JSONArray()
        for (block in blocks) {
            val obj = JSONObject()
            obj.put("o", block.originalText)
            obj.put("tt", block.translatedText)
            obj.put("r", rectToJson(block.boundingBox))
            obj.put("bb", rectToJson(block.bubbleBox))
            obj.put("bu", if (block.isBubble) 1 else 0)
            obj.put("dk", if (block.isDarkBubble) 1 else 0)
            obj.put("nl", block.normalizedLeft.toDouble())
            obj.put("nt", block.normalizedTop.toDouble())
            obj.put("nw", block.normalizedWidth.toDouble())
            obj.put("nh", block.normalizedHeight.toDouble())
            block.backgroundColor?.let { obj.put("bg", it) }
            obj.put("lb", rectToJson(block.layoutBox))
            obj.put("ok", if (block.translationSucceeded) 1 else 0)
            obj.put("cw", block.coordinateImageWidth)
            obj.put("ch", block.coordinateImageHeight)
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializeBlocks(raw: String): List<TextBlockObj> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<TextBlockObj>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val rect = rectFromJson(obj.optJSONArray("r")) ?: continue
            val bubble = rectFromJson(obj.optJSONArray("bb")) ?: rect
            val layout = rectFromJson(obj.optJSONArray("lb")) ?: bubble
            out.add(
                TextBlockObj(
                    originalText = obj.optString("o", ""),
                    translatedText = obj.optString("tt", ""),
                    boundingBox = rect,
                    bubbleBox = bubble,
                    isBubble = obj.optInt("bu", 0) == 1,
                    isDarkBubble = obj.optInt("dk", 0) == 1,
                    normalizedLeft = obj.optDouble("nl", 0.0).toFloat(),
                    normalizedTop = obj.optDouble("nt", 0.0).toFloat(),
                    normalizedWidth = obj.optDouble("nw", 0.0).toFloat(),
                    normalizedHeight = obj.optDouble("nh", 0.0).toFloat(),
                    backgroundColor = if (obj.has("bg")) obj.optInt("bg") else null,
                    layoutBox = layout,
                    translationSucceeded = obj.optInt("ok", 1) == 1,
                    coordinateImageWidth = obj.optInt("cw", 0),
                    coordinateImageHeight = obj.optInt("ch", 0)
                )
            )
        }
        return out
    }

    private fun rectToJson(rect: Rect): JSONArray = JSONArray().apply {
        put(rect.left)
        put(rect.top)
        put(rect.right)
        put(rect.bottom)
    }

    private fun rectFromJson(array: JSONArray?): Rect? {
        if (array == null || array.length() < 4) return null
        return Rect(
            array.optInt(0, 0),
            array.optInt(1, 0),
            array.optInt(2, 0),
            array.optInt(3, 0)
        )
    }
}
