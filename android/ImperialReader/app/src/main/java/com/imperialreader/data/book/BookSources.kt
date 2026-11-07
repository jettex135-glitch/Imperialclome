package com.imperialreader.data.book

import android.net.Uri
import com.imperialreader.App
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.imperialreader.data.source.GenericSource
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.TapasSupport
import com.imperialreader.data.source.hostId
import com.imperialreader.data.source.lazyImage
import com.imperialreader.data.source.extractGenreTags
import com.imperialreader.data.source.parseRetryAfterMillis
import org.json.JSONArray
import org.jsoup.Jsoup
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/** One downloadable EPUB from an online catalog. */
data class BookItem(
    val sourceId: String,
    val sourceName: String,
    val id: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val epubUrl: String?,
    val description: String? = null,
    val artist: String? = null,
    val genre: String? = null,
    val pdfUrl: String? = null
) {
    val key: String get() = "$sourceId:$id"
}

data class NovelChapter(val title: String, val url: String)

interface BookSource {
    val id: String
    val name: String
    val webUrl: String? get() = null

    /** Short label shown in the Extensions list. */
    val description: String get() = ""

    /** true = a web-novel site (chapters are text pages that get packed into an EPUB when you download). */
    val isWebNovel: Boolean get() = false

    /** Whether Global search should also query this catalog. */
    val inGlobalSearch: Boolean get() = true

    /** Web novels: chapter list in READING order (oldest first). */
    suspend fun chapters(item: BookItem): List<NovelChapter> = emptyList()

