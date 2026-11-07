package com.imperialreader.data.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** Comick catalog and reader backed by its public API and embedded page data. */
class ComickSource : HttpSource() {
    override val id = "comick"
    override val name = "Comick"
    override val lang = "en"
    override val baseUrl = "https://comick.live"

    private val requestLimiter = Semaphore(2)
    private val latestCursors = ConcurrentHashMap<Int, String>()
    private val searchCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

    override suspend fun getString(url: String, headers: Map<String, String>): String =
        requestLimiter.withPermit { super.getString(url, headers) }

    override suspend fun getPopular(page: Int): MangasPage {
        val (days, type) = when (page) {
            1 -> 7 to "follow"
            2 -> 30 to "follow"
            3 -> 90 to "follow"
            4 -> 7 to "most_follow_new"
            5 -> 30 to "most_follow_new"
            6 -> 90 to "most_follow_new"
            else -> return MangasPage(emptyList(), false)
        }
        val data = JSONObject(getString("$baseUrl/api/comics/top?day=$days&type=$type", API_HEADERS))
            .requiredArray("data")
        return MangasPage(data.toMangas(), page < 6)
    }

    override suspend fun getLatest(page: Int): MangasPage {
        val url = buildString {
            append("$baseUrl/api/chapters/latest?order=new&page=$page")
            if (page > 1) {
                val cursor = latestCursors[page - 1]
                    ?: throw IllegalStateException("Load the previous Comick page before requesting this page.")
                append("&cursor=").append(enc(cursor))
            }
        }
        val response = JSONObject(getString(url, API_HEADERS))
        val results = response.requiredArray("data")
        val cursor = response.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
        cursor?.let { latestCursors[page] = it }
        return MangasPage(results.toMangas(), cursor != null)
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isNotEmpty() && normalizedQuery.length < 3) {
            throw IllegalArgumentException("Comick search requires at least 3 characters.")
        }
        val cursors = searchCursors.getOrPut(normalizedQuery) { ConcurrentHashMap() }
        val url = buildString {
            append("$baseUrl/api/search?order_by=created_at&order_direction=desc")
            append("&showAll=false&exclude_mylist=false&type=comic")
            if (normalizedQuery.isNotEmpty()) append("&q=").append(enc(normalizedQuery))
            append("&page=$page")
            if (page > 1) {
                val cursor = cursors[page - 1]
                    ?: throw IllegalStateException("Load the previous Comick page before requesting this page.")
                append("&cursor=").append(enc(cursor))
            }
        }
        val response = JSONObject(getString(url, API_HEADERS))
        val results = response.requiredArray("data")
        val cursor = response.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
        cursor?.let { cursors[page] = it }
        return MangasPage(results.toMangas(), cursor != null)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val document = Jsoup.parse(getString("$baseUrl/comic/${manga.url}"))
        val raw = document.selectFirst("script#comic-data")?.data()
            ?: throw IllegalStateException("Comick comic data was not present in the details page.")
        val data = JSONObject(raw)
        manga.title = data.optString("title").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Comick returned a comic without a title.")
        manga.url = data.optString("slug").takeIf { it.isNotBlank() } ?: manga.url
        manga.thumbnailUrl = data.optString("default_thumbnail").takeIf { it.isNotBlank() }
        manga.author = data.names("authors").ifBlank { null }
        manga.artist = data.names("artists").ifBlank { null }
        val description = Jsoup.parseBodyFragment(data.optString("desc")).text()
            .replace(Regex("\\s+"), " ")
            .trim()
        val alternativeTitles = data.optJSONArray("md_titles")
        manga.description = buildString {
            append(description)
            if (alternativeTitles != null && alternativeTitles.length() > 0) {
                if (isNotBlank()) append("\n\n")
                append("Alternative Titles:\n")
                for (index in 0 until alternativeTitles.length()) {
                    alternativeTitles.optJSONObject(index)?.optString("title")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append("- ").append(it.trim()).append('\n') }
                }
            }
        }.trim().ifBlank { null }
        manga.status = when (data.optInt("status")) {
            1 -> 1
            2 -> if (data.optBoolean("translation_completed")) 2 else 1
            3 -> 3
            4 -> 4
            else -> 0
        }

        val genres = mutableListOf<String>()
        when (data.optString("country").lowercase(Locale.ROOT)) {
            "jp" -> genres += "Manga"
            "cn" -> genres += "Manhua"
            "kr", "ko" -> genres += "Manhwa"
        }
        val genreData = data.optJSONArray("md_comic_md_genres") ?: JSONArray()
        for (index in 0 until genreData.length()) {
            val genre = genreData.optJSONObject(index)
                ?.optJSONObject("md_genres")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
            if (genre != null) genres += genre
        }
        manga.genre = genres.distinct().joinToString(", ").ifBlank { null }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val first = chapterList(manga.url, 1)
        val lastPage = first.optJSONObject("pagination")?.optInt("last_page")
            ?: throw IllegalStateException("Comick chapter list did not include pagination.")
        if (lastPage < 1) throw IllegalStateException("Comick returned an invalid chapter page count.")
        val pages = coroutineScope {
            (2..lastPage).map { page ->
                async {
                    chapterList(manga.url, page).requiredArray("data")
                }
            }.awaitAll()
        }
        val allChapters = mutableListOf<JSONObject>()
        first.requiredArray("data").forEachObject { allChapters.add(it) }
        pages.forEach { page -> page.forEachObject(allChapters::add) }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return allChapters.asSequence()
            .filter { it.optString("lang") == "en" && it.optString("hid").isNotBlank() }
            .map { chapter ->
                val number = chapter.optString("chap").takeIf { it.isNotBlank() && it != "null" }
                val title = chapter.optString("title").takeIf { it.isNotBlank() && it != "null" }
                val volume = chapter.optString("vol").takeIf { it.isNotBlank() && it != "null" }
                val name = buildString {
                    if (volume != null) append("Vol. ").append(volume).append(' ')
                    if (number != null) append("Ch. ").append(number)
                    else if (title == null) append("Oneshot")
                    if (title != null) {
                        if (isNotEmpty()) append(": ")
                        append(title)
                    }
                }.trim()
                val createdAt = chapter.optString("created_at")
                val uploaded = if (createdAt.isBlank() || createdAt == "null") {
                    0L
                } else {
                    dateFormat.parse(createdAt.substringBefore('.'))?.time ?: 0L
                }
                val groups = chapter.optJSONArray("group_name")
                    ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }
                    .orEmpty()
                val chapterUrl = "/comic/${manga.url}/${chapter.optString("hid")}-chapter-${number ?: "0"}-en"
                SChapter(
                    url = chapterUrl,
                    name = name,
                    dateUpload = uploaded,
                    chapterNumber = number?.toFloatOrNull() ?: -1f,
                    scanlator = groups.joinToString(", ").ifBlank { null }
                )
            }
            .toList()
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val document = Jsoup.parse(getString(absoluteUrl(chapter.url)))
        val raw = document.selectFirst("script#sv-data")?.data()
            ?: throw IllegalStateException("Comick page data was not present in the chapter.")
        val chapterData = JSONObject(raw).optJSONObject("chapter")
            ?: throw IllegalStateException("Comick chapter page data was invalid.")
        val images = chapterData.optJSONArray("images")
            ?: throw IllegalStateException("Comick chapter did not contain an image list.")
        val urls = (0 until images.length()).map { index ->
            images.optJSONObject(index)?.optString("url")
                ?.takeIf { it.startsWith("https://") }
                ?: throw IllegalStateException("Comick returned an invalid page image at index $index.")
        }
        if (urls.isEmpty()) throw IllegalStateException("Comick chapter did not contain any pages.")
        return urls
    }

    private suspend fun chapterList(slug: String, page: Int): JSONObject =
        JSONObject(
            getString(
                "$baseUrl/api/comics/${enc(slug)}/chapter-list?lang=en&page=$page",
                API_HEADERS
            )
        )
            .also { it.requiredArray("data") }

    private fun JSONObject.requiredArray(key: String): JSONArray =
        optJSONArray(key) ?: throw IllegalStateException("Comick response did not contain a valid $key list.")

    private fun JSONObject.names(field: String): String {
        val values = optJSONArray(field) ?: return ""
        return (0 until values.length()).mapNotNull { index ->
            values.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
        }.distinct().joinToString(", ")
    }

    private fun JSONArray.toMangas(): List<SManga> =
        (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SManga(
                url = slug,
                title = title,
                thumbnailUrl = item.optString("default_thumbnail").takeIf { it.startsWith("https://") }
            )
        }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(action)
        }
    }

    private companion object {
        val API_HEADERS = mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest"
        )
    }
}
