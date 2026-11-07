package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

class PawchiveSource : HttpSource() {
    private data class Creator(
        val id: String,
        val service: String,
        val name: String,
        val publicId: String,
        val updated: Long,
        val favorited: Long
    )

    private data class MediaFile(val path: String)

    override val id = "pawchive_pw"
    override val name = "Pawchive"
    override val lang = "en"
    override val baseUrl = "https://pawchive.pw"
    override val nsfw = true

    private val creatorsMutex = Mutex()

    @Volatile
    private var cachedCreators: List<Creator>? = null

    override suspend fun getPopular(page: Int): MangasPage =
        pageCreators(creators().sortedByDescending(Creator::favorited), page)

    override suspend fun getLatest(page: Int): MangasPage =
        pageCreators(creators().sortedByDescending(Creator::updated), page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val needle = query.trim()
        if (needle.isBlank()) return MangasPage(emptyList(), false)
        val matches = creators().filter {
            it.name.contains(needle, ignoreCase = true) ||
                it.publicId.contains(needle, ignoreCase = true)
        }
        return pageCreators(matches, page)
    }

    private suspend fun creators(): List<Creator> {
        cachedCreators?.let { return it }
        return creatorsMutex.withLock {
            cachedCreators?.let { return@withLock it }
            val response = JSONArray(getString("$baseUrl/api/v1/creators", mapOf("Accept" to "application/json")))
            val loaded = (0 until response.length()).mapNotNull { index ->
                val json = response.optJSONObject(index) ?: return@mapNotNull null
                val id = json.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val service = json.optString("service")
                    .takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return@mapNotNull null
                val name = json.optString("name").trim().ifBlank { json.optString("public_id").trim() }
                if (name.isBlank()) return@mapNotNull null
                Creator(
                    id = id,
                    service = service,
                    name = name,
                    publicId = json.optString("public_id"),
                    updated = json.optLong("updated"),
                    favorited = json.optLong("favorited")
                )
            }
            cachedCreators = loaded
            loaded
        }
    }

    private fun pageCreators(creators: List<Creator>, page: Int): MangasPage {
        val start = ((page - 1).coerceAtLeast(0) * PAGE_SIZE).coerceAtMost(creators.size)
        val end = (start + PAGE_SIZE).coerceAtMost(creators.size)
        val entries = creators.subList(start, end).map { creator ->
            creator.toManga()
        }
        return MangasPage(entries, end < creators.size)
    }

