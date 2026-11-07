package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser logic for ManhwaFan and SFM Compile. The HTML here is hand-written to mirror the structure
 * seen on the live sites; the real sites are exercised by LiveSourceCheckTest (run from GitHub Actions).
 */
class NewSourcesParsingTest {
    private val mf = "https://www.manhwafan.com"
    private val sfm = "https://sfmcompile.club"

    @Test
    fun manhwaFanListingGroupsCardLinksAndCleansTitles() {
        val html = """
            <div class="page-item-detail">
              <a href="$mf/manga/the-necromancer-of-a-noble-sword-family">
                <img data-src="https://img-r2.2xstorage.com/thumb/the-necromancer-of-a-noble-sword-family.webp"
                     alt="The Necromancer of a Noble Sword Family on ManhwaClan"
                     title="Read The Necromancer of a Noble Sword Family Manga Online - manhwafan.com"></a>
              <h3><a href="$mf/manga/the-necromancer-of-a-noble-sword-family">The Necromancer of a Noble Sword Family</a></h3>
              <a href="$mf/manga/the-necromancer-of-a-noble-sword-family/chapter-34">Chapter 34</a>
            </div>
            <div class="page-item-detail">
              <a href="/manga/damn-necromancer"><img src="//cdn.zinmanga1.com/thumb/damn-necromancer.webp" alt="Damn Necromancer on ManhwaClan"></a>
            </div>
            <a href="https://dub.sh/secretshm"><img src="/ad.png" alt="Triss"></a>
            <a href="$mf/manga/page/2/">2</a>
        """.trimIndent()
        val items = ManhwaFanSource.parseListing(html, mf)
        assertEquals(listOf("The Necromancer of a Noble Sword Family", "Damn Necromancer"), items.map { it.title })
        assertEquals("$mf/manga/the-necromancer-of-a-noble-sword-family", items[0].url)
        assertEquals("https://img-r2.2xstorage.com/thumb/the-necromancer-of-a-noble-sword-family.webp", items[0].thumbnailUrl)
        assertEquals("https://cdn.zinmanga1.com/thumb/damn-necromancer.webp", items[1].thumbnailUrl)
    }

    @Test
    fun manhwaFanChaptersKeepOnlyThisSeriesAndParseDecimalNumbers() {
        val mangaUrl = "$mf/manga/munou-to-iware"
        val doc = Jsoup.parse(
            """
            <a href="$mangaUrl/chapter-0">Read First</a><a href="$mangaUrl/chapter-10-2">Read Last</a>
            <ul>
              <li class="wp-manga-chapter"><a href="$mangaUrl/chapter-10-2">Chapter 10.2</a><span class="chapter-release-date"><i>2 hours ago</i></span></li>
              <li class="wp-manga-chapter"><a href="$mangaUrl/chapter-10">Chapter 10</a><span class="chapter-release-date"><i>October 30, 2026</i></span></li>
              <li class="wp-manga-chapter"><a href="$mangaUrl/chapter-0">Chapter 0</a></li>
              <li><a href="$mf/manga/other-series/chapter-5">Chapter 5</a></li>
            </ul>
            """.trimIndent(), mangaUrl
        )
        val chapters = ManhwaFanSource.parseChapters(doc, mangaUrl)
        assertEquals(listOf("Chapter 10.2", "Chapter 10", "Chapter 0"), chapters.map { it.name })
        assertEquals(listOf(10.2f, 10f, 0f), chapters.map { it.chapterNumber })
        assertTrue(chapters[0].dateUpload > 0L)
        assertTrue(chapters[1].dateUpload > 0L)
    }

    @Test
    fun manhwaFanChapterImagesSkipThumbnailsAndLogos() {
        val doc = Jsoup.parse(
            """
            <img src="/logo.png">
            <div class="reading-content">
              <div class="page-break"><img data-src=" https://img-r1.2xstorage.com/c/1.webp "></div>
              <div class="page-break"><img src="https://img-r1.2xstorage.com/c/2.webp"></div>
              <div class="page-break"><img src="https://img-r1.2xstorage.com/c/2.webp"></div>
              <img src="https://img-r1.2xstorage.com/thumb/related.webp">
            </div>
            """.trimIndent(), "$mf/manga/x/chapter-1"
        )
        assertEquals(
            listOf("https://img-r1.2xstorage.com/c/1.webp", "https://img-r1.2xstorage.com/c/2.webp"),
            ManhwaFanSource.parseChapterImages(doc)
        )
    }

