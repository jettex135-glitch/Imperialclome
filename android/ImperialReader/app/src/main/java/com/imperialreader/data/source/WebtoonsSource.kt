package com.imperialreader.data.source

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale

internal data class WebtoonEpisode(
    val title: String,
    val viewerLink: String,
    val publishedAt: Long,
    val episodeNumber: Int,
    val hasBgm: Boolean,
    val audioUrl: String? = null
)

/** English WEBTOON catalog and reader, adapted from Mihon's Webtoons extension. */
class WebtoonsSource : HttpSource() {
    override val id = "webtoons_en"
    override val name = "WEBTOON"
    override val lang = "en"
    override val baseUrl = "https://www.webtoons.com"

    private val mobileUrl = "https://m.webtoons.com"
    private val episodeAudioUrls = ConcurrentHashMap<String, String>()
    private val requestHeaders = mapOf(
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "ageGatePass=true; locale=en; needGDPR=false",
        "Referer" to "$mobileUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val extraHeaders: Map<String, String>
        get() = requestHeaders

    override fun imageHeaders(): Map<String, String> =
        mapOf("Referer" to "$baseUrl/")

    override suspend fun getPopular(page: Int): MangasPage {
        val ranking = when (page) {
            1 -> "trending"
            2 -> "popular"
            3 -> "originals"
            4 -> "canvas"
            else -> return MangasPage(emptyList(), false)
        }
        val document = Jsoup.parse(getString("$baseUrl/$lang/ranking/$ranking"), baseUrl)
        val entries = parseMangaCards(document)
        return MangasPage(entries, hasNextPage = ranking != "canvas" && entries.isNotEmpty())
    }

    override suspend fun getLatest(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val day = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            else -> throw IOException("Could not determine the current weekday")
        }
        val document = Jsoup.parse(
            getString("$baseUrl/$lang/originals/$day?sortOrder=UPDATE"),
            baseUrl
        )
        return MangasPage(parseMangaCards(document), false)
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank() || page < 1) return MangasPage(emptyList(), false)
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(lang)
            .addPathSegment("search")
            .addQueryParameter("keyword", query)
            .apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }
            .build()
        val document = Jsoup.parse(getString(url.toString()), url.toString())
        val entries = parseMangaCards(document)
        val hasNext = document.selectFirst("a.pagination[aria-current=true] + a") != null
        return MangasPage(entries, hasNext)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val detailsUrl = absoluteUrl(manga.url)
        val document = Jsoup.parse(getString(detailsUrl), detailsUrl)
        val details = document.selectFirst(".detail_header .info")
        val info = document.selectFirst(".info_area")
        val aside = document.selectFirst("#_asideDetail, .details_area")
        manga.title = document.selectFirst("h1.subj, h3.subj, .info_area .subject")
            ?.text()
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("WEBTOON did not return a title on its details page")
        val creators = (info?.selectFirst(".author")?.text()
            ?: details?.selectFirst(".author_area")?.text()).orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
        manga.author = details?.selectFirst(".author:nth-of-type(1)")?.ownText()
            ?.ifBlank { null }
            ?: creators.getOrNull(0)
        manga.artist = details?.selectFirst(".author:nth-of-type(2)")?.ownText()
            ?.ifBlank { null }
            ?: creators.getOrNull(1)
            ?: manga.author
        manga.genre = (info?.select(".tag_box .tag")?.ifEmpty { null }
            ?: details?.select(".genre"))
            ?.joinToString(", ") { it.text() }
            ?.ifBlank { null }
        manga.description = info?.selectFirst(".summary")?.text()?.ifBlank { null }
            ?: aside?.selectFirst("p.summary")?.text()?.ifBlank { null }
        val status = aside?.selectFirst("p.day_info, dd.value")?.text().orEmpty().uppercase(Locale.ROOT)
        manga.status = when {
            listOf("UP", "EVERY", "NOUVEAU").any(status::contains) -> 1
            listOf("END", "COMPLETED", "TERMINÉ").any(status::contains) -> 2
            else -> 0
        }
        manga.thumbnailUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("https://") }
            ?: manga.thumbnailUrl
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val url = absoluteUrl(manga.url).toHttpUrl()
        val titleNo = url.queryParameter("title_no")
            ?: url.queryParameter("titleNo")
            ?: throw IOException("WEBTOON title URL is missing its title number")
        val path = url.encodedPath.split('/').filter(String::isNotBlank)
        val isCanvas = path.getOrNull(1) == "canvas" || path.firstOrNull() == "challenge"
        val endpoint = mobileUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v1")
            .addPathSegment(if (isCanvas) "canvas" else "webtoon")
            .addPathSegment(titleNo)
            .addPathSegment("episodes")
            .addQueryParameter("pageSize", "99999")
            .apply {
                if (isCanvas) addQueryParameter("readingLanguageCode", lang)
            }
            .build()
        val response = JSONObject(getString(endpoint.toString(), requestHeaders))
        val episodeList = response.optJSONObject("result")?.optJSONArray("episodeList")
            ?: throw IOException("WEBTOON did not return an episode list")
        val episodes = (0 until episodeList.length()).mapNotNull { index ->
            val episode = episodeList.optJSONObject(index) ?: return@mapNotNull null
            val title = episode.optString("episodeTitle").trim()
            val viewerLink = episode.optString("viewerLink").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            WebtoonEpisode(
                title = title.ifBlank { "Episode ${episode.optInt("episodeNo")}" },
                viewerLink = absoluteUrl(viewerLink),
                publishedAt = episode.optLong("exposureDateMillis"),
                episodeNumber = episode.optInt("episodeNo", -1),
                hasBgm = episode.optBoolean("hasBgm"),
                audioUrl = parseEpisodeAudioUrl(episode)
            )
        }
        if (episodes.isEmpty()) throw IOException("WEBTOON returned an empty episode list")
        episodes.forEach { episode ->
            episode.audioUrl?.let { episodeAudioUrls[episode.viewerLink] = it }
        }
        return parseWebtoonChapters(episodes)
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        return loadEpisode(chapter).urls
    }

    override suspend fun loadPages(manga: SManga, chapter: SChapter): ChapterPages =
        loadEpisode(chapter)

    private suspend fun loadEpisode(chapter: SChapter): ChapterPages {
        val viewerUrl = absoluteUrl(chapter.url)
        val document = Jsoup.parse(getString(viewerUrl), viewerUrl)
        val audioUrl = episodeAudioUrls[viewerUrl] ?: parseViewerAudioUrl(document, viewerUrl)
        val pages = document.select("div#_imageList > img")
            .mapNotNull { it.attr("data-url").ifBlank { it.attr("src") } }
            .mapNotNull { imageUrl ->
                runCatching { absoluteUrl(imageUrl) }.getOrNull()
                    ?.takeIf { it.startsWith("https://") }
            }
            .distinct()
        if (pages.isNotEmpty()) return ChapterPages(pages, imageHeaders(), audioUrl)

        val source = document.toString()
        val embeddedPages = parseEmbeddedImageList(source)
        if (embeddedPages.isNotEmpty()) return ChapterPages(embeddedPages, imageHeaders(), audioUrl)

        val documentUrl = DOCUMENT_URL_REGEX.find(source)?.groupValues?.get(1)
            ?: throw IOException("WEBTOON viewer did not contain readable episode images")
        val imagePath = MOTION_TOON_PATH_REGEX.find(source)?.groupValues?.get(1)
            ?: throw IOException("WEBTOON motion episode did not contain an image path")
        val assets = JSONObject(getString(absoluteUrl(documentUrl), requestHeaders))
            .optJSONObject("assets")
            ?.optJSONObject("images")
            ?: throw IOException("WEBTOON did not return motion episode images")
        val motionPages = assets.keys().asSequence()
            .filter { it.contains("layer", ignoreCase = true) }
            .mapNotNull { key -> assets.optString(key).takeIf(String::isNotBlank) }
            .map { imagePath + it }
            .toList()
        if (motionPages.isEmpty()) throw IOException("WEBTOON returned no readable episode images")
        return ChapterPages(motionPages, imageHeaders(), audioUrl)
    }

    private fun parseMangaCards(document: Document): List<SManga> =
        document.select(".webtoon_list li a").mapNotNull(::mangaFromElement).distinctBy { it.url }

    private fun mangaFromElement(element: Element): SManga? {
        val href = element.absUrl("href").takeIf { it.startsWith("https://") } ?: return null
        val image = element.selectFirst("img")
        val title = sequenceOf(
            element.selectFirst(".title")?.text().orEmpty(),
            image?.attr("alt").orEmpty(),
            element.attr("title"),
            element.text()
        ).map(String::trim).firstOrNull(String::isNotBlank) ?: return null
        val imageUrl = image?.let {
            val raw = it.attr("data-src").ifBlank { it.attr("src") }
            when {
                raw.startsWith("https://") -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.isNotBlank() -> baseUrl.toHttpUrl().resolve(raw)?.toString()
                else -> null
            }
        }?.takeIf { it.startsWith("https://") }
        return SManga(url = href, title = title, thumbnailUrl = imageUrl)
    }

    companion object {
        private val EPISODE_NUMBER_REGEX = Regex(
            """(?:(s(eason)?|saison|part|vol(ume)?)\s*\.?\s*(\d+).*?)?(.*?(mini|bonus|special).*?)?(e(p(isode)?)?|ch(apter)?)\s*\.?\s*(\d+(\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
        private val DOCUMENT_URL_REGEX = Regex("""documentURL:.*?'(.*?)'""")
        private val MOTION_TOON_PATH_REGEX = Regex("""jpg:.*?'(.*?)\{""")
        private val IMAGE_LIST_REGEX = Regex(
            """var imageList\s*=\s*\[(.*?)]\s*;""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val IMAGE_URL_REGEX = Regex("""url\s*:\s*["'](https?://[^"']+)["']""")
        private val AUDIO_URL_REGEX = Regex(
            """(?:https?:)?//[^"'\\\s<>]+?\.(?:mp3|m4a|aac|ogg|opus|wav|m3u8)(?:[?#][^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "m3u8")

        internal fun parseEmbeddedImageList(source: String): List<String> =
            IMAGE_LIST_REGEX.find(source)?.groupValues?.get(1)
                ?.let { imageList ->
                    IMAGE_URL_REGEX.findAll(imageList)
                        .map { it.groupValues[1] }
                        .distinct()
                        .toList()
                }
                .orEmpty()

        internal fun parseViewerAudioUrl(document: Document, pageUrl: String): String? {
            val candidates = mutableListOf<String>()
            val mediaAttributes = listOf("src", "data-src", "data-bgm-url", "data-audio-url", "data-music-url")
            for (element in document.select(
                "audio[src], audio source[src], audio source[data-src], " +
                    "[data-bgm-url], [data-audio-url], [data-music-url]"
            )) {
                for (attribute in mediaAttributes) {
                    val value = element.attr(attribute)
                    if (value.isNotBlank()) candidates.add(value)
                }
            }
            val source = document.toString()
                .replace("\\/", "/")
                .replace("\\u0026", "&", ignoreCase = true)
            for (match in AUDIO_URL_REGEX.findAll(source)) {
                candidates.add(match.value)
            }
            for (candidate in candidates) {
                val normalized = candidate.trim().trimEnd(',', ';', ')', '}', ']')
                    .let { if (it.startsWith("//")) "https:$it" else it }
                val url = runCatching { pageUrl.toHttpUrl().resolve(normalized)?.toString() }.getOrNull()
                    ?: continue
                val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: continue
                val extension = parsed.encodedPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (parsed.isHttps && extension in AUDIO_EXTENSIONS) return url
            }
            return null
        }

        private fun parseEpisodeAudioUrl(episode: JSONObject): String? {
            val keys = episode.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = episode.opt(key)
                if (key.containsAny("audio", "bgm", "music", "sound")) {
                    when (value) {
                        is String -> parseMediaUrl(value)
                        is JSONObject -> parseEpisodeAudioUrl(value)
                        else -> null
                    }?.let { return it }
                }
                when (value) {
                    is JSONObject -> parseEpisodeAudioUrl(value)?.let { return it }
                    is org.json.JSONArray -> {
                        for (index in 0 until value.length()) {
                            val nested = value.optJSONObject(index)
                                ?.let(::parseEpisodeAudioUrl)
                            if (nested != null) return nested
                        }
                    }
                }
            }
            return null
        }

        private fun parseMediaUrl(raw: String): String? {
            val candidate = raw.trim().replace("\\/", "/")
                .let { if (it.startsWith("//")) "https:$it" else it }
            val parsed = runCatching { candidate.toHttpUrl() }.getOrNull() ?: return null
            return candidate.takeIf {
                parsed.isHttps && parsed.encodedPath.substringAfterLast('.', "")
                    .lowercase(Locale.ROOT) in AUDIO_EXTENSIONS
            }
        }

        private fun String.containsAny(vararg values: String): Boolean =
            values.any { contains(it, ignoreCase = true) }

        internal fun parseWebtoonChapters(episodes: List<WebtoonEpisode>): List<SChapter> {
            var recognized = 0
            var unrecognized = 0
            val numbers = episodes.map { episode ->
                val match = EPISODE_NUMBER_REGEX.find(episode.title)
                val isSpecial = match?.groupValues?.get(6)?.isNotEmpty() == true
                val number = if (isSpecial) {
                    -1f
                } else {
                    match?.groupValues?.get(11)?.toFloatOrNull()
                        ?: episode.episodeNumber.takeIf { it > 0 }?.toFloat()
                        ?: -1f
                }
                if (number < 0f) unrecognized++ else recognized++
                number
            }.toMutableList()

            if (unrecognized > recognized) {
                numbers.indices.forEach { numbers[it] = (it + 1).toFloat() }
            } else {
                var maxChapterNumber = 0f
                var currentSeason = 1
                var seasonOffset = 0f
                episodes.forEachIndexed { index, episode ->
                    val match = EPISODE_NUMBER_REGEX.find(episode.title)
                    val isSpecial = match?.groupValues?.get(6)?.isNotEmpty() == true
                    val season = if (isSpecial) 1 else match?.groupValues?.get(4)?.toIntOrNull() ?: 1
                    if (numbers[index] >= 0f) {
                        val originalNumber = numbers[index]
                        if (season > currentSeason) {
                            currentSeason = season
                            if (originalNumber <= maxChapterNumber) seasonOffset = maxChapterNumber
                        }
                        numbers[index] = seasonOffset + originalNumber
                        maxChapterNumber = maxOf(maxChapterNumber, numbers[index])
                    } else {
                        numbers[index] = (numbers.getOrNull(index - 1) ?: 0f) + 0.01f
                    }
                }
            }

            val formatter = DecimalFormat("#.##", DecimalFormatSymbols(Locale.US))
            return episodes.mapIndexed { index, episode ->
                val number = numbers[index]
                SChapter(
                    url = episode.viewerLink,
                    name = buildString {
                        append(Parser.unescapeEntities(episode.title, false))
                        append(" (ch. ").append(formatter.format(number)).append(')')
                        if (episode.hasBgm) append(" ♫")
                    },
                    dateUpload = episode.publishedAt,
                    chapterNumber = number
                )
            }.asReversed()
        }
    }
}
