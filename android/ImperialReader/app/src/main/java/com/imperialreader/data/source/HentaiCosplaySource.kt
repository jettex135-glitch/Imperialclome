package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Hentai Cosplay gallery source, adapted from Mihon's site-specific extension. */
class HentaiCosplaySource : HttpSource() {
    override val id = "hentai_cosplay"
    override val name = "Hentai Cosplay"
    override val lang = "all"
    override val baseUrl = "https://hentai-cosplay-xxx.com"
    override val nsfw = true

    private val postedDates = ConcurrentHashMap<String, String>()

    override suspend fun getPopular(page: Int): MangasPage {
        if (page < 1) return MangasPage(emptyList(), false)
        return listing("$baseUrl/ranking/page/$page/")
    }

    override suspend fun getLatest(page: Int): MangasPage {
        if (page < 1) return MangasPage(emptyList(), false)
        return listing("$baseUrl/search/page/$page/")
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        if (page < 1) return MangasPage(emptyList(), false)
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/keyword/${enc(query.trim())}/page/$page/"
        } else {
            "$baseUrl/search/page/$page/"
        }
        return listing(url)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val document = Jsoup.parse(getString(absoluteUrl(manga.url)), absoluteUrl(manga.url))
        val tags = document.select("#detail_tag a[href*=/tag/]").eachText()
        manga.genre = tags.joinToString(", ").ifBlank { null }
        manga.status = STATUS_COMPLETED
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val storyUrl = manga.url.replace("/image/", "/story/")
        if (storyUrl == manga.url) throw IOException("Hentai Cosplay returned an invalid gallery URL")
        val date = postedDates[manga.url]?.let(::parseDate)
        return listOf(SChapter(url = storyUrl, name = "Gallery", dateUpload = date ?: 0L))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val url = absoluteUrl(chapter.url)
        val pages = parseGalleryImages(getString(url), url)
        if (pages.isEmpty()) throw IOException("Hentai Cosplay returned no gallery images")
        return pages
    }

    private suspend fun listing(url: String): MangasPage {
        val result = parseListingHtml(getString(url), url)
        postedDates.putAll(result.postedDates)
        return result.page
    }

    companion object {
        private const val STATUS_COMPLETED = 2
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ENGLISH)

        internal data class ListingResult(
            val page: MangasPage,
            val postedDates: Map<String, String>
        )

        internal fun parseListingHtml(html: String, pageUrl: String): ListingResult {
            val document = Jsoup.parse(html, pageUrl)
            val mobile = document.selectFirst("div.image-list-item") == null
            val entries = if (mobile) parseMobileListing(document) else parseDesktopListing(document)
            val hasNextPage = if (mobile) {
                document.selectFirst("a.paginator_page[rel=next]") != null
            } else {
                document.selectFirst("div.wp-pagenavi > a[rel=next]") != null
            }
            return ListingResult(
                page = MangasPage(entries.map { it.first }, hasNextPage),
                postedDates = entries.mapNotNull { (manga, date) ->
                    date?.let { manga.url to it }
                }.toMap()
            )
        }

        internal fun parseGalleryImages(html: String, pageUrl: String): List<String> =
            Jsoup.parse(html, pageUrl)
                .select("amp-img[src*=upload]:not(.related-thumbnail)")
                .mapNotNull { image ->
                    val url = image.absUrl("src").replaceFirst("http://", "https://")
                    url.takeIf { it.startsWith("https://") }
                }
                .distinct()

        private fun parseMobileListing(document: Document): List<Pair<SManga, String?>> =
            document.select("#entry_list > li > a[href*=/image/]")
                .mapNotNull { element ->
                    val title = element.selectFirst("span:not(.posted)")?.text()?.trim()
                        ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val manga = SManga(
                        url = element.absUrl("href"),
                        title = title,
                        thumbnailUrl = element.selectFirst("img")?.absUrl("src")
                            ?.replaceFirst("http://", "https://")
                            ?.takeIf { it.startsWith("https://") }
                    )
                    manga to element.selectFirst("span.posted")?.text()?.trim()
                }

        private fun parseDesktopListing(document: Document): List<Pair<SManga, String?>> =
            document.select("div.image-list-item:has(a[href*=/image/])")
                .mapNotNull { item ->
                    val link = item.selectFirst("a[href*=/image/]") ?: return@mapNotNull null
                    val title = item.select(".image-list-item-title").text().trim()
                        .takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val manga = SManga(
                        url = link.absUrl("href"),
                        title = title,
                        thumbnailUrl = item.selectFirst("img")?.absUrl("src")
                            ?.replaceFirst("http://", "https://")
                            ?.takeIf { it.startsWith("https://") }
                    )
                    manga to item.selectFirst(".image-list-item-regist-date")?.text()?.trim()
                }

        private fun parseDate(value: String): Long? =
            runCatching {
                LocalDate.parse(value, DATE_FORMAT)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrNull()
    }
}
