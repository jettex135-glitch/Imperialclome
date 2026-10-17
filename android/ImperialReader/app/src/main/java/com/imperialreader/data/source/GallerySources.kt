package com.imperialreader.data.source

import android.net.Uri
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

/** MangaK's public API returns full title metadata, chapter indexes, and reader image URLs. */
class MangaKSource : HttpSource() {
    override val id = "mangak_io"
    override val name = "MangaK.io"
    override val lang = "en"
    override val baseUrl = "https://mangak.io"

    private val apiBase = "https://api.mangak.io"

    override suspend fun getPopular(page: Int): MangasPage = list("sort=views", page)

    override suspend fun getLatest(page: Int): MangasPage = list("sort=latest", page)

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return getPopular(page)
        val genre = query.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
        val responses = coroutineScope {
            listOf(
                async { list("q=${enc(query.trim())}", page) },
                async { list("genres=${enc(genre)}", page) },
                async { list("author=${enc(query.trim())}", page) }
            ).awaitAll()
        }
        return MangasPage(
            responses.flatMap { it.mangas }.distinctBy { it.url },
            responses.any { it.hasNextPage }
        )
    }

    override suspend fun searchMetadata(query: String, page: Int) = MangasPage(emptyList(), false)

    private suspend fun list(filters: String, page: Int): MangasPage {
        val separator = if (filters.isBlank()) "" else "$filters&"
        val json = api("/titles/search?${separator}page=${page.coerceAtLeast(1)}&limit=24")
        val data = json.optJSONObject("data") ?: throw IOException("MangaK returned no search data")
        val items = data.optJSONArray("items") ?: JSONArray()
        val mangas = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val slug = item.optString("slug").trim()
            val title = item.optString("name").trim()
            if (id.isBlank() || slug.isBlank() || title.isBlank()) return@mapNotNull null
            SManga(
                url = titleUrl(slug, id),
                title = title,
                thumbnailUrl = item.optString("cover").takeIf { it.startsWith("http") },
                genre = names(item.optJSONArray("genres")).ifBlank { null },
                status = status(item.optString("status"))
            )
        }.distinctBy { it.url }
        val pagination = data.optJSONObject("pagination")
        return MangasPage(mangas, pagination?.optBoolean("has_next") == true)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val id = mangaId(manga.url) ?: throw IOException("Invalid MangaK title address")
        val title = api("/titles/$id").optJSONObject("data")?.optJSONObject("title")
            ?: throw IOException("MangaK could not find this title")
        manga.title = title.optString("name").ifBlank { manga.title }
        manga.thumbnailUrl = title.optString("cover").takeIf { it.startsWith("http") } ?: manga.thumbnailUrl
        manga.description = title.optString("summary").trim().ifBlank { null } ?: manga.description
        manga.genre = names(title.optJSONArray("genres")).ifBlank { null } ?: manga.genre
        manga.author = names(title.optJSONArray("authors")).ifBlank { null } ?: manga.author
        manga.artist = names(title.optJSONArray("artists")).ifBlank { null } ?: manga.artist
        manga.status = status(title.optString("status"))
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val id = mangaId(manga.url) ?: throw IOException("Invalid MangaK title address")
        val chapters = api("/titles/$id/chapters").optJSONObject("data")?.optJSONArray("chapters")
            ?: throw IOException("MangaK returned no chapter index")
        val slug = Uri.parse(manga.url).path.orEmpty().trim('/').substringAfterLast('/')
        val result = (0 until chapters.length()).mapNotNull { index ->
            val chapter = chapters.optJSONObject(index) ?: return@mapNotNull null
            val chapterId = chapter.optString("id").trim()
            val chapterName = chapter.optString("name").trim()
            if (chapterId.isBlank() || chapterName.isBlank()) return@mapNotNull null
            val pageUrl = absoluteUrl(chapter.optString("url"))
            SChapter(
                url = "$pageUrl?hsid=$id&chapterId=$chapterId&series=$slug",
                name = chapterName,
                chapterNumber = chapter.optDouble("number", -1.0).toFloat()
            )
        }.distinctBy { it.url }
        if (result.isEmpty()) throw IOException("MangaK returned no readable chapters")
        return result
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val uri = Uri.parse(chapter.url)
        val mangaId = uri.getQueryParameter("hsid") ?: throw IOException("Invalid MangaK chapter address")
        val chapterId = uri.getQueryParameter("chapterId") ?: throw IOException("Invalid MangaK chapter address")
        val images = api("/titles/$mangaId/chapters/$chapterId/images")
            .optJSONObject("data")?.optJSONArray("images") ?: JSONArray()
        val pages = (0 until images.length()).mapNotNull { images.optString(it).trim().takeIf(String::isNotBlank) }
        if (pages.isEmpty() || pages.any { !it.startsWith("https://") && !it.startsWith("http://") }) {
            throw IOException("MangaK returned an empty or invalid chapter image list")
        }
        return pages.distinct()
    }

    private suspend fun api(path: String): JSONObject = JSONObject(
        getString("$apiBase$path", mapOf("Accept" to "application/json"))
    ).takeIf { it.optBoolean("success", true) } ?: throw IOException("MangaK API request failed")

    private fun mangaId(url: String): String? = Uri.parse(url).getQueryParameter("hsid")?.takeIf { it.isNotBlank() }

    private fun titleUrl(slug: String, id: String) = "$baseUrl/${slug.trim('/')}?hsid=$id"

    private fun names(array: JSONArray?): String = (0 until (array?.length() ?: 0))
        .mapNotNull { array?.optJSONObject(it)?.optString("name")?.trim()?.ifBlank { null } }
        .distinct()
        .joinToString(", ")

    private fun status(value: String): Int = when (value.lowercase()) {
        "ongoing", "publishing" -> 1
        "completed", "complete" -> 2
        "hiatus", "cancelled", "canceled" -> 3
        else -> 0
    }
}

