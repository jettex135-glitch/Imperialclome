package com.imperialreader.data.book

import android.net.Uri
import com.imperialreader.App
import com.imperialreader.data.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RoliaScanNovelSource : BookSource {
    override val id = "novel_roliascan_com"
    override val name = "RoliaScans Novels"
    override val webUrl = BASE_URL
    override val description = "Web novel • RoliaScans catalog"
    override val isWebNovel = true

    override suspend fun popular(page: Int): List<BookItem> = loadCatalog(page, "popular_desc")

    override suspend fun search(query: String, page: Int): List<BookItem> =
        loadCatalog(page, "post_desc", query.trim())

    private suspend fun loadCatalog(page: Int, sort: String, query: String = ""): List<BookItem> {
        if (page < 1) return emptyList()
        val requestBody = JSONObject()
            .put("page", page)
            .put("search", query)
            .put("years", "[]")
            .put("genres", "[]")
            .put("types", """["Novel"]""")
            .put("statuses", "[]")
            .put("sort", sort)
            .put("genreMatchMode", "any")
        val response = requestJson(
            Request.Builder()
                .url("$BASE_URL/wp-json/manga/v1/load")
                .header("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
        val items = response as? JSONArray
            ?: throw IOException("Roliascans returned an invalid novel catalog")
        val novels = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val url = checkedNovelUrl(item.optString("url")) ?: return@mapNotNull null
            val title = item.optString("title").trim().ifBlank { return@mapNotNull null }
            if (!item.optString("type").equals("Novel", ignoreCase = true)) return@mapNotNull null
            BookItem(
                sourceId = id,
                sourceName = name,
                id = url,
                title = title,
                author = null,
                coverUrl = item.optString("cover").takeIf(String::isNotBlank),
                epubUrl = null,
                description = item.optString("description").takeIf(String::isNotBlank),
                genre = null
            )
        }.distinctBy { it.id }
        return novels
    }

    override suspend fun details(item: BookItem): BookItem {
        val url = checkedNovelUrl(item.id) ?: throw IOException("Invalid Roliascans novel address")
        val document = fetchDocument(url)
        val title = document.select("h1").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            ?: item.title
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)?.let(::absoluteUrl) ?: item.coverUrl
        val description = document.selectFirst("#description-content-tab")?.text()?.trim()
            ?.takeIf(String::isNotBlank) ?: item.description
        val metadata = document.select("script[type=application/ld+json]").mapNotNull {
            runCatching { JSONObject(it.data().ifBlank { it.html() }) }.getOrNull()
        }.firstOrNull { it.optString("@type").equals("Book", ignoreCase = true) }
        val author = metadata?.optJSONObject("author")?.optString("name")
            ?.takeIf(String::isNotBlank) ?: item.author
        return item.copy(
            title = title,
            author = author,
            coverUrl = cover,
            description = description,
            genre = metadata?.optString("genre")?.takeIf(String::isNotBlank) ?: item.genre
        )
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val url = checkedNovelUrl(item.id) ?: throw IOException("Invalid Roliascans novel address")
        val document = fetchDocument(url)
        val mangaId = document.body().attr("data-manga-id").toLongOrNull()
            ?: throw IOException("Roliascans did not provide a valid novel ID")
        val chapters = mutableListOf<NovelChapter>()
        var offset = 0
        var hasMore = true
        while (hasMore && chapters.size < MAX_CHAPTERS) {
            val timestamp = System.currentTimeMillis() / 1000L
            val token = chapterToken(timestamp)
            val chapterEndpoint = "$BASE_URL/auth/manga-chapters?manga_id=$mangaId" +
                "&offset=$offset&limit=$CHAPTER_PAGE_SIZE&order=ASC&_t=$token&_ts=$timestamp"
            val response = requestJson(Request.Builder().url(chapterEndpoint).build()) as? JSONObject
                ?: throw IOException("Roliascans returned an invalid novel chapter list")
            if (!response.optBoolean("success")) {
                throw IOException("Roliascans could not load chapters for ${item.title}")
            }
            val rows = response.optJSONArray("chapters")
                ?: throw IOException("Roliascans returned an invalid novel chapter list")
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                if (!row.optString("chapter_type").equals("text", ignoreCase = true)) continue
                val chapterUrl = checkedChapterUrl(row.optString("url")) ?: continue
                val chapterNumber = row.optString("chapter").trim()
                val chapterTitle = row.optString("title").trim()
                val title = buildString {
                    append("Chapter ").append(chapterNumber.ifBlank { row.optString("id") })
                    if (chapterTitle.isNotBlank() && !chapterTitle.equals("N/A", true)) {
                        append(": ").append(chapterTitle)
                    }
                }
                chapters += NovelChapter(title, chapterUrl)
            }
            hasMore = response.optBoolean("has_more")
            val received = rows.length()
            if (received == 0) break
            offset += received
        }
        if (chapters.isEmpty()) throw IOException("Roliascans returned no readable novel chapters")
        return chapters.distinctBy { it.url }
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val url = checkedChapterUrl(chapter.url) ?: throw IOException("Invalid Roliascans chapter address")
        val chapterId = Uri.parse(url).lastPathSegment.orEmpty()
            .substringBefore('?').substringAfterLast('-').toLongOrNull()
            ?: throw IOException("Invalid Roliascans chapter address")
        val endpoint = "$BASE_URL/auth/chapter-content?chapter_id=$chapterId"
        val response = requestJson(Request.Builder().url(endpoint).build()) as? JSONObject
            ?: throw IOException("Roliascans returned an invalid chapter response")
        if (!response.optBoolean("success") ||
            !response.optString("chapter_type").equals("text", ignoreCase = true)
        ) {
            throw IOException("Roliascans did not return text for ${chapter.title}")
        }
        val content = Jsoup.parseBodyFragment(response.optString("content"), url).body()
        content.select("script, style, noscript, iframe, nav, header, footer, aside, form").remove()
        if (content.select("p").none { it.text().isNotBlank() }) {
            throw IOException("Roliascans returned an empty novel chapter")
        }
        return content.html()
    }

    private suspend fun fetchDocument(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Referer", "$BASE_URL/").build()
        App.instance.network.client.await(request).use { response ->
            val html = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Roliascans returned HTTP ${response.code}")
            if (html.isBlank()) throw IOException("Roliascans returned an empty response")
            Jsoup.parse(html, url)
        }
    }

    private suspend fun requestJson(request: Request): Any =
        withContext(Dispatchers.IO) {
            App.instance.network.client.await(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("Roliascans returned HTTP ${response.code}")
                try {
                    if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
                } catch (_: JSONException) {
                    throw IOException("Roliascans returned an invalid response")
                }
            }
        }

    private fun checkedNovelUrl(raw: String): String? =
        checkedSiteUrl(raw)?.takeIf {
            runCatching { URI(it).path }.getOrNull()?.startsWith("/manga/") == true
        }

    private fun checkedChapterUrl(raw: String): String? =
        checkedSiteUrl(raw)?.takeIf {
            runCatching { URI(it).path }.getOrNull()?.startsWith("/read/") == true
        }

    private fun checkedSiteUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        return raw.takeIf {
            uri.scheme.equals("https", true) && uri.host.equals("roliascan.com", true)
        }
    }

    private fun chapterToken(timestamp: Long): String {
        val hour = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(timestamp * 1000L)
        val hash = MessageDigest.getInstance("MD5")
            .digest("${timestamp}mng_ch_$hour".toByteArray())
        return hash.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("https://", true) -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$BASE_URL$raw"
        else -> "$BASE_URL/$raw"
    }

    private companion object {
        const val BASE_URL = "https://roliascan.com"
        const val CHAPTER_PAGE_SIZE = 500
        const val MAX_CHAPTERS = 5000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
