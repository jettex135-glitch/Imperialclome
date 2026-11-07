package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import com.imperialreader.data.network.CloudflareException
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Generic scraper for sites that run the WordPress "Madara" manga theme.
 * Many scanlation sites use it, so one class covers dozens of sites.
 */
open class MadaraSource(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
    private val mangaPath: String = "manga",
    idOverride: String? = null,
    override val nsfw: Boolean = false
) : HttpSource() {

    override val id: String = idOverride ?:
        "madara_" + (runCatching { URI(baseUrl).host }.getOrNull() ?: name).lowercase().replace(Regex("[^a-z0-9]"), "_")

    private val itemSelector = "div.page-item-detail, div.c-tabs-item__content"

    override suspend fun getPopular(page: Int): MangasPage =
        listRequest("$baseUrl/$mangaPath/page/$page/?m_orderby=views", page)

    override suspend fun getLatest(page: Int): MangasPage =
        listRequest("$baseUrl/$mangaPath/page/$page/?m_orderby=latest", page)

    override suspend fun search(query: String, page: Int): MangasPage =
        listRequest("$baseUrl/page/$page/?s=${enc(query)}&post_type=wp-manga", page)

    private suspend fun listRequest(url: String, page: Int): MangasPage {
        val html = try {
            getString(url)
        } catch (e: HttpStatusException) {
            // Madara returns 404 once you run past the last page.
            if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
            throw e
        }
        val doc = Jsoup.parse(html, baseUrl)
        val items = doc.select(itemSelector).mapNotNull { parseListItem(it) }
        return MangasPage(items.distinctBy { it.url }, items.isNotEmpty())
    }

    private fun parseListItem(el: Element): SManga? {
        val link = el.selectFirst(".post-title a, h3 a, h5 a, a[href]") ?: return null
        val title = link.text().trim().ifBlank { link.attr("title").trim() }
        if (title.isBlank()) return null
        val href = link.absUrl("href").ifBlank { absoluteUrl(link.attr("href")) }
        return SManga(url = href, title = title, thumbnailUrl = imageOf(el.selectFirst("img")))
    }

    private fun imageOf(img: Element?): String? {
        if (img == null) return null
        val raw = listOf("data-src", "data-lazy-src", "data-cfsrc", "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("srcset").substringBefore(" ").trim().takeIf { it.isNotBlank() }
        return raw?.let { absoluteUrl(it) }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val title = doc.selectFirst(".post-title h1, .post-title h3")?.also { it.select("span").remove() }?.text()?.trim()
        manga.title = title?.takeIf { it.isNotBlank() } ?: manga.title
        manga.author = doc.select(".author-content a").joinToString(", ") { it.text() }.ifBlank { null }
        manga.artist = doc.select(".artist-content a").joinToString(", ") { it.text() }.ifBlank { null }
        manga.description = doc.selectFirst("div.description-summary div.summary__content, div.summary_content div.post-content_item > div.summary-content, .manga-excerpt")
            ?.text()?.trim()?.ifBlank { null }
        manga.genre = (doc.select("div.genres-content a").map { it.text().trim() } + extractGenreTags(doc))
            .distinct().joinToString(", ").ifBlank { manga.genre }
        manga.thumbnailUrl = imageOf(doc.selectFirst("div.summary_image img")) ?: manga.thumbnailUrl
        val statusText = doc.select("div.post-content_item").firstOrNull {
            it.selectFirst("h5, .summary-heading")?.text()?.contains("Status", true) == true
        }?.selectFirst("div.summary-content")?.text()?.lowercase().orEmpty()
        manga.status = when {
            "ongoing" in statusText || "publishing" in statusText -> 1
            "completed" in statusText || "complete" in statusText || "end" in statusText -> 2
            "hiatus" in statusText || "on hold" in statusText -> 3
            "axed" in statusText || "discontinued" in statusText ||
                "cancel" in statusText || "drop" in statusText -> 4
            else -> 0
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val mangaUrl = manga.url.trimEnd('/') + "/"
        val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to mangaUrl)

        // 1) modern Madara: POST <manga>/ajax/chapters/
        var doc: Document? = try {
            Jsoup.parse(postString(mangaUrl + "ajax/chapters/", headers = ajaxHeaders), baseUrl)
        } catch (e: CloudflareException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        var elements = doc?.select("li.wp-manga-chapter").orEmpty()

        // 2) the manga page itself may already contain the chapter list
        var pageDoc: Document? = null
        if (elements.isEmpty()) {
            pageDoc = Jsoup.parse(getString(mangaUrl), mangaUrl)
            elements = pageDoc.select("li.wp-manga-chapter")
        }

        // 3) legacy Madara: admin-ajax.php with the numeric post id
        if (elements.isEmpty() && pageDoc != null) {
            val postId = pageDoc.selectFirst("#manga-chapters-holder")?.attr("data-id")?.ifBlank { null }
                ?: pageDoc.selectFirst("input.rating-post-id")?.attr("value")
            if (!postId.isNullOrBlank()) {
                doc = try {
                    Jsoup.parse(
                        postString(
                            "$baseUrl/wp-admin/admin-ajax.php",
                            mapOf("action" to "manga_get_chapters", "manga" to postId),
                            ajaxHeaders
                        ), baseUrl
                    )
                } catch (e: CloudflareException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                elements = doc?.select("li.wp-manga-chapter").orEmpty()
            }
        }

        val chapters = elements.mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { absoluteUrl(a.attr("href")) }
            val name = a.text().trim()
            if (name.isBlank()) return@mapNotNull null
            val dateText = li.selectFirst("span.chapter-release-date i, span.chapter-release-date")?.text()?.trim().orEmpty()
            val dateFromImg = li.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
            SChapter(
                url = href,
                name = name,
                dateUpload = parseDate(dateText.ifBlank { dateFromImg }),
                chapterNumber = parseChapterNumber(name)
            )
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("Madara did not return a readable chapter list")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val url = if (chapter.url.contains("?")) chapter.url else chapter.url.trimEnd('/') + "/?style=list"
        val doc = Jsoup.parse(getString(url), chapter.url)
        val imgs = doc.select("div.page-break img, li.blocks-gallery-item img, .reading-content img")
        return imgs.mapNotNull { img ->
            listOf("data-src", "data-lazy-src", "data-cfsrc", "src")
                .map { img.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { absoluteUrl(it) }
        }.distinct()
    }

    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L
        val dateFormats = listOf("MMMM dd, yyyy", "MMM dd, yyyy", "dd MMMM yyyy", "dd/MM/yyyy", "yyyy-MM-dd")
            .map { SimpleDateFormat(it, Locale.US) }
        val rel = Regex("""(\d+)\s*(second|min|hour|day|week|month|year)""", RegexOption.IGNORE_CASE).find(text)
        if (rel != null) {
            val n = rel.groupValues[1].toLong()
            val unit = when (rel.groupValues[2].lowercase()) {
                "second" -> 1_000L
                "min" -> 60_000L
                "hour" -> 3_600_000L
                "day" -> 86_400_000L
                "week" -> 604_800_000L
                "month" -> 2_592_000_000L
                else -> 31_536_000_000L
            }
            return System.currentTimeMillis() - n * unit
        }
        for (f in dateFormats) {
            val d = runCatching { f.parse(text) }.getOrNull()
            if (d != null) return d.time
        }
        return 0L
    }
}
