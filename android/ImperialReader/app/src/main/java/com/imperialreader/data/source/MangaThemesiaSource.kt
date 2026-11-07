package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException

/** Engine for the "MangaThemesia" WordPress theme (a very common non-Madara scanlation theme). */
open class MangaThemesiaSource(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
    private val mangaPath: String = "manga"
) : HttpSource() {

    override val id: String = "themesia_" + hostId(baseUrl, name)

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/$mangaPath/?page=$page&order=popular", page)

    override suspend fun getLatest(page: Int): MangasPage =
        list("$baseUrl/$mangaPath/?page=$page&order=update", page)

    override suspend fun search(query: String, page: Int): MangasPage =
        list("$baseUrl/page/$page/?s=${enc(query)}", page)

    private suspend fun list(url: String, page: Int): MangasPage {
        val html = try {
            getString(url)
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
            throw e
        }
        val doc = Jsoup.parse(html, baseUrl)
        val items = doc.select("div.listupd div.bs div.bsx, div.bs div.bsx").mapNotNull { parseItem(it) }
        val hasNext = doc.selectFirst("a.next.page-numbers, div.pagination a.next, div.hpage a.r") != null
        return MangasPage(items.distinctBy { it.url }, hasNext)
    }

    private fun parseItem(el: Element): SManga? {
        val a = el.selectFirst("a[href]") ?: return null
        val title = a.attr("title").ifBlank { el.selectFirst(".tt, h2, h4")?.text().orEmpty() }.trim()
        if (title.isBlank()) return null
        return SManga(url = a.absUrl("href"), title = title, thumbnailUrl = lazyImage(el.selectFirst("img")))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = lazyImage(doc.selectFirst("div.thumb img, .infox img")) ?: manga.thumbnailUrl

        val rows = doc.select(".infotable tr, .spe span, .imptdt, .tsinfo .imptdt").map { it.text().trim() }
        fun field(label: String): String? =
            rows.firstOrNull { it.startsWith(label, ignoreCase = true) }
                ?.substring(label.length)?.trim(' ', ':')?.ifBlank { null }

        manga.author = field("Author") ?: field("Authors")
        manga.artist = field("Artist")
        manga.status = statusFromText(field("Status").orEmpty())
        manga.description = doc.select("div.entry-content[itemprop=description] p, div.entry-content.entry-content-single p")
            .joinToString("\n\n") { it.text() }.ifBlank { null } ?: manga.description
        manga.genre = (doc.select("span.mgen a, .seriestugenre a").map { it.text().trim() } + extractGenreTags(doc))
            .distinct().joinToString(", ").ifBlank { manga.genre }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val chapters = doc.select("#chapterlist li").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val name = li.selectFirst(".chapternum")?.text()?.trim().orEmpty().ifBlank { a.text().trim() }
            if (name.isBlank()) return@mapNotNull null
            SChapter(
                url = a.absUrl("href"),
                name = name,
                dateUpload = parseSiteDate(li.selectFirst(".chapterdate")?.text().orEmpty()),
                chapterNumber = parseChapterNumber(li.attr("data-num").ifBlank { name })
            )
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("MangaThemesia did not return a readable chapter list")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        // Most installs ship the page list as JSON inside ts_reader.run({...})
        val m = Regex("""ts_reader\.run\((\{.*?\})\)\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (m != null) {
            val fromJson = runCatching {
                val images = JSONObject(m.groupValues[1]).getJSONArray("sources").getJSONObject(0).getJSONArray("images")
                (0 until images.length()).map { images.getString(it) }
            }.getOrNull()
            if (!fromJson.isNullOrEmpty()) return fromJson
        }
        val doc = Jsoup.parse(html, chapter.url)
        return doc.select("#readerarea img").mapNotNull { lazyImage(it) }.distinct()
    }
}
