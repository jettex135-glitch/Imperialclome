package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** QiManga catalog and reader using the public API consumed by Mihon's QiScans extension. */
class QiMangaSource : HttpSource() {
    override val id = "qimanga_com"
    override val name = "QiManga"
    override val lang = "en"
    override val baseUrl = "https://qimanga.com"

    private val apiUrl = "https://api.qimanga.com/api/v1"

    override suspend fun getPopular(page: Int): MangasPage =
        fetchSeries("$apiUrl/series?page=${page.coerceAtLeast(1)}&perPage=20&sort=popular", page)

    override suspend fun getLatest(page: Int): MangasPage =
        fetchSeries("$apiUrl/series?page=${page.coerceAtLeast(1)}&perPage=20&sort=latest", page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val normalized = query.trim()
        if (normalized.isBlank()) return getLatest(page)
        val url = "$apiUrl/series/search?page=${page.coerceAtLeast(1)}&perPage=20&q=${enc(normalized)}"
        return fetchSeries(url, page)
    }

    private suspend fun fetchSeries(url: String, page: Int): MangasPage =
        parseSeriesPage(JSONObject(getString(url, API_HEADERS)), page)

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val slug = seriesSlug(manga.url)
        val response = JSONObject(getString("$apiUrl/series/${enc(slug)}", API_HEADERS))
        val details = response.optJSONObject("data") ?: response
        val parsed = parseSeries(details)
        manga.url = parsed.url
        manga.title = parsed.title
        manga.thumbnailUrl = parsed.thumbnailUrl ?: manga.thumbnailUrl
        manga.author = parsed.author
        manga.artist = parsed.artist
        manga.description = parsed.description
        manga.genre = parsed.genre
        manga.status = parsed.status
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val slug = seriesSlug(manga.url)
        val firstUrl = "$apiUrl/series/${enc(slug)}/chapters?page=1&perPage=$CHAPTERS_PER_PAGE&sort=desc"
        val first = JSONObject(getString(firstUrl, API_HEADERS))
        val totalPages = first.optInt("totalPages", 1)
        if (totalPages !in 1..MAX_CHAPTER_PAGES) {
            throw IOException("QiManga returned an invalid chapter page count")
        }

