package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "manga")
data class MangaEntity(
    @PrimaryKey val id: String, // format: "source_id"
    val title: String,
    val coverUrl: String,
    val author: String = "Bilinmiyor",
    val artist: String = "Bilinmiyor",
    val description: String = "Açıklama bulunamadı",
    val status: String = "Devam Ediyor",
    val rating: Double = 4.5,
    val sourceName: String,
    val originLanguage: String = "Japonca",
    val availableLanguages: String = "TR, EN",
    val isFavorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapter",
    indices = [Index(value = ["mangaId"])]
)
data class ChapterEntity(
    @PrimaryKey val id: String, // format: "mangaId_chapterNumber"
    val mangaId: String,
    val title: String,
    val chapterNumber: Double,
    val uploadDate: String = "Bugün",
    val isRead: Boolean = false,
    val readPage: Int = 0,
    val isDownloaded: Boolean = false,
    val localImagePathList: String = "", // comma-separated paths
    val totalPages: Int = 0,
    val language: String = "tr"
)

@Entity(tableName = "translation_correction")
data class TranslationCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val originalText: String,
    val turkishText: String,
    val sourceLang: String = "Bilinmiyor",
    @ColumnInfo(defaultValue = "tr") val targetLang: String = "tr",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "download_queue")
data class DownloadQueueItemEntity(
    @PrimaryKey val chapterId: String,
    val mangaId: String,
    val chapterTitle: String,
    val mangaTitle: String,
    val mangaCover: String,
    val progress: Float = 0f,
    val status: String = "Bekliyor", // "Bekliyor", "İndiriliyor", "Tamamlandı", "Başarısız"
    val addedAt: Long = System.currentTimeMillis()
)
