package com.imperialreader.data.source

import android.content.Context
import com.imperialreader.App
import com.imperialreader.data.network.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Site-specific gallery adapters. These sites store a complete gallery as one source chapter so the
 * existing chapter list, offline download queue, reading progress, and library UI continue to work.
 */
class HentaiDbSource : HttpSource() {
    override val id = "hentaidb_xyz"
    override val name = "HentaiDB"
    override val lang = "en"
    override val baseUrl = "https://hentaidb.xyz"
    override val nsfw = true

    private val galleryInfo = ConcurrentHashMap<String, GalleryInfo>()
    private val galleryInfoPreferences by lazy {
        App.instance.getSharedPreferences("hentaidb_gallery_info", Context.MODE_PRIVATE)
    }

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/comics${if (page > 1) "?p=$page" else ""}")

    override suspend fun getLatest(page: Int): MangasPage = getPopular(page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val suffix = buildString {
            append("?q=").append(enc(query))
            if (page > 1) append("&p=").append(page)
        }
        return list("$baseUrl/comics$suffix")
    }

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val results = doc.select("a.ca[href]").asSequence()
            .filter { it.attr("href").startsWith("/comics/") }
            .filter { it.selectFirst("img") != null }
            .mapNotNull { a ->
                val href = a.absUrl("href").takeIf { it.startsWith("$baseUrl/comics/") } ?: return@mapNotNull null
                val title = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }.trim()
                if (title.isBlank()) return@mapNotNull null
                SManga(url = href, title = title, thumbnailUrl = imageUrl(a.selectFirst("img")))
            }.distinctBy { it.url }.toList()
        return MangasPage(results, results.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("img[alt*=cover]")?.let(::imageUrl) ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.ifBlank { null } ?: manga.description
        cacheGalleryInfo(manga.url, galleryInfo(doc))
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val uri = runCatching { URI(manga.url) }.getOrNull()
            ?: throw IOException("Invalid HentaiDB gallery address")
        if (uri.host != URI(baseUrl).host || !uri.path.startsWith("/comics/")) {
            throw IOException("Invalid HentaiDB gallery address")
        }
        val readUrl = "$baseUrl${uri.path.trimEnd('/')}/read"
        val pages = cachedGalleryInfo(manga.url)?.pageCount
        val name = pages?.let { "Gallery ($it pages)" } ?: "Gallery"
        return listOf(SChapter(readUrl, name, chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val galleryUrl = chapter.url.substringBefore('?').substringBeforeLast("/read")
        val embeddedPageCount = Regex("""(?i)Gallery\s+\((\d+)\s+pages?\)""")
            .find(chapter.name)?.groupValues?.get(1)?.toIntOrNull()
        val galleryId = Regex("""-n(\d+)$""")
            .find(runCatching { URI(galleryUrl).path }.getOrNull().orEmpty())
            ?.groupValues?.get(1)
        var info = cachedGalleryInfo(galleryUrl)
        if ((embeddedPageCount == null && info?.pageCount == null) ||
            (info?.coverUrl == null && galleryId == null)
        ) {
            val gallery = Jsoup.parse(getString(galleryUrl), galleryUrl)
            info = galleryInfo(gallery)
            cacheGalleryInfo(galleryUrl, info)
        }
        val pageCount = embeddedPageCount ?: info?.pageCount
        val coverUrl = info?.coverUrl ?: galleryId?.let { "$baseUrl/cm/n$it/001.webp" }
        val coverUri = coverUrl?.let { runCatching { URI(it) }.getOrNull() }
        val coverPath = coverUri?.path.orEmpty()
        val filename = coverPath.substringAfterLast('/')
        val imageName = Regex("""^(\d+)(\.[A-Za-z0-9]+)$""").matchEntire(filename)
        val pages = if (pageCount != null && pageCount > 0 && coverUri != null && imageName != null) {
            val prefix = coverPath.substringBeforeLast('/')
            val padding = imageName.groupValues[1].length
            val extension = imageName.groupValues[2]
            (1..pageCount).map { page ->
                URI(
                    coverUri.scheme,
                    coverUri.authority,
                    "$prefix/${page.toString().padStart(padding, '0')}$extension",
                    coverUri.query,
                    null
                ).toString()
            }
        } else {
            val reader = Jsoup.parse(getString(chapter.url), chapter.url)
            reader.select("img[alt^=Page]").mapNotNull(::imageUrl).distinct()
        }
        if (pages.isEmpty()) throw IOException("HentaiDB returned the reader page but no page images")
        return pages
    }

    private fun galleryInfo(doc: org.jsoup.nodes.Document): GalleryInfo {
        val pageCount = Regex("""(?i)(\d+)\s+pages?""")
            .find(doc.selectFirst("#readM")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: doc.select("script[type=application/ld+json]").asSequence()
                .mapNotNull { runCatching { JSONObject(it.data()).optInt("numberOfPages") }.getOrNull() }
                .firstOrNull { it > 0 }
        val coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let(::absoluteUrl)
        return GalleryInfo(pageCount, coverUrl)
    }

    private fun galleryKey(url: String): String = url.substringBefore('?').trimEnd('/')

    private fun cachedGalleryInfo(url: String): GalleryInfo? {
        val key = galleryKey(url)
        galleryInfo[key]?.let { return it }
        val preferenceKey = "gallery_${key.hashCode()}"
        val pageCount = galleryInfoPreferences.getInt("${preferenceKey}_pages", 0).takeIf { it > 0 }
        val coverUrl = galleryInfoPreferences.getString("${preferenceKey}_cover", null)
        if (pageCount == null && coverUrl == null) return null
        return GalleryInfo(pageCount, coverUrl).also { galleryInfo[key] = it }
    }

    private fun cacheGalleryInfo(url: String, info: GalleryInfo) {
        val key = galleryKey(url)
        galleryInfo[key] = info
        val preferenceKey = "gallery_${key.hashCode()}"
        galleryInfoPreferences.edit().apply {
            info.pageCount?.let { putInt("${preferenceKey}_pages", it) }
            info.coverUrl?.let { putString("${preferenceKey}_cover", it) }
        }.apply()
    }

    private data class GalleryInfo(val pageCount: Int?, val coverUrl: String?)
}

/** HTML gallery adapter for the nhentai.xxx domain. */
open class NhentaiXxxSource(
    override val id: String = "nhentai_xxx",
    override val name: String = "nhentai.xxx",
    override val baseUrl: String = "https://nhentai.xxx",
    private val galleryPath: String = "g",
    private val pagePath: String = galleryPath
) : HttpSource() {
    override val lang = "en"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/search/?page=${page.coerceAtLeast(1)}")
    override suspend fun getLatest(page: Int): MangasPage = getPopular(page)
    override suspend fun search(query: String, page: Int): MangasPage = list(pageUrl(query, page))

    private fun pageUrl(query: String, page: Int): String =
        "$baseUrl/search/?key=${enc(query)}&page=$page"

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val cards = doc.select(".thumb, .gallery, .gallery-item, .thumb_container, .gallery_item")
        val results = cards.mapNotNull { card ->
            val links = card.select("a[href]")
            val link = links.firstOrNull { galleryId(it.absUrl("href")) != null }
                ?: return@mapNotNull null
            val id = galleryId(link.absUrl("href")) ?: return@mapNotNull null
            val image = card.select("img").firstOrNull { imageUrl(it) != null }
            val title = sequenceOf(
                card.selectFirst(".caption, .title, h2, h3")?.text().orEmpty(),
                links.asSequence().map { it.attr("title").ifBlank { it.text() } }
                    .firstOrNull { it.isNotBlank() }.orEmpty(),
                image?.attr("alt").orEmpty()
            ).map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty()
            if (title.isBlank()) return@mapNotNull null
            SManga(
                url = "$baseUrl/$galleryPath/$id/",
                title = title,
                thumbnailUrl = imageUrl(image)
            )
        }.distinctBy { it.url }
        return MangasPage(results, results.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("#info h1, .title h1, h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - IMHentai")?.trim()?.ifBlank { null }
            ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("#cover img, .cover img, img[alt$=cover]")?.let(::imageUrl)
            ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        manga.genre = (doc.select("#tags a, .taglist a, .tag-list a, .gallery-tags a, a[href*=/tag/]")
            .map { it.text().trim() } + extractGenreTags(doc))
            .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { manga.genre }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val id = galleryId(manga.url) ?: throw IOException("Invalid nhentai.xxx gallery address")
        val pages = galleryPageCount(doc, id)
        if (pages <= 0) throw IOException("nhentai.xxx did not return a page count for this gallery")
        return listOf(SChapter(manga.url, "Gallery ($pages pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val id = galleryId(chapter.url) ?: throw IOException("Invalid nhentai.xxx gallery address")
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val count = galleryPageCount(doc, id)
        if (count <= 0) throw IOException("nhentai.xxx did not return a page count for this gallery")

        val embedded = if (pagePath != galleryPath) emptyList() else doc.select("img[src], img[data-src], img[data-original]")
            .mapNotNull(::imageUrl)
            .filter(::isGalleryImage)
            .distinct()
        if (embedded.size >= count) return embedded.take(count)

        // Fetch each reader page concurrently and return the full image URL, not its HTML wrapper.
        val gate = Semaphore(6)
        return coroutineScope {
            (1..count).map { page ->
                async {
                    gate.withPermit { pageImage(id, page) }
                }
            }.awaitAll()
        }
    }

    private suspend fun pageImage(id: String, page: Int): String {
        val url = "$baseUrl/$pagePath/$id/$page/"
        val doc = Jsoup.parse(getString(url), url)
        return doc.select("img[src], img[data-src], img[data-original]")
            .mapNotNull(::imageUrl)
            .firstOrNull(::isGalleryImage)
            ?: throw IOException("nhentai.xxx did not return image $page")
    }

    private fun isGalleryImage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        val isImageHost = host == "nhentaimg.com" || host.endsWith(".nhentaimg.com") ||
            (host.endsWith(".imhentai.xxx") && host != "www.imhentai.xxx")
        return isImageHost &&
            Regex("""\.(jpe?g|png|webp|gif)$""", RegexOption.IGNORE_CASE).containsMatchIn(path) &&
            !url.substringBefore('?').endsWith("/thumb.jpg", ignoreCase = true) &&
            !Regex("""/\d+t\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
                .containsMatchIn(url.substringBefore('?'))
    }

    private fun galleryPageCount(doc: Document, id: String): Int {
        val linkedPages = doc.select("a[href]").asSequence()
            .mapNotNull { galleryPageNumber(it.attr("href"), id) }
            .maxOrNull() ?: 0
        val fromLabel = Regex("""(?i)\bpages?\s*:\s*(\d{1,4})\b""").find(doc.text())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val fromText = Regex("""(?i)\b(\d{1,4})\s+pages?\b""").find(doc.text())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val fromMetadata = doc.selectFirst("#load_pages, [name=load_pages], [data-page-count]")
            ?.let { it.attr("value").ifBlank { it.attr("data-page-count") }.toIntOrNull() } ?: 0
        return maxOf(linkedPages, fromLabel, fromText, fromMetadata)
    }

    private fun galleryId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val expectedHost = runCatching { URI(baseUrl).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
        if (uri.host.orEmpty().removePrefix("www.") != expectedHost) return null
        return Regex("""/\Q$galleryPath\E/(\d+)(?:/|$)""", RegexOption.IGNORE_CASE)
            .find(uri.path.orEmpty())?.groupValues?.get(1)
    }

    private fun galleryPageNumber(path: String, id: String): Int? =
        Regex("""/\Q$galleryPath\E/\Q$id\E/(\d+)/?""", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.get(1)?.toIntOrNull()
}

class ImhentaiXxxSource : NhentaiXxxSource(
    id = "imhentai_xxx",
    name = "imhentai.xxx",
    baseUrl = "https://imhentai.xxx",
    galleryPath = "gallery",
    pagePath = "view"
)

/** 9hentai.so's live JSON API provides search results, gallery metadata, and page counts. */
class NineHentaiSource : HttpSource() {
    override val id = "9hentai_so"
    override val name = "9hentai.so"
    override val lang = "en"
    override val baseUrl = "https://9hentai.so"
    override val nsfw = true

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun getPopular(page: Int): MangasPage = searchPage("", page)
    override suspend fun getLatest(page: Int): MangasPage = searchPage("", page)
    override suspend fun search(query: String, page: Int): MangasPage = searchPage(query, page)

    private suspend fun searchPage(query: String, page: Int): MangasPage {
        val form = JSONObject()
            .put("text", query)
            .put("page", (page - 1).coerceAtLeast(0))
            .put("sort", 0)
            .put("pages", JSONObject().put("range", JSONArray().put(0).put(2000)))
            .put(
                "tag",
                JSONObject()
                    .put("text", "")
                    .put("type", 1)
                    .put("tags", JSONArray())
                    .put("items", JSONObject().put("included", JSONArray()).put("excluded", JSONArray()))
            )
        val json = postJson("$baseUrl/api/getBook", JSONObject().put("search", form))
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { i ->
            val entry = results.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = entry.optString("title").ifBlank { return@mapNotNull null }
            val imageServer = entry.optString("image_server").trimEnd('/')
            SManga(
                url = "$baseUrl/g/$id/",
                title = title,
                thumbnailUrl = if (imageServer.isNotBlank()) "$imageServer/$id/cover-small.jpg" else "$baseUrl/images/$id/cover-small.jpg"
            )
        }
        return MangasPage(items.distinctBy { it.url }, items.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val book = bookById(galleryId(manga.url) ?: throw IOException("Invalid 9hentai.so gallery address"))
        manga.title = book.optString("title").ifBlank { manga.title }
        val id = book.opt("id")?.toString().orEmpty()
        val server = book.optString("image_server").trimEnd('/')
        if (id.isNotBlank() && server.isNotBlank()) manga.thumbnailUrl = "$server/$id/cover-small.jpg"
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val id = galleryId(manga.url) ?: throw IOException("Invalid 9hentai.so gallery address")
        val book = bookById(id)
        val count = book.optInt("total_page", 0)
        if (count <= 0) throw IOException("9hentai.so returned an empty gallery")
        return listOf(SChapter(manga.url, "Gallery ($count pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val id = galleryId(chapter.url) ?: throw IOException("Invalid 9hentai.so gallery address")
        val book = bookById(id)
        val count = book.optInt("total_page", 0)
        val server = book.optString("image_server").trimEnd('/')
        if (count <= 0 || server.isBlank()) throw IOException("9hentai.so returned incomplete gallery metadata")
        return (1..count).map { "$server/$id/$it.jpg" }
    }

    private suspend fun bookById(id: String): JSONObject {
        val json = postJson("$baseUrl/api/getBookByID", JSONObject().put("id", id))
        return json.optJSONObject("results") ?: throw IOException("9hentai.so could not find that gallery")
    }

    private suspend fun postJson(url: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            val text = response.body?.string().orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("9hentai.so returned invalid JSON") }
        }
    }

    private fun galleryId(url: String): String? =
        Regex("""/g/(\d+)(?:/|$)""").find(url)?.groupValues?.get(1)
}

/** Source for the current xoxocomic.com comic/issue site; the older .net source stays available. */
class XoxoComicSource : HttpSource() {
    override val id = "xoxocomic_com"
    override val name = "XOXO Comic"
    override val lang = "en"
    override val baseUrl = "https://xoxocomic.com"

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/comic-list?page=${page.coerceAtLeast(1)}")

    override suspend fun getLatest(page: Int): MangasPage = getPopular(page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val terms = comicSearchTerms(query)
        if (terms.isEmpty()) return MangasPage(emptyList(), false)
        val pageSuffix = if (page > 1) "&page=$page" else ""
        val result = list("$baseUrl/search-comic?keyword=${enc(query.trim())}$pageSuffix")
        val matches = result.mangas.filter { matchesComicQuery(it.title, terms) }
        return MangasPage(matches, result.hasNextPage && matches.isNotEmpty())
    }

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val items = parseComicCards(doc)
        return MangasPage(items, hasNextPage(doc, url))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("main h1, h1")?.text()?.removeSuffix(" Comic")?.trim()
            ?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("main img[alt*=cover]")?.let(::imageUrl) ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val seriesPath = comicPath(manga.url)?.trimEnd('/')
            ?.takeIf { it.matches(Regex("""^/comic/[^/]+$""", RegexOption.IGNORE_CASE)) }
            ?: throw IOException("Invalid XOXO Comic series address")
        val issuePattern = Regex(
            """^${Regex.escape(seriesPath)}/(issue-([^/]+)|tpb)/?$""",
            RegexOption.IGNORE_CASE
        )
        val rows = doc.select("a[href]").asSequence()
            .mapNotNull { a ->
                val url = a.absUrl("href").ifBlank { absoluteUrl(a.attr("href")) }
                val match = comicPath(url)?.let(issuePattern::matchEntire) ?: return@mapNotNull null
                val issueSlug = match.groupValues[1]
                val issueNumber = match.groupValues[2]
                val name = issueTitle(a, issueNumber.ifBlank { null })
                val number = Regex("""(?i)(?:issue|#)[\s-]*(\d+(?:\.\d+)?)""").find(name)
                    ?.groupValues?.get(1)?.toFloatOrNull()
                    ?: issueSlug.removePrefix("issue-").toFloatOrNull()
                    ?: -1f
                SChapter(url.trimEnd('/'), name, chapterNumber = number)
            }.distinctBy { it.url }.toList()
        if (rows.isEmpty()) throw IOException("XOXO Comic did not return issue links for this series")
        return rows.sortedWith(
            compareByDescending<SChapter> { it.chapterNumber >= 0f }
                .thenByDescending { it.chapterNumber }
        )
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val chapterPath = comicPath(chapter.url)?.trimEnd('/')
            ?.takeIf { Regex("""^/comic/[^/]+/(?:issue-[^/]+|tpb)$""", RegexOption.IGNORE_CASE).matches(it) }
            ?: throw IOException("Invalid XOXO Comic issue address")
        val pages = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var pageUrl: String? = chapter.url

        // XOXO serves one image per reader URL and links pages with rel=next. Follow those links so
        // opening or downloading an issue gets the complete issue, not just page one.
        while (pageUrl != null) {
            if (!visited.add(pageUrl)) break
            if (visited.size > MAX_ISSUE_PAGES) {
                throw IOException("XOXO Comic issue exceeded the $MAX_ISSUE_PAGES-page safety limit")
            }

            val html = getString(pageUrl)
            val doc = Jsoup.parse(html, pageUrl)
            val pageImages = doc.select(
                "img#imgChapter, #imgChapter img, .reading-detail .page-chapter img, " +
                    ".page-chapter img, .reading img, .reader img, img.chapter_img"
            )
                .asSequence()
                .mapNotNull(::imageUrl)
                .filter(::isComicPageImage)
                .distinct()
                .toList()
            if (pageImages.isEmpty()) {
                throw IOException("XOXO Comic returned no reader image for issue page ${visited.size}")
            }
            pages += pageImages

            val next = doc.selectFirst("a.next-page[href], a[rel=next][href]")
                ?.absUrl("href")
                ?.takeIf { url ->
                    val nextPath = comicPath(url)
                    nextPath != null && (nextPath == chapterPath || nextPath.startsWith("$chapterPath/"))
                }
                ?.takeUnless { it in visited }
            if (next != null && visited.size >= MAX_ISSUE_PAGES) {
                throw IOException("XOXO Comic issue exceeded the $MAX_ISSUE_PAGES-page safety limit")
            }
            pageUrl = next
        }

        if (pages.isEmpty()) throw IOException("XOXO Comic returned no reader pages")
        return pages.distinct()
    }

    private fun comicPath(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.") ?: return null
        val expectedHost = URI(baseUrl).host?.removePrefix("www.") ?: return null
        if (!host.equals(expectedHost, ignoreCase = true)) return null
        return uri.path
    }

    private companion object {
        const val MAX_ISSUE_PAGES = 500
    }

    private fun parseComicCards(doc: Document): List<SManga> {
        val candidates = doc.select("a[href]").asSequence().mapNotNull { link ->
            val url = link.absUrl("href").ifBlank { absoluteUrl(link.attr("href")) }
            val path = comicPath(url)?.trimEnd('/') ?: return@mapNotNull null
            if (!path.matches(Regex("""^/comic/[^/]+$""", RegexOption.IGNORE_CASE))) return@mapNotNull null
            val title = comicCardTitle(link) ?: return@mapNotNull null
            SManga(url.trimEnd('/'), title, imageUrl(link.selectFirst("img")))
        }.distinctBy { it.url }.toList()
        if (candidates.isNotEmpty()) return candidates

        val path = doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?.let(::comicPath)?.trimEnd('/') ?: return emptyList()
        if (!path.matches(Regex("""^/comic/[^/]+$""", RegexOption.IGNORE_CASE))) return emptyList()
        val title = doc.selectFirst("main h1, h1")?.text()?.trim()?.removeSuffix(" Comic")?.ifBlank { null }
            ?: return emptyList()
        return listOf(
            SManga(
                "$baseUrl$path",
                title,
                doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            )
        )
    }

    private fun hasNextPage(doc: Document, currentUrl: String): Boolean {
        val currentPage = queryParameterInt(currentUrl, "page") ?: 1
        return doc.select("a[rel=next][href], .pagination a.next[href], .pagination a.r[href], a.next-page[href]")
            .any { link ->
                val next = link.absUrl("href")
                val nextPage = queryParameterInt(next, "page")
                next.isNotBlank() && next != currentUrl && (nextPage == null || nextPage > currentPage)
            }
    }

    private fun comicCardTitle(link: Element): String? =
        sequenceOf(link.attr("title"), link.selectFirst("img")?.attr("alt").orEmpty(), link.text())
            .map { it.trim().removePrefix("Read ").removeSuffix(" Comic").trim() }
            .firstOrNull { it.isNotBlank() && it.length < 180 }

    private fun issueTitle(link: Element, number: String?): String {
        val label = sequenceOf(link.attr("title"), link.text())
            .map { it.trim() }
            .firstOrNull {
                it.isNotBlank() && it.length < 160 &&
                    !it.matches(Regex("""(?i)(read|view|open|download)(\s+(now|online))?"""))
            }
        return label ?: if (number != null) "Issue #$number" else "TPB"
    }

    private fun isComicPageImage(url: String): Boolean =
        !url.contains("logo", true) && !url.contains("loading", true) &&
            !url.contains("avatar", true) && !url.contains("cover", true)
}

private fun comicSearchTerms(query: String): List<String> =
    Regex("""[\p{L}\p{N}]+""").findAll(query.lowercase())
        .map { normalizeTitle(it.value) }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

private fun matchesComicQuery(title: String, terms: List<String>): Boolean {
    val normalized = normalizeTitle(title)
    return terms.all(normalized::contains)
}

private fun queryParameterInt(url: String, name: String): Int? =
    runCatching {
        URI(url).rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.toIntOrNull()
    }.getOrNull()

private fun imageUrl(img: Element?): String? {
    if (img == null) return null
    for (attribute in listOf("data-src", "data-lazy-src", "data-original", "src")) {
        val value = img.attr(attribute).trim()
        if (value.isNotBlank() && !value.startsWith("data:")) {
            return img.absUrl(attribute).ifBlank { value }
        }
    }
    val srcset = img.attr("srcset").substringBefore(',').substringBefore(' ').trim()
    return srcset.takeIf { it.isNotBlank() }?.let {
        runCatching { URI(img.baseUri()).resolve(it).toString() }.getOrDefault(it)
    }
}
