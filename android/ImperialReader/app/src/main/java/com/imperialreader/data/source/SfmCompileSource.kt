package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * sfmcompile.club: a WordPress video site. Every post is one MP4 under /wp-content/uploads/YYYY/MM/.
 *
 * Mapping to the reader (same MP4 path Pawchive uses, the reader plays any page URL ending in .mp4):
 *  - a post            -> one entry
 *  - the entry's video -> a single "chapter" whose only page is the MP4 URL
 * Adult site: hidden until "Show 18+" is enabled.
 */
class SfmCompileSource : HttpSource() {
    override val id = "sfmcompile_club"
    override val name = "SFM Compile"
    override val lang = "en"
    override val baseUrl = "https://sfmcompile.club"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage = getLatest(page)

    override suspend fun getLatest(page: Int): MangasPage = listing(if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/", page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val q = query.trim()
        if (q.isEmpty()) return MangasPage(emptyList(), false)
        val url = if (page <= 1) "$baseUrl/?s=${enc(q)}" else "$baseUrl/page/$page/?s=${enc(q)}"
        return listing(url, page)
    }

    private suspend fun listing(url: String, page: Int): MangasPage {
        val html = try {
            getStringWith502Retry(url)
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
            throw e
        }
        return parseListing(html, baseUrl, page)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getStringWith502Retry(manga.url), manga.url)
        return parseDetails(doc, manga)
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getStringWith502Retry(manga.url), manga.url)
        if (parseVideoUrls(doc, baseUrl).isEmpty()) throw IOException("SFM Compile: no video found on this post")
        return listOf(SChapter(url = manga.url, name = "Video", dateUpload = publishedMillis(doc), chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getStringWith502Retry(chapter.url), chapter.url)
        val videos = parseVideoUrls(doc, baseUrl)
        if (videos.isEmpty()) throw IOException("SFM Compile: no MP4 found on this post")
        return videos
    }

    private suspend fun getStringWith502Retry(url: String): String {
        var retries = 0
        while (true) {
            try {
                return getString(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code != 502 || retries >= MAX_502_RETRIES) throw e
                val backoffMs = 500L shl retries
                delay(e.retryAfterMs?.coerceIn(backoffMs, MAX_RETRY_AFTER_MS) ?: backoffMs)
                retries++
            }
        }
    }

    companion object {
        private const val MAX_502_RETRIES = 2
        private const val MAX_RETRY_AFTER_MS = 3_000L

        private val reserved = setOf(
            "category", "tag", "page", "author", "wp-content", "wp-admin", "wp-json", "feed", "search", "login", "register",
            "about", "contact", "dmca", "privacy-policy", "terms", "collections", "favourites", "trending", "tags", "categories"
        )

        private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")

        /** One path segment under the site root = a post (…/<slug>/), excluding system and taxonomy pages. */
        fun postSlug(url: String, baseUrl: String): String? {
            if (!hostOf(url).equals(hostOf(baseUrl), ignoreCase = true)) return null
            val path = runCatching { URI(url).rawPath }.getOrNull().orEmpty().trim('/')
            if (path.isEmpty() || path.contains('/')) return null
            if (path.lowercase() in reserved || path.contains('.')) return null
            return path
        }

        fun encodeSpaces(url: String): String = url.trim().replace(" ", "%20")

        private fun posterOf(container: Element?): String? {
            if (container == null) return null
            container.selectFirst("video[poster]")?.attr("abs:poster")?.takeIf { it.isNotBlank() }?.let { return it }
            for (img in container.select("img")) {
                val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
                    .map { img.attr(it).trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") } ?: continue
                val abs = runCatching { URI(img.baseUri()).resolve(raw).toString() }.getOrDefault(raw)
                if (abs.contains("logo", true) || abs.contains("avatar", true) || abs.contains("gravatar", true)) continue
                return abs
            }
            return null
        }

        /** Post cards on the home page, category pages and search results. */
        fun parseListing(html: String, baseUrl: String, page: Int = 1): MangasPage {
            val doc = Jsoup.parse(html, baseUrl)
            val found = LinkedHashMap<String, SManga>()
            for (a in doc.select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href]")) {
                val href = a.absUrl("href")
                val slug = postSlug(href, baseUrl) ?: continue
                val title = a.text().trim().ifBlank { a.attr("title").trim() }
                if (title.isBlank()) continue
                val url = "${baseUrl.trimEnd('/')}/$slug/"
                val container = a.closest("article") ?: a.parents().firstOrNull { it.selectFirst("video, img") != null }
                val existing = found[url]
                val poster = posterOf(container)
                if (existing == null) found[url] = SManga(url = url, title = title, thumbnailUrl = poster)
                else if (existing.thumbnailUrl == null && poster != null) existing.thumbnailUrl = poster
            }
            val hasNext = doc.select("a.next, a[rel=next], .nav-links a, .pagination a, .page-numbers a").any {
                val t = it.text().trim()
                t.equals("Next", true) || t.startsWith("Next") || t == (page + 1).toString() ||
                    it.absUrl("href").contains("/page/${page + 1}/")
            }
            return MangasPage(found.values.toList(), hasNext && found.isNotEmpty())
        }

        fun parseDetails(doc: Document, manga: SManga): SManga {
            val title = doc.selectFirst("h1.entry-title, h1.post-title, article h1, h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            title?.takeIf { it.isNotBlank() }?.let { manga.title = it.removeSuffix(" - SFM Compile").trim() }
            manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: posterOf(doc.selectFirst("article") ?: doc.body())
                ?: manga.thumbnailUrl
            manga.description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
                ?: manga.description
            val tags = doc.select("a[rel~=category], a[rel~=tag], a[href*=/category/], a[href*=/tag/]")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            if (tags.isNotEmpty()) manga.genre = tags.joinToString(", ")
            manga.author = doc.selectFirst("a[rel=author], .author a, .byline a")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: manga.author
            manga.status = 2 // a single video never "updates"
            return manga
        }

        fun publishedMillis(doc: Document): Long {
            val raw = doc.selectFirst("meta[property=article:published_time]")?.attr("content")
                ?: doc.selectFirst("time[datetime]")?.attr("datetime")
                ?: return 0L
            return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { ZonedDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
                ?: 0L
        }

        /**
         * MP4 URLs of a post, best sources first: <video>/<source>, og:video, direct links, then any .mp4 in the HTML.
         * When the post has files from the site's own uploads folder, third-party (ad/embed) files are dropped.
         */
        fun parseVideoUrls(doc: Document, baseUrl: String): List<String> {
            val found = LinkedHashSet<String>()
            fun add(raw: String?) {
                var u = raw?.trim().orEmpty()
                if (u.isEmpty()) return
                u = u.replace("\\/", "/").replace("&amp;", "&")
                val abs = when {
                    u.startsWith("http://") || u.startsWith("https://") -> u
                    u.startsWith("//") -> "https:$u"
                    else -> runCatching { URI(doc.baseUri().ifBlank { baseUrl }).resolve(u).toString() }.getOrDefault(u)
                }
                if (abs.substringBefore('?').substringBefore('#').endsWith(".mp4", ignoreCase = true)) {
                    found += encodeSpaces(abs.replace("http://", "https://"))
                }
            }
            doc.select("video source[src], video[src], source[type=video/mp4][src]").forEach { add(it.attr("src")) }
            doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream]")
                .forEach { add(it.attr("content")) }
            doc.select("a[href]").forEach { add(it.attr("href")) }
            Regex("""https?:(?:\\?/){2}[^"'\s<>\\]+(?:\\?/[^"'\s<>\\]+)*\.mp4""", RegexOption.IGNORE_CASE)
                .findAll(doc.html()).forEach { add(it.value) }
            val own = found.filter { hostOf(it).equals(hostOf(baseUrl), ignoreCase = true) }
            return (if (own.isNotEmpty()) own else found.toList()).distinct()
        }
    }
}
