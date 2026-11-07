package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import com.imperialreader.data.network.CloudflareException
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

/** Adapter for ReadComicsOnline's MMRCMS catalog and advanced-search routes. */
class ReadComicsOnlineSource : HttpSource() {
    override val id = "wp_readcomicsonline_ru"
    override val name = "ReadComicsOnline"
    override val lang = "en"
    override val baseUrl = "https://readcomicsonline.ru"

    private val generic = GenericSource(name, baseUrl)

    override suspend fun getPopular(page: Int): MangasPage =
        list(
            listOf(
                "$baseUrl/comic-list?sort=views&page=${page.coerceAtLeast(1)}",
                "$baseUrl/comic-list?sort=latest&page=${page.coerceAtLeast(1)}",
                "$baseUrl/comic-list?page=${page.coerceAtLeast(1)}"
            ), page
        )

    override suspend fun getLatest(page: Int): MangasPage =
        list(
            listOf(
                "$baseUrl/comic-list?sort=latest&page=${page.coerceAtLeast(1)}",
                "$baseUrl/comic-list?page=${page.coerceAtLeast(1)}"
            ), page
        )

    override suspend fun search(query: String, page: Int): MangasPage =
        list(
            listOf(
                "$baseUrl/advanced-search?name=${enc(query.trim())}&page=${page.coerceAtLeast(1)}",
                "$baseUrl/search?keyword=${enc(query.trim())}&page=${page.coerceAtLeast(1)}",
                "$baseUrl/?s=${enc(query.trim())}&paged=${page.coerceAtLeast(1)}"
            ), page
        )

    private suspend fun list(urls: List<String>, page: Int): MangasPage {
        var lastError: Exception? = null
        var receivedPage = false
        for (url in urls.distinct()) try {
        val doc = Jsoup.parse(getString(url), url)
        receivedPage = true
        val mangas = doc.select("a[href]").mapNotNull { link ->
            val path = sitePath(link.absUrl("href"))?.trimEnd('/') ?: return@mapNotNull null
            if (!path.matches(Regex("""^/comic/[^/]+$""", RegexOption.IGNORE_CASE))) return@mapNotNull null
            val card = sequenceOf(link).plus(link.parents().take(5))
                .firstOrNull { it.selectFirst("img") != null } ?: return@mapNotNull null
            val image = card.selectFirst("img") ?: return@mapNotNull null
            val title = sequenceOf(
                link.attr("title"), link.text(),
                card.selectFirst("h1, h2, h3, h4, .font-semibold, [title]")?.text().orEmpty(),
                image.attr("alt").orEmpty()
            ).map(String::trim).firstOrNull(String::isNotBlank) ?: return@mapNotNull null
            SManga(url = "$baseUrl$path", title = title, thumbnailUrl = imageUrl(image))
        }.distinctBy { it.url }
        if (mangas.isNotEmpty()) return MangasPage(mangas, hasNextPage(doc, page))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareException) {
            throw e
        } catch (e: Exception) {
            lastError = e
        }
        if (receivedPage) return MangasPage(emptyList(), false)
        throw lastError ?: IOException("ReadComicsOnline did not return a readable catalog page")
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = generic.getMangaDetails(manga)

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val markedLinks = doc.select(".overflow-hidden.border-ink-600 > a[href]")
        val chapterPattern = Regex("""(?i)(?:^|[-/])(?:chapter|issue|volume|vol)[-_/ ]*#?\d+(?:\.\d+)?(?:$|[-/])""")
        val chapterLinks = (markedLinks + doc.select("a[href]").filter { link ->
            val url = link.absUrl("href")
            sitePath(url) != null && chapterPattern.containsMatchIn(URI(url).path.orEmpty())
        }).distinctBy { it.absUrl("href") }
        val chapters = chapterLinks.mapNotNull { link ->
            val url = link.absUrl("href")
            if (sitePath(url) == null) return@mapNotNull null
            val name = listOf(link.selectFirst(".text-brand-400")?.text().orEmpty(), link.attr("title"), link.text(), link.attr("aria-label"))
                .map(String::trim).firstOrNull(String::isNotBlank)
                ?: URI(url).path.substringAfterLast('/').replace('-', ' ').ifBlank { return@mapNotNull null }
            SChapter(url = url, name = name, chapterNumber = parseChapterNumber(name))
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("ReadComicsOnline did not return a readable chapter list")
        return chapters.filter { it.chapterNumber >= 0f }.sortedByDescending { it.chapterNumber } +
            chapters.filter { it.chapterNumber < 0f }
    }

    override suspend fun getPages(chapter: SChapter): List<String> = generic.getPages(chapter)

    override fun imageHeaders(): Map<String, String> = generic.imageHeaders()

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.selectFirst("nav a[rel=next]") != null || doc.select("a[href]").any { link ->
            val href = link.absUrl("href")
            val linkedPage = Regex("""[?&]page=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            linkedPage != null && linkedPage > page ||
                link.text().trim().equals("next", ignoreCase = true) || link.classNames().any { it.equals("next", true) }
        }

    private fun sitePath(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.") ?: return null
        if (!host.equals("readcomicsonline.ru", ignoreCase = true)) return null
        return uri.path
    }

    private fun imageUrl(image: Element): String? =
        listOf("data-src", "data-lazy-src", "src", "data-original")
            .map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let(::absoluteUrl)
}