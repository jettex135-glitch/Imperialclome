package com.imperialreader.data.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.network.await
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import kotlin.math.min

class MangaFireSource : HttpSource() {
    override val id = "wp_mangafire_to"
    override val name = "MangaFire"
    override val lang = "en"
    override val baseUrl = "https://mangafire.to"
    override val extraHeaders: Map<String, String>
        get() = super.extraHeaders + mapOf("Accept-Language" to "en-US,en;q=0.9")

    private val jsonHeaders = mapOf("Accept" to "application/json")

    override suspend fun getPopular(page: Int): MangasPage =
        listTitles(page, order = "views_30d")

    override suspend fun getLatest(page: Int): MangasPage =
        listTitles(page, order = "chapter_updated_at")

    override suspend fun search(query: String, page: Int): MangasPage =
        listTitles(page, query = query.trim())

    private suspend fun listTitles(page: Int, order: String? = null, query: String? = null): MangasPage {
        if (page < 1) return MangasPage(emptyList(), false)
        val params = buildList {
            order?.let { add("order[$it]" to "desc") }
            query?.takeIf { it.isNotBlank() }?.let { add("keyword" to it) }
            add("page" to page.toString())
            add("limit" to "50")
        }
        val response = JSONObject(getApi("/api/titles", params))
        val entries = response.optJSONArray("items")
            ?: throw IOException("MangaFire returned no title list")
        val mangas = (0 until entries.length()).mapNotNull { index ->
            val item = entries.optJSONObject(index) ?: return@mapNotNull null
            val hid = item.optString("hid").takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
                ?: return@mapNotNull null
            val title = item.optString("title").trim().ifBlank { return@mapNotNull null }
            val slug = item.optString("slug").trim().takeIf { it.isNotBlank() }
            val poster = item.optJSONObject("poster")
            SManga(
                url = titleUrl(hid, slug),
                title = title,
                thumbnailUrl = poster?.optString("large")?.takeIf { it.startsWith("https://") }
                    ?: poster?.optString("medium")?.takeIf { it.startsWith("https://") }
                    ?: poster?.optString("small")?.takeIf { it.startsWith("https://") }
            )
        }.distinctBy { it.url }
        val hasNext = response.optJSONObject("meta")?.optBoolean("hasNext") ?: (entries.length() >= 50)
        return MangasPage(mangas, hasNext)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val hid = mangaId(manga.url)
        val response = JSONObject(getApi("/api/titles/$hid"))
        val details = response.optJSONObject("data") ?: throw IOException("MangaFire returned no title details")
        val poster = details.optJSONObject("poster")
        manga.url = titleUrl(hid, details.optString("slug").takeIf { it.isNotBlank() })
        manga.title = details.optString("title").ifBlank { manga.title }
        manga.thumbnailUrl = poster?.optString("large")?.takeIf { it.startsWith("https://") }
            ?: poster?.optString("medium")?.takeIf { it.startsWith("https://") }
            ?: poster?.optString("small")?.takeIf { it.startsWith("https://") }
            ?: manga.thumbnailUrl
        manga.description = details.optString("synopsisHtml").takeIf { it.isNotBlank() }
            ?.let { Jsoup.parseBodyFragment(it).text() } ?: manga.description
        manga.author = entityTitles(details.optJSONArray("authors")).joinToString(", ")
            .ifBlank { manga.author.orEmpty() }
        manga.artist = entityTitles(details.optJSONArray("artists")).joinToString(", ")
            .ifBlank { manga.artist.orEmpty() }
        manga.genre = buildList {
            details.optString("type").takeIf { it.isNotBlank() }?.let { add(it.replaceFirstChar(Char::uppercase)) }
            addAll(entityTitles(details.optJSONArray("genres")))
            addAll(entityTitles(details.optJSONArray("themes")))
        }.distinct().joinToString(", ").ifBlank { manga.genre.orEmpty() }
        manga.status = when (details.optString("status").lowercase()) {
            "releasing" -> 1
            "finished" -> 2
            "on_hiatus", "discontinued" -> 3
            else -> 0
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val hid = mangaId(manga.url)
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var lastPage = 1
        while (page <= lastPage.coerceAtMost(MAX_CHAPTER_PAGES)) {
            val params = listOf(
                "language" to lang,
                "sort" to "number",
                "order" to "desc",
                "page" to page.toString(),
                "limit" to "200"
            )
            val response = JSONObject(getApi("/api/titles/$hid/chapters", params))
            val items = response.optJSONArray("items") ?: JSONArray()
            lastPage = response.optJSONObject("meta")?.optInt("lastPage", 1) ?: 1
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optInt("id", -1).takeIf { it > 0 } ?: continue
                val number = item.optDouble("number", -1.0).toFloat()
                if (number < 0f) continue
                val numberText = number.toString().removeSuffix(".0")
                val name = item.optString("name").ifBlank { "Chapter $numberText" }
                chapters += SChapter(
                    url = "${manga.url.trimEnd('/')}/$id-chapter-$numberText-${enc(lang)}",
                    name = name,
                    dateUpload = item.optLong("createdAt", 0L) * 1000L,
                    chapterNumber = number,
                    scanlator = item.optString("type").ifBlank { null }
                )
            }
            if (items.length() == 0) break
            page++
        }
        if (page > MAX_CHAPTER_PAGES) throw IOException("MangaFire chapter list exceeded the page limit")
        if (chapters.isEmpty()) throw IOException("MangaFire returned no chapter entries")
        return chapters.distinctBy { it.url }.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val id = chapterId(chapter.url)
        val data = JSONObject(getApi("/api/chapters/$id")).optJSONObject("data")
            ?: throw IOException("MangaFire returned no chapter page data")
        val entries = data.optJSONArray("pages") ?: JSONArray()
        val pages = (0 until entries.length()).mapNotNull { index ->
            val page = entries.optJSONObject(index) ?: return@mapNotNull null
            page.optString("url").takeIf { it.startsWith("https://") }
        }.distinct()
        if (pages.isEmpty()) throw IOException("MangaFire returned no readable pages for this chapter")
        return pages
    }

