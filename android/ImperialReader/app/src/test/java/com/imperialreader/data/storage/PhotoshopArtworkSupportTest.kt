package com.imperialreader.data.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoshopArtworkSupportTest {
    @Test
    fun recognizesPsdAndPsbAsLocalReaderPages() {
        assertTrue(LocalArtworkPreview.supports("Chapter 01.PSD"))
        assertTrue(LocalArtworkPreview.supports("Chapter 02.psb"))
        assertFalse(LocalArtworkPreview.supports("Chapter 03.jpg"))
    }

    @Test
    fun scansPsdAndPsbAsLocalChapterFiles() {
        assertTrue(LocalScanner.isArchive("Chapter 01.psd"))
        assertTrue(LocalScanner.isArchive("Chapter 02.psb"))
        assertFalse(LocalScanner.isArchive("Chapter 03.txt"))
    }
}