        val chapters = mutableListOf<SChapter>()
        chapters += parseChapters(
            first.optJSONArray("data") ?: throw IOException("QiManga returned no chapter data"),
            slug
        )
        for (page in 2..totalPages) {
            val url = "$apiUrl/series/${enc(slug)}/chapters?page=$page&perPage=$CHAPTERS_PER_PAGE&sort=desc"
            val response = JSONObject(getString(url, API_HEADERS))
            chapters += parseChapters(
                response.optJSONArray("data") ?: throw IOException("QiManga returned no chapter data"),
                slug
            )
        }
        if (chapters.isEmpty()) throw IOException("QiManga returned no readable chapters")
        return chapters.distinctBy { it.url }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val path = runCatching { URI(chapter.url).path.orEmpty() }
            .getOrElse { chapter.url.substringBefore('?').substringBefore('#') }
            .trimStart('/')
            .removePrefix("api/v1/")
        if (!path.startsWith("series/") || "/chapters/" !in path) {
            throw IOException("QiManga returned an invalid chapter address")
        }
        val response = JSONObject(getString("$apiUrl/$path", API_HEADERS))
        if (response.optBoolean("requiresPurchase")) {
            throw IOException("This QiManga chapter requires purchase")
        }
        return parsePageList(response)
    }

    override fun imageHeaders(): Map<String, String> = mapOf("Referer" to "$baseUrl/")

    private fun seriesSlug(value: String): String {
        val path = runCatching { URI(value).path }.getOrNull().orEmpty().trimEnd('/')
        val slug = if (path.isBlank()) value.trim('/') else path.substringAfterLast('/')
        if (slug.isBlank() || slug == "series") throw IOException("QiManga returned an invalid series address")
        return slug
    }

    internal fun parseSeriesPage(response: JSONObject, requestedPage: Int): MangasPage {
        val data = response.optJSONArray("data") ?: throw IOException("QiManga returned no series data")
        val mangas = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val parsed = parseSeriesOrNull(item) ?: continue
                add(parsed)
            }
        }.distinctBy { it.url }
        val current = response.optInt("current", requestedPage)
        val total = response.optInt("totalPages", current)
        return MangasPage(mangas, mangas.isNotEmpty() && current < total)
    }

    internal fun parseSeries(json: JSONObject): SManga =
        parseSeriesOrNull(json) ?: throw IOException("QiManga returned a series without a title or address")

    internal fun parseChapters(data: JSONArray, seriesSlug: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        appendChapters(data, seriesSlug, chapters)
        return chapters
    }

    internal fun parsePageList(response: JSONObject): List<String> {
        if (response.optBoolean("requiresPurchase")) {
            throw IOException("This QiManga chapter requires purchase")
        }
        val images = response.optJSONArray("images")
            ?: throw IOException("QiManga returned no chapter images")
        val pages = buildList {
            for (index in 0 until images.length()) {
                val image = images.optJSONObject(index) ?: continue
                val url = image.optString("url").trim()
                if (url.isNotBlank() && url != "null") add(absoluteUrl(url))
            }
        }.distinct()
        if (pages.isEmpty()) throw IOException("QiManga returned no readable pages")
        return pages
    }

    private fun appendChapters(data: JSONArray, seriesSlug: String, out: MutableList<SChapter>) {
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            if (item.optBoolean("requiresPurchase")) continue
            val slug = item.optString("slug").trim()
            if (slug.isBlank() || slug == "null") continue

            val number = item.optDouble("number", Double.NaN)
                .takeIf { it.isFinite() }
                ?.toFloat()
            val title = item.optString("title").trim().takeUnless { it.isBlank() || it == "null" }
            val name = when {
                number == null && title == null -> "Chapter"
                number == null -> title!!
                title == null -> "Chapter ${formatNumber(number)}"
                parseChapterNumber(title) == number -> title
                else -> "Chapter ${formatNumber(number)} - $title"
            }
            out += SChapter(
                url = "$baseUrl/series/${enc(seriesSlug)}/chapters/${enc(slug)}",
                name = name,
                dateUpload = parseDate(item.optString("createdAt")),
                chapterNumber = number ?: -1f
            )
        }
    }

    private fun parseSeriesOrNull(json: JSONObject): SManga? {
        val slug = json.optString("slug").trim().takeUnless { it.isBlank() || it == "null" } ?: return null
        val title = json.optString("title").trim().takeUnless { it.isBlank() || it == "null" } ?: return null
        val cover = json.optString("cover").trim().takeUnless { it.isBlank() || it == "null" }
        val genres = json.optJSONArray("genres")?.let(::parseGenres).orEmpty()
        val description = json.optString("description")
            .trim()
            .takeUnless { it.isBlank() || it == "null" }
        return SManga(
            url = "$baseUrl/series/${enc(slug)}",
            title = title,
            thumbnailUrl = cover?.let(::absoluteUrl),
            author = json.optString("author").trim().takeUnless { it.isBlank() || it == "null" },
            artist = json.optString("artist").trim().takeUnless { it.isBlank() || it == "null" },
            description = description,
            genre = genres.ifBlank { null },
            status = when (json.optString("status").uppercase()) {
                "ONGOING", "MASS_RELEASED" -> 1
                "COMPLETED" -> 2
                "DROPPED", "CANCELLED" -> 3
                else -> 0
            }
        )
    }

    private fun parseGenres(genres: JSONArray): String =
        (0 until genres.length()).mapNotNull { index ->
            genres.optJSONObject(index)?.optString("name")
                ?.trim()
                ?.takeUnless { it.isBlank() || it == "null" }
        }.distinct().joinToString(", ")

    private fun parseDate(value: String): Long = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    private fun formatNumber(number: Float): String =
        if (number % 1f == 0f) number.toInt().toString() else number.toString()

    private companion object {
        const val CHAPTERS_PER_PAGE = 100
        const val MAX_CHAPTER_PAGES = 100
        val API_HEADERS = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "https://qimanga.com/"
        )
    }
}
