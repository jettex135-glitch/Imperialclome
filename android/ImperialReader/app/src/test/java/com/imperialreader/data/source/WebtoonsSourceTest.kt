package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class WebtoonsSourceTest {
    @Test
    fun extractsActualServerRenderedImageUrls() {
        val pages = WebtoonsSource.parseEmbeddedImageList(
            """var imageList = [{ url: "https://webtoon-phinf.pstatic.net/page-1.png", spec: "PNG" }, { url: "https://webtoon-phinf.pstatic.net/page-2.png", spec: "PNG" }];"""
        )

        assertEquals(
            listOf(
                "https://webtoon-phinf.pstatic.net/page-1.png",
                "https://webtoon-phinf.pstatic.net/page-2.png"
            ),
            pages
        )
    }

    @Test
    fun extractsMp3AndM4aAudioUrlsFromEpisodeMarkup() {
        val mp3 = Jsoup.parse(
            """<audio><source src="https://webtoon-phinf.pstatic.net/bgm/episode.mp3?token=abc" type="audio/mpeg"></audio>""",
            "https://m.webtoons.com/viewer/1"
        )
        val m4a = Jsoup.parse(
            """<script>window.episodeAudio = "https:\/\/webtoon-phinf.pstatic.net\/bgm\/episode.m4a";</script>""",
            "https://m.webtoons.com/viewer/2"
        )

        assertEquals(
            "https://webtoon-phinf.pstatic.net/bgm/episode.mp3?token=abc",
            WebtoonsSource.parseViewerAudioUrl(mp3, "https://m.webtoons.com/viewer/1")
        )
        assertEquals(
            "https://webtoon-phinf.pstatic.net/bgm/episode.m4a",
            WebtoonsSource.parseViewerAudioUrl(m4a, "https://m.webtoons.com/viewer/2")
        )
    }

    @Test
    fun doesNotTreatEpisodeImagesAsAudio() {
        val document = Jsoup.parse(
            """<div id="_imageList"><img src="https://webtoon-phinf.pstatic.net/episode.jpg"></div>""",
            "https://m.webtoons.com/viewer/3"
        )

        assertNull(WebtoonsSource.parseViewerAudioUrl(document, "https://m.webtoons.com/viewer/3"))
    }

    @Test
    fun mapsLiveEpisodeRecordsIntoNewestFirstChapters() {
        val chapters = WebtoonsSource.parseWebtoonChapters(
            listOf(
                episode("Episode 1", 1, "https://m.webtoons.com/viewer/1"),
                episode("Episode 2", 2, "https://m.webtoons.com/viewer/2"),
                episode("Episode 3", 3, "https://m.webtoons.com/viewer/3", hasBgm = true)
            )
        )

        assertEquals(listOf(3f, 2f, 1f), chapters.map { it.chapterNumber })
        assertEquals("Episode 3 (ch. 3) ♫", chapters.first().name)
        assertEquals("https://m.webtoons.com/viewer/3", chapters.first().url)
        assertTrue(chapters.first().dateUpload > 0L)
    }

    @Test
    fun assignsSequentialNumbersToNonstandardEpisodeTitles() {
        val chapters = WebtoonsSource.parseWebtoonChapters(
            listOf(
                episode("A New Beginning", -1, "https://m.webtoons.com/viewer/1"),
                episode("The Journey Continues", -1, "https://m.webtoons.com/viewer/2"),
                episode("A Final Chapter", -1, "https://m.webtoons.com/viewer/3")
            )
        )

        assertEquals(listOf(3f, 2f, 1f), chapters.map { it.chapterNumber })
    }

    private fun episode(
        title: String,
        number: Int,
        url: String,
        hasBgm: Boolean = false
    ) = WebtoonEpisode(
        title = title,
        viewerLink = url,
        publishedAt = number.coerceAtLeast(1) * 1_000L,
        episodeNumber = number,
        hasBgm = hasBgm
    )
}