    private fun Creator.toManga(): SManga {
        val creatorUrl = "$baseUrl/$service/user/$id"
        return SManga(
            url = creatorUrl,
            title = name,
            thumbnailUrl = "$baseUrl/icons/$service/$id",
            author = serviceLabel(service),
            artist = name,
            description = null
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val uri = checkedUri(manga.url)
        if (uri.path.trim('/').startsWith("posts/")) {
            val (service, userId, postId) = parsePostRoute(manga.url)
            val post = loadPost(service, userId, postId)
            val artist = creators().firstOrNull { it.service == service && it.id == userId }?.name
            return manga.copy(
                title = post.optString("title").trim().ifBlank { manga.title },
                author = serviceLabel(service),
                artist = artist,
                thumbnailUrl = mediaFiles(post).firstOrNull()?.let(::mediaUrl) ?: manga.thumbnailUrl,
                description = Jsoup.parse(post.optString("content")).text().trim().ifBlank { null },
                genre = post.optString("tags").trim().trim('{', '}').ifBlank { null }
            )
        }
        val creator = parseCreatorRoute(manga.url)
        val record = creators().firstOrNull { it.service == creator.first && it.id == creator.second }
            ?: throw IOException("Pawchive no longer lists this creator")
        val posts = loadPosts(record.service, record.id, 0)
        val cover = posts.firstNotNullOfOrNull { mediaFiles(it).firstOrNull()?.let(::mediaUrl) }
        return manga.copy(
            title = record.name,
            author = serviceLabel(record.service),
            artist = record.name,
            thumbnailUrl = cover ?: "$baseUrl/icons/${record.service}/${record.id}",
            description = null
        )
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val uri = checkedUri(manga.url)
        if (uri.path.trim('/').startsWith("posts/")) {
            val (service, userId, postId) = parsePostRoute(manga.url)
            val post = loadPost(service, userId, postId)
            return listOf(
                SChapter(
                    url = manga.url,
                    name = post.optString("title").trim().ifBlank { manga.title },
                    dateUpload = parseDate(post.optString("published"))
                        .takeIf { it > 0L } ?: parseDate(post.optString("added"))
                )
            )
        }
        val (service, userId) = parseCreatorRoute(manga.url)
        val result = mutableListOf<SChapter>()
        var offset = 0
        while (result.size < MAX_CHAPTERS) {
            val posts = loadPosts(service, userId, offset)
            posts.forEach { post ->
                if (mediaFiles(post).isNotEmpty()) {
                    val postId = post.optString("id")
                    if (postId.isNotBlank()) {
                        val title = post.optString("title").trim().ifBlank { "Post $postId" }
                        result += SChapter(
                            url = "$baseUrl/posts/$service/$userId/$postId",
                            name = title,
                            dateUpload = parseDate(post.optString("published"))
                                .takeIf { it > 0L } ?: parseDate(post.optString("added")),
                            chapterNumber = parseChapterNumber(title)
                        )
                    }
                }
            }
            if (posts.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        result.forEachIndexed { index, chapter ->
            if (chapter.chapterNumber < 0f) chapter.chapterNumber = (result.size - index).toFloat()
        }
        return result
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val (service, userId, postId) = parsePostRoute(chapter.url)
        val post = loadPost(service, userId, postId)
        val pages = mediaFiles(post).map(::mediaUrl).distinct()
        if (pages.isEmpty()) throw IOException("Pawchive returned no readable media for this post")
        return pages
    }

    private suspend fun loadPosts(service: String, userId: String, offset: Int): List<JSONObject> {
        val json = JSONArray(
            getString(
                "$baseUrl/api/v1/$service/user/$userId/posts?o=$offset",
                mapOf("Accept" to "application/json")
            )
        )
        return (0 until json.length()).mapNotNull(json::optJSONObject)
    }

    private suspend fun loadPost(service: String, userId: String, postId: String): JSONObject {
        val response = JSONObject(
            getString(
                "$baseUrl/api/v1/$service/user/$userId/post/$postId",
                mapOf("Accept" to "application/json")
            )
        )
        return response.optJSONObject("post") ?: response
    }

    private fun parseCreatorRoute(raw: String): Pair<String, String> {
        val uri = checkedUri(raw)
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 3 || parts[1] != "user" ||
            !parts[0].matches(Regex("[A-Za-z0-9_-]+")) ||
            !parts[2].matches(Regex("[A-Za-z0-9_-]+"))
        ) throw IOException("Invalid Pawchive creator address")
        return parts[0] to parts[2]
    }

    private fun parsePostRoute(raw: String): Triple<String, String, String> {
        val uri = checkedUri(raw)
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 4 || parts[0] != "posts" ||
            parts.drop(1).any { !it.matches(Regex("[A-Za-z0-9_-]+")) }
        ) throw IOException("Invalid Pawchive post address")
        return Triple(parts[1], parts[2], parts[3])
    }

    private fun checkedUri(raw: String): URI {
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw IOException("Invalid Pawchive address")
        if (!uri.host.equals(URI(baseUrl).host, ignoreCase = true)) {
            throw IOException("Invalid Pawchive address")
        }
        return uri
    }

    private fun mediaFiles(post: JSONObject): List<MediaFile> = buildList {
        addMediaFile(post.optJSONObject("file"))?.let(::add)
        val attachments = post.optJSONArray("attachments") ?: JSONArray()
        for (index in 0 until attachments.length()) {
            addMediaFile(attachments.optJSONObject(index))?.let(::add)
        }
    }.distinctBy { it.path }

    private fun addMediaFile(file: JSONObject?): MediaFile? {
        val path = file?.optString("path")?.takeIf { it.isNotBlank() } ?: return null
        val normalized = path.removePrefix("/data/").trimStart('/')
        if (normalized.substringAfterLast('.', "").lowercase() !in MEDIA_EXTENSIONS) return null
        return MediaFile(normalized)
    }

    private fun mediaUrl(file: MediaFile): String =
        "https://file.pawchive.pw/data/${file.path}"

    private fun serviceLabel(service: String): String =
        service.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_CHAPTERS = 1000
        val MEDIA_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "mp4")
    }
}
