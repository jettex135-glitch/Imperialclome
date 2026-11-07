package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** MangaPill catalog and chapter lists. */
class MangaPillSource : HttpSource() {
    override val id = "mangapill"
    override val name = "MangaPill"
    override val lang = "en"
    override val baseUrl = "https://mangapill.com"

    override suspend fun getPopular(page: Int): MangasPage =
        if (page == 1) list("$baseUrl/", page, singlePage = true) else MangasPage(emptyList(), false)
    override suspend fun getLatest(page: Int): MangasPage = list("$baseUrl/chapters", page, singlePage = true)
    override suspend fun search(query: String, page: Int): MangasPage =
        list("$baseUrl/search?q=${enc(query)}&type=&status=&page=$page", page)

    private suspend fun list(url: String, page: Int, singlePage: Boolean = false): MangasPage {
        if (singlePage && page > 1) return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(getString(url), baseUrl)
        // Layout-agnostic: group every /manga/ link by href; title = first non-blank link text or image alt.
        val byHref = LinkedHashMap<String, SManga>()
        for (a in doc.select("a[href^=/manga/]")) {
            val href = a.absUrl("href")
            val entry = byHref.getOrPut(href) { SManga(url = href, title = "") }
            val img = a.selectFirst("img")
            if (entry.thumbnailUrl == null && img != null) entry.thumbnailUrl = lazyImage(img)
            if (entry.title.isBlank()) {
                entry.title = a.text().trim().ifBlank { img?.attr("alt").orEmpty().trim() }
            }
        }
        val items = byHref.values.filter { it.title.isNotBlank() }
        return MangasPage(items, !singlePage && items.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.description = doc.selectFirst("p.text-sm.text--secondary, meta[name=description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }?.trim()?.ifBlank { null } ?: manga.description
        manga.genre = doc.select("a[href*=genre=]").joinToString(", ") { it.text() }.ifBlank { null }
        manga.thumbnailUrl = lazyImage(doc.selectFirst("img")) ?: manga.thumbnailUrl
        manga.status = statusFromText(doc.text())
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        return doc.select("#chapters a[href^=/chapters/]").map { a ->
            val name = a.text().trim()
            SChapter(url = a.absUrl("href"), name = name, chapterNumber = parseChapterNumber(name))
        }.distinctBy { it.url }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc: Document = Jsoup.parse(getString(chapter.url), chapter.url)
        return doc.select("picture img, chapter-page img").mapNotNull { lazyImage(it) }.distinct()
    }
}
