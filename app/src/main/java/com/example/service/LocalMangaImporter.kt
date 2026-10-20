package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.data.db.ChapterDao
import com.example.data.db.ChapterEntity
import com.example.data.db.MangaDao
import com.example.data.db.MangaEntity
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class LocalImportResult(
    val success: Boolean,
    val message: String,
    val mangaId: String? = null,
    val mangaTitle: String? = null,
    val pageCount: Int = 0,
    val chapterCount: Int = 0
)

object NaturalOrderComparator : Comparator<String> {
    private val regex = Regex("(\\d+|\\D+)")

    override fun compare(s1: String, s2: String): Int {
        val tokens1 = regex.findAll(s1).map { it.value }.toList()
        val tokens2 = regex.findAll(s2).map { it.value }.toList()
        val minSize = minOf(tokens1.size, tokens2.size)
        for (i in 0 until minSize) {
            val t1 = tokens1[i]
            val t2 = tokens2[i]
            val isDigit1 = t1.all { it.isDigit() }
            val isDigit2 = t2.all { it.isDigit() }
            if (isDigit1 && isDigit2) {
                val num1 = t1.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                val num2 = t2.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                val cmp = num1.compareTo(num2)
                if (cmp != 0) return cmp
            } else {
                val cmp = t1.compareTo(t2, ignoreCase = true)
                if (cmp != 0) return cmp
            }
        }
        return tokens1.size.compareTo(tokens2.size)
    }
}

