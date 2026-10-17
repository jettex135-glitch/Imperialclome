package com.imperialreader.data.book

import android.net.Uri
import com.imperialreader.App
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import com.imperialreader.data.source.GenericSource
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.hostId
import com.imperialreader.data.source.lazyImage
import com.imperialreader.data.source.matchesMetadataQuery
import org.json.JSONArray
import org.jsoup.Jsoup
import org.json.JSONObject
import org.jsoup.nodes.Document
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
    val genre: String? = null
) {
    val key: String get() = "$sourceId:$id"
}

data class NovelChapter(val title: String, val url: String)

data class BookSearchPage(val items: List<BookItem>, val hasNextPage: Boolean)

interface BookSource {
    val id: String
    val name: String

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

    suspend fun searchMetadata(query: String, page: Int): BookSearchPage {
        if (query.isBlank()) return BookSearchPage(emptyList(), false)
        val candidates = popular(page)
        val matches = candidates.take(8).mapNotNull { candidate ->
            val detailed = try {
                details(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            detailed?.takeIf { matchesMetadataQuery(query, it.author, it.artist, it.genre) }
        }
        return BookSearchPage(matches, candidates.size >= 8)
    }

    /** Direct link to the .epub file (some catalogs need a second request to find it). */
    suspend fun resolveEpubUrl(item: BookItem): String =
        item.epubUrl ?: throw IOException("No EPUB available for this title")
}

internal suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    App.instance.network.client.await(request).use { response -> readResponse(url, response) }
}

internal suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).header("Accept", "application/json").build()
    App.instance.network.client.await(request).use { response -> readResponse(url, response) }
}

