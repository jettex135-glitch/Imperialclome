package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

class RavenScansSource : HttpSource() {
    override val id = "wp_ravenscans_com"
    override val name = "RavenScans"
    override val lang = "en"
    override val baseUrl = "https://ravenscans.org"

    private val generic = GenericSource(name, baseUrl)

    override fun imageHeaders(): Map<String, String> = generic.imageHeaders()

    override suspend fun getPopular(page: Int): MangasPage = generic.getPopular(page)
    override suspend fun getLatest(page: Int): MangasPage = generic.getLatest(page)
    override suspend fun search(query: String, page: Int): MangasPage = generic.search(query, page)
    override suspend fun getMangaDetails(manga: SManga): SManga =
        generic.getMangaDetails(manga.copy(url = canonicalUrl(manga.url)))

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val mangaUrl = canonicalUrl(manga.url).trimEnd('/') + "/"
        val doc = Jsoup.parse(getString(mangaUrl), mangaUrl)
        val siteHost = URI(baseUrl).host
        val chapters = doc.select("#chapterlist li").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href")
            if (runCatching { URI(url).host }.getOrNull() != siteHost) return@mapNotNull null

            val name = item.selectFirst(".chapternum")?.text()?.trim()
                ?.ifBlank { null }
                ?: link.text().trim()
            if (name.isBlank()) return@mapNotNull null

            SChapter(
                url = url,
                name = name,
                dateUpload = parseSiteDate(item.selectFirst(".chapterdate")?.text().orEmpty()),
                chapterNumber = parseChapterNumber(name)
            )
        }.distinctBy { it.url }

        if (chapters.isEmpty()) {
            throw IOException("RavenScans returned no readable chapters for this series")
        }
        return chapters.filter { it.chapterNumber >= 0f }.sortedByDescending { it.chapterNumber } +
            chapters.filter { it.chapterNumber < 0f }
    }

    override suspend fun getPages(chapter: SChapter): List<String> =
        generic.getPages(chapter.copy(url = canonicalUrl(chapter.url)))

    private fun canonicalUrl(url: String): String =
        url.replace(Regex("(?i)^https?://(?:www\\.)?ravenscans\\.com"), baseUrl)
}
