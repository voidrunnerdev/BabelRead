package com.example.data.linked

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.db.ChapterDao
import com.example.data.db.ChapterEntity
import com.example.data.db.LinkedFolderDao
import com.example.data.db.LinkedFolderEntity
import com.example.data.db.MangaDao
import com.example.data.db.MangaEntity
import com.example.service.LocalMangaImporter
import com.example.service.NaturalOrderComparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LinkedScanResult(
    val success: Boolean,
    val message: String,
    val addedMangaCount: Int = 0,
    val updatedMangaCount: Int = 0,
    val chapterCount: Int = 0,
    val pageCount: Int = 0,
    val permissionLost: Boolean = false
)

class LinkedFolderScanner(
    private val context: Context,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val linkedFolderDao: LinkedFolderDao,
    private val resolver: LinkedPageResolver
) {
    private data class ScannedChapter(
        val relativePath: String,
        val title: String,
        val chapterNumber: Double,
        val locators: List<String>
    )

    private data class ScannedManga(
        val relativePath: String,
        val title: String,
        val chapters: List<ScannedChapter>,
        val coverLocator: String?
    )

    suspend fun scan(
        folder: LinkedFolderEntity,
        onProgress: (String) -> Unit = {}
    ): LinkedScanResult = withContext(Dispatchers.IO) {
        val root = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
        }.getOrNull()
        val rootAccessible = root?.let { candidate ->
            runCatching { candidate.exists() && candidate.canRead() }.getOrDefault(false)
        } ?: false
        if (root == null || !rootAccessible) {
            val permissionLost = !LinkedFolderManager.hasReadPermission(context, folder.treeUri)
            return@withContext fail(
                folder,
                if (permissionLost) {
                    "Bağlı klasör izni kayboldu. Kitaplıktan 'Yeniden Bağla' ile klasörü tekrar seçin."
                } else {
                    "Bağlı klasör bulunamadı veya erişilemiyor. Kaynak yeniden bağlanmalı."
                },
                permissionLost
            )
        }
        try {
            if (folder.mode == LinkedFolderEntity.MODE_LIBRARY_ROOT) {
                scanLibraryRoot(folder, root, onProgress)
            } else {
                scanSingleManga(folder, root, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            fail(
                folder,
                "Bağlı klasör izni kayboldu. Kitaplıktan 'Yeniden Bağla' ile klasörü tekrar seçin.",
                true
            )
        } catch (e: Exception) {
            fail(
                folder,
                "Bağlı klasör taranırken hata oluştu: ${e.localizedMessage ?: e.message}"
            )
        }
    }

    private suspend fun scanSingleManga(
        folder: LinkedFolderEntity,
        root: DocumentFile,
        onProgress: (String) -> Unit
    ): LinkedScanResult {
        val rootName = root.name?.trim().orEmpty().ifBlank { folder.displayName }
        val title = LinkedNaming.cleanTitle(rootName)
        onProgress("Bağlı klasör taranıyor: $title")
        val children = listChildren(root)
        val chapters = buildChapters(children, "", onProgress)
        if (chapters.isEmpty()) {
            return fail(
                folder,
                "Seçilen klasörde okunabilir resim, CBZ veya ZIP bulunamadı."
            )
        }
        val coverLocator = findCoverLocator(children) ?: chapters.first().locators.firstOrNull()
        val scanned = ScannedManga("", title, chapters, coverLocator)
        val added = saveManga(folder, scanned)
        markRemovedMangasUnavailable(folder, setOf(LinkedIds.mangaId(folder.id, scanned.relativePath)))
        linkedFolderDao.updateScanResult(folder.id, System.currentTimeMillis(), null)

        val pageCount = chapters.sumOf { it.locators.size }
        val message = buildString {
            if (added) append("'$title' bağlandı: 1 manga eklendi") else append("'$title' yeniden tarandı")
            append(" (${chapters.size} bölüm, $pageCount sayfa).")
            append(" Kaynak dosyalar kopyalanmadı.")
        }
        return LinkedScanResult(
            success = true,
            message = message,
            addedMangaCount = if (added) 1 else 0,
            updatedMangaCount = if (added) 0 else 1,
            chapterCount = chapters.size,
            pageCount = pageCount
        )
    }

    private suspend fun scanLibraryRoot(
        folder: LinkedFolderEntity,
        root: DocumentFile,
        onProgress: (String) -> Unit
    ): LinkedScanResult {
        val rootChildren = listChildren(root)
        val directories = naturalByName(
            rootChildren.filter { it.isDirectory && !isHiddenEntry(it.name) }
        )
        // Mihon local/ rule: subfolders are mangas; loose images in root are never treated as mangas.
        // Loose root archives are only scanned when no manga subfolders exist.
        val rootArchives = if (directories.isEmpty()) {
            naturalByName(rootChildren.filter { it.isFile && isArchive(it.name) })
        } else {
            emptyList()
        }
        if (directories.isEmpty() && rootArchives.isEmpty()) {
            return fail(folder, "Klasör kitaplığında manga bulunamadı.")
        }

        val scannedMangas = mutableListOf<ScannedManga>()
        var index = 0
        val total = directories.size + rootArchives.size

        directories.forEach { directory ->
            index++
            val folderName = directory.name?.trim().orEmpty().ifBlank { "Manga" }
            onProgress("Klasör kitaplığı: $index/$total - $folderName")
            val children = listChildren(directory)

            // Support Mihon downloads/ 3-level structure (downloads / Source / Manga / Chapters):
            // If this directory has no direct non-cover images or archives, and its subdirectories
            // themselves contain chapter subfolders or archives, descend one level so each actual
            // series becomes its own manga instead of collapsing the whole source into one manga.
            val directNonCoverImages = children.any { it.isFile && isImage(it.name) && !isCoverImage(it.name) }
            val directArchives = children.any { it.isFile && isArchive(it.name) }
            val subDirs = naturalByName(children.filter { it.isDirectory && !isHiddenEntry(it.name) })
            val looksLikeMihonSourceDir = !directNonCoverImages && !directArchives && subDirs.isNotEmpty() &&
                subDirs.none { isVolumeOrChapterFolderName(it.name) } &&
                subDirs.any { sub ->
                    val subChildren = listChildren(sub)
                    subChildren.any { it.isFile && isArchive(it.name) } ||
                        subChildren.any { it.isDirectory && !isHiddenEntry(it.name) }
                }

            if (looksLikeMihonSourceDir) {
                subDirs.forEach { mangaDir ->
                    val mangaName = mangaDir.name?.trim().orEmpty().ifBlank { "Manga" }
                    onProgress("Mihon kaynağı ($folderName): $mangaName")
                    val mangaChildren = listChildren(mangaDir)
                    val relPath = "source:$folderName/folder:$mangaName"
                    val chapters = buildChapters(mangaChildren, relPath, onProgress)
                    if (chapters.isNotEmpty()) {
                        scannedMangas += ScannedManga(
                            relativePath = relPath,
                            title = LinkedNaming.cleanTitle(mangaName),
                            chapters = chapters,
                            coverLocator = findCoverLocator(mangaChildren)
                                ?: chapters.first().locators.firstOrNull()
                        )
                    }
                }
            } else {
                val chapters = buildChapters(children, folderName, onProgress)
                if (chapters.isNotEmpty()) {
                    scannedMangas += ScannedManga(
                        relativePath = "folder:$folderName",
                        title = LinkedNaming.cleanTitle(folderName),
                        chapters = chapters,
                        coverLocator = findCoverLocator(children)
                            ?: chapters.first().locators.firstOrNull()
                    )
                }
            }
        }

        rootArchives.forEach { archive ->
            index++
            val archiveName = archive.name?.trim().orEmpty().ifBlank { "Manga" }
            onProgress("Klasör kitaplığı: $index/$total - $archiveName")
            val locators = archiveLocators(archive)
            if (locators.isNotEmpty()) {
                val chapter = ScannedChapter(
                    relativePath = "archive:$archiveName",
                    title = LinkedNaming.cleanTitle(archiveName),
                    chapterNumber = LinkedNaming.chapterNumber(archiveName, 1),
                    locators = locators
                )
                scannedMangas += ScannedManga(
                    relativePath = "archive:$archiveName",
                    title = LinkedNaming.cleanTitle(archiveName),
                    chapters = listOf(chapter.copy(relativePath = "@root-images")),
                    coverLocator = locators.firstOrNull()
                )
            }
        }

        if (scannedMangas.isEmpty()) {
            return fail(folder, "Klasör kitaplığında okunabilir manga bulunamadı.")
        }

        var addedCount = 0
        var updatedCount = 0
        var chapterCount = 0
        var pageCount = 0
        val scannedIds = mutableSetOf<String>()
        scannedMangas.forEach { manga ->
            val mangaId = LinkedIds.mangaId(folder.id, manga.relativePath)
            scannedIds += mangaId
            if (saveManga(folder, manga)) addedCount++ else updatedCount++
            chapterCount += manga.chapters.size
            pageCount += manga.chapters.sumOf { it.locators.size }
        }
        val unavailableCount = markRemovedMangasUnavailable(folder, scannedIds)
        linkedFolderDao.updateScanResult(folder.id, System.currentTimeMillis(), null)

        val message = buildString {
            if (addedCount > 0) {
                append("Klasör kitaplığı bağlandı: $addedCount manga eklendi")
            } else {
                append("Klasör kitaplığı yeniden tarandı")
            }
            if (updatedCount > 0) append(", $updatedCount manga güncellendi")
            append(" ($chapterCount bölüm, $pageCount sayfa).")
            if (unavailableCount > 0) {
                append(" $unavailableCount manga artık bulunamıyor; okuma ilerlemesi korundu.")
            }
            append(" Kaynak dosyalar kopyalanmadı.")
        }
        return LinkedScanResult(
            success = true,
            message = message,
            addedMangaCount = addedCount,
            updatedMangaCount = updatedCount,
            chapterCount = chapterCount,
            pageCount = pageCount
        )
    }

    private suspend fun fail(
        folder: LinkedFolderEntity,
        message: String,
        permissionLost: Boolean = false
    ): LinkedScanResult {
        linkedFolderDao.updateScanResult(folder.id, System.currentTimeMillis(), message)
        mangaDao.updateLinkedFolderAvailability(folder.id, false)
        return LinkedScanResult(false, message, permissionLost = permissionLost)
    }

    private suspend fun markRemovedMangasUnavailable(
        folder: LinkedFolderEntity,
        scannedMangaIds: Set<String>
    ): Int {
        var count = 0
        mangaDao.getMangasByLinkedFolder(folder.id).forEach { manga ->
            if (manga.id !in scannedMangaIds) {
                mangaDao.updateLinkedAvailability(manga.id, false)
                count++
            }
        }
        return count
    }

    private suspend fun saveManga(folder: LinkedFolderEntity, scanned: ScannedManga): Boolean {
        val mangaId = LinkedIds.mangaId(folder.id, scanned.relativePath)
        val previous = mangaDao.getMangaById(mangaId)
        val pageCount = scanned.chapters.sumOf { it.locators.size }
        val coverUrl = when {
            scanned.coverLocator == null -> previous?.coverUrl.orEmpty()
            resolver.hasCachedCover(mangaId) -> resolver.cachedCoverUrl(mangaId)
            else -> resolver.cacheCoverThumbnail(mangaId, scanned.coverLocator)
                ?: previous?.coverUrl.orEmpty()
        }
        val entity = MangaEntity(
            id = mangaId,
            title = scanned.title,
            coverUrl = coverUrl,
            author = "Cihaz Arşivi",
            artist = "Bağlı Klasör",
            description = "Bağlı klasörden kopyalanmadan okunan yerel manga " +
                "(${scanned.chapters.size} bölüm, $pageCount sayfa).",
            status = "Tamamlandı",
            rating = 5.0,
            sourceName = "Cihaz / Yerel Mangalar",
            originLanguage = "Yerel",
            availableLanguages = "Yerel, TR",
            isFavorite = previous?.isFavorite ?: true,
            addedAt = previous?.addedAt ?: System.currentTimeMillis(),
            linkedFolderId = folder.id,
            linkedRelativePath = scanned.relativePath,
            linkedAvailable = true
        )
        mangaDao.insertManga(entity)

        val existingChapters = chapterDao.getChaptersByMangaIdSync(mangaId).associateBy { it.id }
        val chapterEntities = scanned.chapters.map { chapter ->
            val chapterId = LinkedIds.chapterId(
                folder.id,
                scanned.relativePath,
                chapter.relativePath
            )
            val previousChapter = existingChapters[chapterId]
            ChapterEntity(
                id = chapterId,
                mangaId = mangaId,
                title = "${scanned.title} - ${chapter.title}",
                chapterNumber = chapter.chapterNumber,
                uploadDate = previousChapter?.uploadDate ?: "Bağlı klasör",
                isRead = previousChapter?.isRead ?: false,
                readPage = previousChapter?.readPage ?: 0,
                isDownloaded = false,
                localImagePathList = "",
                totalPages = chapter.locators.size,
                language = previousChapter?.language ?: "tr",
                linkedFolderId = folder.id,
                linkedRelativePath = chapter.relativePath,
                linkedPageLocator = LinkedPageLocator.encodeList(chapter.locators)
            )
        }
        chapterDao.upsertChapters(chapterEntities)
        val activeIds = chapterEntities.mapTo(HashSet()) { it.id }
        existingChapters.keys
            .filterNot { it in activeIds }
            .forEach { chapterDao.deleteChapterById(it) }
        return previous == null
    }

    private fun buildChapters(
        children: List<DocumentFile>,
        mangaRelativePath: String,
        onProgress: (String) -> Unit
    ): List<ScannedChapter> {
        val chapters = mutableListOf<ScannedChapter>()
        val usedTitles = mutableSetOf<String>()

        fun addChapter(
            relativePath: String,
            title: String,
            locators: List<String>
        ) {
            if (locators.isEmpty()) return
            val uniqueTitle = uniqueChapterTitle(title, usedTitles)
            chapters += ScannedChapter(
                relativePath = relativePath,
                title = uniqueTitle,
                chapterNumber = LinkedNaming.chapterNumber(title, chapters.size + 1),
                locators = locators
            )
        }

        naturalByName(children.filter { it.isDirectory && !isHiddenEntry(it.name) }).forEach { directory ->
            val folderName = directory.name?.trim().orEmpty().ifBlank { "Bölüm" }
            val folderChildren = listChildren(directory)
            val allImages = naturalByName(folderChildren.filter { it.isFile && isImage(it.name) })
            val nonCoverImages = allImages.filterNot { isCoverImage(it.name) }
            val images = if (nonCoverImages.isNotEmpty()) nonCoverImages else allImages
            if (images.isNotEmpty()) {
                onProgress("Bölüm taranıyor: $folderName")
                addChapter(
                    "folder:$folderName",
                    LinkedNaming.cleanTitle(folderName),
                    images.map { LinkedPageLocator.document(it.uri.toString()) }
                )
            }
            naturalByName(folderChildren.filter { it.isFile && isArchive(it.name) }).forEach { archive ->
                val archiveName = archive.name?.trim().orEmpty()
                if (archiveName.isNotBlank()) {
                    onProgress("Arşiv taranıyor: $folderName/$archiveName")
                    addChapter(
                        "folder:$folderName/archive:$archiveName",
                        LinkedNaming.cleanTitle(archiveName),
                        archiveLocators(archive)
                    )
                }
            }
        }

        naturalByName(children.filter { it.isFile && isArchive(it.name) }).forEach { archive ->
            val archiveName = archive.name?.trim().orEmpty()
            if (archiveName.isNotBlank()) {
                onProgress("Arşiv taranıyor: $archiveName")
                addChapter(
                    "archive:$archiveName",
                    LinkedNaming.cleanTitle(archiveName),
                    archiveLocators(archive)
                )
            }
        }

        // Mihon local rule: cover.jpg/png/webp is strictly a cover image, never a chapter.
        // Only group direct loose images into "Bölüm 1" when no other chapters exist or there are
        // multiple real page images (prevents a stray thumbnail from creating a fake 1-page chapter).
        val directImages = naturalByName(
            children.filter { it.isFile && isImage(it.name) && !isCoverImage(it.name) }
        )
        if (directImages.isNotEmpty() && (chapters.isEmpty() || directImages.size >= 2)) {
            addChapter(
                "@root-images",
                "Bölüm 1",
                directImages.map { LinkedPageLocator.document(it.uri.toString()) }
            )
        }

        val prefix = mangaRelativePath.takeIf { it.isNotBlank() }?.let { "$it/" }.orEmpty()
        return chapters
            .sortedWith { first, second ->
                NaturalOrderComparator.compare(first.relativePath, second.relativePath)
            }
            .map { chapter -> chapter.copy(relativePath = prefix + chapter.relativePath) }
    }

    private fun archiveLocators(archive: DocumentFile): List<String> {
        val archiveUri = archive.uri.toString()
        return try {
            resolver.listArchiveImageEntries(archiveUri)
                .map { entry -> LinkedPageLocator.archiveEntry(archiveUri, entry) }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findCoverLocator(children: List<DocumentFile>): String? {
        return naturalByName(children.filter { it.isFile && isImage(it.name) })
            .firstOrNull { document ->
                document.name
                    ?.substringBeforeLast(".")
                    ?.equals("cover", ignoreCase = true) == true
            }
            ?.let { LinkedPageLocator.document(it.uri.toString()) }
    }

    private fun listChildren(directory: DocumentFile): List<DocumentFile> {
        return try {
            directory.listFiles().toList()
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun naturalByName(files: List<DocumentFile>): List<DocumentFile> {
        return files.sortedWith { first, second ->
            NaturalOrderComparator.compare(first.name.orEmpty(), second.name.orEmpty())
        }
    }

    private fun isImage(name: String?): Boolean =
        name != null && LocalMangaImporter.isImageFileName(name)

    private fun isCoverImage(name: String?): Boolean = isCoverImageName(name)

    private fun isHiddenEntry(name: String?): Boolean = isHiddenEntryName(name)

    companion object {
        internal fun isCoverImageName(name: String?): Boolean {
            val trimmed = name?.trim() ?: return false
            if (!LocalMangaImporter.isImageFileName(trimmed)) return false
            val base = trimmed.substringBeforeLast(".")
            return base.equals("cover", ignoreCase = true)
        }

        internal fun isHiddenEntryName(name: String?): Boolean {
            val trimmed = name?.trim() ?: return true
            return trimmed.isEmpty() || trimmed.startsWith(".")
        }

        private val VOLUME_OR_CHAPTER_REGEX = Regex(
            "^(vol\.?|volume|cilt|ch\.?|chapter|bölüm|bolum|part|book|season|ep\.?|episode|v|c)\s*\d+.*$",
            RegexOption.IGNORE_CASE
        )

        internal fun isVolumeOrChapterFolderName(name: String?): Boolean {
            val trimmed = name?.trim() ?: return false
            return VOLUME_OR_CHAPTER_REGEX.matches(trimmed)
        }
    }

    private fun isArchive(name: String?): Boolean {
        val lower = name?.trim()?.lowercase() ?: return false
        if (lower.startsWith(".")) return false
        return lower.endsWith(".cbz") || lower.endsWith(".zip")
    }

    private fun uniqueChapterTitle(rawTitle: String, used: MutableSet<String>): String {
        val base = rawTitle.trim().ifBlank { "Bölüm" }
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "$base ($suffix)"
            suffix++
        }
        return candidate
    }
}