    /** Web novels: the readable HTML of one chapter. */
    suspend fun chapterHtml(chapter: NovelChapter): String = throw IOException("Not a web-novel source")
    suspend fun details(item: BookItem): BookItem = item
    suspend fun popular(page: Int): List<BookItem>
    suspend fun search(query: String, page: Int): List<BookItem>
    suspend fun searchByAuthor(query: String, page: Int): List<BookItem> {
        val results = search(query, page)
        val limiter = Semaphore(4)
        return coroutineScope {
            results.map { item ->
                async {
                    try {
                        limiter.withPermit {
                            details(item).takeIf { result ->
                                listOfNotNull(result.author, result.artist)
                                    .any { it.contains(query, ignoreCase = true) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
    suspend fun searchByGenre(query: String, page: Int): List<BookItem> {
        val limiter = Semaphore(4)
        return coroutineScope {
            popular(page).take(8).map { item ->
                async {
                    try {
                        limiter.withPermit {
                            details(item).takeIf { it.genre?.contains(query, ignoreCase = true) == true }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Direct link to the .epub file (some catalogs need a second request to find it). */
    suspend fun resolveEpubUrl(item: BookItem): String =
        item.epubUrl ?: throw IOException("No EPUB available for this title")
    suspend fun resolvePdfUrl(item: BookItem): String =
        item.pdfUrl ?: throw IOException("No PDF available for this title")
}

internal suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    App.instance.network.client.await(request).use { resp ->
        val html = resp.body?.string().orEmpty()
        val cloudflare = resp.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true ||
            (resp.code in setOf(403, 503) &&
                (resp.header("Server")?.contains("cloudflare", true) == true || resp.header("cf-ray") != null)) ||
            html.contains("<title>Just a moment", ignoreCase = true)
        if (cloudflare) throw CloudflareException(url)
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        if (html.isBlank()) throw IOException("The source returned an empty response")
        html
    }
}

internal suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).header("Accept", "application/json").build()
    App.instance.network.client.await(request).use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        resp.body?.string().orEmpty()
    }
}

private object NovelFireRequestGate {
    private const val MIN_REQUEST_INTERVAL_MS = 750L
    private const val DEFAULT_RATE_LIMIT_MS = 30_000L
    private val mutex = Mutex()
    private var nextRequestAt = 0L

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val waitMs = (nextRequestAt - System.currentTimeMillis()).coerceAtLeast(0L)
            if (waitMs > 0) delay(waitMs)

            val request = Request.Builder()
                .url(url)
                .header("Referer", "https://novelfire.net/")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            try {
                App.instance.network.client.await(request).use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        val retryAfter = parseRetryAfterMillis(response.header("Retry-After"))
                        val cooldown = maxOf(retryAfter ?: DEFAULT_RATE_LIMIT_MS, MIN_REQUEST_INTERVAL_MS)
                        nextRequestAt = maxOf(nextRequestAt, safeAdd(System.currentTimeMillis(), cooldown))
                        val retryMessage = retryAfter?.let {
                            val seconds = it / 1_000L + if (it % 1_000L == 0L) 0L else 1L
                            "Retry-After: wait at least $seconds seconds before retrying."
                        } ?: "No Retry-After was provided; requests are paused for 30 seconds."
                        throw IOException("NovelFire rate-limited this request (HTTP 429). $retryMessage")
                    }

                    val challenged = response.header("cf-mitigated")
                        ?.equals("challenge", ignoreCase = true) == true ||
                        (response.code in setOf(403, 503) &&
                            (response.header("Server")?.contains("cloudflare", true) == true ||
                                response.header("cf-ray") != null)) ||
                        body.contains("<title>Just a moment", ignoreCase = true)
                    if (challenged) throw CloudflareException(url)
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    if (body.isBlank()) throw IOException("NovelFire returned an empty response")
                    body
                }
            } finally {
                nextRequestAt = maxOf(
                    nextRequestAt,
                    safeAdd(System.currentTimeMillis(), MIN_REQUEST_INTERVAL_MS)
                )
            }
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}

private suspend fun fetchNovelFireHtml(url: String): String = NovelFireRequestGate.get(url)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun webNovelDetails(item: BookItem, html: String): BookItem {
    val doc = Jsoup.parse(html, item.id)
    val detailArea = doc.selectFirst(
        ".book-info, .book-information, .novel-info, .book-meta, .book-detail, #book-info, #info"
    ) ?: doc.body()
    val fields = detailArea?.select(
        ".book-info li, .book-info p, .book-information li, .novel-info li, .novel-info p, .book-meta li, .info li"
    ).orEmpty()
    fun field(label: String): String? = fields.firstNotNullOfOrNull { element ->
        val text = element.text().trim()
        if (text.startsWith(label, ignoreCase = true)) {
            text.substring(label.length).trim(' ', ':', '-').ifBlank { null }
        } else null
    }
    val genres = detailArea?.select(
        ".genres a, .genre a, .tags a, .tag a, a[href*=genre], a[href*=tag]"
    ).orEmpty()
        .filter { link -> link.parents().none { it.tagName() in setOf("nav", "header", "footer") } }
        .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
    val genre = (genres + extractGenreTags(doc)).distinct().joinToString(", ").ifBlank { null }
        ?: field("Genres") ?: field("Tags") ?: item.genre
    val description = detailArea?.selectFirst("#book-description, .book-description, .novel-description, .description")
        ?.text()?.trim()?.ifBlank { null }
    return item.copy(
        author = field("Author") ?: field("Authors")
            ?: detailArea?.selectFirst(".author a, .book-author a")?.text()?.trim()?.ifBlank { null }
            ?: item.author,
        artist = field("Artist") ?: item.artist,
        genre = genre,
        description = description ?: item.description
    )
}

/** Project Gutenberg through the public Gutendex API: public-domain classics, real EPUB files. */
class GutenbergSource : BookSource {
    override val id = "gutenberg"
    override val name = "Project Gutenberg"
    override val webUrl = "https://www.gutenberg.org"
    override val description = "EPUB library • public-domain classics"

    override suspend fun popular(page: Int) = parse(fetchText("https://gutendex.com/books/?page=$page&mime_type=application%2Fepub"))

    override suspend fun search(query: String, page: Int) =
        parse(fetchText("https://gutendex.com/books/?search=${enc(query)}&page=$page&mime_type=application%2Fepub"))

    private fun flip(name: String) =
        if (',' in name) name.substringAfter(',').trim() + " " + name.substringBefore(',').trim() else name

    private fun parse(json: String): List<BookItem> {
        val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val formats = o.optJSONObject("formats") ?: return@mapNotNull null
            val epub = formats.optString("application/epub+zip").ifBlank { null } ?: return@mapNotNull null
            val authors = o.optJSONArray("authors")
            val author = if (authors != null && authors.length() > 0) flip(authors.getJSONObject(0).optString("name")) else null
            val summary = o.optJSONArray("summaries")?.optString(0)?.ifBlank { null }
            BookItem(
                sourceId = id, sourceName = name, id = o.optInt("id").toString(),
                title = o.optString("title"), author = author,
                coverUrl = formats.optString("image/jpeg").ifBlank { null },
                epubUrl = epub, description = summary
            )
        }
    }
}

/** Internet Archive: openly downloadable EPUBs and PDFs (lending-restricted books are excluded). */
class InternetArchiveSource : BookSource {
    override val id = "archive"
    override val name = "Internet Archive"
    override val webUrl = "https://archive.org"
    override val description = "EPUB and PDF library • openly downloadable books"

    override suspend fun popular(page: Int) = query("", page)
    override suspend fun search(query: String, page: Int) = query(query, page)

    private suspend fun query(text: String, page: Int): List<BookItem> = coroutineScope {
        val epub = async { queryFormat(text, page, "EPUB") }
        val pdf = async { queryFormat(text, page, "PDF") }
        val results = linkedMapOf<String, BookItem>()
        (epub.await() + pdf.await()).forEach { item ->
            val existing = results[item.id]
            results[item.id] = if (existing == null) item else existing.copy(
                epubUrl = existing.epubUrl ?: item.epubUrl,
                pdfUrl = existing.pdfUrl ?: item.pdfUrl
            )
        }
        results.values.toList()
    }

    private suspend fun queryFormat(text: String, page: Int, format: String): List<BookItem> {
        val base = "mediatype:texts AND format:$format AND -access-restricted-item:true"
        val q = if (text.isBlank()) base else "($text) AND $base"
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=description" +
            "&fl%5B%5D=format&sort%5B%5D=downloads+desc&rows=10&page=$page&output=json"
        val docs = JSONObject(fetchText(url)).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.getJSONObject(i)
            val identifier = d.getString("identifier")
            val formats = d.optJSONArray("format") ?: JSONArray()
            val hasEpub = (0 until formats.length()).any { formats.optString(it).contains("EPUB", true) }
            val hasPdf = (0 until formats.length()).any { formats.optString(it).contains("PDF", true) }
            if (format == "EPUB" && !hasEpub || format == "PDF" && !hasPdf) return@mapNotNull null
            BookItem(
                sourceId = id, sourceName = name, id = identifier,
                title = firstString(d.opt("title")) ?: identifier,
                author = firstString(d.opt("creator")),
                coverUrl = "https://archive.org/services/img/$identifier",
                epubUrl = if (format == "EPUB") "archive:epub" else null,
                description = firstString(d.opt("description"))?.replace(Regex("<[^>]*>"), " ")?.trim(),
                pdfUrl = if (format == "PDF") "archive:pdf" else null
            )
        }
    }

    private fun firstString(v: Any?): String? = when (v) {
        is String -> v.ifBlank { null }
        is JSONArray -> if (v.length() > 0) v.optString(0).ifBlank { null } else null
        else -> null
    }

    override suspend fun resolveEpubUrl(item: BookItem): String {
        val files = JSONObject(fetchText("https://archive.org/metadata/${item.id}")).optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val name = files.getJSONObject(i).optString("name")
            if (name.endsWith(".epub", ignoreCase = true)) {
                val path = name.split('/').joinToString("/") { Uri.encode(it) }
                return "https://archive.org/download/${item.id}/$path"
            }
        }
        throw IOException("No EPUB file found for this item")
    }

    override suspend fun resolvePdfUrl(item: BookItem): String {
        val files = JSONObject(fetchText("https://archive.org/metadata/${item.id}")).optJSONArray("files")
            ?: throw IOException("No files found for this item")
        val pdf = (0 until files.length())
            .mapNotNull { files.optJSONObject(it)?.optString("name") }
            .filter { it.endsWith(".pdf", ignoreCase = true) }
            .sortedBy { it.contains("_text.", ignoreCase = true) }
            .firstOrNull()
            ?: throw IOException("No PDF file found for this item")
        val path = pdf.split('/').joinToString("/") { Uri.encode(it) }
        return "https://archive.org/download/${item.id}/$path"
    }
}

/** Royal Road: original web fiction. Chapters are packed into an EPUB when you download. */
class RoyalRoadSource : BookSource {
    override val id = "royalroad"
    override val name = "Royal Road"
    override val description = "Web novel • original web fiction"
    override val isWebNovel = true
    private val base = "https://www.royalroad.com"
    override val webUrl = base

    override suspend fun popular(page: Int) = list("$base/fictions/best-rated?page=$page")
    override suspend fun search(query: String, page: Int) = list("$base/fictions/search?title=${enc(query)}&page=$page")

    override suspend fun details(item: BookItem): BookItem {
        val doc = Jsoup.parse(fetchHtml(item.id), item.id)
        val info = doc.selectFirst(".fiction-info, .fiction-stats, .fiction-content") ?: doc
        val genres = (info.select(".tags a, .fiction-tags a, a[href*=/fiction/genre/]")
            .map { it.text().trim() } + extractGenreTags(doc)).filter { it.isNotBlank() }.distinct()
        val author = doc.selectFirst(".author a, .fiction-author a")?.text()?.trim()
            ?.ifBlank { null } ?: item.author
        return item.copy(
            author = author,
            description = doc.selectFirst(".description, .fiction-description")?.text()?.trim()
                ?.ifBlank { null } ?: item.description,
            genre = genres.joinToString(", ").ifBlank { null }
        )
    }

    private suspend fun list(url: String): List<BookItem> {
        val doc = Jsoup.parse(fetchHtml(url), base)
        return doc.select("div.fiction-list-item").mapNotNull { el ->
            val a = el.selectFirst("h2.fiction-title a") ?: return@mapNotNull null
            BookItem(
                sourceId = id, sourceName = name, id = a.absUrl("href"), title = a.text().trim(), author = null,
                coverUrl = el.selectFirst("img")?.absUrl("src")?.ifBlank { null }, epubUrl = null,
                description = el.selectFirst("div[id^=description]")?.text()?.take(400)
            )
        }
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val doc = Jsoup.parse(fetchHtml(item.id), item.id)
        return doc.select("table#chapters a[href*=/chapter/]")
            .map { NovelChapter(it.text().trim(), it.absUrl("href")) }
            .distinctBy { it.url }
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val doc = Jsoup.parse(fetchHtml(chapter.url), chapter.url)
        return doc.selectFirst("div.chapter-content")?.html() ?: throw IOException("Chapter text not found")
    }
}

/** NovelPhoenix uses a dedicated search route and a separately paginated chapter index. */
class NovelPhoenixSource : BookSource {
    override val id = "novel_novelphoenix_com"
    override val name = "Novel Phoenix"
    override val description = "Web novel • paginated chapters"
    override val isWebNovel = true
    private val base = "https://novelphoenix.com"
    override val webUrl = base

    override suspend fun popular(page: Int): List<BookItem> =
        if (page == 1) parseNovels("$base/ranking") else emptyList()

    override suspend fun search(query: String, page: Int): List<BookItem> =
        parseNovels("$base/search?keyword=${enc(query)}&page=$page")

    override suspend fun details(item: BookItem): BookItem = webNovelDetails(item, fetchNovelFireHtml(item.id))

    private suspend fun parseNovels(url: String): List<BookItem> {
        val doc = Jsoup.parse(fetchNovelFireHtml(url), url)
        return doc.select("a[href]").asSequence()
            .mapNotNull { a ->
                val href = novelUrl(a.absUrl("href")) ?: return@mapNotNull null
                val img = a.selectFirst("img") ?: return@mapNotNull null
                val title = a.selectFirst(".novel-title")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: a.attr("title").trim().ifBlank { img.attr("alt").trim() }
                if (title.isBlank()) return@mapNotNull null
                BookItem(
                    sourceId = id,
                    sourceName = name,
                    id = href,
                    title = title,
                    author = null,
                    coverUrl = lazyImage(img),
                    epubUrl = null
                )
            }
            .distinctBy { it.id }
            .toList()
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val novelUrl = novelUrl(item.id) ?: throw IOException("Invalid Novel Phoenix title address")
        val indexUrl = "$novelUrl/chapters?page=1"
        val firstPage = fetchDoc(indexUrl)
        val lastPage = chapterIndexPageCount(firstPage)
        if (lastPage > MAX_CHAPTER_INDEX_PAGES) {
            throw IOException("Novel Phoenix chapter list is too large to load safely ($lastPage pages)")
        }
        val semaphore = Semaphore(4)
        val otherPages = coroutineScope {
            (2..lastPage).map { page ->
                async {
                    semaphore.withPermit {
                        fetchDoc("$novelUrl/chapters?page=$page")
                    }
                }
            }.awaitAll()
        }
        val docs = listOf(firstPage) + otherPages
        val chapters = docs.flatMap(::parseChapters).distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("Novel Phoenix returned no chapters for this title")
        return chapters
    }

    private fun chapterIndexPageCount(doc: Document): Int =
        doc.select(".chapter-range-pager option[value], .chapter-range-pager a[href], #chpagedlist select option[value]")
            .asSequence()
            .mapNotNull { element ->
                val value = if (element.tagName() == "option") element.attr("value") else element.absUrl("href")
                Uri.parse(value).getQueryParameter("page")?.toIntOrNull()
            }
            .maxOrNull()
            ?.coerceAtLeast(1)
            ?: 1

    private fun parseChapters(doc: Document): List<NovelChapter> =
        doc.select(".chapter-list a[href]").asSequence()
            .mapNotNull { a ->
                val url = a.absUrl("href")
                val uri = Uri.parse(url)
                val path = uri.path.orEmpty()
                if (uri.host?.removePrefix("www.")?.equals("novelphoenix.com", true) != true ||
                    !path.matches(Regex("""^/novel/[^/]+/chapter-[^/]+/?$""", RegexOption.IGNORE_CASE))
                ) return@mapNotNull null
                val title = a.selectFirst(".chapter-title")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: a.attr("title").trim().ifBlank { a.text().trim() }
                if (title.isBlank()) null else NovelChapter(title, url)
            }
            .toList()

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val doc = fetchDoc(chapter.url)
        val content = doc.selectFirst("#content")
            ?: throw IOException("Novel Phoenix chapter text was not found")
        content.select("script, style, noscript, iframe, nav, header, footer, aside, form, .nf-ads, [class*=advert], [id*=advert]")
            .remove()
        if (content.select("p").none { it.text().isNotBlank() }) {
            throw IOException("Novel Phoenix returned an empty chapter")
        }
        return content.html()
    }

    private suspend fun fetchDoc(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun novelUrl(raw: String): String? {
        val uri = Uri.parse(raw)
        if (uri.host?.removePrefix("www.")?.equals("novelphoenix.com", true) != true) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/novel/[^/]+$""", RegexOption.IGNORE_CASE))) return null
        return "$base$path"
    }

    private companion object {
        const val MAX_CHAPTER_INDEX_PAGES = 300
    }
}

/** NovelFire has a distinct search route, book URL shape, and paginated chapter index. */
class NovelFireSource : BookSource {
    override val id = "novel_novelfire_net"
    override val name = "NovelFire"
    override val description = "Web novel • paginated chapters"
    override val isWebNovel = true
    private val base = "https://novelfire.net"
    override val webUrl = base

    override suspend fun popular(page: Int): List<BookItem> =
        if (page == 1) parseNovels("$base/ranking") else emptyList()

    override suspend fun search(query: String, page: Int): List<BookItem> =
        parseNovels("$base/search?keyword=${enc(query)}&page=$page")

    override suspend fun details(item: BookItem): BookItem = webNovelDetails(item, fetchHtml(item.id))

    private suspend fun parseNovels(url: String): List<BookItem> {
        val doc = Jsoup.parse(fetchHtml(url), url)
        return doc.select("a[href]").asSequence()
            .mapNotNull { a ->
                val href = bookUrl(a.absUrl("href")) ?: return@mapNotNull null
                val img = a.selectFirst("img") ?: return@mapNotNull null
                val title = a.selectFirst(".novel-title")?.text()?.trim()?.ifBlank { null }
                    ?: a.attr("title").trim().ifBlank { img.attr("alt").trim() }
                if (title.isBlank()) return@mapNotNull null
                BookItem(
                    sourceId = id,
                    sourceName = name,
                    id = href,
                    title = title,
                    author = null,
                    coverUrl = lazyImage(img),
                    epubUrl = null
                )
            }
            .distinctBy { it.id }
            .toList()
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val book = bookUrl(item.id) ?: throw IOException("Invalid NovelFire title address")
        val firstUrl = "$book/chapters?page=1"
        val firstPage = fetchDoc(firstUrl)
        val lastPage = chapterIndexPageCount(firstPage)
        if (lastPage > MAX_CHAPTER_INDEX_PAGES) {
            throw IOException("NovelFire chapter list is too large to load safely ($lastPage pages)")
        }
        val chapters = buildList {
            addAll(parseChapters(firstPage))
            for (page in 2..lastPage) {
                addAll(parseChapters(fetchDoc("$book/chapters?page=$page")))
            }
        }
            .distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("NovelFire returned no chapters for this title")
        return chapters
    }

    private fun chapterIndexPageCount(doc: Document): Int =
        doc.select(".pagination a[href], .pagedlist a[href]")
            .asSequence()
            .mapNotNull { Uri.parse(it.absUrl("href")).getQueryParameter("page")?.toIntOrNull() }
            .maxOrNull()
            ?.coerceAtLeast(1)
            ?: 1

    private fun parseChapters(doc: Document): List<NovelChapter> =
        doc.select(".chapter-list a[href]").asSequence()
            .mapNotNull { a ->
                val url = a.absUrl("href")
                val uri = Uri.parse(url)
                if (uri.host?.removePrefix("www.")?.equals("novelfire.net", true) != true ||
                    !uri.path.orEmpty().matches(Regex("""^/book/[^/]+/chapter-[^/]+/?$""", RegexOption.IGNORE_CASE))
                ) return@mapNotNull null
                val title = a.selectFirst(".chapter-title")?.text()?.trim()?.ifBlank { null }
                    ?: a.attr("title").trim().ifBlank { a.text().trim() }
                if (title.isBlank()) null else NovelChapter(title, url)
            }
            .toList()

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val uri = Uri.parse(chapter.url)
        if (uri.host?.removePrefix("www.")?.equals("novelfire.net", true) != true ||
            !uri.path.orEmpty().matches(Regex("""^/book/[^/]+/chapter-[^/]+/?$""", RegexOption.IGNORE_CASE))
        ) throw IOException("Invalid NovelFire chapter address")

        val doc = fetchDoc(chapter.url)
        val content = doc.selectFirst("#content")
            ?: throw IOException("NovelFire chapter text was not found")
        content.select("script, style, noscript, iframe, nav, header, footer, aside, form, .nf-ads, [class*=advert], [id*=advert]")
            .remove()
        if (content.select("p").none { it.text().isNotBlank() }) {
            throw IOException("NovelFire returned an empty chapter")
        }
        return content.html()
    }

    private suspend fun fetchDoc(url: String): Document = Jsoup.parse(fetchNovelFireHtml(url), url)

    private fun bookUrl(raw: String): String? {
        val uri = Uri.parse(raw)
        if (uri.host?.removePrefix("www.")?.equals("novelfire.net", true) != true) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/book/[^/]+$""", RegexOption.IGNORE_CASE))) return null
        return "$base$path"
    }

    private companion object {
        const val MAX_CHAPTER_INDEX_PAGES = 300
    }
}

class TapasNovelSource : BookSource {
    override val id = "novel_tapas_io"
    override val name = "Tapas Novels"
    override val description = "Web novel • Tapas novel catalog"
    override val isWebNovel = true
    private val base = "https://tapas.io"
    override val webUrl = base

    override suspend fun popular(page: Int): List<BookItem> {
        val url = "$base/landing-list/6${if (page > 1) "?page=$page" else ""}"
        val doc = Jsoup.parse(fetchHtml(url), url)
        return parseItems(doc)
    }

    override suspend fun search(query: String, page: Int): List<BookItem> {
        if (query.isBlank()) return emptyList()
        val url = "$base/search?q=${enc(query)}&t=NOVELS&page=$page"
        val doc = Jsoup.parse(fetchHtml(url), url)
        return parseItems(doc.body() ?: return emptyList())
    }

    private fun parseItems(container: Element): List<BookItem> =
        container.select("a[href^=/series/], li[data-href^=/series/]").mapNotNull { card ->
            val rawUrl = if (card.hasAttr("href")) card.attr("href") else card.attr("data-href")
            val url = seriesUrl(rawUrl) ?: return@mapNotNull null
            val image = card.selectFirst("img")
            val title = card.attr("title").trim().ifBlank {
                card.selectFirst("[title]")?.attr("title")?.trim().orEmpty()
            }.ifBlank {
                image?.attr("alt")?.trim().orEmpty().ifBlank { card.text().trim() }
            }
            if (title.isBlank()) return@mapNotNull null
            BookItem(id, name, url, title, null, image?.let(::lazyImage), null)
        }.distinctBy { it.id }

    override suspend fun details(item: BookItem): BookItem {
        val url = seriesUrl(item.id) ?: throw IOException("Invalid Tapas series address")
        val doc = Jsoup.parse(fetchHtml("$url/info"), "$url/info")
        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.removePrefix("Read ")?.substringBefore(" | Tapas")?.trim()?.ifBlank { null }
            ?: item.title
        val author = doc.select("[class*=author] a, a[href*=/creator/]")
            .map { it.text().trim() }.firstOrNull { it.isNotBlank() }
        val genres = doc.select(
            "a[href*=/genre/], .genre a, .genres a, .tag-list a, .detail-row__body--genre a"
        )
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        return item.copy(
            title = title,
            author = author ?: item.author,
            coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null } ?: item.coverUrl,
            description = doc.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")?.trim()?.ifBlank { null } ?: item.description,
            genre = genres.joinToString(", ").ifBlank { null } ?: item.genre
        )
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val url = seriesUrl(item.id) ?: throw IOException("Invalid Tapas series address")
        val doc = Jsoup.parse(fetchHtml("$url/info"), "$url/info")
        return TapasSupport.chapters(base, doc).map { NovelChapter(it.title, it.url) }
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val url = TapasSupport.episodeUrl(chapter.url) ?: throw IOException("Invalid Tapas episode address")
        val doc = Jsoup.parse(fetchHtml(url), url)
        val content = doc.selectFirst("article.viewer__body")
            ?: throw IOException("Tapas novel text was not found")
        content.select("script, style, noscript, iframe, nav, header, footer, aside, form").remove()
        val hasText = content.select("p, div, span").any { it.ownText().isNotBlank() }
        val hasImages = content.select("img").toList().any { image ->
            val src = image.attr("src")
            when {
                src.startsWith("data:image/", ignoreCase = true) -> {
                    if (TapasSupport.hasReadableImage(src)) true else {
                        image.remove()
                        false
                    }
                }
                else -> lazyImage(image) != null
            }
        }
        if (!hasText && !hasImages) {
            throw IOException("Tapas returned an empty novel episode")
        }
        return content.html()
    }

    private fun seriesUrl(raw: String): String? {
        val uri = Uri.parse(if (raw.startsWith("http", true)) raw else "$base/${raw.removePrefix("/")}")
        if (uri.host.orEmpty().lowercase() !in setOf("tapas.io", "www.tapas.io", "m.tapas.io")) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/series/[^/]+$""", RegexOption.IGNORE_CASE))) return null
        return "$base$path"
    }

}

private class LunarXSource : BookSource {
    override val id = "novel_" + hostId("https://lunarx.to", "LunarX")
    override val name = "LunarX"
    override val description = "Web novel • LunarX catalog"
    override val isWebNovel = true
    override val inGlobalSearch = false
    override val webUrl = "https://lunarx.to"
    private val api = "https://api.lunarx.to"
    private val site = "https://lunarx.to"

    override suspend fun popular(page: Int): List<BookItem> {
        if (page < 1) return emptyList()
        val json = JSONObject(fetchText("$api/api/novels/db/search?sort=popular&page=$page&limit=24"))
        return parseItems(json.optJSONArray("novels"))
    }

    override suspend fun search(query: String, page: Int): List<BookItem> {
        if (query.isBlank() || page < 1) return emptyList()
        val json = JSONObject(
            fetchText("$api/api/novels/db/search?query=${enc(query)}&page=$page&limit=24")
        )
        return parseItems(json.optJSONArray("novels"))
    }

    private fun parseItems(entries: JSONArray?): List<BookItem> {
        if (entries == null) return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val slug = entry.optString("slug").takeIf { it.matches(Regex("""[A-Za-z0-9_-]+""")) }
                ?: return@mapNotNull null
            val title = entry.optString("title").trim().ifBlank { return@mapNotNull null }
            val genres = parseGenres(entry.opt("genres"))
            BookItem(
                sourceId = id,
                sourceName = name,
                id = "$site/novel/$slug",
                title = title,
                author = entry.optString("author").takeIf { it.isNotBlank() && it != "null" },
                coverUrl = entry.optString("cover_url").takeIf { it.startsWith("https://") },
                epubUrl = null,
                description = entry.optString("description").takeIf { it.isNotBlank() && it != "null" },
                artist = entry.optString("artist").takeIf { it.isNotBlank() && it != "null" },
                genre = genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
            )
        }.distinctBy { it.id }
    }

    private fun parseGenres(raw: Any?): List<String> {
        val values = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { array.optString(it) }
            }.getOrElse { raw.split(',') }
            else -> emptyList()
        }
        return values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun novelSlug(itemUrl: String): String? {
        val uri = Uri.parse(itemUrl)
        if (uri.host?.removePrefix("www.")?.equals("lunarx.to", true) != true) return null
        return Regex("""^/novel/([A-Za-z0-9_-]+)/?$""")
            .matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
    }

    override suspend fun details(item: BookItem): BookItem {
        val slug = novelSlug(item.id) ?: throw IOException("Invalid LunarX novel address")
        val results = search(item.title, 1)
        return results.firstOrNull { novelSlug(it.id) == slug } ?: item
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val slug = novelSlug(item.id) ?: throw IOException("Invalid LunarX novel address")
        val result = JSONObject(fetchText("$api/api/novels/chapters/${Uri.encode(slug)}"))
        val entries = result.optJSONArray("chapters")
            ?: throw IOException("LunarX did not return a chapter list")
        if (entries.length() == 0) throw IOException("LunarX has no published chapters for this novel")
        val expected = result.optInt("count", entries.length())
        if (expected > entries.length()) {
            throw IOException("LunarX returned an incomplete chapter list")
        }
        val chapters = (0 until entries.length()).mapNotNull { index ->
            val chapter = entries.optJSONObject(index) ?: return@mapNotNull null
            if (chapter.optBoolean("is_coming_soon")) return@mapNotNull null
            if (!chapter.optString("language").equals("en", ignoreCase = true)) return@mapNotNull null
            val number = chapter.optInt("chapter", -1).takeIf { it >= 0 }
                ?: return@mapNotNull null
            val title = chapter.optString("chapter_title").trim()
                .takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
                ?: "Chapter $number"
            NovelChapter(title, "$site/novel/$slug/$number")
        }.distinctBy { it.url }.sortedBy { chapter ->
            chapter.url.substringAfterLast('/').toIntOrNull() ?: Int.MAX_VALUE
        }
        if (chapters.isEmpty()) throw IOException("LunarX has no readable English chapters for this novel")
        return chapters
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val url = chapter.url
        val chapterSlug = Regex("""^https://lunarx\.to/novel/([A-Za-z0-9_-]+)/(\d+)/?$""")
            .matchEntire(url)?.groupValues?.get(1)
            ?: throw IOException("Invalid LunarX chapter address")
        if (!chapterSlug.matches(Regex("""[A-Za-z0-9_-]+"""))) {
            throw IOException("Invalid LunarX chapter address")
        }
        val doc = Jsoup.parse(fetchHtml(url), url)
        val contentSelectors = listOf(
            ".chapter-content", ".chapter__content", ".novel-content", ".chapter-body",
            ".chapter-text", ".reader-content", ".novel-reader", ".reading-content",
            ".text-content", ".chapter-content__text", ".chapter__body", ".chapter-inner",
            ".reading-area", ".novel__content", ".prose", "[class*=chapter-text]", "[id*=chapter-text]",
            "[class*=reader-content]", "[id*=reader-content]"
        )
        val specificContent = contentSelectors.flatMap { doc.select(it).toList() }.distinct()
        val candidates = (specificContent + doc.select(
            "article, main, section, .entry-content, [class*=chapter], [id*=chapter]"
        ).toList()).distinct().map { candidate ->
            candidate.clone().apply {
                select("script, style, noscript, iframe, nav, header, footer, aside, form, " +
                    "[class*=advert], [id*=advert], [class*=comment], [id*=comment], " +
                    "[class*=recommend], [id*=recommend], .ads, .ad").remove()
            }
        }
        val content = candidates.maxByOrNull { candidate ->
            candidate.text().length
        } ?: throw IOException("LunarX chapter text was not found")
        val readableText = content.text().trim()
        if (readableText.length < 80) {
            throw IOException("LunarX returned an empty chapter")
        }
        return content.html()
    }
}

/** Lunox Scans hosts its translated novels as text chapters on a Madara-based series catalog. */
private class LunoxScansSource : BookSource {
    private val base = "https://lunoxscans.com"

