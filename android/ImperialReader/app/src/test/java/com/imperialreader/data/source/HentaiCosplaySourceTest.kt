package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HentaiCosplaySourceTest {
    @Test
    fun parsesMobileGalleryListingsAndDates() {
        val result = HentaiCosplaySource.parseListingHtml(
            """
                <ul id="entry_list">
                    <li><a href="/image/gallery-1">
                        <img src="/upload/cover-1.jpg">
                        <span>Gallery One</span>
                        <span class="posted">2025/6/10</span>
                    </a></li>
                </ul>
                <a class="paginator_page" rel="next" href="/search/page/2/">Next</a>
            """.trimIndent(),
            "https://hentai-cosplay-xxx.com/search/page/1/"
        )

        assertEquals(1, result.page.mangas.size)
        assertEquals("Gallery One", result.page.mangas.single().title)
        assertEquals(
            "https://hentai-cosplay-xxx.com/image/gallery-1",
            result.page.mangas.single().url
        )
        assertEquals(
            "https://hentai-cosplay-xxx.com/upload/cover-1.jpg",
            result.page.mangas.single().thumbnailUrl
        )
        assertEquals("2025/6/10", result.postedDates[result.page.mangas.single().url])
        assertTrue(result.page.hasNextPage)
    }

    @Test
    fun parsesDesktopListingsAndIgnoresInvalidEntries() {
        val result = HentaiCosplaySource.parseListingHtml(
            """
                <div class="image-list-item">
                    <a href="/image/gallery-2"><img src="/upload/cover-2.jpg"></a>
                    <span class="image-list-item-title">Gallery Two</span>
                    <span class="image-list-item-regist-date">2025/6/11</span>
                </div>
                <div class="image-list-item">
                    <a href="/image/missing-title"></a>
                </div>
                <div class="wp-pagenavi"><a rel="next" href="/ranking/page/2/">Next</a></div>
            """.trimIndent(),
            "https://hentai-cosplay-xxx.com/ranking/page/1/"
        )

        assertEquals(listOf("Gallery Two"), result.page.mangas.map { it.title })
        assertTrue(result.page.hasNextPage)
        assertEquals("2025/6/11", result.postedDates.values.single())
    }

    @Test
    fun parsesGalleryImagesAndExcludesRelatedThumbnails() {
        val pages = HentaiCosplaySource.parseGalleryImages(
            """
                <amp-img src="/upload/page-1.jpg"></amp-img>
                <amp-img class="related-thumbnail" src="/upload/related.jpg"></amp-img>
                <amp-img src="http://hentai-cosplay-xxx.com/upload/page-2.jpg"></amp-img>
            """.trimIndent(),
            "https://hentai-cosplay-xxx.com/story/gallery-1"
        )

        assertEquals(
            listOf(
                "https://hentai-cosplay-xxx.com/upload/page-1.jpg",
                "https://hentai-cosplay-xxx.com/upload/page-2.jpg"
            ),
            pages
        )
        assertFalse(pages.any { it.contains("related") })
    }
}
