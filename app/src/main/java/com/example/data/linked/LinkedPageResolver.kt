package com.example.data.linked

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.service.LocalMangaImporter
import com.example.service.NaturalOrderComparator
import com.example.util.ReaderBitmapPolicy
import java.io.File
import java.io.FileOutputStream
import kotlin.io.use

class LinkedPageResolver(private val context: Context) {
    companion object {
        private const val COVER_DIR = "linked_covers"
        private const val COVER_MAX_DIMENSION = 640
    }

    fun listArchiveImageEntries(archiveUri: String): List<String> {
        val entries = withArchive(archiveUri) { reader -> reader.entries() } ?: return emptyList()
        return entries.asSequence()
            .filter { it.isFile && LocalMangaImporter.isImageFileName(it.name) }
            .map { it.name }
            .sortedWith { first, second -> NaturalOrderComparator.compare(first, second) }
            .toList()
    }

    fun decodeSampled(
        encodedLocator: String,
        maxPixels: Long,
        targetWidthPx: Int = 0
    ): Bitmap? {
        return when (val locator = LinkedPageLocator.decode(encodedLocator)) {
            is LinkedPageLocator.Document -> decodeDocument(locator.uri, maxPixels, targetWidthPx)
            is LinkedPageLocator.ArchiveEntry -> decodeArchiveEntry(locator, maxPixels, targetWidthPx)
            null -> null
        }
    }

    fun cacheCoverThumbnail(mangaId: String, encodedLocator: String): String? {
        val bitmap = decodeSampled(encodedLocator, 2_000_000L) ?: return null
        var thumbnail: Bitmap? = null
        return try {
            val maxDimension = maxOf(bitmap.width, bitmap.height)
            val outputBitmap = if (maxDimension > COVER_MAX_DIMENSION) {
                val scale = COVER_MAX_DIMENSION.toFloat() / maxDimension.toFloat()
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            thumbnail = outputBitmap
            val dir = File(context.cacheDir, COVER_DIR).apply { mkdirs() }
            val file = File(dir, "$mangaId.jpg")
            val temp = File(dir, "$mangaId.jpg.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                        "Kapak görseli sıkıştırılamadı."
                    }
                }
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                }
            } finally {
                temp.delete()
            }
            "file://${file.absolutePath}"
        } catch (_: Exception) {
            null
        } finally {
            val created = thumbnail
            if (created != null && created !== bitmap && !created.isRecycled) {
                created.recycle()
            }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun hasCachedCover(mangaId: String): Boolean = coverFile(mangaId).let { it.isFile && it.length() > 0L }

    fun cachedCoverUrl(mangaId: String): String = "file://${coverFile(mangaId).absolutePath}"

    fun deleteCachedCover(mangaId: String) {
        runCatching { coverFile(mangaId).delete() }
    }

    private fun coverFile(mangaId: String): File = File(File(context.cacheDir, COVER_DIR), "$mangaId.jpg")

    private fun decodeDocument(
        uriString: String,
        maxPixels: Long,
        targetWidthPx: Int
    ): Bitmap? {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ReaderBitmapPolicy.chooseSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidthPx,
                maxPixels
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    private fun decodeArchiveEntry(
        locator: LinkedPageLocator.ArchiveEntry,
        maxPixels: Long,
        targetWidthPx: Int
    ): Bitmap? {
        val bytes = withArchive(locator.archiveUri) { reader ->
            reader.readEntryBytes(locator.entryName)
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ReaderBitmapPolicy.chooseSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidthPx,
                maxPixels
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun <T> withArchive(archiveUri: String, block: (LinkedArchiveReader) -> T): T? {
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(archiveUri), "r")
            ?: return null
        return descriptor.use { pfd ->
            LinkedArchiveReader.open(pfd).use(block)
        }
    }

}
