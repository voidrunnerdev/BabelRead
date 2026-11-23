package com.example.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "web_page_cache")
data class WebPageCacheEntity(
    @PrimaryKey val chapterId: String,
    val urlsJson: String,
    val fetchedAt: Long = System.currentTimeMillis()
)

@Dao
interface WebPageCacheDao {
    @Query("SELECT * FROM web_page_cache WHERE chapterId = :chapterId LIMIT 1")
    suspend fun get(chapterId: String): WebPageCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: WebPageCacheEntity)

    @Query("DELETE FROM web_page_cache WHERE chapterId = :chapterId")
    suspend fun delete(chapterId: String)

    @Query("DELETE FROM web_page_cache WHERE fetchedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