    private fun mangaId(url: String): String {
        val segment = runCatching { URI(url).path.trimEnd('/').substringAfterLast('/') }.getOrNull().orEmpty()
        val id = when {
            '.' in segment -> segment.substringAfterLast('.')
            '-' in segment -> segment.substringBefore('-')
            else -> segment
        }
        return id.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
            ?: throw IOException("MangaFire returned an invalid title address")
    }

    private fun chapterId(url: String): String {
        val segment = runCatching { URI(url).path.trimEnd('/').substringAfterLast('/') }.getOrNull().orEmpty()
        val id = segment.substringBefore('-')
        return id.takeIf { it.matches(Regex("\\d+")) }
            ?: throw IOException("MangaFire returned an invalid chapter address")
    }

    private fun titleUrl(hid: String, slug: String?): String =
        "$baseUrl/title/$hid${slug?.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()}"

    private fun entityTitles(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    private suspend fun getApi(path: String, query: List<Pair<String, String>> = emptyList()): String =
        withContext(Dispatchers.IO) {
            val builder = "$baseUrl$path".toHttpUrl().newBuilder()
            query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            val unsignedUrl = builder.build()
            val parameters = unsignedUrl.queryParameterNames
                .flatMap { key -> unsignedUrl.queryParameterValues(key).map { key to it } }
                .sortedBy { it.first }
            val signatureInput = buildString {
                append(unsignedUrl.encodedPath.removePrefix("/api"))
                if (parameters.isNotEmpty()) {
                    append('?')
                    var previousArrayKey = ""
                    var arrayIndex = 0
                    append(parameters.joinToString("&") { (key, value) ->
                        val signedKey = if (key.endsWith("[]")) {
                            if (previousArrayKey != key) arrayIndex = 0
                            previousArrayKey = key
                            key.removeSuffix("[]") + "[${arrayIndex++}]"
                        } else key
                        "$signedKey=$value"
                    })
                }
            }
            val signedUrl = unsignedUrl.newBuilder()
                .addQueryParameter("vrf", MangaFireApiSigner.sign(signatureInput))
                .build()
            val request = Request.Builder().url(signedUrl).apply {
                extraHeaders.forEach { (key, value) -> header(key, value) }
                jsonHeaders.forEach { (key, value) -> header(key, value) }
            }.build()
            client.await(request).use { response ->
                val body = response.body?.string().orEmpty()
                val captchaRequired = response.code == 403 &&
                    runCatching { JSONObject(body).optString("error") == "captcha_required" }.getOrDefault(false)
                val cloudflarePage = Regex(
                    "(?is)<title[^>]*>\\s*(?:just a moment|attention required|checking your browser)"
                ).containsMatchIn(body)
                if (captchaRequired || response.header("cf-mitigated")?.equals("challenge", true) == true || cloudflarePage) {
                    throw CloudflareException(signedUrl.toString())
                }
                if (!response.isSuccessful) throw HttpStatusException(response.code, signedUrl.toString())
                if (body.isBlank()) throw IOException("MangaFire returned an empty API response")
                body
            }
        }

    private companion object {
        const val MAX_CHAPTER_PAGES = 100
    }
}

private object MangaFireApiSigner {
    private val stages = listOf(
        Triple(
            Base64.getDecoder().decode("yINlmUNho8VYJT+ibTIP+9ESiULpVEtMOoD6U6lRE0R/xwXo/Xp9NrUgC4cw/Lmo33vUyjUE40kUoEWIr/fxfNNcq2s79ShQ5NhNrFnJ4hXPwOu/SuXzIbuTQKGFvfm08E9jvCfqAtoDqvQq3dVWPQFmJjgvkISBeXY3BgANR+yVnjGbcxZ47d6kLNfZPIayTq3/YGySb1KuVZodWp/WGNAO5pfMcpaK53Hhs0allBszaMaxuouOwdxbwgxIw6YunSsXjI05Yi0j9j4eHKfSXR8Ifo/Od+8iamRfCXTyvm7NGRGYdcQ0ywcK/u6RXhrbcCm4t2eCtrDgQVecJGkQ+A=="),
            Base64.getDecoder().decode("0Ec58JOY3uBzJK9m3zqIOpdlF7UFiax9DmA="),
            0x5A
        ),
        Triple(
            Base64.getDecoder().decode("IUFltCxD3Oc2cwCgkJffthaOg9cgPUb0LgW6H/VtfcF0kc5F25t+aWj6JH9VOhOaY0rAFdUxlDnl5BLNvwEJvQtP5qcw7vdb/K+chnbwnspSHT8mz5lqwz41TezG0hkO06FTjJZhsyNuFLDpD2ZZxQj/QIRcF90zpmQ7Byu483WsQqUE0C342HL+JXngRB6fRzxRyVTaKu83h7UYTJ0QMt6ixFh6S3F8gqkKwrGTL3jHNBsD45UnifK8+RGtishQV2K3rujLKEkiZxpr2dYcudFW4oFsDKhad3CLBvuyTqsCo4B7mL5IKQ1vXo/MOOvq1I1d8ar9X6Ttu5KF4fZgiA=="),
            Base64.getDecoder().decode("AAdjb1iPY8CiDmq9H34tKTBF8a3oDQ=="),
            0x35
        ),
        Triple(
            Base64.getDecoder().decode("NQHlu1/wVO5EmkwQymF810qqY2xG1k2obcas4Z9mCsPEIFl9pRIjFxbJ7ybMHbBckT5Ton85E0FOeHezbh/mjlEYpmpnlXOS8dgrqeq2KfxImTh1YK9y0PeMNhzA1OQzSY9brYOJq/l2QnE/hwOeZIhPixVSKIUlDb5vLcH6RWKxkIEMuP0bDwIqQ71AJJaEaMJL7A6YtyIwoRT+L5v4aZzodN/0+3nOGsfblFjgxSfPzVDjNFeNl5P26+kEC/8AHgdrpAbt3hHz3HrRN1Y6e+JHgF7ncFWnoF0y3THL1S71WgWGCa6KtSzTCCG58n68nTyj2T3Sshk7utqCtMi/ZQ=="),
            Base64.getDecoder().decode("DELOJgPsVaCcblDtTGMdHzM="),
            0xBA
        )
    )

