package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MangaEntity::class,
        ChapterEntity::class,
        TranslationCorrectionEntity::class,
        DownloadQueueItemEntity::class,
        TranslatedPageCacheEntity::class,
        WebPageCacheEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun translationCorrectionDao(): TranslationCorrectionDao
    abstract fun downloadQueueDao(): DownloadQueueDao
    abstract fun translationCacheDao(): TranslationCacheDao
    abstract fun webPageCacheDao(): WebPageCacheDao

    companion object {
        @Volatile
        private var INSTANCE: MangaDatabase? = null

        // Çeviri önbelleği tablosu: mevcut kütüphane ve okuma ilerlemesi korunur.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `translated_page_cache` (" +
                        "`chapterId` TEXT NOT NULL, " +
                        "`pageIndex` INTEGER NOT NULL, " +
                        "`pageUrl` TEXT NOT NULL, " +
                        "`sourceName` TEXT NOT NULL, " +
                        "`blocksJson` TEXT NOT NULL, " +
                        "`maskedPath` TEXT NOT NULL, " +
                        "`byteSize` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chapterId`, `pageIndex`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_translated_page_cache_sourceName` " +
                        "ON `translated_page_cache` (`sourceName`)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `web_page_cache` (" +
                        "`chapterId` TEXT NOT NULL, " +
                        "`urlsJson` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chapterId`))"
                )
            }
        }

        // Kullanıcı düzeltmeleri artık hedef dile göre ayrılır; eski kayıtlar
        // varsayılan olarak Türkçe kabul edilir.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `translation_correction` " +
                        "ADD COLUMN `targetLang` TEXT NOT NULL DEFAULT 'tr'"
                )
            }
        }

        fun getDatabase(context: Context): MangaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    "manga_tr_database"
                )
                // Deliberately omit destructive fallback: schema changes must ship
                // an explicit Migration so manga and reading progress are preserved.
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
