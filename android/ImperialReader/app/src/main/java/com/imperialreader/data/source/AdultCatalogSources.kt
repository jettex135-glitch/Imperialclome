package com.imperialreader.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.imperialreader.data.network.await
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
import kotlin.math.ceil

/** Doujins.com catalog. Protected or inaccessible pages fail explicitly instead of returning thumbnails. */
class DoujinsSource : HttpSource() {
    override val id = "doujins_com"
    override val name = "Doujins.com"
    override val lang = "en"
    override val baseUrl = "https://doujins.com"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage =
        if (page < 1) MangasPage(emptyList(), false) else listGalleries("$baseUrl/top?page=$page", page)

    override suspend fun getLatest(page: Int): MangasPage = feed(page)

    private suspend fun feed(page: Int): MangasPage {
        if (page < 1) return MangasPage(emptyList(), false)
        val end = System.currentTimeMillis() / DAY_MS * DAY_SECONDS - (page - 1) * DAY_SECONDS
        val start = end - DAY_SECONDS
        val json = JSONObject(getString("$baseUrl/folders?start=$start&end=$end"))
        val folders = sequenceOf(json.optJSONArray("data"), json.optJSONArray("premium"), json.optJSONArray("folders"))
            .filterNotNull()
            .flatMap { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
            .toList()
        val items = folders.mapNotNull(::itemFromFolder).distinctBy { it.url }
        return if (items.isNotEmpty()) {
            MangasPage(items, folders.isNotEmpty())
        } else {
            listGalleries("$baseUrl/top?page=$page", page)
        }
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank() || page < 1) return MangasPage(emptyList(), false)
        val url = "$baseUrl/searches?words=${enc(query)}&page=$page"
        return listGalleries(url, page)
    }