    override val id = "novel_lunoxscans_com"
    override val name = "Lunox Scans"
    override val description = "Web novel • complete text chapters"
    override val isWebNovel = true
    override val webUrl = base

    override suspend fun popular(page: Int): List<BookItem> {
        if (page < 1) return emptyList()
        val url = if (page == 1) "$base/" else "$base/page/$page/"
        return parseItems(Jsoup.parse(fetchHtml(url), url))
    }

    override suspend fun search(query: String, page: Int): List<BookItem> {
        if (query.isBlank() || page < 1) return emptyList()
        val url = "$base/?s=${enc(query)}&post_type=wp-manga&paged=$page"
        return parseItems(Jsoup.parse(fetchHtml(url), url))
    }

    private fun parseItems(doc: Document): List<BookItem> =
        doc.select("a[href]").mapNotNull { link ->
            val url = seriesUrl(link.absUrl("href")) ?: return@mapNotNull null
            val image = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
            val title = sequenceOf(
                link.attr("title"),
                link.selectFirst(".post-title, .manga-title, h2, h3, h4")?.text().orEmpty(),
                image?.attr("alt").orEmpty(),
                link.text()
            ).map(String::trim).firstOrNull { it.isNotBlank() && it.length < 250 }
                ?: return@mapNotNull null
            BookItem(
                sourceId = id,
                sourceName = name,
                id = url,
                title = title,
                author = null,
                coverUrl = image?.let(::lazyImage),
                epubUrl = null
            )
        }.distinctBy { it.id }

