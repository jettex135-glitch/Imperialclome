package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

class PhiliaScansSource : HttpSource() {
    override val id = "wp_philiascans_com"
    override val name = "PhiliaScans"
    override val lang = "en"
    override val baseUrl = "https://philiascans.org"

    private val wordpress = WordPressSource(name, baseUrl, lang, idOverride = id)
    private val tokenMutex = Mutex()
    @Volatile private var cachedReaderToken: String? = null
    @Volatile private var readerTokenExpiresAt = 0L
    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "Accept" to "application/json",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$baseUrl/",
            "Sec-Fetch-Mode" to "cors",
            "X-Requested-With" to "XMLHttpRequest"
        )

    override fun imageHeaders(): Map<String, String> = wordpress.imageHeaders()

    override suspend fun getPopular(page: Int): MangasPage =
        mangaList(page, orderBy = "views")

    override suspend fun getLatest(page: Int): MangasPage = mangaList(page)

    override suspend fun search(query: String, page: Int): MangasPage =
        mangaList(page, query = query)

    override suspend fun getMangaDetails(manga: SManga) = wordpress.getMangaDetails(manga)

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val mangaSlug = runCatching { URI(manga.url).path.trimEnd('/').substringAfterLast('/') }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("PhiliaScans returned an invalid manga address")
        val json = JSONObject(getString("$baseUrl/api/manga/${enc(mangaSlug)}/chapters"))
        val items = json.optJSONArray("items")
            ?: throw IOException("PhiliaScans returned no chapter list")
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.toChapter(mangaSlug)
        }.also {
            if (it.isEmpty()) throw IOException("PhiliaScans returned no chapters for this manga")
        }
    }

    private suspend fun mangaList(page: Int, query: String = "", orderBy: String? = null): MangasPage {
        val safePage = page.coerceAtLeast(1)
        val url = buildString {
            append("$baseUrl/api/manga?page=$safePage&perPage=20")
            if (query.isNotBlank()) append("&q=${enc(query)}")
            if (orderBy != null) append("&orderby=${enc(orderBy)}&order=desc")
        }
        val response = JSONObject(getString(url, apiHeaders))
        val items = response.optJSONArray("items")
            ?: throw IOException("PhiliaScans returned no manga list")
        val mangas = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.toManga()
        }
        val currentPage = response.optInt("page", safePage)
        val totalPages = response.optInt("totalPages", currentPage)
        return MangasPage(mangas, currentPage < totalPages)
    }

    private fun JSONObject.toManga(): SManga? {
        val slug = optString("slug").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val cover = optString("coverImageUrl").takeIf { it.isNotBlank() && it != "null" }
            ?.let(::absoluteUrl)
        return SManga(url = "$baseUrl/series/$slug", title = title, thumbnailUrl = cover)
    }

    private fun JSONObject.toChapter(mangaSlug: String): SChapter? {
        val slug = optString("slug").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val number = optString("number").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() && it != "null" && it != number }
        val locked = optInt("coinPrice") != 0 && !optBoolean("purchased")
        val name = buildString {
            if (locked) append("Locked - ")
            append("Chapter ").append(number)
            if (title != null) append(" - ").append(title)
        }
        val date = optString("publishedAt").takeIf { it.isNotBlank() && it != "null" }
        val dateUpload = try {
            date?.let { Instant.parse(it).toEpochMilli() } ?: 0L
        } catch (_: DateTimeParseException) {
            0L
        }
        return SChapter(
            url = "$baseUrl/series/$mangaSlug/$slug",
            name = name,
            dateUpload = dateUpload,
            chapterNumber = number.toFloatOrNull() ?: parseChapterNumber(number)
        )
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val (mangaSlug, chapterSlug) = chapterSlugs(chapter.url)
        return loadChapterPages(mangaSlug, chapterSlug).urls
    }

    override suspend fun loadPages(manga: SManga, chapter: SChapter): ChapterPages {
        val mangaSlug = slugFromMangaUrl(manga.url)
        val (chapterMangaSlug, chapterSlug) = chapterSlugs(chapter.url)
        if (mangaSlug != chapterMangaSlug) {
            throw IOException("PhiliaScans chapter does not belong to the selected manga")
        }
        return loadChapterPages(mangaSlug, chapterSlug)
    }

    private suspend fun loadChapterPages(mangaSlug: String, chapterSlug: String): ChapterPages {
        val api = "$baseUrl/api"
        val viewer = JSONObject(
            getString("$api/manga/${enc(mangaSlug)}/chapters/${enc(chapterSlug)}", apiHeaders)
        )
        if (!viewer.optBoolean("hasAccess")) {
            throw IOException("PhiliaScans requires chapter access. Sign in or unlock the chapter on the site, then retry.")
        }
        val chapter = viewer.optJSONObject("chapter")
            ?: throw IOException("PhiliaScans returned no chapter data")
        val chapterId = chapter.optInt("id").takeIf { it > 0 }
            ?: throw IOException("PhiliaScans returned an invalid chapter ID")
        val (token, keys) = getPageKeys(api, chapterId, readerAccessToken(api))
        val pageList = chapter.optJSONArray("pages")
            ?: throw IOException("PhiliaScans returned no chapter pages")
        if (pageList.length() == 0) throw IOException("PhiliaScans returned no pages for this chapter")

        val pageKey = keys.optString("chapterKeyB64")
        if (pageKey.isBlank() || pageKey == "null") {
            throw IOException("PhiliaScans returned no chapter image key")
        }
        val useSessionKey = keys.optBoolean("sessionDefault")
        val sessionKeys = if (useSessionKey) {
            val headers = readerHeaders(token)
            val opened = JSONObject(postString("$api/chapters/$chapterId/open", headers = headers))
            val sessionId = opened.optString("sessionId")
            if (sessionId.isBlank() || sessionId == "null") {
                throw IOException("PhiliaScans did not open the reader session")
            }
            val drm = try {
                JSONObject(getString("$api/chapters/$chapterId/get-drm?session=${enc(sessionId)}", headers))
                    .optString("payloadB").takeIf { it.isNotBlank() && it != "null" }
            } catch (e: HttpStatusException) {
                if (e.code == 401 || e.code == 403) throw e
                null
            }
            opened.optString("payloadA").takeIf { it.isNotBlank() && it != "null" } to drm
        } else {
            null to null
        }
        val imageHeaders = readerHeaders(token)
        val orderedPages = (0 until pageList.length()).mapNotNull { index ->
            val page = pageList.optJSONObject(index) ?: return@mapNotNull null
            val rawUrl = page.optString("url").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val mime = page.optString("mime").takeIf { it.isNotBlank() && it != "null" } ?: "image/jpeg"
            val imageUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                rawUrl
            } else {
                "$baseUrl/${rawUrl.trimStart('/')}"
            }
            Triple(page.optInt("position", index), imageUrl, mime)
        }.sortedBy { it.first }
        val urls = orderedPages.mapIndexed { index, (_, imageUrl, mime) ->
            val payloadA = sessionKeys.first ?: "null"
            val payloadB = sessionKeys.second ?: "null"
            "$imageUrl#philia;${if (chapter.optBoolean("scrambled")) 1 else 0};$mime;$pageKey;" +
                "${keys.optInt("gridSize")};$payloadA;$payloadB;$index"
        }
        if (urls.isEmpty()) throw IOException("PhiliaScans returned no usable page addresses")
        return ChapterPages(urls, imageHeaders)
    }

    private suspend fun getPageKeys(api: String, chapterId: Int, token: String): Pair<String, JSONObject> {
        val url = "$api/chapters/$chapterId/page-keys"
        return try {
            token to JSONObject(getString(url, readerHeaders(token)))
        } catch (e: HttpStatusException) {
            if (e.code == 401) {
                throw IOException("PhiliaScans reader access requires a valid site login. Sign in on the site, then retry.", e)
            }
            if (e.code != 404) throw e
            val refreshed = readerAccessToken(api, forceRefresh = true)
            refreshed to JSONObject(getString(url, readerHeaders(refreshed)))
        }
    }

    private suspend fun readerAccessToken(api: String, forceRefresh: Boolean = false): String =
        tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                cachedReaderToken?.takeIf { readerTokenExpiresAt - now > TOKEN_EXPIRY_SKEW_MS }
                    ?.let { return it }
            }
            val response = try {
                JSONObject(postString("$api/reader/access-token", headers = apiHeaders))
            } catch (e: HttpStatusException) {
                if (e.code == 401) {
                    throw IOException("PhiliaScans reader access requires a valid site login. Sign in on the site, then retry.", e)
                }
                throw e
            }
            val token = response.optString("token").takeIf { it.isNotBlank() && it != "null" }
                ?: throw IOException("PhiliaScans returned no reader access token")
            val expiresAt = response.optLong("expiresAt").takeIf { it > 0L }
                ?: throw IOException("PhiliaScans returned an invalid reader token expiry")
            cachedReaderToken = token
            readerTokenExpiresAt = expiresAt * 1000L
            token
        }

    private fun readerHeaders(token: String): Map<String, String> =
        apiHeaders + ("X-Reader-Access-Token" to token)

    private fun slugFromMangaUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val segments = uri?.path?.split('/')?.filter { it.isNotBlank() }
        if (!uri.isPhiliaScansAddress() || segments?.size != 2 || segments[0] != "series") {
            throw IOException("PhiliaScans returned an invalid manga address")
        }
        return segments[1]
    }

    private fun chapterSlugs(url: String): Pair<String, String> {
        val uri = runCatching { URI(url) }.getOrNull()
        val segments = uri?.path?.split('/')?.filter { it.isNotBlank() }
        if (!uri.isPhiliaScansAddress() || segments?.size != 3 || segments[0] != "series") {
            throw IOException("PhiliaScans returned an invalid chapter address")
        }
        return segments[1] to segments[2]
    }

    private fun URI?.isPhiliaScansAddress(): Boolean =
        this?.scheme == "https" && this.host?.equals("philiascans.org", ignoreCase = true) == true

    private companion object {
        const val TOKEN_EXPIRY_SKEW_MS = 60_000L
    }
}