/** E-Hentai gallery adapter. A gallery is one chapter, backed by actual page links and image URLs. */
class EHentaiSource : HttpSource() {
    override val id = "e_hentai_org"
    override val name = "E-Hentai"
    override val lang = "en"
    override val baseUrl = "https://e-hentai.org"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage = list(pageUrl("", page))

    override suspend fun getLatest(page: Int): MangasPage = list(pageUrl("", page))

    override suspend fun search(query: String, page: Int): MangasPage = list(pageUrl(query, page))

    private fun pageUrl(query: String, page: Int): String = buildString {
        append(baseUrl).append('/').append("?f_search=").append(enc(query.trim()))
        if (page > 1) append("&page=").append(page - 1)
    }

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val items = doc.select("table.itg tr").mapNotNull { row ->
            val link = row.selectFirst("td.gl3c a[href*=/g/]") ?: return@mapNotNull null
            val galleryUrl = link.absUrl("href")
            if (galleryId(galleryUrl) == null) return@mapNotNull null
            val title = link.selectFirst(".glink")?.text()?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SManga(galleryUrl, title, imageUrl(row.selectFirst(".glthumb img")))
        }.distinctBy { it.url }
        val currentPage = queryParameterInt(url, "page") ?: 0
        val hasNext = doc.select(".ptt a[href]").any { link ->
            (queryParameterInt(link.absUrl("href"), "page") ?: -1) > currentPage
        }
        return MangasPage(items, hasNext)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("#gj, h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("#gd1 img, #gimg img, meta[property=og:image]")
            ?.let { element ->
                if (element.tagName() == "meta") element.attr("content").takeIf { it.startsWith("http") }
                else imageUrl(element)
            } ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        val tags = doc.select("#taglist .gt, .gt").mapNotNull { element ->
            element.attr("title").trim().ifBlank { element.text().trim() }.takeIf { it.isNotBlank() }
        }.distinct()
        val artists = tags.filter { it.startsWith("artist:", ignoreCase = true) }
            .map { it.substringAfter(':').trim() }
        manga.artist = artists.joinToString(", ").ifBlank { null } ?: manga.artist
        manga.genre = tags.filterNot { it.startsWith("artist:", ignoreCase = true) }
            .joinToString(", ").ifBlank { null } ?: manga.genre
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val count = pageCount(doc)
        if (count <= 0) throw IOException("E-Hentai did not return a gallery page count")
        return listOf(SChapter(manga.url, "Gallery ($count pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val galleryUrl = chapter.url.substringBefore("?p=")
        val first = Jsoup.parse(getString(galleryUrl), galleryUrl)
        val count = pageCount(first)
        if (count <= 0 || count > MAX_GALLERY_PAGES) {
            throw IOException("E-Hentai returned an invalid gallery page count")
        }

        val readerUrls = mutableListOf<String>()
        var galleryPage = 0
        while (readerUrls.size < count) {
            if (galleryPage >= MAX_GALLERY_INDEX_PAGES) {
                throw IOException("E-Hentai gallery index exceeded its safety limit")
            }
            val url = if (galleryPage == 0) galleryUrl else "$galleryUrl?p=$galleryPage"
            val doc = if (galleryPage == 0) first else Jsoup.parse(getString(url), url)
            val pageLinks = doc.select("#gdt a[href*=/s/]").map { it.absUrl("href") }.filter { it.startsWith("$baseUrl/s/") }
            if (pageLinks.isEmpty()) throw IOException("E-Hentai returned no reader pages for gallery page ${galleryPage + 1}")
            readerUrls += pageLinks
            val hasNext = doc.select(".ptt a[href]").any {
                (queryParameterInt(it.absUrl("href"), "p") ?: -1) > galleryPage
            }
            if (!hasNext) break
            galleryPage++
        }
        if (readerUrls.size < count) throw IOException("E-Hentai returned ${readerUrls.size} of $count reader pages")

        val gate = Semaphore(6)
        val pages = coroutineScope {
            readerUrls.take(count).mapIndexed { index, url ->
                async {
                    gate.withPermit {
                        val doc = Jsoup.parse(getString(url), url)
                        val image = doc.selectFirst("#img")?.let(::imageUrl)
                            ?: throw IOException("E-Hentai did not return image ${index + 1}")
                        if (!image.startsWith("https://") && !image.startsWith("http://")) {
                            throw IOException("E-Hentai returned an invalid image URL for page ${index + 1}")
                        }
                        image
                    }
                }
            }.awaitAll()
        }
        if (pages.size != count || pages.any(String::isBlank)) throw IOException("E-Hentai returned an incomplete gallery")
        return pages
    }

    private fun pageCount(doc: Document): Int = Regex("(?i)\\b(\\d{1,5})\\s+pages?\\b")
        .find(doc.selectFirst("#gdd")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("(?i)\\b(\\d{1,5})\\s+pages?\\b").find(doc.selectFirst(".gl4c")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        ?: 0

    private fun galleryId(url: String): String? =
        Regex("/g/(\\d+)/[A-Za-z0-9]+/?").find(url)?.groupValues?.get(1)

    private companion object {
        const val MAX_GALLERY_PAGES = 5000
        const val MAX_GALLERY_INDEX_PAGES = 200
    }
}

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
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val read = doc.selectFirst("#readB[href]")?.absUrl("href")
            ?: throw IOException("HentaiDB did not provide a reader link for this gallery")
        val pages = Regex("""(?i)(\d+)\s+pages?""").find(doc.selectFirst("#readM")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        return listOf(SChapter(read, if (pages != null) "Gallery ($pages pages)" else "Gallery", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select("img[alt^=Page]").mapNotNull(::imageUrl).distinct()
        if (pages.isEmpty()) throw IOException("HentaiDB returned the reader page but no page images")
        return pages
    }
}

/** HTML gallery adapter for the nhentai.xxx domain. */
class NhentaiXxxSource : HttpSource() {
    override val id = "nhentai_xxx"
    override val name = "nhentai.xxx"
    override val lang = "en"
    override val baseUrl = "https://nhentai.xxx"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage =
        list(if (page <= 1) "$baseUrl/" else "$baseUrl/?page=$page")
    override suspend fun getLatest(page: Int): MangasPage = getPopular(page)
    override suspend fun search(query: String, page: Int): MangasPage = list(pageUrl(query, page))

    private fun pageUrl(query: String, page: Int): String =
        "$baseUrl/search/?key=${enc(query)}&page=$page"

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val results = doc.select("a[href]").asSequence()
            .filter { it.attr("href").startsWith("/g/") }
            .filter { it.selectFirst("img") != null }
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val id = galleryId(href) ?: return@mapNotNull null
                val img = a.selectFirst("img")
                val title = a.attr("title").ifBlank {
                    img?.attr("alt").orEmpty().ifBlank { a.text() }
                }.trim()
                if (title.isBlank()) return@mapNotNull null
                SManga(
                    url = "$baseUrl/g/$id/",
                    title = title,
                    thumbnailUrl = imageUrl(img)
                )
            }.distinctBy { it.url }.toList()
        return MangasPage(results, results.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("#info h1, .title h1, h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("#cover img, .cover img")?.let(::imageUrl)
            ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
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

        val embedded = doc.select("img[src], img[data-src], img[data-original]")
            .mapNotNull(::imageUrl)
            .filter(::isGalleryImage)
            .distinct()
        if (embedded.size >= count) return embedded.take(count)

        // The site exposes each full-size page at /g/<id>/<page>/. Fetch those pages concurrently,
        // extracting the image URL rather than handing the reader an HTML page URL.
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
        val url = "$baseUrl/g/$id/$page/"
        val doc = Jsoup.parse(getString(url), url)
        return doc.select("img[src], img[data-src], img[data-original]")
            .mapNotNull(::imageUrl)
            .firstOrNull(::isGalleryImage)
            ?: throw IOException("nhentai.xxx did not return image $page")
    }

    private fun isGalleryImage(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host.endsWith("nhentaimg.com") &&
            !url.substringBefore('?').endsWith("/thumb.jpg", ignoreCase = true)
    }

    private fun galleryPageCount(doc: Document, id: String): Int {
        val linkedPages = doc.select("a[href]").asSequence()
            .filter { it.attr("href").startsWith("/g/$id/") }
            .mapNotNull { Regex("""/g/\Q$id\E/(\d+)/?""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        val fromText = Regex("""(?i)\b(\d{1,4})\s+pages?\b""").find(doc.text())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val fromMetadata = doc.selectFirst("#load_pages, [name=load_pages], [data-page-count]")
            ?.let { it.attr("value").ifBlank { it.attr("data-page-count") }.toIntOrNull() } ?: 0
        return maxOf(linkedPages, fromText, fromMetadata)
    }

    private fun galleryId(url: String): String? =
        Regex("""/g/(\d+)(?:/|$)""").find(url)?.groupValues?.get(1)
}

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

/** ComicHubFree serves traditional Western comic series and a full-page reader for each issue. */
class ComicHubFreeSource : HttpSource() {
    override val id = "comichubfree_com"
    override val name = "ComicHubFree"
    override val lang = "en"
    override val baseUrl = "https://comichubfree.com"

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/comic-list?page=${page.coerceAtLeast(1)}")

    override suspend fun getLatest(page: Int): MangasPage = getPopular(page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val terms = comicSearchTerms(query)
        if (terms.isEmpty()) return MangasPage(emptyList(), false)
        val pageSuffix = if (page > 1) "&page=$page" else ""
        val result = list("$baseUrl/search-comic?key=${enc(query.trim())}$pageSuffix")
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
        manga.title = doc.selectFirst("main h1, h1")?.text()?.trim()
            ?.removePrefix("Read ")?.removeSuffix(" Online for Free")?.trim()?.ifBlank { null }
            ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("main img[alt*=cover]")?.let(::imageUrl)
            ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val seriesPath = comicPath(manga.url)
            ?.takeIf { it.matches(Regex("""^/comic/[^/]+/?$""", RegexOption.IGNORE_CASE)) }
            ?: throw IOException("Invalid ComicHubFree series address")
        val seriesUrl = "$baseUrl${seriesPath.trimEnd('/')}"
        val firstPage = Jsoup.parse(getString(seriesUrl), seriesUrl)
        val lastPage = issueListPageCount(firstPage)
        if (lastPage > MAX_ISSUE_LIST_PAGES) {
            throw IOException("ComicHubFree issue list is too large to load safely ($lastPage pages)")
        }
        val slug = seriesPath.trimEnd('/').substringAfterLast('/')
        val semaphore = Semaphore(4)
        val otherPages = coroutineScope {
            (2..lastPage).map { page ->
                async {
                    semaphore.withPermit {
                        val url = "$seriesUrl?page=$page"
                        Jsoup.parse(getString(url), url)
                    }
                }
            }.awaitAll()
        }
        val issues = (listOf(firstPage) + otherPages)
            .flatMap { parseIssues(it, slug) }
            .distinctBy { it.url }
        if (issues.isEmpty()) throw IOException("ComicHubFree returned no issues for this series")
        return issues.sortedWith(
            compareByDescending<SChapter> { it.chapterNumber >= 0f }
                .thenByDescending { it.chapterNumber }
        )
    }

    private fun issueListPageCount(doc: Document): Int =
        doc.select(".pagination a[href]")
            .asSequence()
            .mapNotNull { Regex("""[?&]page=(\d+)""").find(it.absUrl("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
            ?.coerceAtLeast(1)
            ?: 1

    private fun parseIssues(doc: Document, slug: String): List<SChapter> =
        doc.select("a[href]").asSequence()
            .mapNotNull { a ->
                val url = a.absUrl("href")
                val path = comicPath(url) ?: return@mapNotNull null
                val match = Regex("""^/(?:comic/)?${Regex.escape(slug)}/issue-([^/]+)/?$""", RegexOption.IGNORE_CASE)
                    .matchEntire(path)
                    ?: return@mapNotNull null
                val issueNumber = match.groupValues[1]
                if (issueNumber.isBlank()) return@mapNotNull null
                val title = issueTitle(a, issueNumber)
                SChapter(url.trimEnd('/'), title, chapterNumber = issueNumber.toFloatOrNull() ?: -1f)
            }
            .toList()

    override suspend fun getPages(chapter: SChapter): List<String> {
        val path = comicPath(chapter.url)
            ?.takeIf { Regex("""^/(?:comic/)?[^/]+/issue-[^/]+/?$""", RegexOption.IGNORE_CASE).matches(it) }
            ?: throw IOException("Invalid ComicHubFree issue address")
        val allUrl = "$baseUrl${path.trimEnd('/')}/all"
        val doc = Jsoup.parse(getString(allUrl), allUrl)
        val pages = doc.select(
            "#imgChapter img.chapter_img, .chapter-container img.chapter_img, " +
                "img#imgChapter, img.chapter_img, .reader-image img, .comic-image img"
        )
            .mapNotNull(::imageUrl)
            .filter(::isComicPageImage)
            .distinct()
        if (pages.isEmpty()) throw IOException("ComicHubFree returned no pages for this issue")
        return pages
    }

    private fun comicPath(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.") ?: return null
        val expectedHost = URI(baseUrl).host?.removePrefix("www.") ?: return null
        if (!host.equals(expectedHost, ignoreCase = true)) return null
        return uri.path
    }

    private companion object {
        const val MAX_ISSUE_LIST_PAGES = 250
    }

    private fun parseComicCards(doc: Document): List<SManga> {
        val candidates = doc.select("a[href]").asSequence().mapNotNull { link ->
            val url = link.absUrl("href")
            val path = comicPath(url)?.trimEnd('/') ?: return@mapNotNull null
            if (!path.matches(Regex("""^/comic/[^/]+$""", RegexOption.IGNORE_CASE))) return@mapNotNull null
            val title = comicCardTitle(link) ?: return@mapNotNull null
            SManga(url.trimEnd('/'), title, imageUrl(link.selectFirst("img")))
        }.distinctBy { it.url }.toList()
        if (candidates.isNotEmpty()) return candidates

        val path = doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?.let(::comicPath)?.trimEnd('/') ?: return emptyList()
        if (!path.matches(Regex("""^/comic/[^/]+$""", RegexOption.IGNORE_CASE))) return emptyList()
        val title = doc.selectFirst("main h1, h1")?.text()?.trim()
            ?.removePrefix("Read ")?.removeSuffix(" Online for Free")?.ifBlank { null }
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
            .map {
                it.trim().removePrefix("Read ").removeSuffix(" online")
                    .removeSuffix(" Online for Free").removeSuffix(" Comic").trim()
            }
            .firstOrNull { it.isNotBlank() && it.length < 180 }

    private fun issueTitle(link: Element, number: String): String {
        val label = sequenceOf(link.attr("title"), link.text())
            .map { it.trim() }
            .firstOrNull {
                it.isNotBlank() && it.length < 160 &&
                    !it.matches(Regex("""(?i)(read|view|open|download)(\s+(now|online))?"""))
            }
        return label ?: "Issue #$number"
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