    override suspend fun details(item: BookItem): BookItem {
        val url = seriesUrl(item.id) ?: throw IOException("Invalid Lunox Scans series address")
        val doc = Jsoup.parse(fetchHtml(url), url)
        val title = doc.selectFirst("h1, .post-title")?.text()?.trim()?.ifBlank { null } ?: item.title
        val rows = doc.select(".post-content_item, .post-content_item .summary-content, .post-content_item .summary-heading")
            .map { it.text().trim() }
        val author = doc.select("a[href*=/author/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { null }
        val genres = doc.select("a[href*=/genre/], a[href*=/genres/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        return item.copy(
            title = title,
            author = author ?: item.author,
            coverUrl = doc.selectFirst(".summary_image img, .poster img, .manga-thumb img")?.let(::lazyImage)
                ?: item.coverUrl,
            description = doc.selectFirst(".description-summary, .summary__content, .post-content_item .summary-content")
                ?.text()?.trim()?.ifBlank { null } ?: item.description,
            genre = genres.joinToString(", ").ifBlank { null }
                ?: rows.firstOrNull { it.startsWith("Genre", true) }?.substringAfter(':')?.trim()
        )
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val url = seriesUrl(item.id) ?: throw IOException("Invalid Lunox Scans series address")
        val doc = Jsoup.parse(fetchHtml(url), url)
        val chapterList = doc.selectFirst(".lunox-chapters-grid")
            ?: throw IOException("Lunox Scans did not provide its chapter list")
        val chapters = chapterList.select("a[href]").mapNotNull { link ->
            val chapterUrl = chapterUrl(link.absUrl("href")) ?: return@mapNotNull null
            val title = sequenceOf(
                link.attr("title"),
                link.selectFirst(".chapter, .chapter-name")?.text().orEmpty(),
                link.text()
            ).map(String::trim).firstOrNull { it.isNotBlank() }
                ?: chapterUrl.substringAfterLast('/').replace('-', ' ')
            NovelChapter(title, chapterUrl)
        }.distinctBy { it.url }
            .sortedBy { chapter ->
                Regex("""/chapter-(\d+(?:\.\d+)?)""").find(chapter.url)
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.MAX_VALUE
            }
        if (chapters.isEmpty()) throw IOException("Lunox Scans returned no chapters for this series")
        return chapters
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val url = chapterUrl(chapter.url) ?: throw IOException("Invalid Lunox Scans chapter address")
        val doc = Jsoup.parse(fetchHtml(url), url)
        val content = doc.selectFirst(".reading-content .text-left")
            ?: doc.selectFirst(".reading-content")
            ?: throw IOException("Lunox Scans chapter text was not found")
        content.select("script, style, noscript, iframe, nav, header, footer, aside, form, " +
            ".chapter-navigation, .chapter-nav, .login-required, .chapter-coin").remove()
        val readableText = content.text().trim()
        if (readableText.length < 100) throw IOException("Lunox Scans returned an empty chapter")
        return content.html()
    }

    private fun seriesUrl(raw: String): String? {
        val uri = Uri.parse(raw)
        if (uri.host?.removePrefix("www.")?.equals("lunoxscans.com", true) != true) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/series/[^/]+$""", RegexOption.IGNORE_CASE))) return null
        return "$base$path/"
    }

    private fun chapterUrl(raw: String): String? {
        val uri = Uri.parse(raw)
        if (uri.host?.removePrefix("www.")?.equals("lunoxscans.com", true) != true) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/series/[^/]+/chapter-[\d.]+$""", RegexOption.IGNORE_CASE))) return null
        return "$base$path/"
    }
}