private fun readResponse(url: String, response: Response): String {
    val body = response.body?.string().orEmpty()
    val challenge = response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true
    val cloudflare = response.header("Server")?.contains("cloudflare", ignoreCase = true) == true ||
        response.header("cf-ray") != null
    if (challenge || (!response.isSuccessful && cloudflare && response.code in setOf(403, 503))) {
        throw CloudflareException(url)
    }
    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
    return body
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun webNovelDetails(item: BookItem, html: String): BookItem {
    val doc = Jsoup.parse(html, item.id)
    val fields = doc.select(
        ".book-info li, .book-info p, .book-information li, .novel-info li, .novel-info p, .book-meta li, .info li"
    )
    fun field(label: String): String? = fields.firstNotNullOfOrNull { element ->
        val text = element.text().trim()
        if (text.startsWith(label, ignoreCase = true)) {
            text.substring(label.length).trim(' ', ':', '-').ifBlank { null }
        } else null
    }
    val genres = doc.select(
        ".genres a, .genre a, .tags a, .tag a, .genres-tags a, " +
            ".book-info a[href*=genre], .book-info a[href*=tag], a[itemprop=genre], [rel=tag]"
    ).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
    val artist = field("Artist") ?: field("Artists") ?: field("Illustrator")
        ?: doc.select(".artist a, .book-artist a, [itemprop=artist]").joinToString(", ") { it.text().trim() }
            .ifBlank { null }
    val description = doc.selectFirst("#book-description, .book-description, .novel-description, .description")
        ?.text()?.trim()?.ifBlank { null }
    return item.copy(
        author = field("Author") ?: field("Authors")
            ?: doc.selectFirst(".author a, .book-author a")?.text()?.trim()?.ifBlank { null }
            ?: item.author,
        artist = artist ?: item.artist,
        genre = genres.joinToString(", ").ifBlank { null } ?: field("Genres") ?: field("Tags") ?: item.genre,
        description = description ?: item.description
    )
}

/** Project Gutenberg through the public Gutendex API: public-domain classics, real EPUB files. */
class GutenbergSource : BookSource {
    override val id = "gutenberg"
    override val name = "Project Gutenberg"
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

/** Internet Archive: openly downloadable EPUBs (lending-restricted books are excluded). */
class InternetArchiveSource : BookSource {
    override val id = "archive"
    override val name = "Internet Archive"
    override val description = "EPUB library • openly downloadable books"

    override suspend fun popular(page: Int) = query("", page)
    override suspend fun search(query: String, page: Int) = query(query, page)

    private suspend fun query(text: String, page: Int): List<BookItem> {
        val base = "mediatype:texts AND format:EPUB AND -access-restricted-item:true"
        val q = if (text.isBlank()) base else "($text) AND $base"
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=description" +
            "&sort%5B%5D=downloads+desc&rows=20&page=$page&output=json"
        val docs = JSONObject(fetchText(url)).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val identifier = d.getString("identifier")
            BookItem(
                sourceId = id, sourceName = name, id = identifier,
                title = firstString(d.opt("title")) ?: identifier,
                author = firstString(d.opt("creator")),
                coverUrl = "https://archive.org/services/img/$identifier",
                epubUrl = null,
                description = firstString(d.opt("description"))?.replace(Regex("<[^>]*>"), " ")?.trim()
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
}

/** Royal Road: original web fiction. Chapters are packed into an EPUB when you download. */
class RoyalRoadSource : BookSource {
    override val id = "royalroad"
    override val name = "Royal Road"
    override val description = "Web novel • original web fiction"
    override val isWebNovel = true
    private val base = "https://www.royalroad.com"

    override suspend fun popular(page: Int) = list("$base/fictions/best-rated?page=$page")
    override suspend fun search(query: String, page: Int) = list("$base/fictions/search?title=${enc(query)}&page=$page")

    override suspend fun details(item: BookItem): BookItem {
        val doc = Jsoup.parse(fetchHtml(item.id), item.id)
        val info = doc.selectFirst(".fiction-info, .fiction-stats, .fiction-content") ?: doc
        val genres = info.select(".tags a, .fiction-tags a, a[href*=/fiction/genre/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
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

    override suspend fun popular(page: Int): List<BookItem> =
        if (page == 1) parseNovels("$base/ranking") else emptyList()

    override suspend fun search(query: String, page: Int): List<BookItem> =
        parseNovels("$base/search?keyword=${enc(query)}&page=$page")

    override suspend fun details(item: BookItem): BookItem = webNovelDetails(item, fetchHtml(item.id))

    private suspend fun parseNovels(url: String): List<BookItem> {
        val doc = Jsoup.parse(fetchHtml(url), url)
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
        val semaphore = Semaphore(4)
        val otherPages = coroutineScope {
            (2..lastPage).map { page ->
                async {
                    semaphore.withPermit {
                        fetchDoc("$book/chapters?page=$page")
                    }
                }
            }.awaitAll()
        }
        val chapters = (listOf(firstPage) + otherPages)
            .flatMap(::parseChapters)
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

    private suspend fun fetchDoc(url: String): Document = Jsoup.parse(fetchHtml(url), url)

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

/** Any other web-novel site, read with the pattern-based scraper (best effort; some sites need a browser). */
class WebNovelSource(
    override val id: String,
    override val name: String,
    baseUrl: String
) : BookSource {
    override val description = "Web novel • best-effort"
    override val isWebNovel = true
    private val engine = GenericSource(name, baseUrl, "en", false, novel = true)

    private fun toItem(m: SManga) = BookItem(
        sourceId = id, sourceName = name, id = m.url, title = m.title, author = null,
        coverUrl = m.thumbnailUrl, epubUrl = null
    )

    override suspend fun popular(page: Int) = engine.getPopular(page).mangas.map { toItem(it) }
    override suspend fun search(query: String, page: Int) = engine.search(query, page).mangas.map { toItem(it) }

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

/** Lunox is a Madara-based text catalog; premium or empty chapter bodies are rejected. */
class LunoxScansSource : BookSource {
    override val id = "novel_lunoxscans_com"
    override val name = "Lunox Scans"
    override val description = "Web novel • Lunox Novels"
    override val isWebNovel = true

    private val engine = GenericSource(name, "https://lunoxscans.com", "en", novel = true)

    private fun item(manga: SManga) = BookItem(
        sourceId = id,
        sourceName = name,
        id = manga.url,
        title = manga.title,
        author = manga.author,
        coverUrl = manga.thumbnailUrl,
        epubUrl = null,
        description = manga.description,
        artist = manga.artist,
        genre = manga.genre
    )

    override suspend fun popular(page: Int) = engine.getPopular(page).mangas.map(::item)

    override suspend fun search(query: String, page: Int) = engine.search(query, page).mangas.map(::item)

    override suspend fun searchMetadata(query: String, page: Int): BookSearchPage {
        if (query.isBlank()) return BookSearchPage(emptyList(), false)
        val slug = query.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
        val routes = listOf("series-genre/$slug", "series-author/$slug", "series-artist/$slug")
        var candidates = emptyList<SManga>()
        var hasNextPage = false
        for (route in routes) {
            val url = buildString {
                append("https://lunoxscans.com/").append(route.trim('/')).append('/')
                if (page > 1) append("page/").append(page).append('/')
            }
            try {
                val doc = Jsoup.parse(fetchHtml(url), url)
                val found = doc.select(".page-item-detail, .c-tabs-item__content").mapNotNull { card ->
                    val link = card.selectFirst(".post-title a, h3 a, h5 a, a[href*=/series/]")
                        ?: return@mapNotNull null
                    val href = link.absUrl("href")
                    val title = link.text().trim().ifBlank { link.attr("title").trim() }
                    if (!href.startsWith("https://lunoxscans.com/series/") || title.isBlank()) return@mapNotNull null
                    SManga(href, title, lazyImage(card.selectFirst("img")))
                }.distinctBy { it.url }
                if (found.isNotEmpty()) {
                    candidates = found
                    hasNextPage = doc.select("a[rel=next][href], .pagination a.next[href]").isNotEmpty()
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareException) {
                throw e
            } catch (_: Exception) {
            }
        }
        if (candidates.isEmpty()) return super<BookSource>.searchMetadata(query, page)
        val matches = candidates.take(24).mapNotNull { candidate ->
            val detailed = details(item(candidate))
            detailed.takeIf { matchesMetadataQuery(query, it.author, it.artist, it.genre) }
        }
        return BookSearchPage(matches, hasNextPage)
    }

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
        val chapters = engine.getChapters(SManga(url = item.id, title = item.title))
            .asReversed()
            .mapNotNull { chapter ->
                val title = chapter.name.trim()
                if (title.isBlank() || !chapter.url.startsWith("https://lunoxscans.com/series/")) null
                else NovelChapter(title, chapter.url)
            }
        if (chapters.isEmpty()) throw IOException("Lunox returned no readable chapters")
        return chapters
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        if (!chapter.url.startsWith("https://lunoxscans.com/series/")) {
            throw IOException("Invalid Lunox chapter address")
        }
        val doc = Jsoup.parse(fetchHtml(chapter.url), chapter.url)
        val content = doc.selectFirst(".reading-content") ?: throw IOException("Lunox chapter text was not found")
        content.select(
            "script, style, noscript, iframe, nav, header, footer, aside, form, " +
                ".premium, .premium-block, [class*=premium], [class*=coin], [class*=advert]"
        ).remove()
        val paragraphs = content.select("p").filter { it.text().isNotBlank() }
        if (paragraphs.size < 2 || paragraphs.sumOf { it.text().length } < 80) {
            throw IOException("Lunox chapter is empty or premium-locked")
        }
        return content.html()
    }
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
        listOf(LocalBookSource(), GutenbergSource(), InternetArchiveSource(), RoyalRoadSource(), LunoxScansSource()) +
            novelSites.map { (name, url) ->
                when (hostId(url, name)) {
                    "novelphoenix_com" -> NovelPhoenixSource()
                    "novelfire_net" -> NovelFireSource()
                    "novelping_com" -> NovelPingSource()
                    else -> WebNovelSource("novel_" + hostId(url, name), name, url)
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