    private suspend fun listGalleries(url: String, page: Int): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val items = doc.select("a.gallery-visited-from-favorites[href], .thumbnail-doujin a[href]")
            .mapNotNull(::itemFromGalleryLink)
            .distinctBy { it.url }
        return MangasPage(items, hasNextPage(doc, page))
    }

    private fun itemFromGalleryLink(link: Element): SManga? {
        val href = link.absUrl("href")
        if (!isGalleryUrl(href)) return null
        val image = link.selectFirst("img")
        val title = sequenceOf(
            link.selectFirst(".title .text")?.text().orEmpty(),
            link.attr("title"),
            link.selectFirst("h2,h3,h4")?.text().orEmpty(),
            image?.attr("alt").orEmpty(),
            link.text()
        ).map { it.trim() }.firstOrNull { it.isNotBlank() && it.length < 200 } ?: return null
        val card = link.parents().firstOrNull { it.hasClass("thumbnail-doujin") }?.parent()
        val cardText = card?.text().orEmpty().ifBlank { link.parent()?.parent()?.text().orEmpty() }
        val artist = Regex("""(?i)artist\s*:\s*(.+?)(?:\s+tags?\s*:|$)""")
            .find(cardText)?.groupValues?.get(1)?.trim()
        return SManga(href, title, imageUrl(image), artist = artist)
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select("a[href]").any { link ->
            val text = link.text().trim()
            val linkedPage = Regex("""[?&]page=(\d+)""")
                .find(link.absUrl("href"))?.groupValues?.get(1)?.toIntOrNull()
            link.attr("rel").split(Regex("""\s+""")).any { it.equals("next", true) } ||
                text.equals("next", true) || text == "»" || (linkedPage != null && linkedPage > page)
        }

    private fun pageCount(doc: Document): Int? {
        val pageCount = Regex("""(?i)\b(\d+)\s+(?:pages?|images?)\b""")
        return sequenceOf(doc.selectFirst(".folder-message")?.text().orEmpty(), doc.text())
            .mapNotNull { pageCount.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull()
    }

    override suspend fun searchByAuthor(query: String, page: Int): MangasPage {
        val results = search(query, page)
        val matches = results.mangas.filter {
            it.artist?.contains(query, ignoreCase = true) == true ||
                it.title.substringBefore(']').contains(query, ignoreCase = true)
        }
        return MangasPage(matches, results.hasNextPage && matches.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        if (!isGalleryUrl(manga.url)) throw IOException("Invalid Doujins.com gallery address")
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.select(".gallery-page .folder-title a").lastOrNull()?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: manga.title
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        val detailText = doc.select("main, #content, .container").text()
        manga.artist = doc.select(".gallery-artist a, .artist a, a[href*=/artist/], a[href*=/artists/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank {
                Regex("""(?i)artist\s*:\s*([^\n|]+)""").find(detailText)?.groupValues?.get(1)?.trim()
            } ?: manga.artist
        manga.genre = doc.select("a[href*=/tag], a[href*=/tags/]")
            .map { it.text().trim() }.filter { it.isNotBlank() && it.length < 60 }
            .distinct().joinToString(", ").ifBlank { null } ?: manga.genre
        doc.selectFirst("img.doujin[data-thumb]")?.let { img ->
            if (manga.thumbnailUrl.isNullOrBlank()) manga.thumbnailUrl = img.attr("data-thumb")
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val images = readerImages(doc)
        val count = pageCount(doc) ?: images.size
        if (count <= 0) throw IOException("Doujins.com did not provide a gallery page count")
        return listOf(SChapter(manga.url, "Gallery ($count pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        if (!isGalleryUrl(chapter.url)) throw IOException("Invalid Doujins.com gallery address")
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val expected = pageCount(doc)
        val readerImages = readerImages(doc).mapNotNull { image ->
            val link = image.attr("data-link")
            if (link == "/subscribe") throw IOException("This Doujins.com gallery requires a subscription")
            sequenceOf(image.attr("data-file"), image.attr("data-src"), image.attr("src"))
                .map(String::trim)
                .firstOrNull { it.isNotBlank() && isFullReaderImage(it) }
        }.distinct()
        if (readerImages.isEmpty()) throw IOException("Doujins.com returned no full-size reader images")
        if (expected != null && readerImages.size < expected) {
            throw IOException("Doujins.com exposed ${readerImages.size} of $expected gallery pages")
        }
        return readerImages
    }

    private fun readerImages(doc: Document): List<Element> =
        doc.select("img.doujin, img[id^=swiper-][data-src], img.swiper-lazy[data-src]")

    private fun itemFromFolder(folder: JSONObject): SManga? {
        val link = folder.optString("link").takeIf { it.startsWith("/") } ?: return null
        val title = folder.optString("name").trim().ifBlank { return null }
        return SManga(
            url = "$baseUrl$link",
            title = title,
            thumbnailUrl = folder.optString("thumbnail").takeIf { it.startsWith("https://") },
            artist = folder.optString("artistList").takeIf { it.isNotBlank() },
            genre = folder.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).mapNotNull { tags.optJSONObject(it)?.optString("tag") }
                    .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { null }
            }
        )
    }

    private fun imageUrl(image: Element?): String? {
        if (image == null) return null
        val raw = sequenceOf(image.attr("data-src"), image.attr("src"), image.attr("data-thumb"), image.attr("srcset"))
            .map { it.substringBefore(',').substringBefore(' ').trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?: return null
        return absoluteUrl(raw).takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun isGalleryUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.host.equals("doujins.com", true) &&
            uri.path.matches(Regex("""/(?:[^/]+/)+[^/]+-\d+/?"""))
    }

    private fun isFullReaderImage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.host.equals("static.doujins.com", true) &&
            uri.path.substringAfterLast('/').startsWith("n-") &&
            uri.path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp")
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
        const val DAY_MS = DAY_SECONDS * 1000
    }
}

/** E-Hentai's gdata API supplies gallery titles, page counts, and complete tag metadata. */
class EHentaiSource : HttpSource() {
    override val id = "e_hentai_org"
    override val name = "E-Hentai"
    override val lang = "en"
    override val baseUrl = "https://e-hentai.org"
    override val nsfw = true

    private val apiUrl = "$baseUrl/api.php"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun getPopular(page: Int): MangasPage =
        list(if (page <= 1) "$baseUrl/popular" else "$baseUrl/popular?page=${page - 1}")

    override suspend fun getLatest(page: Int): MangasPage =
        list(if (page <= 1) "$baseUrl/" else "$baseUrl/?page=${page - 1}")

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        return list("$baseUrl/?f_search=${enc(query)}&page=${(page - 1).coerceAtLeast(0)}")
    }

    override suspend fun searchByAuthor(query: String, page: Int): MangasPage =
        search("artist:\"$query\"", page)

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val items = doc.select("a[href]").asSequence()
            .mapNotNull { link ->
                val address = link.absUrl("href")
                val gallery = galleryAddress(address) ?: return@mapNotNull null
                val img = link.selectFirst("img, [style*=background-image]")
                val title = sequenceOf(link.attr("title"), img?.attr("alt").orEmpty(), link.text())
                    .map { it.trim() }.firstOrNull { it.isNotBlank() && it.length < 300 }
                    ?: return@mapNotNull null
                SManga(
                    url = gallery.url,
                    title = title,
                    thumbnailUrl = img?.let(::imageUrl)
                )
            }.distinctBy { it.url }.toList()
        val current = pageFromUrl(url)
        val hasNext = doc.select("a[href]").any {
            it.text().trim() in setOf(">", "»", "Next") ||
                it.attr("rel").contains("next", ignoreCase = true) ||
                pageFromUrl(it.absUrl("href")) > current
        }
        return MangasPage(items, hasNext)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val address = galleryAddress(manga.url) ?: throw IOException("Invalid E-Hentai gallery address")
        val meta = try {
            galleryMetadata(address)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            val doc = Jsoup.parse(getString(address.url), address.url)
            manga.title = doc.selectFirst("#gn, h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            manga.thumbnailUrl = galleryPageCover(doc) ?: manga.thumbnailUrl
            return manga
        }
        if (meta.optBoolean("expunged")) throw IOException("This E-Hentai gallery has been removed")
        manga.title = meta.optString("title").ifBlank { manga.title }
        manga.thumbnailUrl = coverUrl(meta.optString("thumb"))
            ?: galleryPageCover(address)
            ?: manga.thumbnailUrl
        val tags = meta.optJSONArray("tags") ?: JSONArray()
        val artists = mutableListOf<String>()
        val genres = mutableListOf<String>()
        for (index in 0 until tags.length()) {
            val tagged = tags.optString(index).trim()
            val namespace = tagged.substringBefore(':', "")
            val value = tagged.substringAfter(':', tagged).trim()
            if (value.isBlank()) continue
            when (namespace.lowercase()) {
                "artist" -> artists += value
                "language", "group", "parody", "character", "reclass" -> Unit
                else -> genres += value
            }
        }
        manga.artist = artists.distinct().joinToString(", ").ifBlank { null } ?: manga.artist
        manga.genre = (genres + meta.optString("category").takeIf { it.isNotBlank() })
            .distinct().joinToString(", ").ifBlank { null }
        manga.author = meta.optString("uploader").ifBlank { null } ?: manga.author
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val address = galleryAddress(manga.url) ?: throw IOException("Invalid E-Hentai gallery address")
        val metadata = try {
            galleryMetadata(address)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
        if (metadata?.optBoolean("expunged") == true) {
            throw IOException("This E-Hentai gallery has been removed")
        }
        val count = metadata?.optInt("filecount", 0)?.takeIf { it > 0 }
            ?: galleryPageCount(address)
        if (count <= 0) throw IOException("E-Hentai returned an empty gallery")
        return listOf(SChapter(address.url, "Gallery ($count pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val address = galleryAddress(chapter.url) ?: throw IOException("Invalid E-Hentai gallery address")
        val metadata = try {
            galleryMetadata(address)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
        val count = metadata?.optInt("filecount", 0)?.takeIf { it > 0 }
            ?: galleryPageCount(address)
        if (count <= 0 || count > MAX_GALLERY_PAGES) {
            throw IOException("E-Hentai returned an invalid gallery page count")
        }
        val indexPageCount = ceil(count.toDouble() / GALLERY_INDEX_PAGE_SIZE).toInt()
        val indexLimiter = Semaphore(4)
        val readerPages = coroutineScope {
            (0 until indexPageCount).map { indexPage ->
                async {
                    indexLimiter.withPermit {
                        val url = if (indexPage == 0) address.url else "${address.url}?p=$indexPage"
                        val doc = Jsoup.parse(getString(url), url)
                        doc.select("a[href*=/s/]").mapNotNull { link ->
                            readerAddress(link.absUrl("href"), address.id)
                        }
                    }
                }
            }.awaitAll().flatten().distinctBy { it.page }
        }.sortedBy { it.page }
        if (readerPages.size < count) {
            throw IOException("E-Hentai exposed ${readerPages.size} of $count reader pages")
        }

        val readerLimiter = Semaphore(6)
        val images = coroutineScope {
            readerPages.take(count).map { reader ->
                async {
                    readerLimiter.withPermit {
                        val doc = Jsoup.parse(getString(reader.url), reader.url)
                        val image = doc.selectFirst("#img[src], img#img[src]")?.absUrl("src")
                            ?: throw IOException("E-Hentai did not return page ${reader.page}")
                        image.takeIf(::isReaderImage)
                            ?: throw IOException("E-Hentai returned an invalid image for page ${reader.page}")
                    }
                }
            }.awaitAll()
        }
        if (images.size != count) throw IOException("E-Hentai returned an incomplete gallery")
        return images
    }

    private suspend fun galleryMetadata(address: GalleryAddress): JSONObject {
        val payload = JSONObject()
            .put("method", "gdata")
            .put("gidlist", JSONArray().put(JSONArray().put(address.id).put(address.token)))
            .put("namespace", 1)
        var lastFailure: IOException? = null
        repeat(METADATA_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/json")
                    .header("Referer", "$baseUrl/")
                    .post(payload.toString().toRequestBody(jsonType))
                    .build()
                val metadata = client.await(request).use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code, apiUrl)
                    val galleries = JSONObject(response.body?.string().orEmpty()).optJSONArray("gmetadata")
                        ?: throw IOException("E-Hentai returned no gallery metadata")
                    galleries.optJSONObject(0) ?: throw IOException("E-Hentai returned no gallery metadata")
                }
                val apiError = metadata.optString("error").takeIf { it.isNotBlank() }
                if (apiError != null) throw IOException("E-Hentai API error: $apiError")
                return metadata
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code != 429 && e.code !in 500..599) throw e
                lastFailure = e
            } catch (e: org.json.JSONException) {
                lastFailure = IOException("E-Hentai returned invalid gallery metadata", e)
            } catch (e: IOException) {
                lastFailure = e
            }
            if (attempt < METADATA_ATTEMPTS - 1) delay(METADATA_RETRY_DELAY_MS * (attempt + 1))
        }
        throw lastFailure ?: IOException("E-Hentai returned no gallery metadata")
    }

    private suspend fun galleryPageCount(address: GalleryAddress): Int {
        val doc = Jsoup.parse(getString(address.url), address.url)
        val pageCount = Regex("""(?i)\b([\d,]+)\s+pages?\b""")
        return doc.select("#gdd tr, #gdd .gdt2, #gdd")
            .asSequence()
            .mapNotNull { element ->
                pageCount.find(element.text())?.groupValues?.get(1)
                    ?.replace(",", "")?.toIntOrNull()
            }
            .firstOrNull { it > 0 }
            ?: throw IOException("E-Hentai did not report a gallery page count")
    }

    private suspend fun galleryPageCover(address: GalleryAddress): String? {
        val doc = Jsoup.parse(getString(address.url), address.url)
        return galleryPageCover(doc)
    }

    private fun galleryPageCover(doc: Document): String? {
        val socialCover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let(::coverUrl)
        return socialCover ?: doc.selectFirst("#gd1 img, #gimg, .gdtm img, #gd1 [style*=background-image]")
            ?.let(::imageUrl)
    }

    private fun galleryAddress(url: String): GalleryAddress? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals("e-hentai.org", true)) return null
        val match = Regex("""^/g/(\d+)/([A-Za-z0-9]+)/?""").matchEntire(uri.path.orEmpty()) ?: return null
        return GalleryAddress(url.substringBefore('?').trimEnd('/') + "/", match.groupValues[1].toLongOrNull()
            ?: return null, match.groupValues[2])
    }

    private fun readerAddress(url: String, galleryId: Long): ReaderAddress? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals("e-hentai.org", true)) return null
        val match = Regex("""^/s/([A-Za-z0-9]+)/(\d+)-(\d+)/?$""").matchEntire(uri.path.orEmpty()) ?: return null
        if (match.groupValues[2].toLongOrNull() != galleryId) return null
        val page = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return ReaderAddress(url, page)
    }

    private fun pageFromUrl(url: String): Int =
        runCatching { URI(url).rawQuery.orEmpty().substringAfter("p=", "").substringBefore('&').toInt() }
            .getOrDefault(0)

    private fun imageUrl(image: Element): String? {
        val attributes = listOf(
            "data-src", "data-original", "data-lazy-src", "data-thumb", "data-cfsrc",
            "src", "data-srcset", "srcset"
        )
        val fromAttribute = attributes.asSequence().mapNotNull { attribute ->
            val raw = image.attr(attribute)
                .substringBefore(',')
                .trim()
                .split(Regex("""\s+"""))
                .firstOrNull()
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                ?: return@mapNotNull null
            coverUrl(raw)
        }.firstOrNull()
        if (fromAttribute != null) return fromAttribute
        val background = Regex("""(?i)url\(['"]?([^)'"]+)""")
            .find(image.attr("style"))?.groupValues?.get(1)
        return background?.let(::coverUrl)
    }

    private fun coverUrl(raw: String): String? {
        val value = raw.trim()
            .takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            ?: return null
        val uri = runCatching { URI(baseUrl).resolve(value) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.scheme !in setOf("https", "http")) return null
        return uri.toString().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            .takeUnless { it.contains("placeholder", true) || it.contains("loading", true) }
    }

    private fun isReaderImage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return false
        if (uri.host.equals("ehgt.org", true)) return false
        val path = uri.path.orEmpty()
        return path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif") &&
            !path.contains(Regex("""(?i)(placeholder|loading|thumb|logo|avatar|blank)"""))
    }

    private data class GalleryAddress(val url: String, val id: Long, val token: String)
    private data class ReaderAddress(val url: String, val page: Int)

    private companion object {
        const val GALLERY_INDEX_PAGE_SIZE = 40
        const val MAX_GALLERY_PAGES = 2_000
        const val METADATA_ATTEMPTS = 3
        const val METADATA_RETRY_DELAY_MS = 350L
    }
}
