package com.imperialreader.data.source

import com.imperialreader.data.network.await
import com.imperialreader.util.parseChapterNumber
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RoliaScanSource : HttpSource() {
    override val id = "roliascan_com"
    override val name = "RoliaScans"
    override val lang = "en"
    override val baseUrl = "https://roliascan.com"

    override suspend fun getPopular(page: Int): MangasPage = list(page, "popular_desc")

    override suspend fun getLatest(page: Int): MangasPage = list(page, "post_desc")

    override suspend fun search(query: String, page: Int): MangasPage =
        list(page, "post_desc", query.trim())

    private suspend fun list(page: Int, sort: String, query: String = ""): MangasPage {
        if (page < 1) return MangasPage(emptyList(), false)
        val results = postJson(
            "$baseUrl/wp-json/manga/v1/load",
            JSONObject()
                .put("page", page)
                .put("search", query)
                .put("years", "[]")
                .put("genres", "[]")
                .put("types", "[]")
                .put("statuses", "[]")
                .put("sort", sort)
                .put("genreMatchMode", "any")
        )
        val mangas = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val url = checkedMangaUrl(item.optString("url")) ?: return@mapNotNull null
            val title = item.optString("title").trim().ifBlank { return@mapNotNull null }
            SManga(
                url = url,
                title = title,
                thumbnailUrl = item.optString("cover").takeIf(String::isNotBlank),
                description = item.optString("description").takeIf(String::isNotBlank),
                status = statusFromText(item.optString("status"))
            )
        }.distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= PAGE_SIZE)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val url = checkedMangaUrl(manga.url) ?: throw IOException("Invalid Roliascans manga address")
        val doc = Jsoup.parse(getString(url), url)
        doc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.title = it }
        doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)?.let { manga.thumbnailUrl = absoluteUrl(it) }
        doc.selectFirst("#description-content-tab")?.text()?.trim()
            ?.takeIf(String::isNotBlank)?.let { manga.description = it }
        val creatorLabel = doc.select("div").firstOrNull {
            it.ownText().equals("Author & Artist", ignoreCase = true)
        }
        creatorLabel?.parent()?.children()?.firstOrNull()?.text()?.trim()?.takeIf(String::isNotBlank)
            ?.let { manga.author = it; manga.artist = it }
        val chapterList = doc.selectFirst(".chapter-list")
            ?: throw IOException("Roliascans did not provide chapter metadata for this title")
        manga.status = statusFromText(chapterList.attr("data-status"))
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val url = checkedMangaUrl(manga.url) ?: throw IOException("Invalid Roliascans manga address")
        val doc = Jsoup.parse(getString(url), url)
        val chapterList = doc.selectFirst(".chapter-list")
            ?: throw IOException("Roliascans did not provide a chapter list")
        val mangaId = chapterList.attr("data-manga-id").toLongOrNull()
            ?: throw IOException("Roliascans did not provide a valid manga ID")
        val chapters = mutableListOf<SChapter>()
        var offset = 0
        var hasMore = true
        while (hasMore && chapters.size < MAX_CHAPTERS) {
            val timestamp = System.currentTimeMillis() / 1000L
            val token = chapterToken(timestamp)
            val params = buildString {
                append("$baseUrl/auth/manga-chapters?manga_id=$mangaId")
                append("&offset=$offset&limit=$CHAPTER_PAGE_SIZE&order=DESC")
                append("&_t=$token&_ts=$timestamp")
            }
            val response = JSONObject(getString(params))
            if (!response.optBoolean("success")) {
                throw IOException("Roliascans could not load chapters for ${manga.title}")
            }
            val items = response.optJSONArray("chapters")
                ?: throw IOException("Roliascans returned an invalid chapter list")
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val chapterUrl = checkedReaderUrl(item.optString("url")) ?: continue
                val number = item.optString("chapter").trim()
                val group = item.optString("group_name").trim()
                val title = item.optString("title").trim().takeUnless {
                    it.isBlank() || it.equals("N/A", ignoreCase = true)
                }
                val name = buildString {
                    append("Chapter ").append(number.ifBlank { item.optString("id") })
                    if (title != null) append(" - ").append(title)
                    if (group.isNotBlank()) append(" (").append(group).append(')')
                }
                chapters += SChapter(
                    url = chapterUrl,
                    name = name,
                    dateUpload = parseSiteDate(item.optString("date")),
                    chapterNumber = parseChapterNumber(number)
                )
            }
            hasMore = response.optBoolean("has_more")
            val received = items.length()
            if (received == 0) break
            offset += received
        }
        if (chapters.isEmpty()) throw IOException("Roliascans returned no readable chapters")
        chapters.forEachIndexed { index, chapter ->
            if (chapter.chapterNumber < 0f) chapter.chapterNumber = (chapters.size - index).toFloat()
        }
        return chapters.distinctBy { it.url }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val chapterId = checkedReaderUrl(chapter.url)
            ?.substringBefore('?')
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.substringAfterLast('-')
            ?.toLongOrNull()
            ?: throw IOException("Invalid Roliascans chapter address")
        val response = JSONObject(getString("$baseUrl/auth/chapter-content?chapter_id=$chapterId"))
        if (!response.optBoolean("success")) {
            throw IOException("Roliascans could not load chapter ${chapter.name}")
        }
        val images = response.optJSONArray("images")
            ?: throw IOException("Roliascans returned an invalid chapter response")
        val pages = (0 until images.length()).mapNotNull { index ->
            images.optString(index).takeIf(String::isNotBlank)?.let(::absoluteUrl)
        }.distinct()
        if (pages.isEmpty()) throw IOException("Roliascans returned no pages for ${chapter.name}")
        return pages
    }

    private suspend fun postJson(url: String, body: JSONObject): JSONArray =
        client.await(
            Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            try {
                JSONArray(text)
            } catch (_: JSONException) {
                throw IOException("Roliascans returned an invalid catalog response")
            }
        }

    private fun checkedMangaUrl(raw: String): String? =
        checkedSiteUrl(raw)?.takeIf { runCatching { java.net.URI(it).path }.getOrNull()?.startsWith("/manga/") == true }

    private fun checkedReaderUrl(raw: String): String? =
        checkedSiteUrl(raw)?.takeIf { runCatching { java.net.URI(it).path }.getOrNull()?.startsWith("/read/") == true }

    private fun checkedSiteUrl(raw: String): String? {
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        return raw.takeIf {
            uri.scheme.equals("https", true) &&
                uri.host.equals("roliascan.com", true)
        }
    }

    private fun chapterToken(timestamp: Long): String {
        val hour = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(timestamp * 1000L)
        val digest = MessageDigest.getInstance("MD5")
            .digest("${timestamp}mng_ch_$hour".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val PAGE_SIZE = 24
        const val CHAPTER_PAGE_SIZE = 500
        const val MAX_CHAPTERS = 5000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
