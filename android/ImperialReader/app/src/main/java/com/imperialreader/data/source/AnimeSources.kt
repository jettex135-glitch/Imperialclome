package com.imperialreader.data.source

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Anime sources (Aniyomi-style mapping onto the existing manga contract):
 *
 *   Aniyomi AnimeHttpSource            ->  this app
 *   ---------------------------------      ---------------------------------------------
 *   SAnime                             ->  SManga      (a "title" = one anime)
 *   SEpisode                           ->  SChapter    (a "chapter" = one episode)
 *   popularAnimeRequest/Parse          ->  getPopular()
 *   latestUpdatesRequest/Parse         ->  getLatest()
 *   searchAnimeRequest/Parse           ->  search()
 *   animeDetailsParse                  ->  getMangaDetails()
 *   episodeListParse                   ->  getChapters()   (newest first, like every other source here)
 *   videoListParse (+ Video.headers)   ->  loadEpisode()   (one playable URL + its headers, played by the reader's video page)
 *
 * The reader already plays a single MP4 "page"; PageAdapter was extended to also accept .m3u8 (HLS).
 * Nothing else in the reader/database/library code is aware of anime: it is just a source with isAnime = true.
 */

/** Dean Edwards p.a.c.k.e.r unpacker: many embed players hide their stream URL inside one of these. */
internal object PackedJs {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val packed = Regex("""\}\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""")
    private val token = Regex("""\b\w+\b""")

    private fun decode(value: String, radix: Int): Int? {
        var n = 0
        for (ch in value) {
            val digit = ALPHABET.indexOf(ch)
            if (digit < 0 || digit >= radix) return null
            n = n * radix + digit
        }
        return n
    }

    fun unpackAll(js: String): List<String> = packed.findAll(js).mapNotNull { m ->
        val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val radix = m.groupValues[2].toIntOrNull()?.takeIf { it in 2..62 } ?: return@mapNotNull null
        val words = m.groupValues[4].split("|")
        token.replace(payload) { t ->
            val index = decode(t.value, radix)
            words.getOrNull(index ?: -1)?.takeIf { it.isNotEmpty() } ?: t.value
        }
    }.toList()
}

private val STREAM_REGEX =
    Regex("""https?://[^\s"'<>\\)]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\)]*)?""", RegexOption.IGNORE_CASE)
private val EMBED_REGEX =
    Regex("""https?://[^\s"'<>\\]+/(?:v|e|embed|player)/[A-Za-z0-9_\-]{6,}[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)

internal fun findStreamUrl(text: String): String? {
    val cleaned = text.replace("\\/", "/").replace("&amp;", "&")
    val all = STREAM_REGEX.findAll(cleaned).map { it.value }.toList()
    return all.firstOrNull { it.contains(".m3u8", ignoreCase = true) } ?: all.firstOrNull()
}

private fun formatNumber(n: Float): String = if (n % 1f == 0f) n.toInt().toString() else n.toString()

abstract class AnimeHttpSource : HttpSource() {
    override val lang: String get() = "en"
    override val isAnime: Boolean get() = true

    /** Implemented per site: return the playable URL of one episode plus the headers the player must send. */
    protected abstract suspend fun loadEpisode(episode: SChapter): ChapterPages

    override suspend fun getPages(chapter: SChapter): List<String> = loadEpisode(chapter).urls

    /**
     * SUB / DUB servers listed on the episode page (buttons carrying an embed URL in a data-* attribute).
     * UNVERIFIED against the live markup: when no such buttons are found only the page's default player is offered.
     */
    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> {
        val doc = Jsoup.parse(getString(chapter.url, mapOf("Referer" to "$baseUrl/")), chapter.url)
        val found = parseServers(doc, chapter.url)
        val options = found.map { s -> VideoOption(s.label, s.dub) { resolveEmbed(s.url, chapter.url) } }
        val default = VideoOption("Default", false) { loadEpisode(chapter) }
        return if (options.any { !it.dub }) options else listOf(default) + options
    }

    private class ServerLink(val label: String, val dub: Boolean, val url: String)

    private fun parseServers(doc: Document, pageUrl: String): List<ServerLink> {
        val urlAttrs = listOf("data-embed", "data-link", "data-video", "data-url", "data-src", "data-server")
        val skipTags = setOf("img", "iframe", "script", "source", "video", "link", "meta")
        val out = LinkedHashMap<String, ServerLink>()
        for (el in doc.select(urlAttrs.joinToString(",") { "[$it]" })) {
            if (el.tagName() in skipTags || navigationChrome(el)) continue
            val raw = urlAttrs.map { el.attr(it).trim() }
                .firstOrNull { it.startsWith("http") || it.startsWith("//") || it.startsWith("/") } ?: continue
            val url = runCatching { URI(pageUrl).resolve(raw).toString() }.getOrNull() ?: continue
            if (url in out) continue
            val signals = buildString {
                append(el.text()).append(' ')
                el.attributes().forEach { append(it.value).append(' ') }
                el.parent()?.let { p ->
                    append(p.ownText()).append(' ')
                    p.attributes().forEach { append(it.value).append(' ') }
                    p.previousElementSibling()?.text()?.takeIf { it.length <= 20 }?.let { append(it) }
                }
            }.lowercase()
            val dub = "dub" in signals
            val text = el.text().trim().ifBlank { "Server ${out.size + 1}" }
            out[url] = ServerLink((if (dub) "DUB " else "SUB ") + text, dub, url)
        }
        return out.values.toList()
    }

    override suspend fun loadPages(manga: SManga, chapter: SChapter): ChapterPages = loadEpisode(chapter)

    protected fun originOf(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.authority}" } }.getOrDefault(baseUrl)

    /**
     * Opens an episode/embed page and looks for a direct stream: plain HTML first, then unpacked scripts, then
     * nested iframes / data-* embed links (up to two levels, each sent with the previous page as Referer).
     */
    protected suspend fun resolveEmbed(pageUrl: String, referer: String, depth: Int = 0): ChapterPages {
        val html = getString(pageUrl, mapOf("Referer" to referer))
        val texts = listOf(html) + PackedJs.unpackAll(html)
        for (text in texts) {
            val stream = findStreamUrl(text) ?: continue
            val origin = originOf(pageUrl)
            return ChapterPages(listOf(stream), mapOf("Referer" to "$origin/", "Origin" to origin))
        }
        if (depth < 2) {
            val doc = Jsoup.parse(html, pageUrl)
            val next = LinkedHashSet<String>()
            doc.select("iframe[src], iframe[data-src]").forEach {
                next += it.absUrl("src").ifBlank { it.absUrl("data-src") }
            }
            doc.select("[data-embed], [data-video], [data-url], [data-link]").forEach { el ->
                listOf("data-embed", "data-video", "data-url", "data-link")
                    .map { el.absUrl(it) }.filter { it.startsWith("http") }.forEach { next += it }
            }
            EMBED_REGEX.findAll(html.replace("\\/", "/")).forEach { next += it.value }
            for (candidate in next.filter { it.startsWith("http") && it != pageUrl }) {
                try {
                    return resolveEmbed(candidate, pageUrl, depth + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // try the next embed
                }
            }
        }
        throw IOException(
            "${name}: no playable stream found on ${originOf(pageUrl)}. " +
                "The site may use an encrypted player; check it with \"Open in WebView\"."
        )
    }

    /** Search endpoints differ between clones of the same theme, so try the usual query parameter names. */
    protected suspend fun searchWithFallbacks(
        path: String,
        query: String,
        page: Int,
        parse: (String, Int) -> MangasPage
    ): MangasPage {
        val q = query.trim()
        if (q.isEmpty()) return MangasPage(emptyList(), false)
        var last: MangasPage = MangasPage(emptyList(), false)
        for (key in listOf("keyword", "q", "s")) {
            val html = try {
                getString("$baseUrl$path${if ('?' in path) '&' else '?'}$key=${enc(q)}&page=$page")
            } catch (e: HttpStatusException) {
                if (e.code == 404) continue else throw e
            }
            val result = parse(html, page)
            if (result.mangas.isNotEmpty()) return result
            last = result
        }
        return last
    }

    // ---- shared card helpers
    protected fun cardBox(a: Element): Element =
        a.takeIf { it.selectFirst("img") != null }
            ?: a.parents().firstOrNull { it.selectFirst("img") != null && it.select("a[href]").size <= 4 }
            ?: a

    protected fun cardTitle(a: Element, box: Element): String =
        listOf(
            a.attr("title"),
            box.selectFirst("img[alt]")?.attr("alt"),
            box.selectFirst(".title, .name, .film-name, h2, h3, h4, h5")?.text(),
            a.text()
        ).map { it?.trim().orEmpty().removePrefix("Poster of ").trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

    protected fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select("a[rel=next], a[title=Next]").isNotEmpty() ||
            doc.select("a[href]").any {
                val h = it.attr("href")
                h.endsWith("/page/${page + 1}") || h.endsWith("/page/${page + 1}/") || h.contains("page=${page + 1}")
            }

    protected fun navigationChrome(el: Element): Boolean =
        el.parents().any { it.tagName() in setOf("nav", "header", "footer") || it.hasClass("dropdown-menu") || it.hasClass("navbar") }
}

internal data class AnimePaheEpisodePage(val episodes: List<SChapter>, val lastPage: Int)

internal data class AnimePaheServer(val name: String, val dub: Boolean, val url: String)

internal fun parseAnimePaheEpisodeResponse(body: String, animeId: String, baseUrl: String): AnimePaheEpisodePage {
    val response = JSONObject(body)
    val data = response.optJSONArray("data") ?: JSONArray()
    val episodes = (0 until data.length()).mapNotNull { index ->
        val item = data.optJSONObject(index) ?: return@mapNotNull null
        val session = item.optString("session").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val number = item.optDouble("episode", -1.0).toFloat()
        val title = item.optString("title").takeIf { it.isNotBlank() }
            ?: "Episode ${formatNumber(number)}"
        val uploaded = item.optString("created_at").let { value ->
            runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)?.time }.getOrNull() ?: 0L
        }
        SChapter(
            url = "${baseUrl.trimEnd('/')}/play/$animeId/$session",
            name = title,
            dateUpload = uploaded,
            chapterNumber = number
        )
    }
    return AnimePaheEpisodePage(episodes, response.optInt("last_page", 1).coerceAtLeast(1))
}

internal fun parseAnimePaheServers(body: String, baseUrl: String): List<AnimePaheServer> {
    val response = JSONObject(body)
    if (!response.optString("status").equals("success", ignoreCase = true)) return emptyList()
    val servers = response.optJSONArray("servers") ?: return emptyList()
    return (0 until servers.length()).mapNotNull { index ->
        val item = servers.optJSONObject(index) ?: return@mapNotNull null
        val rawUrl = item.optString("url").trim()
        if (rawUrl.isBlank()) return@mapNotNull null
        val url = runCatching { URI(baseUrl).resolve(rawUrl).toString() }.getOrNull() ?: return@mapNotNull null
        val name = item.optString("name").ifBlank { "Server ${index + 1}" }
        val resolution = item.optString("resolution").takeIf {
            it.isNotBlank() && !it.equals("multi", ignoreCase = true)
        }
        AnimePaheServer(
            name = listOfNotNull(name, resolution).joinToString(" · "),
            dub = name.contains("dub", ignoreCase = true),
            url = url
        )
    }
}

/** AnimePahe episode releases and playback servers use the site's live JSON endpoints. */
class AnimePaheTvSource : AnimeHttpSource() {
    override val id = "animepahetv_to"
    override val name = "AnimePahe"
    override val baseUrl = "https://animepahetv.to"

    override suspend fun getPopular(page: Int): MangasPage = listing("$baseUrl/az-list/?page=$page", page)

    override suspend fun getLatest(page: Int): MangasPage = listing("$baseUrl/latest-updated?page=$page", page)

    override suspend fun search(query: String, page: Int): MangasPage =
        searchWithFallbacks("/search", query, page) { html, p -> parseListing(html, p) }

    private suspend fun listing(url: String, page: Int): MangasPage {
        val html = try {
            getString(url)
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
            throw e
        }
        return parseListing(html, page)
    }

    private fun parseListing(html: String, page: Int): MangasPage {
        val doc = Jsoup.parse(html, baseUrl)
        val found = LinkedHashMap<String, SManga>()
        for (a in doc.select("a[href*=/anime/]")) {
            if (navigationChrome(a)) continue
            val id = ANIME_ID.find(a.absUrl("href"))?.groupValues?.get(1) ?: continue
            val url = "$baseUrl/anime/$id"
            val box = cardBox(a)
            val title = cardTitle(a, box)
            val thumb = lazyImage(box.selectFirst("img"))
            val existing = found[url]
            if (existing == null) {
                found[url] = SManga(url = url, title = title, thumbnailUrl = thumb)
            } else {
                if (existing.title.isBlank() && title.isNotBlank()) existing.title = title
                if (existing.thumbnailUrl == null && thumb != null) existing.thumbnailUrl = thumb
            }
        }
        val items = found.values.filter { it.title.isNotBlank() }
        return MangasPage(items, items.isNotEmpty() && hasNextPage(doc, page))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        doc.selectFirst("h1")?.ownText()?.trim()?.takeIf { it.isNotBlank() }?.let { manga.title = it }
        manga.thumbnailUrl = doc.selectFirst("img[alt^=Poster of]")?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.takeIf { it.isNotBlank() }
            ?: manga.thumbnailUrl
        doc.select("p").filterNot(::navigationChrome).map { it.text().trim() }.maxByOrNull { it.length }
            ?.takeIf { it.length > 60 }?.let { manga.description = it }
        val genres = doc.select("a[href*=/genre/]").filterNot(::navigationChrome).map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        if (genres.isNotEmpty()) manga.genre = genres.joinToString(", ")
        doc.selectFirst("a[href*=/studio/]")?.text()?.trim()?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
            ?.let { manga.author = it }
        val statusText = Regex("""Status:\s*(Finished Airing|Currently Airing|Not yet aired)""", RegexOption.IGNORE_CASE)
            .find(doc.text())?.groupValues?.get(1).orEmpty().lowercase()
        manga.status = when {
            "currently" in statusText -> 1
            "finished" in statusText -> 2
            else -> manga.status
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val animeId = ANIME_ID.find(manga.url)?.groupValues?.get(1) ?: throw IOException("AnimePahe: bad title URL")
        val headers = mapOf("Referer" to manga.url, "Accept" to "application/json")
        val chapters = ArrayList<SChapter>()
        var page = 1
        var lastPage = 1
        do {
            val response = parseAnimePaheEpisodeResponse(
                getString("$baseUrl/viewApi?m=release&id=$animeId&sort=episode_desc&page=$page", headers),
                animeId,
                baseUrl
            )
            if (page == 1 && response.episodes.isEmpty()) {
                throw IOException("AnimePahe returned no episodes for ${manga.title.ifBlank { manga.url }}")
            }
            chapters += response.episodes
            lastPage = response.lastPage
            page++
        } while (page <= lastPage)
        return chapters.distinctBy { it.url }.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> {
        val session = chapter.url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        if (session.isBlank()) throw IOException("AnimePahe: episode URL has no release session")
        val body = getString(
            "$baseUrl/anime/get-servers/$session",
            mapOf("Referer" to chapter.url, "Accept" to "application/json")
        )
        val servers = parseAnimePaheServers(body, baseUrl)
        if (servers.isEmpty()) throw IOException("AnimePahe returned no playable servers for ${chapter.name}")
        return servers.map { server ->
            VideoOption(server.name, server.dub, useWebView = true) {
                ChapterPages(listOf(server.url), mapOf("Referer" to chapter.url))
            }
        }
    }

    override suspend fun loadEpisode(episode: SChapter): ChapterPages {
        val option = getVideoOptions(episode).firstOrNull()
            ?: throw IOException("AnimePahe returned no playable servers for ${episode.name}")
        return option.resolve()
    }

    private companion object {
        val ANIME_ID = Regex("""/anime/([0-9a-fA-F]{32})""")
    }
}
