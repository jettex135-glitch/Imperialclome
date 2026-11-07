package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeSourcesParsingTest {
    private val baseUrl = "https://animepahetv.to"
    private val animeId = "595e630426d2cb9f3e7314cff2707006"

    @Test
    fun parsesAnimePaheReleaseApiEpisodesAndPagination() {
        val response = """
            {
              "current_page": 1,
              "last_page": 2,
              "data": [
                {
                  "episode": 8,
                  "title": "Episode 8",
                  "session": "bced4437fcaff42c57a70f6ae2d9f14b",
                  "created_at": "2026-11-07 02:20:03"
                }
              ]
            }
        """.trimIndent()

        val page = parseAnimePaheEpisodeResponse(response, animeId, baseUrl)

        assertEquals(2, page.lastPage)
        assertEquals(1, page.episodes.size)
        assertEquals("Episode 8", page.episodes.single().name)
        assertEquals(8f, page.episodes.single().chapterNumber)
        assertEquals(
            "$baseUrl/play/$animeId/bced4437fcaff42c57a70f6ae2d9f14b",
            page.episodes.single().url
        )
        assertTrue(page.episodes.single().dateUpload > 0L)
    }

    @Test
    fun parsesAnimePaheJavaScriptServerAsWebPlayerUrl() {
        val response = """
            {
              "status": "success",
              "result": "",
              "servers": [
                {
                  "name": "Sub-Megaplay",
                  "resolution": "Multi",
                  "url": "https://megaplay.buzz/stream/s-2/849818/sub"
                }
              ]
            }
        """.trimIndent()

        val servers = parseAnimePaheServers(response, baseUrl)

        assertEquals(1, servers.size)
        assertEquals("Sub-Megaplay", servers.single().name)
        assertFalse(servers.single().dub)
        assertEquals("https://megaplay.buzz/stream/s-2/849818/sub", servers.single().url)
    }
}
