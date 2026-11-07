package com.example.data.linked

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedIdsTest {
    @Test
    fun folderIdIsDeterministicPerUriAndMode() {
        val uri = "content://com.example.provider/tree/primary%3AKitaplik"

        assertEquals(
            LinkedIds.folderId(uri, "single"),
            LinkedIds.folderId(uri, "single")
        )
        assertNotEquals(
            LinkedIds.folderId(uri, "single"),
            LinkedIds.folderId(uri, "library")
        )
        assertNotEquals(
            LinkedIds.folderId(uri, "single"),
            LinkedIds.folderId("content://other", "single")
        )
    }

    @Test
    fun mangaIdDependsOnFolderAndPath() {
        val folderId = LinkedIds.folderId("content://tree", "single")

        assertEquals(
            LinkedIds.mangaId(folderId, "folder:One Piece"),
            LinkedIds.mangaId(folderId, "folder:One Piece")
        )
        assertNotEquals(
            LinkedIds.mangaId(folderId, "folder:One Piece"),
            LinkedIds.mangaId(folderId, "folder:One Punch Man")
        )
        assertNotEquals(
            LinkedIds.mangaId(folderId, "folder:One Piece"),
            LinkedIds.mangaId("linkedfolder_other", "folder:One Piece")
        )
    }

    @Test
    fun chapterIdDisambiguatesSameChapterNameAcrossMangas() {
        val folderId = LinkedIds.folderId("content://tree", "library")

        assertNotEquals(
            LinkedIds.chapterId(folderId, "folder:A", "folder:Bölüm 1"),
            LinkedIds.chapterId(folderId, "folder:B", "folder:Bölüm 1")
        )
        assertEquals(
            LinkedIds.chapterId(folderId, "folder:A", "folder:Bölüm 1"),
            LinkedIds.chapterId(folderId, "folder:A", "folder:Bölüm 1")
        )
    }

    @Test
    fun generatedIdsAreSafeForRoomAndFileNames() {
        val folderId = LinkedIds.folderId("content://tree/ıslak", "single")
        val mangaId = LinkedIds.mangaId(folderId, "folder:Çok Özel / Manga")
        val chapterId = LinkedIds.chapterId(folderId, "folder:Çok Özel / Manga", "@root-images")
        val safePattern = Regex("[A-Za-z0-9_]+")

        assertTrue(safePattern.matches(folderId))
        assertTrue(safePattern.matches(mangaId))
        assertTrue(safePattern.matches(chapterId))
    }
}
