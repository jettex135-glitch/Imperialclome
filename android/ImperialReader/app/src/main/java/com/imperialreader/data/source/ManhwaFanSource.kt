package com.imperialreader.data.source

import com.imperialreader.data.network.CloudflareException
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * manhwafan.com: a ManhwaClan-style Madara site (series at /manga/<slug>, chapters at /manga/<slug>/chapter-<n>,
 * chapter list loaded by ajax, covers on 2xstorage / zinmanga CDNs).
 *
 * The markup is parsed by link pattern instead of theme class names, and every network step has fallbacks,
 * so a small theme change does not empty the lists. All parsing lives in [Companion] as pure functions
 * so it can be unit-tested and run against the live site (see LiveSourceCheckTest).
 */
class ManhwaFanSource : HttpSource() {
    override val id = "manhwafan_com"
    override val name = "ManhwaFan"
    override val lang = "en"
    override val baseUrl = "https://www.manhwafan.com"

    override suspend fun getPopular(page: Int): MangasPage = firstNonEmpty(
        listOf(
            "$baseUrl/manga/page/$page/?m_orderby=views",
            "$baseUrl/manga/page/$page/?m_orderby=trending",
            "$baseUrl/manga?orderby=trending&page=$page"
        ),
        homeFallback = page == 1
    )

    override suspend fun getLatest(page: Int): MangasPage = firstNonEmpty(
        listOf(
            "$baseUrl/manga/page/$page/?m_orderby=latest",
            "$baseUrl/manga/page/$page/"
        ),
        homeFallback = page == 1
    )

