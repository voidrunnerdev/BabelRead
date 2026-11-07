package com.example.data.linked

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.example.data.db.LinkedFolderDao
import com.example.data.db.LinkedFolderEntity
import com.example.data.db.MangaDao
import com.example.data.db.MangaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class LinkedSourceInfo(
    val folder: LinkedFolderEntity,
    val permissionGranted: Boolean,
    val mangaCount: Int
)

class LinkedFolderManager(
    private val context: Context,
    private val database: MangaDatabase,
    private val resolver: LinkedPageResolver
) {
    companion object {
        fun hasReadPermission(context: Context, treeUri: String): Boolean {
            val target = treeUri.trimEnd('/')
            return context.contentResolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission && permission.uri.toString().trimEnd('/') == target
            }
        }
    }

    private val mangaDao: MangaDao = database.mangaDao()
    private val linkedFolderDao: LinkedFolderDao = database.linkedFolderDao()
    private val scanner = LinkedFolderScanner(
        context = context,
        mangaDao = mangaDao,
        chapterDao = database.chapterDao(),
        linkedFolderDao = linkedFolderDao,
        resolver = resolver
    )

    suspend fun linkFolder(
        treeUri: Uri,
        mode: String,
        onProgress: (String) -> Unit = {}
    ): LinkedScanResult = withContext(Dispatchers.IO) {
        takeReadPermission(treeUri)
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        val displayName = tree?.name?.trim().orEmpty().ifBlank { "Bağlı Klasör" }
        val folderId = LinkedIds.folderId(treeUri.toString(), mode)
        val existing = linkedFolderDao.getLinkedFolderById(folderId)
        val folder = LinkedFolderEntity(
            id = folderId,
            treeUri = treeUri.toString(),
            displayName = displayName,
            mode = mode,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            lastScanAt = existing?.lastScanAt ?: 0L,
            lastScanError = null
        )
        linkedFolderDao.upsertLinkedFolder(folder)
        scanner.scan(folder, onProgress)
    }

    suspend fun relinkFolder(
        folderId: String,
        treeUri: Uri,
        onProgress: (String) -> Unit = {}
    ): LinkedScanResult = withContext(Dispatchers.IO) {
        takeReadPermission(treeUri)
        val existing = linkedFolderDao.getLinkedFolderById(folderId)
            ?: return@withContext LinkedScanResult(false, "Bağlı klasör kaydı bulunamadı.")
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        val updated = existing.copy(
            treeUri = treeUri.toString(),
            displayName = tree?.name?.trim().orEmpty().ifBlank { existing.displayName },
            lastScanError = null
        )
        linkedFolderDao.upsertLinkedFolder(updated)
        if (existing.treeUri != updated.treeUri) {
            val oldStillUsed = linkedFolderDao.getLinkedFoldersByTreeUri(existing.treeUri).isNotEmpty()
            if (!oldStillUsed) releaseReadPermission(existing.treeUri)
        }
        scanner.scan(updated, onProgress)
    }

    suspend fun rescanFolder(
        folderId: String,
        onProgress: (String) -> Unit = {}
    ): LinkedScanResult = withContext(Dispatchers.IO) {
        val folder = linkedFolderDao.getLinkedFolderById(folderId)
            ?: return@withContext LinkedScanResult(false, "Bağlı klasör kaydı bulunamadı.")
        if (!hasReadPermission(context, folder.treeUri)) {
            val message =
                "Bağlı klasör izni kayboldu. Kitaplıktan 'Yeniden Bağla' ile klasörü tekrar seçin."
            linkedFolderDao.updateScanResult(folderId, System.currentTimeMillis(), message)
            mangaDao.updateLinkedFolderAvailability(folderId, false)
            return@withContext LinkedScanResult(false, message, permissionLost = true)
        }
        scanner.scan(folder, onProgress)
    }

    suspend fun detachFolder(folderId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val folder = linkedFolderDao.getLinkedFolderById(folderId)
            ?: return@withContext (false to "Bağlı klasör kaydı bulunamadı.")
        val mangas = mangaDao.getMangasByLinkedFolder(folderId)
        database.withTransaction {
            mangas.forEach { manga ->
                database.chapterDao().deleteChaptersForManga(manga.id)
                mangaDao.deleteMangaById(manga.id)
            }
            linkedFolderDao.deleteLinkedFolderById(folderId)
        }
        mangas.forEach { manga -> resolver.deleteCachedCover(manga.id) }
        val stillUsed = linkedFolderDao.getLinkedFoldersByTreeUri(folder.treeUri).isNotEmpty()
        if (!stillUsed) releaseReadPermission(folder.treeUri)
        true to "\"${folder.displayName}\" bağlantısı kaldırıldı. Kaynak dosyalarınız silinmedi."
    }

    suspend fun sourceInfos(): List<LinkedSourceInfo> = withContext(Dispatchers.IO) {
        linkedFolderDao.getLinkedFoldersSync().map { folder ->
            LinkedSourceInfo(
                folder = folder,
                permissionGranted = hasReadPermission(context, folder.treeUri),
                mangaCount = mangaDao.countMangasForLinkedFolder(folder.id)
            )
        }
    }

    private fun takeReadPermission(treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            throw IOException(
                "Klasör izni kalıcı olarak saklanamadı. Lütfen klasörü yeniden seçin.",
                e
            )
        }
    }

    private fun releaseReadPermission(treeUri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
