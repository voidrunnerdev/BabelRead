package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {
    @Query("SELECT * FROM manga ORDER BY addedAt DESC")
    suspend fun getAllMangas(): List<MangaEntity>

    @Query("SELECT * FROM manga WHERE isFavorite = 1 ORDER BY addedAt DESC")
    fun getFavoriteMangas(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE id = :id")
    suspend fun getMangaById(id: String): MangaEntity?

    @Query("SELECT * FROM manga WHERE id = :id")
    fun getMangaByIdFlow(id: String): Flow<MangaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManga(manga: MangaEntity)

    @Update
    suspend fun updateManga(manga: MangaEntity)

    @Query("UPDATE manga SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateMangaFavoriteStatus(id: String, isFavorite: Boolean)

    @Delete
    suspend fun deleteManga(manga: MangaEntity)

    @Query("DELETE FROM manga WHERE id = :id")
    suspend fun deleteMangaById(id: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY chapterNumber DESC")
    fun getChaptersForManga(mangaId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY chapterNumber DESC")
    suspend fun getChaptersByMangaIdSync(mangaId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapter WHERE isDownloaded = 1")
    suspend fun getDownloadedChapters(): List<ChapterEntity>

    @Query("DELETE FROM chapter WHERE mangaId = :mangaId")
    suspend fun deleteChaptersForManga(mangaId: String)

    @Query("SELECT * FROM chapter WHERE id = :id")
    suspend fun getChapterById(id: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapter WHERE id = :id")
    suspend fun deleteChapterById(id: String)

    @Query("UPDATE chapter SET isRead = :isRead WHERE id = :id")
    suspend fun updateReadStatus(id: String, isRead: Boolean)

    @Query("UPDATE chapter SET readPage = :readPage WHERE id = :id")
    suspend fun updateReadPage(id: String, readPage: Int)

    @Query("UPDATE chapter SET isDownloaded = :isDownloaded, localImagePathList = :localPath WHERE id = :id")
    suspend fun updateDownloadStatus(id: String, isDownloaded: Boolean, localPath: String)

    @Query("UPDATE chapter SET isRead = 1 WHERE mangaId = :mangaId AND chapterNumber <= :chapterNumber")
    suspend fun markChaptersReadFromHere(mangaId: String, chapterNumber: Double)
}

@Dao
interface TranslationCorrectionDao {
    @Query("SELECT * FROM translation_correction WHERE originalText = :originalText AND targetLang = :targetLang LIMIT 1")
    suspend fun getCorrectionForText(originalText: String, targetLang: String): TranslationCorrectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: TranslationCorrectionEntity)

    @Query("DELETE FROM translation_correction WHERE id = :id")
    suspend fun deleteCorrection(id: Int)

    @Query("DELETE FROM translation_correction WHERE originalText = :originalText")
    suspend fun deleteCorrectionByText(originalText: String)

    @Query("SELECT * FROM translation_correction ORDER BY timestamp DESC")
    fun getAllCorrections(): Flow<List<TranslationCorrectionEntity>>

    @Query("DELETE FROM translation_correction")
    suspend fun clearAll()
}

@Dao
interface DownloadQueueDao {
    @Query("SELECT * FROM download_queue ORDER BY addedAt ASC")
    fun getQueueFlow(): Flow<List<DownloadQueueItemEntity>>

    @Query("SELECT * FROM download_queue WHERE status = 'Bekliyor' OR status = 'İndiriliyor' ORDER BY addedAt ASC LIMIT 1")
    suspend fun getNextPendingItem(): DownloadQueueItemEntity?

    @Query("SELECT * FROM download_queue WHERE chapterId = :chapterId")
    suspend fun getQueueItem(chapterId: String): DownloadQueueItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueItem(item: DownloadQueueItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQueueItems(items: List<DownloadQueueItemEntity>)

    @Query("UPDATE download_queue SET status = :status, progress = :progress WHERE chapterId = :chapterId")
    suspend fun updateQueueStatus(chapterId: String, status: String, progress: Float)

    @Query("UPDATE download_queue SET status = 'Bekliyor', progress = 0 WHERE status = 'Başarısız'")
    suspend fun requeueFailedDownloads(): Int

    @Query("UPDATE download_queue SET status = 'Bekliyor', progress = 0 WHERE status = 'CFBekliyor'")
    suspend fun requeueCfPendingDownloads(): Int

    @Query("DELETE FROM download_queue WHERE chapterId = :chapterId")
    suspend fun deleteQueueItem(chapterId: String)

    @Query("DELETE FROM download_queue WHERE mangaId = :mangaId")
    suspend fun deleteQueueItemsForManga(mangaId: String)

    @Query("DELETE FROM download_queue WHERE status = 'Tamamlandı'")
    suspend fun clearCompleted()

    @Query("DELETE FROM download_queue")
    suspend fun clearAllItems()

    @Query("SELECT * FROM download_queue")
    suspend fun getAllItemsSync(): List<DownloadQueueItemEntity>
}