    private suspend fun firstNonEmpty(urls: List<String>, homeFallback: Boolean): MangasPage {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val items = parseListing(getString(url), baseUrl)
                if (items.isNotEmpty()) return MangasPage(items, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (homeFallback) {
            val items = parseListing(getString("$baseUrl/"), baseUrl)
            if (items.isNotEmpty()) return MangasPage(items, false)
        }
        lastError?.let { if (it is HttpStatusException && it.code == 404) return MangasPage(emptyList(), false) else throw it }
        return MangasPage(emptyList(), false)
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        val q = query.trim()
        if (q.isEmpty()) return MangasPage(emptyList(), false)
        var lastError: Exception? = null
        val candidates = listOf(
            "$baseUrl/page/$page/?s=${enc(q)}&post_type=wp-manga",
            "$baseUrl/?s=${enc(q)}&post_type=wp-manga&paged=$page"
        )
        for (url in candidates) {
            try {
                val html = getString(url)
                if (!looksLikeSearchPage(html)) continue // the home page ignores ?s=, so never treat it as results
                val items = parseListing(html, baseUrl)
                if (items.isNotEmpty()) return MangasPage(items, true)
                return MangasPage(emptyList(), false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
        }
        // WordPress REST fallback (Madara registers the wp-manga post type)
        try {
            val json = getString(
                "$baseUrl/wp-json/wp/v2/wp-manga?search=${enc(q)}&per_page=20&page=$page&_embed=wp:featuredmedia",
                mapOf("Accept" to "application/json")
            )
            val items = parseRestSearch(json)
            return MangasPage(items, items.size >= 20)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw lastError ?: e
        }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        return parseDetails(doc, manga)
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val mangaUrl = manga.url.trimEnd('/') + "/"
        val ajax = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to mangaUrl)
        var chapters: List<SChapter> = emptyList()

        // 1) Madara ajax endpoint
        try {
            chapters = parseChapters(Jsoup.parse(postString(mangaUrl + "ajax/chapters/", headers = ajax), mangaUrl), mangaUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareException) {
            throw e
        } catch (_: Exception) {
        }

        // 2) chapters already in the series page
        var pageDoc: Document? = null
        if (chapters.isEmpty()) {
            pageDoc = Jsoup.parse(getString(mangaUrl), mangaUrl)
            chapters = parseChapters(pageDoc, mangaUrl)
        }

        // 3) legacy admin-ajax with the numeric post id
        if (chapters.isEmpty() && pageDoc != null) {
            val postId = postIdOf(pageDoc)
            if (postId != null) {
                try {
                    val html = postString(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        mapOf("action" to "manga_get_chapters", "manga" to postId),
                        ajax
                    )
                    chapters = parseChapters(Jsoup.parse(html, mangaUrl), mangaUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CloudflareException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        if (chapters.isEmpty()) {
            throw IOException("ManhwaFan returned no chapter list for ${manga.title.ifBlank { manga.url }}")
        }
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val styled = if (chapter.url.contains("?")) chapter.url else chapter.url.trimEnd('/') + "/?style=list"
        var pages = parseChapterImages(Jsoup.parse(getString(styled), chapter.url))
        if (pages.isEmpty() && styled != chapter.url) {
            pages = parseChapterImages(Jsoup.parse(getString(chapter.url), chapter.url))
        }
        if (pages.isEmpty()) throw IOException("ManhwaFan returned no page images for this chapter")
        return pages
    }

    companion object {
        private val seriesPath = Regex("^/manga/([^/?#]+)/?$")
        private val chapterPath = Regex("^/manga/([^/?#]+)/(chapter[^/?#]*)/?$", RegexOption.IGNORE_CASE)

        private fun isSiteHost(url: String, baseUrl: String): Boolean {
            val h = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: return false
            return h.equals(runCatching { URI(baseUrl).host }.getOrNull()?.removePrefix("www."), ignoreCase = true)
        }

        private fun pathOf(url: String): String = runCatching { URI(url).rawPath }.getOrNull().orEmpty()

        fun looksLikeSearchPage(html: String): Boolean {
            val doc = Jsoup.parse(html)
            val bodyClass = doc.body().className()
            return Regex("""\bsearch(-results|-no-results)?\b""").containsMatchIn(bodyClass) ||
                doc.select("div.c-tabs-item__content").isNotEmpty()
        }

        fun imageUrlOf(img: Element?): String? {
            if (img == null) return null
            val raw = listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "src")
                .map { img.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?: img.attr("srcset").trim().substringBefore(" ").takeIf { it.isNotBlank() }
            return raw?.let { resolve(img.baseUri(), it) }
        }

        private fun resolve(base: String, raw: String): String = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> runCatching { URI(base).resolve(raw).toString() }.getOrDefault(raw)
        }

        private fun cleanTitle(raw: String): String = raw
            .replace(Regex("""\s+on\s+ManhwaClan\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Read\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Manga\s+Online\s*-\s*\S+\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

        /** Series cards found anywhere on a page: every link to /manga/<slug>, grouped by URL. */
        fun parseListing(html: String, baseUrl: String): List<SManga> {
            val doc = Jsoup.parse(html, baseUrl)
            val order = LinkedHashMap<String, MutableList<Element>>()
            for (a in doc.select("a[href]")) {
                val href = a.absUrl("href")
                if (href.isBlank() || !isSiteHost(href, baseUrl)) continue
                val m = seriesPath.matchEntire(pathOf(href)) ?: continue
                if (m.groupValues[1] == "page") continue
                order.getOrPut("${baseUrl.trimEnd('/')}/manga/${m.groupValues[1]}") { mutableListOf() }.add(a)
            }
            val out = ArrayList<SManga>()
            for ((url, anchors) in order) {
                val text = anchors.map { it.text().trim() }
                    .firstOrNull { it.isNotBlank() && !Regex("""^(chapter|ch\.?)\s*\d""", RegexOption.IGNORE_CASE).containsMatchIn(it) && it.length > 1 }
                val img = anchors.firstNotNullOfOrNull { it.selectFirst("img") }
                    ?: anchors.firstNotNullOfOrNull { it.parent()?.selectFirst("img") }
                val title = cleanTitle(
                    text ?: img?.attr("alt")?.takeIf { it.isNotBlank() } ?: img?.attr("title").orEmpty()
                ).ifBlank { cleanTitle(anchors.firstNotNullOfOrNull { it.attr("title").takeIf { t -> t.isNotBlank() } }.orEmpty()) }
                if (title.isBlank()) continue
                out += SManga(url = url, title = title, thumbnailUrl = imageUrlOf(img))
            }
            return out
        }

        fun parseRestSearch(json: String): List<SManga> {
            val arr = JSONArray(json)
            val out = ArrayList<SManga>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("link").trimEnd('/')
                val title = Jsoup.parse(o.optJSONObject("title")?.optString("rendered").orEmpty()).text().trim()
                if (link.isBlank() || title.isBlank()) continue
                val media = o.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)?.optString("source_url")
                out += SManga(url = link, title = title, thumbnailUrl = media?.ifBlank { null })
            }
            return out
        }

        fun parseDetails(doc: Document, manga: SManga): SManga {
            val h1 = doc.selectFirst(".post-title h1, .post-title h3, h1")
            h1?.select("span")?.remove()
            h1?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { manga.title = cleanTitle(it) }
            manga.thumbnailUrl = imageUrlOf(doc.selectFirst("div.summary_image img, .tab-summary img"))
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: manga.thumbnailUrl
            manga.author = doc.select(".author-content a").joinToString(", ") { it.text() }.ifBlank { null } ?: manga.author
            manga.artist = doc.select(".artist-content a").joinToString(", ") { it.text() }.ifBlank { null } ?: manga.artist
            val genres = doc.select("div.genres-content a, a[href*=/manga-genre/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            if (genres.isNotEmpty()) manga.genre = genres.joinToString(", ")
            val summary = doc.selectFirst("div.description-summary div.summary__content, div.summary__content, .manga-excerpt, .description-summary")
                ?.text()?.trim()
            // The site shows generic filler when a series has no synopsis; keep it out of the description.
            manga.description = summary?.takeIf { it.isNotBlank() && !it.contains("engaging", true) } ?: manga.description
            val statusText = doc.select("div.post-content_item, .post-status").firstOrNull {
                it.text().contains("Status", true)
            }?.text()?.lowercase().orEmpty()
            manga.status = when {
                "ongoing" in statusText -> 1
                "completed" in statusText -> 2
                "hiatus" in statusText || "on hold" in statusText -> 3
                "cancel" in statusText || "drop" in statusText -> 4
                else -> manga.status
            }
            return manga
        }

        fun postIdOf(doc: Document): String? {
            doc.selectFirst("#manga-chapters-holder")?.attr("data-id")?.takeIf { it.isNotBlank() }?.let { return it }
            doc.selectFirst("input.rating-post-id")?.attr("value")?.takeIf { it.isNotBlank() }?.let { return it }
            doc.selectFirst("link[rel=shortlink]")?.attr("href")?.let { Regex("""[?&]p=(\d+)""").find(it)?.groupValues?.get(1) }?.let { return it }
            return Regex("""postid-(\d+)""").find(doc.body().className())?.groupValues?.get(1)
        }

        /** Chapter links belonging to [mangaUrl] (…/manga/<slug>/chapter-N), newest first as the site lists them. */
        fun parseChapters(doc: Document, mangaUrl: String): List<SChapter> {
            val slug = seriesPath.matchEntire(pathOf(mangaUrl.trimEnd('/')))?.groupValues?.get(1)
                ?: pathOf(mangaUrl).trim('/').substringAfter("manga/").substringBefore('/')
            val out = LinkedHashMap<String, SChapter>()
            for (a in doc.select("a[href]")) {
                val href = a.absUrl("href")
                if (href.isBlank()) continue
                val m = chapterPath.matchEntire(pathOf(href)) ?: continue
                if (!m.groupValues[1].equals(slug, ignoreCase = true)) continue
                if (!isSiteHost(href, mangaUrl)) continue
                val url = href.substringBefore('#').substringBefore('?')
                val row = a.closest("li, tr") ?: a.parent()
                val name = a.text().trim().takeIf { it.isNotBlank() && !it.equals("Read First", true) && !it.equals("Read Last", true) }
                    ?: continue
                val chapterName = name.replace(Regex("""\s+(\d+\s+(second|min|hour|day|week|month|year)s?\s+ago|[A-Z][a-z]+ \d{1,2}, \d{4}).*$""", RegexOption.IGNORE_CASE), "").trim()
                    .ifBlank { name }
                out.putIfAbsent(
                    url,
                    SChapter(
                        url = url,
                        name = chapterName,
                        dateUpload = parseDate(row?.text().orEmpty()),
                        chapterNumber = chapterNumberFromPath(m.groupValues[2]).takeIf { it >= 0f } ?: parseChapterNumber(chapterName)
                    )
                )
            }
            return out.values.sortedByDescending { it.chapterNumber }
        }

        /** chapter-10-2 -> 10.2, chapter-5 -> 5 */
        fun chapterNumberFromPath(segment: String): Float {
            val m = Regex("""chapter-?(\d+)(?:[-.](\d+))?""", RegexOption.IGNORE_CASE).find(segment) ?: return -1f
            return (m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")).toFloatOrNull() ?: -1f
        }

        fun parseChapterImages(doc: Document): List<String> {
            val scoped = doc.select("div.reading-content img, div.page-break img, #chapter-reader img, .chapter-content img, .read-container img")
            val imgs = if (scoped.isNotEmpty()) scoped else doc.select("img")
            return imgs.mapNotNull { imageUrlOf(it) }
                .filter { url ->
                    val l = url.lowercase()
                    !l.contains("/thumb/") && !l.contains("logo") && !l.contains("avatar") && !l.contains("banner") &&
                        !l.contains("/icons/") && !l.endsWith(".svg") && !l.endsWith(".ico")
                }
                .distinct()
        }

        private fun parseDate(text: String): Long {
            if (text.isBlank()) return 0L
            Regex("""(\d+)\s*(second|min|hour|day|week|month|year)""", RegexOption.IGNORE_CASE).find(text)?.let { rel ->
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
            Regex("""[A-Z][a-z]+ \d{1,2}, \d{4}""").find(text)?.value?.let { s ->
                for (f in listOf("MMMM dd, yyyy", "MMM dd, yyyy")) {
                    runCatching { SimpleDateFormat(f, Locale.US).parse(s) }.getOrNull()?.let { return it.time }
                }
            }
            return 0L
        }
    }
}
