package com.example.data.linked

import java.security.MessageDigest

object LinkedIds {
    private fun sha256(value: String, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) builder.append(String.format("%02x", byte))
        return builder.substring(0, length)
    }

    fun folderId(treeUri: String, mode: String): String =
        "linkedfolder_" + sha256("$treeUri|$mode", 20)

    fun mangaId(folderId: String, relativePath: String): String =
        "linkedmanga_" + sha256("$folderId|manga|$relativePath", 24)

    fun chapterId(folderId: String, mangaRelativePath: String, chapterRelativePath: String): String =
        "linkedchapter_" + sha256("$folderId|chapter|$mangaRelativePath|$chapterRelativePath", 24)
}
