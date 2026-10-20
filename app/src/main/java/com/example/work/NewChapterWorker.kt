package com.example.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.data.db.ChapterEntity
import com.example.data.db.MangaDatabase
import com.example.data.parser.MangaSourceRegistry
import com.example.data.pref.MangaPreferences
import com.example.data.web.WebCaptureParser
import com.example.data.web.WebPageExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import android.util.Base64
import kotlin.coroutines.coroutineContext

class NewChapterWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "manga_notifications_channel"
        const val UNIQUE_WORK_NAME = "manga_new_chapter_checker"

        fun scheduleCheck(context: Context, intervalHours: Int) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<NewChapterWorker>(
                intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val database = MangaDatabase.getDatabase(applicationContext)
        val mangaDao = database.mangaDao()
        val chapterDao = database.chapterDao()

        try {
            val favoriteList = mangaDao.getFavoriteMangas().first()
            val webPages = MangaPreferences(applicationContext).webMangaPages.first()
            for (dbManga in favoriteList) {
                coroutineContext.ensureActive()
                if (dbManga.id.startsWith("local_") ||
                    dbManga.sourceName.contains("Yerel", ignoreCase = true) ||
                    dbManga.sourceName.contains("Cihaz", ignoreCase = true)
                ) {
                    continue
                }
                val isWeb = dbManga.id.startsWith("web_") || dbManga.sourceName.startsWith("Web: ")
                if (isWeb) {
                    try {
                        val pageUrl = webPages[dbManga.id] ?: continue
                        val raw = WebPageExtractor.captureHttp(pageUrl) ?: continue
                        val parsed = WebCaptureParser.parse(raw) ?: continue
                        val chosen = WebCaptureParser.seriesChaptersOf(parsed)
                        if (chosen.isEmpty()) continue
                        val netChapters = chosen.map { chapter ->
                            ChapterEntity(
                                id = "webch_" + Base64.encodeToString(
                                    chapter.url.toByteArray(Charsets.UTF_8),
                                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                                ),
                                mangaId = dbManga.id,
                                title = chapter.title.ifBlank { "Bölüm" },
                                chapterNumber = chapter.number,
                                uploadDate = "Bugün",
                                isRead = false,
                                isDownloaded = false,
                                language = "en"
                            )
                        }
                        val localChapters = chapterDao.getChaptersByMangaIdSync(dbManga.id)
                        if (localChapters.isEmpty()) {
                            chapterDao.insertChapters(netChapters)
                            continue
                        }
                        val localIds = localChapters.map { it.id }.toSet()
                        val localMaxNum = localChapters.maxOfOrNull { it.chapterNumber } ?: 0.0
                        val newChapters = netChapters.filter { it.id !in localIds && it.chapterNumber > localMaxNum }
                        if (newChapters.isNotEmpty()) {
                            val maxNewChapter = newChapters.maxByOrNull { it.chapterNumber }
                            if (maxNewChapter != null) {
                                sendNotification(
                                    dbManga.title,
                                    "${dbManga.title} serisinin ${formatChapterNumber(maxNewChapter.chapterNumber)}. bölümü yayınlandı!"
                                )
                            }
                            chapterDao.insertChapters(newChapters)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (mangaErr: Throwable) {
                        // Tek bir serideki ağ hatası durdurmasın
                    }
                    continue
                }

                try {
                    val source = MangaSourceRegistry.getSource(dbManga.sourceName)
                    val (_, netChapters) = source.getMangaDetail(dbManga.id)
                    val localChapters = chapterDao.getChaptersByMangaIdSync(dbManga.id)
                    if (localChapters.isEmpty()) {
                        // First check establishes a baseline; do not notify the user for the full back catalog.
                        chapterDao.insertChapters(netChapters.map { chapter ->
                            ChapterEntity(
                                id = chapter.id,
                                mangaId = chapter.mangaId,
                                title = chapter.title,
                                chapterNumber = chapter.chapterNumber,
                                uploadDate = chapter.uploadDate,
                                isRead = false,
                                isDownloaded = false
                            )
                        })
                        continue
                    }
                    val localMaxNum = localChapters.maxOfOrNull { it.chapterNumber } ?: 0.0

                    val newChapters = netChapters.filter { it.chapterNumber > localMaxNum }
                    if (newChapters.isNotEmpty()) {
                        val maxNewChapter = newChapters.maxByOrNull { it.chapterNumber }
                        if (maxNewChapter != null) {
                            sendNotification(
                                dbManga.title,
                                "${dbManga.title} serisinin ${formatChapterNumber(maxNewChapter.chapterNumber)}. bölümü yayınlandı!"
                            )
                        }

                        val entities = newChapters.map {
                            ChapterEntity(
                                id = it.id,
                                mangaId = it.mangaId,
                                title = it.title,
                                chapterNumber = it.chapterNumber,
                                uploadDate = it.uploadDate,
                                isRead = false,
                                isDownloaded = false
                            )
                        }
                        chapterDao.insertChapters(entities)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (mangaErr: Throwable) {
                    // Tek bir serideki ağ hatası durdurmasın
                }
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return Result.retry()
        }
    }

    private fun formatChapterNumber(number: Double): String =
        if (number.isFinite()) BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() else number.toString()

    private fun sendNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Yeni Bölüm Bildirimleri",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Kütüphanenizdeki mangaların yeni bölümleri yayınlandığında bildirim gönderir."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            notificationManager.notify(title.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // Bildirim izni verilmemişse sessizce geç
        }
    }
}
