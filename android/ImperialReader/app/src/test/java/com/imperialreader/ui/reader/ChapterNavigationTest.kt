package com.imperialreader.ui.reader

import com.imperialreader.data.db.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterNavigationTest {
    @Test
    fun keepsCurrentScanlationAndMarksDuplicateVariantsRead() {
        val chapters = listOf(
            chapter(1, 1f, "Chapter 1", "Fan Scan"),
            chapter(2, 1f, "Chapter 1 Official", "Official"),
            chapter(3, 2f, "Chapter 2 Fan", "Fan Scan"),
            chapter(4, 2f, "Chapter 2 Official", "Official"),
            chapter(5, 3f, "Chapter 3 Fan", "Fan Scan")
        )

        val next = ChapterNavigation.next(chapters, currentIndex = 1)

        assertEquals(3, next?.index)
        assertEquals(setOf(1L, 3L), next?.skippedChapterIds)
    }

    @Test
    fun switchesScanlationAfterCurrentOneHasNoFurtherChapters() {
        val chapters = listOf(
            chapter(1, 1f, "Chapter 1 Official", "Official"),
            chapter(2, 2f, "Chapter 2 Official", "Official", read = true),
            chapter(3, 3f, "Chapter 3 Official", "Official", read = true),
            chapter(4, 4f, "Chapter 4 Official", "Official", read = true),
            chapter(5, 5f, "Chapter 5 Official", "Official", read = true),
            chapter(6, 2f, "Chapter 2 Fan", "Fan Scan"),
            chapter(7, 6f, "Chapter 6 Fan", "Fan Scan")
        )

        val next = ChapterNavigation.next(chapters, currentIndex = 0)

        assertEquals(6, next?.index)
        assertEquals(setOf(6L), next?.skippedChapterIds)
    }

    @Test
    fun skipsAndMarksGaidenBeforeTheNextRegularChapter() {
        val chapters = listOf(
            chapter(1, 1f, "Chapter 1", "Official"),
            chapter(2, 1.5f, "Chapter 1.5 Gaiden", "Official"),
            chapter(3, 2f, "Chapter 2", "Official")
        )

        val next = ChapterNavigation.next(chapters, currentIndex = 0)

        assertEquals(2, next?.index)
        assertEquals(setOf(2L), next?.skippedChapterIds)
    }

    @Test
    fun hasNoNextChapterWhenOnlyReadOrGaidenEntriesRemain() {
        val chapters = listOf(
            chapter(1, 1f, "Chapter 1", "Official"),
            chapter(2, 2f, "Chapter 2", "Official", read = true),
            chapter(3, 2.5f, "Chapter 2.5 Gaiden", "Official")
        )

        assertNull(ChapterNavigation.next(chapters, currentIndex = 0))
    }

    private fun chapter(
        id: Long,
        number: Float,
        name: String,
        scanlator: String,
        read: Boolean = false
    ) = ChapterEntity(
        id = id,
        mangaId = 1,
        url = "chapter/$id",
        name = name,
        scanlator = scanlator,
        chapterNumber = number,
        sourceOrder = id.toInt(),
        read = read
    )
}
