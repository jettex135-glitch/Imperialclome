package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hits the REAL sites and checks the parsers return usable data (lists, chapters, images / MP4 files).
 * Skipped in normal builds; run with  LIVE_SOURCES=1 gradle testDebugUnitTest --tests "*LiveSourceCheckTest*" -i
 * or use the "Check live sources" GitHub Action. Every step prints what it found so a failure is easy to read.
 */
class LiveSourceCheckTest {
    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun enabled() = assumeTrue("set LIVE_SOURCES=1 to run", System.getenv("LIVE_SOURCES") == "1")

    private fun get(url: String, method: String = "GET", referer: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        referer?.let { c.setRequestProperty("Referer", it) }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (method == "POST") c.doOutput = true.also { c.outputStream.use { } }
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    @Test
    fun manhwaFan() {
        enabled()
        val base = "https://www.manhwafan.com"
        val (hc, home) = get("$base/")
        println("[ManhwaFan] home HTTP $hc, ${home.length} chars")
        val list = ManhwaFanSource.parseListing(home, base)
        println("[ManhwaFan] list: ${list.size} series, first = ${list.firstOrNull()}")
        assertTrue("no series parsed from the home page", list.isNotEmpty())
        assertTrue("series without cover: ${list.count { it.thumbnailUrl == null }}/${list.size}", list.count { it.thumbnailUrl != null } > list.size / 2)

        // Chapter list: try the same endpoints the app uses
        val series = list.first()
        val mangaUrl = series.url.trimEnd('/') + "/"
        val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to mangaUrl)
        var chapters = runCatching {
            val (c, body) = get(mangaUrl + "ajax/chapters/", "POST", headers = ajaxHeaders)
            println("[ManhwaFan] POST ajax/chapters -> HTTP $c, ${body.length} chars")
            ManhwaFanSource.parseChapters(Jsoup.parse(body, mangaUrl), mangaUrl)
        }.getOrElse { println("[ManhwaFan] ajax/chapters failed: $it"); emptyList() }
        if (chapters.isEmpty()) {
            val (c, body) = get(mangaUrl)
            println("[ManhwaFan] series page HTTP $c, ${body.length} chars")
            val doc = Jsoup.parse(body, mangaUrl)
            chapters = ManhwaFanSource.parseChapters(doc, mangaUrl)
            println("[ManhwaFan] post id = ${ManhwaFanSource.postIdOf(doc)}")
        }
        println("[ManhwaFan] chapters for '${series.title}': ${chapters.size}; newest = ${chapters.firstOrNull()}; oldest = ${chapters.lastOrNull()}")
        assertTrue("no chapters parsed for ${series.url}", chapters.isNotEmpty())

        val chapter = chapters.last()
        val (pc, page) = get(chapter.url.trimEnd('/') + "/?style=list", referer = mangaUrl)
        val images = ManhwaFanSource.parseChapterImages(Jsoup.parse(page, chapter.url))
        println("[ManhwaFan] chapter '${chapter.name}' HTTP $pc: ${images.size} images, first = ${images.firstOrNull()}")
        assertTrue("no page images parsed", images.isNotEmpty())
        val (ic, _) = get(images.first(), "HEAD", referer = "$base/")
        println("[ManhwaFan] first image HEAD -> HTTP $ic")
        assertTrue("image not loadable with Referer (HTTP $ic)", ic in 200..299)

        val (sc, search) = get("$base/?s=necromancer&post_type=wp-manga")
        println("[ManhwaFan] search HTTP $sc, looksLikeSearchPage=${ManhwaFanSource.looksLikeSearchPage(search)}, results=${ManhwaFanSource.parseListing(search, base).size}")
    }

    @Test
    fun sfmCompile() {
        enabled()
        val base = "https://sfmcompile.club"
        val (hc, home) = get("$base/")
        println("[SFM] home HTTP $hc, ${home.length} chars")
        val page = SfmCompileSource.parseListing(home, base, 1)
        println("[SFM] list: ${page.mangas.size} posts, hasNext=${page.hasNextPage}, first = ${page.mangas.firstOrNull()}")
        assertTrue("no posts parsed from the home page", page.mangas.isNotEmpty())
        println("[SFM] covers: ${page.mangas.count { it.thumbnailUrl != null }}/${page.mangas.size}")

        val post = page.mangas.first()
        val (pc, postHtml) = get(post.url, referer = "$base/")
        val doc = Jsoup.parse(postHtml, post.url)
        val videos = SfmCompileSource.parseVideoUrls(doc, base)
        println("[SFM] post '${post.title}' HTTP $pc: ${videos.size} video(s): ${videos.take(2)}")
        assertTrue("no MP4 found on ${post.url}", videos.isNotEmpty())
        val details = SfmCompileSource.parseDetails(doc, post.copy())
        println("[SFM] details: title='${details.title}' cover=${details.thumbnailUrl} genre=${details.genre}")

        val (vc, _) = get(videos.first(), "HEAD", referer = "$base/")
        println("[SFM] MP4 HEAD with Referer -> HTTP $vc")
        val (vc2, _) = get(videos.first(), "HEAD")
        println("[SFM] MP4 HEAD without Referer -> HTTP $vc2")
        assertTrue("MP4 not downloadable (HTTP $vc)", vc in 200..299)

        val (sc, search) = get("$base/?s=spider")
        println("[SFM] search HTTP $sc, results=${SfmCompileSource.parseListing(search, base, 1).mangas.size}")
    }
}