    fun sign(value: String): String {
        var bytes = value.toByteArray(Charsets.UTF_8)
        for ((table, key, initial) in stages) {
            val output = ByteArray(bytes.size)
            var previous = initial
            for (index in bytes.indices) {
                previous = table[(bytes[index].toInt() xor key[index % key.size].toInt() xor previous) and 0xff].toInt() and 0xff
                output[index] = previous.toByte()
            }
            bytes = output
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal object MangaFirePageTransform {
    fun offset(url: String): Int? =
        Regex("""^scrambled_(\d+)$""").matchEntire(runCatching { URI(url).fragment }.getOrNull().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()

    fun unscramble(bitmap: Bitmap, offset: Int): Bitmap {
        fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor

        val width = bitmap.width
        val height = bitmap.height
        val pieceWidth = min(200, ceilDiv(width, 5))
        val pieceHeight = min(200, ceilDiv(height, 5))
        val xMax = ceilDiv(width, pieceWidth) - 1
        val yMax = ceilDiv(height, pieceHeight) - 1
        if (xMax <= 0 || yMax <= 0) return bitmap

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        for (y in 0..yMax) {
            for (x in 0..xMax) {
                val xDst = pieceWidth * x
                val yDst = pieceHeight * y
                val tileWidth = min(pieceWidth, width - xDst)
                val tileHeight = min(pieceHeight, height - yDst)
                val sourceX = pieceWidth * if (x == xMax) x else (xMax - x + offset) % xMax
                val sourceY = pieceHeight * if (y == yMax) y else (yMax - y + offset) % yMax
                canvas.drawBitmap(
                    bitmap,
                    Rect(sourceX, sourceY, sourceX + tileWidth, sourceY + tileHeight),
                    Rect(xDst, yDst, xDst + tileWidth, yDst + tileHeight),
                    paint
                )
            }
        }
        return output
    }
}

private object MangaFireVrf {
    private val rc4Keys = listOf(
        "FgxyJUQDPUGSzwbAq/ToWn4/e8jYzvabE+dLMb1XU1o=",
        "CQx3CLwswJAnM1VxOqX+y+f3eUns03ulxv8Z+0gUyik=",
        "fAS+otFLkKsKAJzu3yU+rGOlbbFVq+u+LaS6+s1eCJs=",
        "Oy45fQVK9kq9019+VysXVlz1F9S1YwYKgXyzGlZrijo=",
        "aoDIdXezm2l3HrcnQdkPJTDT8+W6mcl2/02ewBHfPzg="
    ).map(Base64.getDecoder()::decode)
    private val seeds = listOf(
        "yH6MXnMEcDVWO/9a6P9W92BAh1eRLVFxFlWTHUqQ474=",
        "RK7y4dZ0azs9Uqz+bbFB46Bx2K9EHg74ndxknY9uknA=",
        "rqr9HeTQOg8TlFiIGZpJaxcvAaKHwMwrkqojJCpcvoc=",
        "/4GPpmZXYpn5RpkP7FC/dt8SXz7W30nUZTe8wb+3xmU=",
        "wsSGSBXKWA9q1oDJpjtJddVxH+evCfL5SO9HZnUDFU8="
    ).map(Base64.getDecoder()::decode)
    private val prefixes = listOf(
        "l9PavRg=",
        "Ml2v7ag1Jg==",
        "i/Va0UxrbMo=",
        "WFjKAHGEkQM=",
        "5Rr27rWd"
    ).map(Base64.getDecoder()::decode)
    private val schedules = listOf(
        listOf("s223", "r4", "r4", "a234", "r7", "r2", "r7", "s223", "r7", "r6"),
        listOf("a19", "r7", "a19", "r6", "a19", "r1", "a19", "r6", "r7", "r4"),
        listOf("s223", "r1", "a19", "s223", "l2", "s223", "a19", "l1", "l2", "l1"),
        listOf("a19", "l1", "l1", "r1", "a234", "l1", "s223", "l6", "l4", "l1"),
        listOf("r1", "l1", "l6", "r1", "l2", "r4", "l1", "l1", "s223", "l2")
    )

    fun generate(input: String): String {
        var bytes = URLEncoder.encode(input, StandardCharsets.UTF_8.name())
            .replace("+", "%20").toByteArray(StandardCharsets.UTF_8)
        for (stage in rc4Keys.indices) {
            bytes = rc4(rc4Keys[stage], bytes)
            bytes = transform(bytes, seeds[stage], prefixes[stage], schedules[stage])
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            val temp = state[i]
            state[i] = state[j]
            state[j] = temp
        }
        val output = ByteArray(input.size)
        var i = 0
        j = 0
        for (index in input.indices) {
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            val temp = state[i]
            state[i] = state[j]
            state[j] = temp
            output[index] = (input[index].toInt() xor state[(state[i] + state[j]) and 0xff]).toByte()
        }
        return output
    }

    private fun transform(input: ByteArray, seed: ByteArray, prefix: ByteArray, schedule: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        input.forEachIndexed { index, byte ->
            if (index < prefix.size) output.write(prefix[index].toInt())
            val value = (byte.toInt() and 0xff) xor (seed[index % seed.size].toInt() and 0xff)
            output.write(apply(value, schedule[index % schedule.size]))
        }
        return output.toByteArray()
    }

    private fun apply(value: Int, operation: String): Int {
        val amount = operation.drop(1).toInt()
        return when (operation.first()) {
            'a' -> (value + amount) and 0xff
            's' -> (value - amount + 256) and 0xff
            'l' -> ((value shl amount) or (value ushr (8 - amount))) and 0xff
            else -> ((value ushr amount) or (value shl (8 - amount))) and 0xff
        }
    }
}
