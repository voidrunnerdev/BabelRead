package com.example.data.linked

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedFolderMihonRulesTest {

    @Test
    fun `recognizes Mihon cover images so they never become fake chapters`() {
        assertTrue(LinkedFolderScanner.isCoverImageName("cover.jpg"))
        assertTrue(LinkedFolderScanner.isCoverImageName("COVER.PNG"))
        assertTrue(LinkedFolderScanner.isCoverImageName("Cover.webp"))
        assertFalse(LinkedFolderScanner.isCoverImageName("001.jpg"))
        assertFalse(LinkedFolderScanner.isCoverImageName("cover_page_01.jpg"))
        assertFalse(LinkedFolderScanner.isCoverImageName("cover.txt"))
    }

    @Test
    fun `ignores hidden dot-files and system folders`() {
        assertTrue(LinkedFolderScanner.isHiddenEntryName(".nomedia"))
        assertTrue(LinkedFolderScanner.isHiddenEntryName(".thumbnails"))
        assertTrue(LinkedFolderScanner.isHiddenEntryName(""))
        assertTrue(LinkedFolderScanner.isHiddenEntryName(null))
        assertFalse(LinkedFolderScanner.isHiddenEntryName("Chainsaw Man"))
    }

    @Test
    fun `recognizes volume and chapter folder names so multi-volume series stay one manga`() {
        assertTrue(LinkedFolderScanner.isVolumeOrChapterFolderName("Vol. 1"))
        assertTrue(LinkedFolderScanner.isVolumeOrChapterFolderName("Volume 02"))
        assertTrue(LinkedFolderScanner.isVolumeOrChapterFolderName("Cilt 3"))
        assertTrue(LinkedFolderScanner.isVolumeOrChapterFolderName("Ch. 12"))
        assertTrue(LinkedFolderScanner.isVolumeOrChapterFolderName("Bölüm 5"))
        assertFalse(LinkedFolderScanner.isVolumeOrChapterFolderName("Chainsaw Man"))
        assertFalse(LinkedFolderScanner.isVolumeOrChapterFolderName("Berserk"))
    }
}
