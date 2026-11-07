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
        WebPageCacheEntity::class,
        LinkedFolderEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun translationCorrectionDao(): TranslationCorrectionDao
    abstract fun downloadQueueDao(): DownloadQueueDao
    abstract fun translationCacheDao(): TranslationCacheDao
    abstract fun webPageCacheDao(): WebPageCacheDao
    abstract fun linkedFolderDao(): LinkedFolderDao

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

        // Bağlı klasörler yalnızca metadata saklar; mevcut mangalar ve okuma
        // ilerlemesi korunur, kullanıcı dosyaları uygulamaya kopyalanmaz.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `linked_folder` (" +
                        "`id` TEXT NOT NULL, " +
                        "`treeUri` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`lastScanAt` INTEGER NOT NULL, " +
                        "`lastScanError` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_linked_folder_treeUri` " +
                        "ON `linked_folder` (`treeUri`)"
                )
                db.execSQL("ALTER TABLE `manga` ADD COLUMN `linkedFolderId` TEXT")
                db.execSQL("ALTER TABLE `manga` ADD COLUMN `linkedRelativePath` TEXT")
                db.execSQL("ALTER TABLE `manga` ADD COLUMN `linkedAvailable` INTEGER")
                db.execSQL("ALTER TABLE `chapter` ADD COLUMN `linkedFolderId` TEXT")
                db.execSQL("ALTER TABLE `chapter` ADD COLUMN `linkedRelativePath` TEXT")
                db.execSQL("ALTER TABLE `chapter` ADD COLUMN `linkedPageLocator` TEXT")
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
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
