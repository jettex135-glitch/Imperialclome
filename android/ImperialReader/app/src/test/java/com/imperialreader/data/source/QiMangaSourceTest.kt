package com.imperialreader.data.source

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QiMangaSourceTest {
    private val source = QiMangaSource()

    @Test
    fun parsesSearchResultsAndPagination() {
        val page = source.parseSeriesPage(
            JSONObject(
                """
                {
                  "current": 1,
                  "totalPages": 2,
                  "data": [{
                    "slug": "the-example",
                    "title": "The Example",
                    "cover": "https://media.qimanga.com/cover.webp",
                    "author": "A. Writer",
                    "genres": [{"name": "Action"}, {"name": "Fantasy"}],
                    "status": "ONGOING"
                  }]
                }
                """.trimIndent()
            ),
            requestedPage = 1
        )

        assertTrue(page.hasNextPage)
        assertEquals(1, page.mangas.size)
        assertEquals("The Example", page.mangas.single().title)
        assertEquals("https://qimanga.com/series/the-example", page.mangas.single().url)
        assertEquals("https://media.qimanga.com/cover.webp", page.mangas.single().thumbnailUrl)
        assertEquals("Action, Fantasy", page.mangas.single().genre)
    }

    @Test
    fun parsesChapterListAndSkipsPurchaseLockedChapters() {
        val chapters = source.parseChapters(
            JSONArray(
                """
                [
                  {
                    "slug": "chapter-12",
                    "number": 12,
                    "title": "A New Trial",
                    "createdAt": "2026-10-30T00:00:00.000Z"
                  },
                  {
                    "slug": "chapter-13",
                    "number": 13,
                    "requiresPurchase": true
                  }
                ]
                """.trimIndent()
            ),
            "the-example"
        )

        assertEquals(1, chapters.size)
        assertEquals("Chapter 12 - A New Trial", chapters.single().name)
        assertEquals("https://qimanga.com/series/the-example/chapters/chapter-12", chapters.single().url)
        assertTrue(chapters.single().dateUpload > 0L)
    }

    @Test
    fun parsesPageImageUrlsAndRejectsPurchaseLockedContent() {
        val pages = source.parsePageList(
            JSONObject(
                """{"images":[{"url":"https://media.qimanga.com/page-1.webp"},{"url":"https://media.qimanga.com/page-2.webp"}]}"""
            )
        )
        assertEquals(
            listOf(
                "https://media.qimanga.com/page-1.webp",
                "https://media.qimanga.com/page-2.webp"
            ),
            pages
        )

        val locked = runCatching {
            source.parsePageList(JSONObject("""{"requiresPurchase":true,"images":[]}"""))
        }
        assertFalse(locked.isSuccess)
        assertTrue(locked.exceptionOrNull()?.message.orEmpty().contains("requires purchase"))
    }
}