/** Any other web-novel site, read with the pattern-based scraper (best effort; some sites need a browser). */
class WebNovelSource(
    override val id: String,
    override val name: String,
    baseUrl: String
) : BookSource {
    override val description = "Web novel • best-effort"
    override val isWebNovel = true
    override val webUrl = baseUrl
    private val engine = GenericSource(name, baseUrl, "en", false, novel = true)

    private fun toItem(m: SManga) = BookItem(
        sourceId = id, sourceName = name, id = m.url, title = m.title, author = null,
        coverUrl = m.thumbnailUrl, epubUrl = null
    )

    override suspend fun popular(page: Int) = engine.getPopular(page).mangas.map { toItem(it) }
    override suspend fun search(query: String, page: Int) = engine.search(query, page).mangas.map { toItem(it) }
    override suspend fun searchByGenre(query: String, page: Int) =
        engine.searchByGenre(query, page).mangas.map { toItem(it) }
    override suspend fun searchByAuthor(query: String, page: Int) =
        engine.searchByAuthor(query, page).mangas.map { toItem(it) }

    override suspend fun details(item: BookItem): BookItem {
        val details = engine.getMangaDetails(
            SManga(url = item.id, title = item.title, thumbnailUrl = item.coverUrl, author = item.author)
        )
        return item.copy(
            title = details.title,
            author = details.author,
            artist = details.artist,
            description = details.description,
            genre = details.genre,
            coverUrl = details.thumbnailUrl ?: item.coverUrl
        )
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val newestFirst = engine.getChapters(SManga(url = item.id, title = item.title))
        return newestFirst.reversed().map { NovelChapter(it.name, it.url) }
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String = engine.chapterText(chapter.url)
}

object BookSources {
    /** Web-novel sites from the QuickNovel source list. Domains can change; edit here if one moves. */
    private val novelSites = listOf(
        "AllNovel" to "https://allnovel.org", "Bright Novel" to "https://brightnovels.com",
        "Chikari" to "https://chikari.net", "Chrysanthemum Garden" to "https://chrysanthemumgarden.com",
        "FaqWiki" to "https://faqwiki.us", "FenrirRealm" to "https://fenrirealm.com",
        "FreeWebNovel" to "https://freewebnovel.com", "Graycity" to "https://graycity.net",
        "Hiraeth Translation" to "https://hiraethtranslation.com", "LibRead" to "https://libread.com",
        "Light Novel Translations" to "https://lightnovelstranslations.com", "LnMTL" to "https://lnmtl.com",
        "Luxon Scan" to "https://luxonscan.com", "MtlNovel" to "https://www.mtlnovel.com",
        "Novel Buddy" to "https://novelbuddy.com", "Novel Light" to "https://novellight.net",
        "NovelPing" to "https://novelping.com", "NovelArrow" to "https://novelarrow.com",
        "LunarX" to "https://lunarx.to",
        "Lunox Scans" to "https://lunoxscans.com",
        "Ranobes" to "https://ranobes.net",
        "Novel Phoenix" to "https://novelphoenix.com", "NovelBin" to "https://novelbin.me",
        "NovelFire" to "https://novelfire.net", "NovelFull" to "https://novelfull.com",
        "PawRead" to "https://pawread.com", "Ranovel" to "https://ranovel.com",
        "ReadFrom.Net" to "https://readfrom.net", "ReadHive" to "https://readhive.org",
        "Scribblehub" to "https://www.scribblehub.com", "ScrollersPub" to "https://scrollers.pub",
        "SonicMTL" to "https://sonicmtl.com", "WTR-LAB" to "https://wtr-lab.com",
        "Wattpad" to "https://www.wattpad.com", "WuxiaBox" to "https://www.wuxiabox.com",
        "WuxiaClick" to "https://wuxia.click", "WuxiaWorld" to "https://www.wuxiaworld.com"
    )

    val all: List<BookSource> =
        listOf(
            LocalBookSource(),
            GutenbergSource(),
            InternetArchiveSource(),
            RoyalRoadSource(),
            TapasNovelSource(),
            RoliaScanNovelSource()
        ) +
            novelSites.map { (name, url) ->
                when {
                    name == "LunarX" -> LunarXSource()
                    name == "Lunox Scans" -> LunoxScansSource()
                    else -> when (hostId(url, name)) {
                        "novelphoenix_com" -> NovelPhoenixSource()
                        "novelfire_net" -> NovelFireSource()
                        "novelping_com" -> NovelPingSource()
                        "novelarrow_com" -> NovelPingSource(
                            catalogId = "novel_novelarrow_com",
                            displayName = "NovelArrow",
                            siteUrl = url
                        )
                        else -> WebNovelSource("novel_" + hostId(url, name), name, url)
                    }
                }
            }
    fun get(id: String): BookSource? = all.firstOrNull { it.id == id }

    fun itemFor(manga: MangaEntity): BookItem? {
        val source = get(manga.source) ?: return null
        if (!source.isWebNovel) return null
        return BookItem(
            sourceId = source.id,
            sourceName = source.name,
            id = manga.url,
            title = manga.title,
            author = manga.author,
            artist = manga.artist,
            genre = manga.genre,
            coverUrl = manga.coverUrl,
            epubUrl = null,
            description = manga.description
        )
    }
}

/** Local files are managed by the app's local-folder scanner rather than a network catalog. */
class LocalBookSource : BookSource {
    override val id = LOCAL_SOURCE_ID
    override val name = "Local"
    override val description = "Add, browse, and remove entries stored on your device"
    override val inGlobalSearch = false

    override suspend fun popular(page: Int): List<BookItem> = emptyList()
    override suspend fun search(query: String, page: Int): List<BookItem> = emptyList()
}