    @Test
    fun manhwaFanHomePageIsNotSearchResults() {
        assertFalse(ManhwaFanSource.looksLikeSearchPage("<html><body class=\"home page-template\"></body></html>"))
        assertTrue(ManhwaFanSource.looksLikeSearchPage("<html><body class=\"search search-results\"></body></html>"))
        assertTrue(ManhwaFanSource.looksLikeSearchPage("<html><body><div class=\"c-tabs-item__content\"></div></body></html>"))
    }

    @Test
    fun manhwaFanChapterNumberFromPath() {
        assertEquals(10.2f, ManhwaFanSource.chapterNumberFromPath("chapter-10-2"), 0.001f)
        assertEquals(34f, ManhwaFanSource.chapterNumberFromPath("chapter-34"), 0.001f)
        assertEquals(-1f, ManhwaFanSource.chapterNumberFromPath("prologue"), 0.001f)
    }

    @Test
    fun sfmListingFindsPostsAndIgnoresTaxonomyLinks() {
        val html = """
            <article>
              <a href="$sfm/category/marvel/">Marvel</a>
              <h2><a href="$sfm/some-video-title/">Some Video Title</a></h2>
              <video poster="/wp-content/uploads/2026/11/some.jpg"><source src="$sfm/wp-content/uploads/2026/11/Some Video.mp4"></video>
            </article>
            <article><h2><a href="$sfm/page/3/">not a post</a></h2></article>
            <article><h3><a href="$sfm/another-one/">Another One</a></h3><img src="/wp-content/uploads/2021/11/SFM-Compile-Logo.png"></article>
            <a class="page-numbers" href="$sfm/page/2/">2</a><a class="next" href="$sfm/page/2/">Next</a>
        """.trimIndent()
        val page = SfmCompileSource.parseListing(html, sfm, 1)
        assertEquals(listOf("Some Video Title", "Another One"), page.mangas.map { it.title })
        assertEquals("$sfm/some-video-title/", page.mangas[0].url)
        assertEquals("$sfm/wp-content/uploads/2026/11/some.jpg", page.mangas[0].thumbnailUrl)
        assertNull(page.mangas[1].thumbnailUrl) // the site logo is never used as a cover
        assertTrue(page.hasNextPage)
    }

    @Test
    fun sfmVideoUrlsPreferTheSitesOwnMp4AndEncodeSpaces() {
        val doc = Jsoup.parse(
            """
            <meta property="og:video" content="$sfm/wp-content/uploads/2026/11/Clip One.mp4">
            <video controls><source src="/wp-content/uploads/2026/11/Clip One.mp4" type="video/mp4"></video>
            <a href="https://ads.example.com/promo.mp4">ad</a>
            <script>var u = "https:\/\/sfmcompile.club\/wp-content\/uploads\/2026\/11\/Clip-Two.mp4";</script>
            """.trimIndent(), "$sfm/clip-one/"
        )
        assertEquals(
            listOf(
                "$sfm/wp-content/uploads/2026/11/Clip%20One.mp4",
                "$sfm/wp-content/uploads/2026/11/Clip-Two.mp4"
            ),
            SfmCompileSource.parseVideoUrls(doc, sfm)
        )
    }

    @Test
    fun sfmPostSlugRules() {
        assertEquals("a-post", SfmCompileSource.postSlug("$sfm/a-post/", sfm))
        assertNull(SfmCompileSource.postSlug("$sfm/category/marvel/", sfm))
        assertNull(SfmCompileSource.postSlug("$sfm/page/2/", sfm))
        assertNull(SfmCompileSource.postSlug("https://other.example/a-post/", sfm))
    }
}
