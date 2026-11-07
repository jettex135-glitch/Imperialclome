package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class MangaFreakSource : HttpSource() {
    override val id = "mangafreak"
    override val name = "MangaFreak"
    override val lang = "en"
    override val baseUrl = "https://ww3.mangafreak.me"

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/Mangalist/All/$page", page)

    override suspend fun getLatest(page: Int): MangasPage =
        list("$baseUrl/Latest_Releases/$page", page)

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        return list("$baseUrl/Find/${enc(query.trim().lowercase())}?page=$page", page)
    }

    private suspend fun list(url: String, page: Int): MangasPage {
        val html = try {
            getString(url)
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
            throw e
        }
        val doc = Jsoup.parse(html, baseUrl)
        val mangas = LinkedHashMap<String, SManga>()
        for (link in doc.select("a[href^=/Manga/]")) {
            val mangaUrl = link.absUrl("href")
            val path = runCatching { java.net.URI(mangaUrl).path }.getOrNull().orEmpty()
            if (!path.matches(Regex("""(?i)^/Manga/[^/]+/?$"""))) continue

            val manga = mangas.getOrPut(mangaUrl) { SManga(url = mangaUrl, title = "") }
            val image = link.selectFirst("img")
            if (manga.thumbnailUrl == null) manga.thumbnailUrl = lazyImage(image)
            if (manga.title.isBlank()) {
                manga.title = link.text().trim()
                    .ifBlank { link.attr("title").trim() }
                    .ifBlank { image?.attr("alt").orEmpty().trim() }
                    .ifBlank { link.parent()?.selectFirst("h1, h2, h3, h4")?.text()?.trim().orEmpty() }
            }
        }

        val items = mangas.values.filter { it.title.isNotBlank() }
        return MangasPage(items, hasNextPage(doc, page) && items.isNotEmpty())
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select("a.next_p, a[rel=next]").isNotEmpty() ||
            doc.select("a.n_p[href]").any { it.attr("href").contains("page=${page + 1}") }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val details = doc.selectFirst(".manga_series_data")
        manga.title = details?.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = lazyImage(details?.selectFirst(".manga_series_image img")) ?: manga.thumbnailUrl

        val rows = details?.children()?.toList().orEmpty().filter { it.tagName() == "div" }
        fun field(label: String): String? = rows.asSequence()
            .map { it.ownText().trim() }
            .firstOrNull { it.startsWith(label, ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.ifBlank { null }

        manga.author = field("Written By")
        manga.artist = field("Illustrated By")
        manga.genre = details?.select(".series_sub_genre_list a")
            ?.joinToString(", ") { it.text().trim() }?.ifBlank { null } ?: manga.genre
        manga.description = doc.selectFirst(".manga_series_description p")?.text()?.trim()
            ?.ifBlank { null } ?: manga.description
        manga.status = statusFromText(details?.text().orEmpty())
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val chapters = doc.select(".manga_series_list_section a.chapter-link[href]").mapNotNull { link ->
            val name = link.text().trim()
            if (name.isBlank()) return@mapNotNull null
            val row = link.closest("tr")
            SChapter(
                url = link.absUrl("href"),
                name = name,
                dateUpload = parseDate(row?.select("td")?.getOrNull(1)?.text().orEmpty()),
                chapterNumber = parseChapterNumber(name)
            )
        }.distinctBy { it.url }

        if (chapters.isEmpty()) throw IOException("MangaFreak returned no readable chapters for this series")
        return chapters.filter { it.chapterNumber >= 0f }.sortedByDescending { it.chapterNumber } +
            chapters.filter { it.chapterNumber < 0f }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select("#gohere[src]").mapNotNull { image: Element -> lazyImage(image) }.distinct()
        if (pages.isEmpty()) throw IOException("MangaFreak returned no readable pages for this chapter")
        return pages
    }

    private fun parseDate(text: String): Long =
        runCatching { SimpleDateFormat("yyyy/MM/dd", Locale.US).parse(text.trim())?.time }.getOrNull() ?: 0L
}