class LocalMangaImporter(
    private val context: Context,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao
) {
    companion object {
        private const val TAG = "LocalMangaImporter"

        fun isImageFileName(name: String): Boolean {
            val lower = name.lowercase().trim()
            val fileName = File(lower).name
            if (fileName.startsWith(".") || lower.contains("__macosx")) return false
            return lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") ||
                    lower.endsWith(".webp") ||
                    lower.endsWith(".bmp") ||
                    lower.endsWith(".gif")
        }
    }

    private fun getFileName(uri: Uri): String {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = it.getString(idx)
                        if (!name.isNullOrBlank() && !name.equals("null", ignoreCase = true)) {
                            return name
                        }
                    }
                }
            }
            val last = uri.lastPathSegment
            if (!last.isNullOrBlank() && !last.equals("null", ignoreCase = true)) {
                java.net.URLDecoder.decode(last, "UTF-8")
            } else {
                "Yerel_Manga"
            }
        } catch (e: Exception) {
            "Yerel_Manga"
        }
    }

    private fun cleanTitle(rawName: String): String {
        return rawName
            .substringBeforeLast(".")
            .replace("_", " ")
            .replace("-", " ")
            .replace(".", " ")
            .trim()
            .ifBlank { "Yerel Manga" }
    }

    private fun safeStorageSegment(rawName: String, addCollisionSuffix: Boolean = false): String {
        val leafName = rawName.replace('\\', '/').substringAfterLast('/')
        val cleaned = leafName
            .replace(Regex("[\\u0000-\\u001F<>:\"|?*]"), "_")
            .trim()
            .trimEnd('.', ' ')
            .take(100)
            .ifBlank { "_" }
        return if (addCollisionSuffix && cleaned != leafName) {
            "${cleaned.take(88)}_${Integer.toHexString(leafName.hashCode())}"
        } else {
            cleaned
        }
    }

    private fun detectFormat(file: File): String {
        if (!file.exists() || file.length() < 4) return "unknown"
        val bytes = ByteArray(8)
        try {
            file.inputStream().use { it.read(bytes) }
        } catch (e: Exception) {
            return "unknown"
        }

        // ZIP / CBZ: PK (0x50 0x4B)
        if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return "zip"
        // RAR / CBR: Rar! (0x52 0x61 0x72 0x21)
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x61.toByte() && bytes[2] == 0x72.toByte() && bytes[3] == 0x21.toByte()) {
            return if (bytes.size >= 8 && bytes[4] == 0x1A.toByte() && bytes[5] == 0x07.toByte() && bytes[6] == 0x01.toByte()) {
                "rar5"
            } else {
                "rar"
            }
        }
        // PDF: %PDF (0x25 0x50 0x44 0x46)
        if (bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()) return "pdf"
        // JPEG: 0xFF 0xD8 0xFF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "image"
        // PNG: 0x89 PNG
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return "image"
        // WEBP: RIFF...
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()) return "image"

        val name = file.name.lowercase()
        return when {
            name.endsWith(".cbz") || name.endsWith(".zip") -> "zip"
            name.endsWith(".cbr") || name.endsWith(".rar") -> "rar"
            name.endsWith(".pdf") -> "pdf"
            isImageFileName(name) -> "image"
            else -> "unknown"
        }
    }

    suspend fun importArchiveOrDocument(uri: Uri, onProgress: (String) -> Unit = {}): LocalImportResult = withContext(Dispatchers.IO) {
        val rawFileName = getFileName(uri)
        val title = cleanTitle(rawFileName)
        val importId = "local_manga_${System.currentTimeMillis()}"
        val baseImportDir = File(context.filesDir, "local_imports/$importId").apply { mkdirs() }
        val tempFile = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.tmp")

        try {
            onProgress("Dosya okunuyor...")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { out ->
                    input.copyTo(out)
                }
            } ?: return@withContext LocalImportResult(false, "Dosyaya erişilemedi veya izin reddedildi.")

            if (!tempFile.exists() || tempFile.length() == 0L) {
                return@withContext LocalImportResult(false, "Seçilen dosya boş veya okunamadı.")
            }

            val format = detectFormat(tempFile)
            Log.d(TAG, "Importing file: $rawFileName, size: ${tempFile.length()}, detected format: $format")

            val chapterPagesMap = mutableMapOf<String, MutableList<File>>()
            when (format) {
                "zip" -> {
                    onProgress("Arşiv taranıyor ve ayıklanıyor...")
                    extractZip(tempFile, baseImportDir, chapterPagesMap, onProgress)
                }
                "rar" -> {
                    onProgress("RAR / CBR arşivi ayıklanıyor...")
                    extractRar(tempFile, baseImportDir, chapterPagesMap, onProgress)
                }
                "rar5" -> {
                    baseImportDir.deleteRecursively()
                    return@withContext LocalImportResult(
                        false,
                        "Bu arşiv RAR5 biçiminde. Uygulama yalnızca RAR4 açabiliyor; dosyayı CBZ/ZIP olarak yeniden kaydedip tekrar deneyin."
                    )
                }
                "pdf" -> {
                    onProgress("PDF sayfaları yüksek kalitede işleniyor...")
                    extractPdf(tempFile, baseImportDir, chapterPagesMap, onProgress)
                }
                "image" -> {
                    onProgress("Resim aktarılıyor...")
                    val destFile = File(baseImportDir, "page_0001_${tempFile.name}.jpg")
                    tempFile.copyTo(destFile, overwrite = true)
                    chapterPagesMap.getOrPut("Bölüm 1") { mutableListOf() }.add(destFile)
                }
                else -> {
                    onProgress("Dosya biçimi çözümleniyor...")
                    val extracted = try {
                        extractZip(tempFile, baseImportDir, chapterPagesMap, onProgress)
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (!extracted || chapterPagesMap.isEmpty()) {
                        try {
                            extractRar(tempFile, baseImportDir, chapterPagesMap, onProgress)
                        } catch (e2: Exception) {
                            Log.w(TAG, "Fallback extraction failed", e2)
                        }
                    }
                }
            }

            val totalExtracted = chapterPagesMap.values.sumOf { it.size }
            if (totalExtracted == 0) {
                baseImportDir.deleteRecursively()
                return@withContext LocalImportResult(
                    false,
                    "Arşivde veya belgede okunabilir resim (.jpg, .png, .webp) bulunamadı."
                )
            }

            onProgress("Veritabanına kaydediliyor...")
            val result = saveMangaAndChapters(importId, title, chapterPagesMap)
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Archive import error", e)
            baseImportDir.deleteRecursively()
            return@withContext LocalImportResult(false, "İçe aktarma hatası: ${e.localizedMessage ?: e.message}")
        } finally {
            tempFile.delete()
        }
    }

    private fun archiveEntryDestination(rawEntryName: String, baseDir: File, counter: Int): Pair<String, File> {
        val normalized = rawEntryName.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw IOException("Absolute archive path is not allowed: $rawEntryName")
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) {
            throw IOException("Unsafe archive path: $rawEntryName")
        }

        fun safeSegment(segment: String): String {
            val cleaned = segment.replace(Regex("""[\u0000-\u001F<>:"|?*]"""), "_")
                .trim().trimEnd('.', ' ')
                .ifBlank { "_" }
            val bounded = cleaned.take(120)
            return if (bounded != segment) "${bounded}_${Integer.toHexString(segment.hashCode())}" else bounded
        }

        val parentSegments = segments.dropLast(1)
        val chapterKey = parentSegments.joinToString("/").ifBlank { "Bölüm 1" }
        val safeParents = parentSegments.map(::safeSegment)
        val safeName = safeSegment(segments.last())
        val baseCanonical = baseDir.canonicalFile
        val chapterDir = File(baseCanonical, (safeParents.ifEmpty { listOf("Bölüm 1") }).joinToString(File.separator)).canonicalFile
        val allowedPrefix = baseCanonical.path.trimEnd(File.separatorChar) + File.separator
        if (!chapterDir.path.startsWith(allowedPrefix)) {
            throw IOException("Archive entry escaped extraction directory: $rawEntryName")
        }
        if (!chapterDir.exists() && !chapterDir.mkdirs()) {
            throw IOException("Could not create extraction directory: ${chapterDir.path}")
        }
        val destFile = File(chapterDir, "p_${String.format("%04d", counter)}_$safeName").canonicalFile
        if (!destFile.path.startsWith(allowedPrefix)) {
            throw IOException("Archive entry escaped extraction directory: $rawEntryName")
        }
        return chapterKey to destFile
    }

    private fun extractZip(
        zipFileOnDisk: File,
        baseDir: File,
        chapterPagesMap: MutableMap<String, MutableList<File>>,
        onProgress: (String) -> Unit
    ) {
        var zip: ZipFile? = null
        try {
            zip = ZipFile(zipFileOnDisk, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            try {
                zip = ZipFile(zipFileOnDisk, Charset.forName("CP437"))
            } catch (e2: Exception) {
                try {
                    zip = ZipFile(zipFileOnDisk, StandardCharsets.ISO_8859_1)
                } catch (e3: Exception) {
                    Log.w(TAG, "ZipFile constructor failed, falling back to stream", e3)
                }
            }
        }

        if (zip != null) {
            zip.use { archive ->
                val entries = archive.entries().asSequence()
                    .filter { !it.isDirectory && isImageFileName(it.name) }
                    .sortedWith { a, b -> NaturalOrderComparator.compare(a.name, b.name) }
                    .toList()
                var counter = 1
                entries.forEach { entry ->
                    val (chapterKey, destFile) = archiveEntryDestination(entry.name, baseDir, counter)
                    archive.getInputStream(entry).use { inStream ->
                        FileOutputStream(destFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    chapterPagesMap.getOrPut(chapterKey) { mutableListOf() }.add(destFile)
                    counter++
                }
            }
        } else {
            ZipInputStream(zipFileOnDisk.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                var counter = 1
                val buf = ByteArray(8192)
                while (entry != null) {
                    if (!entry.isDirectory && isImageFileName(entry.name)) {
                        val (chapterKey, destFile) = archiveEntryDestination(entry.name, baseDir, counter)
                        FileOutputStream(destFile).use { fos ->
                            var len: Int
                            while (zis.read(buf).also { len = it } > 0) {
                                fos.write(buf, 0, len)
                            }
                        }
                        chapterPagesMap.getOrPut(chapterKey) { mutableListOf() }.add(destFile)
                        counter++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun extractRar(
        rarFile: File,
        baseDir: File,
        chapterPagesMap: MutableMap<String, MutableList<File>>,
        onProgress: (String) -> Unit
    ) {
        Archive(rarFile).use { archive ->
            val headers = archive.fileHeaders
                .filter { !it.isDirectory && isImageFileName(it.fileName) }
                .sortedWith { a, b -> NaturalOrderComparator.compare(a.fileName, b.fileName) }
            var counter = 1
            headers.forEach { header ->
                val (chapterKey, destFile) = archiveEntryDestination(header.fileName, baseDir, counter)
                FileOutputStream(destFile).use { fos ->
                    archive.extractFile(header, fos)
                }
                chapterPagesMap.getOrPut(chapterKey) { mutableListOf() }.add(destFile)
                counter++
            }
        }
    }

    private fun extractPdf(
        pdfFile: File,
        baseDir: File,
        chapterPagesMap: MutableMap<String, MutableList<File>>,
        onProgress: (String) -> Unit
    ) {
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY) ?: return
        pfd.use { fd ->
            val renderer = PdfRenderer(fd)
            try {
                val chapterDir = File(baseDir, "Bölüm_1").apply { mkdirs() }
                val pageList = mutableListOf<File>()
                val pageCount = renderer.pageCount
                for (i in 0 until pageCount) {
                    onProgress("PDF işleniyor: ${i + 1} / $pageCount sayfa")
                    val page = renderer.openPage(i)
                    var bitmap: Bitmap? = null
                    try {
                        val maxDim = 2048
                        val maxPageDim = maxOf(page.width, page.height)
                        val scale = if (maxPageDim * 2 > maxDim) {
                            (maxDim.toFloat() / maxPageDim.toFloat()).coerceAtLeast(1.0f)
                        } else {
                            2.0f
                        }
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val pageFile = File(chapterDir, "p_${String.format("%04d", i + 1)}.jpg")
                        FileOutputStream(pageFile).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                        }
                        pageList.add(pageFile)
                    } catch (oom: OutOfMemoryError) {
                        try {
                            bitmap?.recycle()
                            val lowScale = 1.0f
                            val lowW = page.width
                            val lowH = page.height
                            val lowBmp = Bitmap.createBitmap(lowW, lowH, Bitmap.Config.RGB_565)
                            lowBmp.eraseColor(Color.WHITE)
                            page.render(lowBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pageFile = File(chapterDir, "p_${String.format("%04d", i + 1)}.jpg")
                            FileOutputStream(pageFile).use { fos ->
                                lowBmp.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                            }
                            lowBmp.recycle()
                            pageList.add(pageFile)
                        } catch (_: Throwable) {
                            // Skip page if device has critically low RAM
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error rendering PDF page $i", e)
                    } finally {
                        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                        page.close()
                    }
                }
                chapterPagesMap["Bölüm 1"] = pageList
            } finally {
                renderer.close()
            }
        }
    }

    suspend fun importFolderTree(treeUri: Uri, onProgress: (String) -> Unit = {}): LocalImportResult = withContext(Dispatchers.IO) {
        val treeDir = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext LocalImportResult(false, "Klasöre erişilemedi.")
        val rawTitle = treeDir.name ?: "Yerel Manga"
        val title = cleanTitle(rawTitle)
        val importId = "local_folder_${System.currentTimeMillis()}"
        val baseImportDir = File(context.filesDir, "local_imports/$importId").apply { mkdirs() }

        try {
            onProgress("Klasör taranıyor...")
            val chapterPagesMap = mutableMapOf<String, MutableList<File>>()
            val children = treeDir.listFiles()
            val subFolders = children.filter { it.isDirectory }
            val directImages = children.filter { it.isFile && isImageFileName(it.name ?: "") }
                .sortedWith { a, b -> NaturalOrderComparator.compare(a.name ?: "", b.name ?: "") }

            if (directImages.isNotEmpty()) {
                val chapterDir = File(baseImportDir, "Bölüm_1").apply { mkdirs() }
                val pageList = mutableListOf<File>()
                directImages.forEachIndexed { index, docFile ->
                    val safeName = "p_${String.format("%04d", index + 1)}_${safeStorageSegment(docFile.name ?: "page.jpg")}"
                    val destFile = File(chapterDir, safeName)
                    context.contentResolver.openInputStream(docFile.uri)?.use { inStream ->
                        FileOutputStream(destFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    if (destFile.exists() && destFile.length() > 0) {
                        pageList.add(destFile)
                    }
                }
                if (pageList.isNotEmpty()) {
                    chapterPagesMap["Bölüm 1"] = pageList
                }
            }

            subFolders.forEach { folder ->
                val folderName = folder.name?.trim() ?: "Bölüm"
                val images = folder.listFiles()
                    .filter { it.isFile && isImageFileName(it.name ?: "") }
                    .sortedWith { a, b -> NaturalOrderComparator.compare(a.name ?: "", b.name ?: "") }
                if (images.isNotEmpty()) {
                    val chapterDir = File(baseImportDir, safeStorageSegment(folderName, addCollisionSuffix = true)).apply { mkdirs() }
                    val pageList = mutableListOf<File>()
                    images.forEachIndexed { index, docFile ->
                        val safeName = "p_${String.format("%04d", index + 1)}_${safeStorageSegment(docFile.name ?: "page.jpg")}"
                        val destFile = File(chapterDir, safeName)
                        context.contentResolver.openInputStream(docFile.uri)?.use { inStream ->
                            FileOutputStream(destFile).use { outStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        if (destFile.exists() && destFile.length() > 0) {
                            pageList.add(destFile)
                        }
                    }
                    if (pageList.isNotEmpty()) {
                        chapterPagesMap[folderName] = pageList
                    }
                }
            }

            val totalCount = chapterPagesMap.values.sumOf { it.size }
            if (totalCount == 0) {
                baseImportDir.deleteRecursively()
                return@withContext LocalImportResult(
                    false,
                    "Seçilen klasörde ve alt klasörlerinde hiç resim dosyası (.jpg, .png, .webp) bulunamadı."
                )
            }

            val result = saveMangaAndChapters(importId, title, chapterPagesMap)
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Folder import error", e)
            baseImportDir.deleteRecursively()
            return@withContext LocalImportResult(false, "Klasör içe aktarma hatası: ${e.localizedMessage ?: e.message}")
        }
    }

    suspend fun importMultipleImages(
        uris: List<Uri>,
        customTitle: String? = null,
        onProgress: (String) -> Unit = {}
    ): LocalImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext LocalImportResult(false, "Hiç resim seçilmedi.")
        val firstFileName = getFileName(uris.first())
        val title = customTitle?.trim()?.ifBlank { null } ?: cleanTitle(firstFileName)
        val importId = "local_images_${System.currentTimeMillis()}"
        val baseImportDir = File(context.filesDir, "local_imports/$importId/Bölüm_1").apply { mkdirs() }

        try {
            onProgress("Resimler kopyalanıyor ve sıralanıyor...")
            val uriWithNames = uris.map { uri ->
                Pair(uri, getFileName(uri))
            }.sortedWith { a, b ->
                NaturalOrderComparator.compare(a.second, b.second)
            }
            val savedFiles = mutableListOf<File>()
            uriWithNames.forEachIndexed { index, (uri, originalName) ->
                val safeName = "p_${String.format("%04d", index + 1)}_${safeStorageSegment(originalName)}"
                val destFile = File(baseImportDir, safeName)
                context.contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(destFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                if (destFile.exists() && destFile.length() > 0) {
                    savedFiles.add(destFile)
                }
            }

            if (savedFiles.isEmpty()) {
                baseImportDir.parentFile?.deleteRecursively()
                return@withContext LocalImportResult(false, "Resimler aktarılamadı.")
            }

            val chapterPagesMap = mapOf("Bölüm 1" to savedFiles)
            return@withContext saveMangaAndChapters(importId, title, chapterPagesMap)
        } catch (e: Exception) {
            Log.e(TAG, "Multi images import error", e)
            baseImportDir.parentFile?.deleteRecursively()
            return@withContext LocalImportResult(false, "Resim içe aktarma hatası: ${e.localizedMessage ?: e.message}")
        }
    }

    private suspend fun saveMangaAndChapters(
        mangaId: String,
        mangaTitle: String,
        chapterPagesMap: Map<String, List<File>>
    ): LocalImportResult {
        val sortedChapters = chapterPagesMap.entries.sortedWith { a, b ->
            NaturalOrderComparator.compare(a.key, b.key)
        }
        val totalPages = sortedChapters.sumOf { it.value.size }
        val firstChapterFiles = sortedChapters.firstOrNull()?.value ?: emptyList()
        val coverFile = firstChapterFiles.firstOrNull()
        val coverUrl = if (coverFile != null) "file://${coverFile.absolutePath}" else ""

        val mangaEntity = MangaEntity(
            id = mangaId,
            title = mangaTitle,
            coverUrl = coverUrl,
            author = "Cihaz Arşivi",
            artist = "Yerel",
            description = "Cihazınızdan aktarılan yerel manga (${sortedChapters.size} bölüm, $totalPages sayfa).",
            status = "Tamamlandı",
            rating = 5.0,
            sourceName = "Cihaz / Yerel Mangalar",
            originLanguage = "Yerel",
            availableLanguages = "Yerel, TR",
            isFavorite = true,
            addedAt = System.currentTimeMillis()
        )
        mangaDao.insertManga(mangaEntity)

        val chapterEntities = sortedChapters.mapIndexed { index, entry ->
            val sortedPages = entry.value.sortedWith { a, b ->
                NaturalOrderComparator.compare(a.name, b.name)
            }
            val paths = sortedPages.map { "file://${it.absolutePath}" }
            val chapterNum = (index + 1).toDouble()
            ChapterEntity(
                id = "${mangaId}_ch_${index + 1}",
                mangaId = mangaId,
                title = "$mangaTitle - ${entry.key}",
                chapterNumber = chapterNum,
                uploadDate = "Bugün",
                isRead = false,
                readPage = 0,
                isDownloaded = true,
                localImagePathList = com.example.data.db.PagePathCodec.encode(paths),
                totalPages = paths.size,
                language = "tr"
            )
        }
        chapterDao.insertChapters(chapterEntities)

        val chCount = sortedChapters.size
        val summaryMsg = if (chCount > 1) {
            "'$mangaTitle' ($chCount bölüm, $totalPages sayfa) başarıyla kütüphanenize eklendi!"
        } else {
            "'$mangaTitle' ($totalPages sayfa) başarıyla kütüphanenize eklendi!"
        }

        return LocalImportResult(
            success = true,
            message = summaryMsg,
            mangaId = mangaId,
            mangaTitle = mangaTitle,
            pageCount = totalPages,
            chapterCount = chCount
        )
    }
}
