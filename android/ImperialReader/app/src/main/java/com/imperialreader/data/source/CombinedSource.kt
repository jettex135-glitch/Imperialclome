package com.imperialreader.data.source

import com.imperialreader.App
import com.imperialreader.data.search.SearchConcurrencyController
import com.imperialreader.data.update.LibraryRefreshConcurrencyController
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One title backed by several sources. The manga "url" holds the member list; chapters are merged by
 * chapter number across all members, so you always see the most up-to-date list. When reading, the first
 * member that actually has the chapter (and loads) is used, with automatic fallback to the next one.
 */
class CombinedSource : Source {

    override val id = ID
    override val name = "Combined"
    override val lang = "all"
    override val baseUrl = ""

    data class Member(val sourceId: String, val url: String)
    data class MergedChapter(val key: String, val name: String, val number: Float, val date: Long)

    companion object {
        const val ID = "combined"
        private val gate = Semaphore(8)
        private val cache = ConcurrentHashMap<String, List<Pair<Member, List<SChapter>>>>()
        private val failures = ConcurrentHashMap<String, List<String>>()
        private const val MAX_COMICK_RETRY_AFTER_MS = 300_000L

        fun failureSummary(members: List<Member>): String? =
            failures[encode(members)]?.take(2)?.joinToString("; ")?.take(180)

        fun encode(members: List<Member>): String {
            val arr = JSONArray()
            members.distinct().filter { it.sourceId != ID }.sortedBy { it.sourceId }.forEach {
                arr.put(JSONObject().put("s", it.sourceId).put("u", it.url))
            }
            return arr.toString()
        }

        fun decode(url: String): List<Member> = runCatching {
            val arr = JSONArray(url)
            (0 until arr.length()).map { Member(arr.getJSONObject(it).getString("s"), arr.getJSONObject(it).getString("u")) }
        }.getOrDefault(emptyList())

        /** Fetches every member's chapter list in parallel and remembers failures for actionable UI errors. */
        suspend fun fetchAll(
            members: List<Member>,
            title: String,
            limiter: Semaphore = gate,
            sharedLimiter: SearchConcurrencyController? = null,
            libraryRefreshLimiter: LibraryRefreshConcurrencyController? = null
        ): List<Pair<Member, List<SChapter>>> {
            val outcomes = coroutineScope {
                members.map { m ->
                    async {
                        val source = App.instance.sources.get(m.sourceId)
                        if (source == null) {
                            Triple(m, emptyList(), "Source ${m.sourceId} is unavailable")
                        } else try {
                            suspend fun fetchChapters(): List<SChapter> =
                                if (sharedLimiter != null) {
                                    sharedLimiter.withPermit {
                                        source.getChapters(SManga(url = m.url, title = title))
                                    }
                                } else if (libraryRefreshLimiter != null) {
                                    libraryRefreshLimiter.withSourcePermit(m.sourceId, "chapters") {
                                        source.getChapters(SManga(url = m.url, title = title))
                                    }
                                } else {
                                    limiter.withPermit {
                                        source.getChapters(SManga(url = m.url, title = title))
                                    }
                                }
                            var retry = 0
                            var chapters: List<SChapter>? = null
                            while (chapters == null) {
                                try {
                                    chapters = fetchChapters()
                                } catch (e: HttpStatusException) {
                                    if (m.sourceId != "comick" || e.code != 429 || retry >= 2) throw e
                                    val waitMs = e.retryAfterMs ?: 1_000L
                                    if (waitMs > MAX_COMICK_RETRY_AFTER_MS) throw e
                                    retry++
                                    delay(waitMs)
                                }
                            }
                            Triple(m, checkNotNull(chapters), null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Triple(m, emptyList(), "${source.name}: ${e.message ?: "chapter fetch failed"}")
                        }
                    }
                }.awaitAll()
            }
            val result = outcomes.map { it.first to it.second }.sortedByDescending { it.second.size }
            cache[encode(members)] = result
            failures[encode(members)] = outcomes.mapNotNull { it.third }
            return result
        }

        private fun numberOf(c: SChapter): Float =
            if (c.chapterNumber >= 0f) c.chapterNumber else parseChapterNumber(c.name)

        fun chapterKey(c: SChapter): String {
            val n = numberOf(c)
            return if (n >= 0f) "n" + String.format(Locale.US, "%.2f", n) else "t" + normalizeTitle(c.name)
        }

        fun formatNumber(n: Float): String = if (n % 1f == 0f) n.toInt().toString() else n.toString()

        /** One entry per chapter number, newest first. */
        fun merge(lists: List<Pair<Member, List<SChapter>>>): List<MergedChapter> {
            val byKey = LinkedHashMap<String, MutableList<SChapter>>()
            for ((_, chapters) in lists) for (c in chapters) byKey.getOrPut(chapterKey(c)) { mutableListOf() }.add(c)
            return byKey.map { (key, chs) ->
                val number = chs.map { numberOf(it) }.firstOrNull { it >= 0f } ?: -1f
                MergedChapter(
                    key = key,
                    name = if (number >= 0f) "Chapter ${formatNumber(number)}" else chs.first().name,
                    number = number,
                    date = chs.maxOf { it.dateUpload }
                )
            }.sortedWith(
                compareByDescending<MergedChapter> { it.number >= 0f }
                    .thenByDescending { it.number }
                    .thenByDescending { it.date }
            )
        }

        /** A page on one of the member sites, for the "open in WebView" button. */
        fun webUrl(mangaUrl: String): String? {
            val m = decode(mangaUrl).firstOrNull() ?: return null
            val source = App.instance.sources.get(m.sourceId) ?: return null
            return if (m.url.startsWith("http")) m.url else source.baseUrl.trimEnd('/') + m.url
        }
    }

    override suspend fun getPopular(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getLatest(page: Int) = MangasPage(emptyList(), false)
    override suspend fun search(query: String, page: Int) = MangasPage(emptyList(), false)

    override suspend fun getMangaDetails(manga: SManga): SManga {
        return getMangaDetails(manga, refreshLimiter = null)
    }

    suspend fun getMangaDetailsForLibraryRefresh(
        manga: SManga,
        refreshLimiter: LibraryRefreshConcurrencyController
    ): SManga = getMangaDetails(manga, refreshLimiter)

    private suspend fun getMangaDetails(
        manga: SManga,
        refreshLimiter: LibraryRefreshConcurrencyController?
    ): SManga {
        for (m in decode(manga.url)) {
            val source = App.instance.sources.get(m.sourceId) ?: continue
            try {
                val d = if (refreshLimiter == null) {
                    source.getMangaDetails(SManga(url = m.url, title = manga.title))
                } else {
                    refreshLimiter.withSourcePermit(m.sourceId, "details") {
                        source.getMangaDetails(SManga(url = m.url, title = manga.title))
                    }
                }
                if (!d.thumbnailUrl.isNullOrBlank()) manga.thumbnailUrl = d.thumbnailUrl
                manga.description = d.description ?: manga.description
                manga.author = d.author ?: manga.author
                manga.artist = d.artist ?: manga.artist
                manga.genre = d.genre ?: manga.genre
                if (d.status != 0) manga.status = d.status
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // try the next source
            }
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        return getChapters(manga, refreshLimiter = null)
    }

    suspend fun getChaptersForLibraryRefresh(
        manga: SManga,
        refreshLimiter: LibraryRefreshConcurrencyController
    ): List<SChapter> = getChapters(manga, refreshLimiter)

    private suspend fun getChapters(
        manga: SManga,
        refreshLimiter: LibraryRefreshConcurrencyController?
    ): List<SChapter> {
        val members = decode(manga.url)
        val lists = fetchAll(members, manga.title, libraryRefreshLimiter = refreshLimiter)
        if (lists.all { it.second.isEmpty() }) {
            val why = failureSummary(members)
            throw IOException(
                if (why.isNullOrBlank()) "None of the sources returned chapters right now"
                else "Could not fetch chapters: $why"
            )
        }
        return merge(lists).map {
            SChapter(url = it.key, name = it.name, dateUpload = it.date, chapterNumber = it.number)
        }
    }

    override suspend fun getPages(chapter: SChapter): List<String> =
        throw IOException("Combined titles load pages through loadPages()")

    override suspend fun loadPages(manga: SManga, chapter: SChapter): ChapterPages {
        val members = decode(manga.url)
        val lists = cache[encode(members)] ?: fetchAll(members, manga.title)
        var lastError: Exception? = null
        for ((member, chapters) in lists) {
            val source = App.instance.sources.get(member.sourceId) ?: continue
            for (option in chapters.filter { chapterKey(it) == chapter.url }) {
                try {
                    val pages = source.loadPages(SManga(url = member.url, title = manga.title), option)
                    if (pages.urls.isNotEmpty()) return pages
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw lastError ?: IOException("No source currently has this chapter")
    }

    override fun imageHeaders(): Map<String, String> = emptyMap()
}
