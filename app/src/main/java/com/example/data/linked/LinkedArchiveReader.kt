package com.example.data.linked

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal data class LinkedArchiveEntry(
    val name: String,
    val isFile: Boolean
)

internal class LinkedArchiveReader private constructor(
    private val archive: Long,
    private val mmapAddress: Long,
    private val mmapSize: Long
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()

    companion object {
        private const val MAX_ENTRY_SIZE = 64L * 1024 * 1024

        fun open(descriptor: ParcelFileDescriptor): LinkedArchiveReader {
            var archive = 0L
            var mmapAddress = 0L
            var mmapSize = 0L
            try {
                archive = Archive.readNew()
                Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
                Archive.readSupportFilterAll(archive)
                Archive.readSupportFormatAll(archive)
                val statSize = descriptor.statSize
                if (statSize > 0) {
                    mmapSize = statSize
                    mmapAddress = Os.mmap(
                        0,
                        statSize,
                        OsConstants.PROT_READ,
                        OsConstants.MAP_PRIVATE,
                        descriptor.fileDescriptor,
                        0
                    )
                    Archive.readOpenMemoryUnsafe(archive, mmapAddress, statSize)
                } else {
                    runCatching {
                        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                    }
                    Archive.readOpenFd(archive, descriptor.fd, 64 * 1024L)
                }
                return LinkedArchiveReader(archive, mmapAddress, mmapSize)
            } catch (t: Throwable) {
                if (archive != 0L) runCatching { Archive.readFree(archive) }
                if (mmapAddress != 0L) runCatching { Os.munmap(mmapAddress, mmapSize) }
                throw t
            }
        }
    }

    fun entries(): List<LinkedArchiveEntry> = synchronized(lock) {
        if (closed.get()) return@synchronized emptyList()
        val result = ArrayList<LinkedArchiveEntry>()
        while (true) {
            val entry = Archive.readNextHeader(archive)
            if (entry == 0L) break
            val name = ArchiveEntry.pathnameUtf8(entry)
                ?: ArchiveEntry.pathname(entry)?.decodeToString()
                ?: continue
            val fileType = ArchiveEntry.filetype(entry)
            val isFile = fileType == ArchiveEntry.AE_IFREG ||
                (fileType == 0 && !name.endsWith("/"))
            result += LinkedArchiveEntry(name, isFile)
        }
        result
    }

    fun readEntryBytes(entryName: String): ByteArray? = synchronized(lock) {
        if (closed.get()) return@synchronized null
        while (true) {
            val entry = Archive.readNextHeader(archive)
            if (entry == 0L) return@synchronized null
            val name = ArchiveEntry.pathnameUtf8(entry)
                ?: ArchiveEntry.pathname(entry)?.decodeToString()
                ?: continue
            if (name != entryName) continue

            val entrySize = ArchiveEntry.size(entry)
            if (entrySize > MAX_ENTRY_SIZE) return@synchronized null
            val initialCapacity = entrySize
                .takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                ?.toInt()
                ?.coerceAtMost(16 * 1024 * 1024)
                ?: 8192
            val output = ByteArrayOutputStream(initialCapacity)
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            val chunk = ByteArray(64 * 1024)
            while (true) {
                buffer.clear()
                Archive.readData(archive, buffer)
                buffer.flip()
                val remaining = buffer.remaining()
                if (remaining <= 0) break
                buffer.get(chunk, 0, remaining)
                output.write(chunk, 0, remaining)
                if (output.size().toLong() > MAX_ENTRY_SIZE) return@synchronized null
            }
            return@synchronized output.toByteArray()
        }
        null
    }

    override fun close() = synchronized(lock) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        runCatching { Archive.readFree(archive) }
        if (mmapAddress != 0L) runCatching { Os.munmap(mmapAddress, mmapSize) }
    }
}
