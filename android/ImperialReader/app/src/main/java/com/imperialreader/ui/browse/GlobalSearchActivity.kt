package com.imperialreader.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.book.BookItem
import com.imperialreader.data.book.BookSource
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.repo.MangaMigrationManager
import com.imperialreader.data.repo.MangaMigrationOptions
import com.imperialreader.data.source.CombinedSource
import com.imperialreader.data.source.MangasPage
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.normalizeTitle
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.source.HttpStatusException
import com.imperialreader.ui.books.BookActions
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.LibraryActions
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.common.RowAdapter
import com.imperialreader.ui.common.RowItem
import com.imperialreader.ui.common.StarSpriteView
import com.imperialreader.ui.common.loadCover
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.ui.webview.WebViewActivity
import com.imperialreader.util.app
import com.imperialreader.util.parseChapterNumber
import com.imperialreader.util.relativeTime
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

const val KIND_SOURCE = 0
const val KIND_LIBRARY = 1
const val KIND_EPUB = 2

enum class SourceSearchStatus {
    PENDING,
    SEARCHING,
    RETRYING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    TIMED_OUT,
    FAILED
}

data class SourceResult(
    val sourceId: String,
    val sourceName: String,
    val cards: List<CardItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val chapterCount: Int = -1,
    val latest: Long = 0L,
    val kind: Int = KIND_SOURCE,
    val status: SourceSearchStatus = SourceSearchStatus.PENDING,
    val resultCount: Int = cards.size,
    val challengeUrl: String? = null
)

private data class SourceSearchOperation(
    val maxPages: Int,
    val fetchPage: suspend (Int) -> MangasPage
)

private data class SourceSearchBatch(
    val mangas: List<SManga>,
    val error: String?,
    val nextPage: Int?,
    val status: SourceSearchStatus = SourceSearchStatus.COMPLETED
)

private class SearchDeadlineBudget(var sourceDeadlineElapsedRealtimeMs: Long? = null)

private class RequestTimedOutException(message: String, cause: Throwable? = null) : IOException(message, cause)
private class SourceSearchDeadlineException(message: String, cause: Throwable? = null) : IOException(message, cause)
private class OverallSearchDeadlineException(message: String, cause: Throwable? = null) : IOException(message, cause)

private class SourceSearchWork(
    val source: com.imperialreader.data.source.Source,
    val query: String,
    val combine: Boolean,
    val generation: Int
) {
    val operations = LinkedHashMap<String, SourceSearchOperation>()
    val results = LinkedHashMap<String, List<SManga>>()
    val operationErrors = LinkedHashMap<String, String>()
    val challengeUrls = LinkedHashMap<String, String>()
    val operationStates = LinkedHashMap<String, SourceSearchStatus>()
    val nextPages = HashMap<String, Int>()
    val metadataMatches = LinkedHashMap<String, SManga>()
    val metadataChecked = HashSet<String>()
    val metadataFailures = LinkedHashMap<String, SManga>()
    val deadlineBudget = SearchDeadlineBudget()
    var metadataTimedOut = false
}

private data class GroupMember(val sourceId: String, val manga: SManga)

private data class GroupStats(
    val count: Int,
    val latestNumber: Float,
    val latestDate: Long,
    val memberCounts: Map<String, Int>,
    val error: String? = null
)

private data class PendingMigrationConfirmation(
    val from: MangaEntity,
    val targetSourceId: String,
    val target: SManga,
    val combinedMembers: List<CombinedSource.Member>? = null
)

/** One title as found on one or more sources. */
private data class TitleGroup(
    val key: String,
    val title: String,
    val members: List<GroupMember>,
    val cover: String?,
    val coverHeaders: Map<String, String>,
    val stats: GroupStats?
)

/**
 * Searches your library, EPUB catalogs and every enabled source.
 * Default view: each TITLE once, with the chapters of all sources merged (most up-to-date count).
 * Untick "Combine" to see the per-source rows instead.
 */
class GlobalSearchActivity : com.imperialreader.ui.theme.ThemedAppCompatActivity() {

    companion object {
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_MIGRATION_FROM_ID = "migration_from_id"
        private const val EXTRA_MIGRATION_SOURCE_IDS = "migration_source_ids"
        private const val EXTRA_MIGRATION_COMBINED = "migration_combined"
        private const val EXTRA_MIGRATION_EXTENSIONS = "migration_extensions"
        private const val REQUEST_SHOW_MIGRATION_ENTRY = 1
        private const val MAX_SOURCE_SEARCH_PAGES = 20
        private const val MAX_METADATA_SEARCH_PAGES = 5
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 6_000L)

        fun intent(ctx: Context, query: String): Intent =
            Intent(ctx, GlobalSearchActivity::class.java).putExtra(EXTRA_QUERY, query)

        fun migrationIntent(ctx: Context, manga: MangaEntity, sourceIds: Set<String>): Intent =
            Intent(ctx, GlobalSearchActivity::class.java)
                .putExtra(EXTRA_QUERY, manga.title)
                .putExtra(EXTRA_MIGRATION_FROM_ID, manga.id)
                .putStringArrayListExtra(EXTRA_MIGRATION_SOURCE_IDS, ArrayList(sourceIds))

        fun combinedMigrationIntent(ctx: Context, manga: MangaEntity, sourceIds: Set<String>): Intent =
            migrationIntent(ctx, manga, sourceIds).putExtra(EXTRA_MIGRATION_COMBINED, true)

        fun bookMigrationIntent(ctx: Context, manga: MangaEntity, sourceIds: Set<String>): Intent =
            migrationIntent(ctx, manga, sourceIds).putExtra(EXTRA_MIGRATION_EXTENSIONS, true)
    }

    private var migrationFromId = -1L
    private var migrationSourceIds = emptySet<String>()
    private val migrationMode get() = migrationFromId >= 0L
    private var migrationCombinedMode = false
    private var migrationExtensions = false
    private val sections = MutableStateFlow<List<SourceResult>>(emptyList())
    private val groups = MutableStateFlow<List<TitleGroup>>(emptyList())
    private val tick = MutableStateFlow(0)
    private val hits = LinkedHashMap<String, List<SManga>>()
    private val searchResultsLock = Any()
    private val sourceSearchWork = LinkedHashMap<String, SourceSearchWork>()
    private val stats = HashMap<String, GroupStats>()
    private var currentQuery = ""
    private var genreFilter = ""
    private var artistFilter = ""
    private var searchJob: Job? = null
    private var retryJob: Job? = null
    private var initialSearchInProgress = false
    private var retryInProgress = false
    private var searchGeneration = 0
    private var overallDeadlineElapsedRealtimeMs = 0L
    private var searchDeadlineMessage: String? = null
    private var pendingMigrationConfirmation: PendingMigrationConfirmation? = null

    private lateinit var emptyText: TextView
    private lateinit var searchProgress: TextView
    private lateinit var retryFailedButton: MaterialButton
    private lateinit var openChallengeButton: MaterialButton
    private lateinit var sprite: StarSpriteView
    private lateinit var sectionAdapter: SectionAdapter
    private lateinit var titleAdapter: RowAdapter

    @Deprecated("Use the activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SHOW_MIGRATION_ENTRY) return
        val pending = pendingMigrationConfirmation ?: return
        pendingMigrationConfirmation = null
        showMigrationConfirmation(
            pending.from,
            pending.targetSourceId,
            pending.target,
            pending.combinedMembers
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_search)

        migrationFromId = intent.getLongExtra(EXTRA_MIGRATION_FROM_ID, -1L)
        migrationSourceIds = intent.getStringArrayListExtra(EXTRA_MIGRATION_SOURCE_IDS)?.toSet().orEmpty()
        migrationCombinedMode = intent.getBooleanExtra(EXTRA_MIGRATION_COMBINED, false)
        migrationExtensions = intent.getBooleanExtra(EXTRA_MIGRATION_EXTENSIONS, false)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        if (migrationMode) {
            toolbar.title = if (migrationCombinedMode) "Create combined entry" else "Migrate"
        } else {
            toolbar.inflateMenu(R.menu.global_search_menu)
            toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_search_filters) {
                    showSearchFilters()
                    true
                } else false
            }
        }
        val input = findViewById<EditText>(R.id.queryInput)
        if (migrationMode) {
            input.hint = "Search selected sources"
            findViewById<View>(R.id.checkCombine).visibility = View.GONE
            findViewById<View>(R.id.checkOnlyResults).visibility = View.GONE
            (findViewById<View>(R.id.checkCombine).parent as? View)?.visibility = View.GONE
        }
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        emptyText = findViewById(R.id.emptyText)
        searchProgress = findViewById(R.id.searchProgress)
        retryFailedButton = findViewById(R.id.retryFailedSources)
        openChallengeButton = findViewById(R.id.openChallengeSource)
        sprite = findViewById(R.id.searchSprite)
        retryFailedButton.setOnClickListener { retryFailedSources() }
        openChallengeButton.setOnClickListener {
            val url = sourceSearchWork.values.firstNotNullOfOrNull { it.challengeUrls.values.firstOrNull() }
            if (url == null) {
                toast("No unresolved Cloudflare challenge")
            } else {
                startActivity(WebViewActivity.intent(this, url))
            }
        }
        if (migrationMode) retryFailedButton.text = "Retry unavailable sources"

        val combine = findViewById<MaterialCheckBox>(R.id.checkCombine)
        val only = findViewById<MaterialCheckBox>(R.id.checkOnlyResults)
        combine.isChecked = app.prefs.searchCombine
        only.isChecked = app.prefs.searchOnlyResults
        combine.setOnCheckedChangeListener { _, checked ->
            app.prefs.searchCombine = checked
            tick.value = tick.value + 1
        }
        only.setOnCheckedChangeListener { _, checked ->
            app.prefs.searchOnlyResults = checked
            tick.value = tick.value + 1
        }

        sectionAdapter = SectionAdapter { section, card -> onCardClick(section, card) }
        titleAdapter = RowAdapter(
            actionIcon = R.drawable.ic_add,
            onClick = { row ->
                val group = row.tag as TitleGroup
                if (migrationCombinedMode) openMigrationGroup(group)
                else openGroup(group, addToLibrary = false)
            },
            onLongClick = { row -> showMembers(row.tag as TitleGroup) },
            onAction = { row ->
                val group = row.tag as TitleGroup
                if (migrationCombinedMode) openMigrationGroup(group)
                else openGroup(group, addToLibrary = true)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ConcatAdapter(sectionAdapter, titleAdapter)
        refreshSearchProgress()

        lifecycleScope.launch {
            combine(sections, groups, tick) { s, g, _ -> s to g }.collect { (s, g) -> render(s, g) }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                beginSearch(input.text.toString().trim())
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                true
            } else false
        }

        val initial = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        if (initial.isNotBlank()) {
            input.setText(initial)
            if (migrationMode) runSearch(initial, extensions = migrationExtensions, selectedIds = migrationSourceIds)
            else beginSearch(initial)
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun render(all: List<SourceResult>, grouped: List<TitleGroup>) {
        val combine = migrationCombinedMode || (!migrationMode && app.prefs.searchCombine)
        val onlyResults = !migrationMode && app.prefs.searchOnlyResults

        val extra = all.asSequence()
            .filter { it.kind != KIND_SOURCE }
            .filter { !onlyResults || it.cards.isNotEmpty() }
            .toList()
        val sourceResults = all.filter { it.kind == KIND_SOURCE }
        val visibleSources = if (onlyResults) {
            sourceResults.filter { it.cards.isNotEmpty() }
        } else {
            sourceResults
        }
        val sourceSections = sortSources(visibleSources).map {
            if (combine) it.copy(cards = emptyList()) else it
        }
        sectionAdapter.submitList(extra + sourceSections)

        val rows = if (combine) visibleGroups(grouped).map { toRow(it) } else emptyList()
        titleAdapter.submitList(rows)

        val searching = all.any { it.loading }
        val anything = extra.isNotEmpty() || rows.isNotEmpty() ||
            sourceSections.any { it.resultCount > 0 || it.error != null || it.loading }
        emptyText.visibility = if (all.isNotEmpty() && !searching && !anything) View.VISIBLE else View.GONE
        val errors = all.count { it.error != null }
        val failureSummary = when {
            onlyResults && errors > 0 -> "No results • $errors source(s) incomplete; see search status above"
            errors > 0 -> "No results • $errors source(s) incomplete"
            else -> "No results"
        }
        emptyText.text = failureSummary
    }

    private fun sortSources(list: List<SourceResult>): List<SourceResult> {
        val withResults = list.filter { it.cards.isNotEmpty() }
            .sortedWith(compareByDescending<SourceResult> { it.chapterCount }.thenByDescending { it.latest })
        val loading = list.filter { it.cards.isEmpty() && it.loading }
        val empty = list.filter { it.cards.isEmpty() && !it.loading }
        return withResults + loading + empty
    }

    private fun relevance(g: TitleGroup): Int {
        val q = normalizeTitle(currentQuery)
        return when {
            q.isBlank() -> 3
            g.key == q -> 0
            g.key.contains(q) -> 1
            q.contains(g.key) -> 2
            else -> 3
        }
    }

    private fun visibleGroups(list: List<TitleGroup>): List<TitleGroup> {
        val sorted = list.sortedWith(
            compareBy<TitleGroup> { relevance(it) }
                .thenByDescending { it.stats?.count ?: -1 }
                .thenByDescending { it.members.size }
                .thenByDescending { it.stats?.latestDate ?: 0L }
        )
        val close = sorted.filter { group ->
            relevance(group) <= 2 || group.members.any {
                it.manga.genre?.contains(currentQuery, ignoreCase = true) == true
            }
        }
        return (if (currentQuery.isBlank()) sorted else close).take(30)
    }

    private fun toRow(g: TitleGroup): RowItem {
        val st = g.stats
        val sourcesLabel = "${g.members.size} source" + if (g.members.size == 1) "" else "s"
        val subtitle = when {
            st == null -> "$sourcesLabel • checking chapters…"
            st.count == 0 -> "$sourcesLabel • ${st.error ?: "no chapters found"}"
            else -> listOfNotNull(
                "${st.count} chapters",
                if (st.latestNumber >= 0f) "latest Ch. ${CombinedSource.formatNumber(st.latestNumber)}" else null,
                if (st.latestDate > 0) "updated ${relativeTime(st.latestDate)}" else null,
                sourcesLabel
            ).joinToString(" • ")
        }
        return RowItem(
            key = "g${g.key}", title = g.title, subtitle = subtitle, cover = g.cover,
            headers = g.coverHeaders, tag = g
        )
    }

    private fun showSearchFilters() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
        }
        val genre = EditText(this).apply {
            hint = "Genre or tag (optional)"
            setSingleLine(true)
            setText(genreFilter)
        }
        val artist = EditText(this).apply {
            hint = "Artist (optional)"
            setSingleLine(true)
            setText(artistFilter)
        }
        fields.addView(genre)
        fields.addView(artist)
        MaterialAlertDialogBuilder(this)
            .setTitle("Search filters")
            .setView(fields)
            .setPositiveButton("Apply") { _, _ ->
                genreFilter = genre.text.toString().trim()
                artistFilter = artist.text.toString().trim()
                beginSearch(findViewById<EditText>(R.id.queryInput).text.toString().trim())
            }
            .setNeutralButton("Clear") { _, _ ->
                genreFilter = ""
                artistFilter = ""
                beginSearch(findViewById<EditText>(R.id.queryInput).text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasSearchFilters() = genreFilter.isNotBlank() || artistFilter.isNotBlank()

    private fun matchesFilters(manga: com.imperialreader.data.db.MangaEntity) =
        matchesSearchFilters(manga.artist, manga.genre)

    private fun matchesFilters(manga: SManga) =
        matchesSearchFilters(manga.artist, manga.genre)

    private fun matchesFilters(item: BookItem) =
        matchesSearchFilters(item.artist, item.genre)

    private fun matchesSearchFilters(artist: String?, genre: String?): Boolean =
        (genreFilter.isBlank() || genre?.contains(genreFilter, ignoreCase = true) == true) &&
            (artistFilter.isBlank() || artist?.contains(artistFilter, ignoreCase = true) == true)

    private fun searchRelevance(title: String, query: String): Int {
        val normalized = normalizeTitle(title)
        val q = normalizeTitle(query)
        return when {
            q.isBlank() -> 1
            normalized == q -> 0
            normalized.contains(q) -> 1
            else -> 2
        }
    }

    private fun isAuthorQuery(query: String): Boolean =
        query.trim().split(Regex("""\s+""")).count { word -> word.any(Char::isLetter) } >= 2

    private suspend fun collectSourcePages(
        work: SourceSearchWork,
        operation: String,
        startPage: Int,
        pageCount: Int,
        fetchPage: suspend (Int) -> MangasPage,
        onPage: (List<SManga>) -> Unit
    ): SourceSearchBatch {
        val found = LinkedHashMap<String, SManga>()
        work.results[operation].orEmpty().forEach { found[it.url] = it }
        val endPage = startPage + pageCount - 1
        var page = startPage
        while (page <= endPage) {
            val result = try {
                searchRequest(work, operation) { fetchPage(page) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logSearchFailure(work.source.id, operation, e)
                    if (e is CloudflareException) work.challengeUrls[operation] = e.url
                    return SourceSearchBatch(
                    found.values.toList(),
                    "Page $page: ${e.message ?: failureCategory(e)}",
                    page,
                    when {
                        e is OverallSearchDeadlineException || e is SourceSearchDeadlineException ->
                            SourceSearchStatus.TIMED_OUT
                        found.isNotEmpty() -> SourceSearchStatus.COMPLETED_WITH_ERRORS
                        else -> SourceSearchStatus.FAILED
                    }
                )
            }
            result.mangas.forEach { manga ->
                if (manga.url.isNotBlank()) found.putIfAbsent(manga.url, manga)
            }
            onPage(found.values.toList())
            if (result.mangas.isEmpty() || !result.hasNextPage) {
                return SourceSearchBatch(found.values.toList(), null, null)
            }
            page++
        }
        return SourceSearchBatch(
            found.values.toList(),
            "More results may be available after page $endPage",
            endPage + 1
        )
    }

    private suspend fun <T> searchRequest(
        work: SourceSearchWork,
        operation: String,
        request: suspend () -> T
    ): T {
        var retry = 0
        while (true) {
            currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                val overallRemaining = overallDeadlineElapsedRealtimeMs - now
                if (overallRemaining <= 0L) throw OverallSearchDeadlineException("Overall search deadline exceeded")
                try {
                    val result = app.searchConcurrency.withPermit(
                        onAcquired = {
                            if (work.deadlineBudget.sourceDeadlineElapsedRealtimeMs == null) {
                                work.deadlineBudget.sourceDeadlineElapsedRealtimeMs =
                                    SystemClock.elapsedRealtime() + app.prefs.globalSearchSourceDeadlineMs
                            }
                            setOperationStatus(work, operation, SourceSearchStatus.SEARCHING)
                        }
                    ) {
                        val acquiredAt = SystemClock.elapsedRealtime()
                        val sourceDeadline = work.deadlineBudget.sourceDeadlineElapsedRealtimeMs
                            ?: (acquiredAt + app.prefs.globalSearchSourceDeadlineMs).also {
                                work.deadlineBudget.sourceDeadlineElapsedRealtimeMs = it
                            }
                        val sourceRemaining = sourceDeadline - acquiredAt
                        val remainingOverall = overallDeadlineElapsedRealtimeMs - acquiredAt
                        if (sourceRemaining <= 0L) throw SourceSearchDeadlineException("Source search deadline exceeded")
                        if (remainingOverall <= 0L) throw OverallSearchDeadlineException("Overall search deadline exceeded")
                        try {
                            withTimeout(minOf(app.prefs.globalSearchRequestTimeoutMs, sourceRemaining, remainingOverall)) {
                                request()
                            }
                        } catch (e: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            val timedOutAt = SystemClock.elapsedRealtime()
                            when {
                                timedOutAt >= overallDeadlineElapsedRealtimeMs ->
                                    throw OverallSearchDeadlineException("Overall search deadline exceeded", e)
                                timedOutAt >= sourceDeadline ->
                                    throw SourceSearchDeadlineException("${work.source.name} source deadline exceeded", e)
                                else -> throw RequestTimedOutException("${work.source.name} request timed out", e)
                            }
                        }
                    }
                    setOperationStatus(work, operation, SourceSearchStatus.PENDING)
                    return result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (e is OverallSearchDeadlineException || e is SourceSearchDeadlineException) throw e
                    if (!isTransientFailure(e) || retry >= RETRY_DELAYS_MS.size) throw e
                    scheduleRetry(work, operation, retry++, e)
                }
        }
    }

    private suspend fun scheduleRetry(
        work: SourceSearchWork,
        operation: String,
        retry: Int,
        failure: Throwable? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        val remaining = minOf(
            overallDeadlineElapsedRealtimeMs,
            work.deadlineBudget.sourceDeadlineElapsedRealtimeMs ?: overallDeadlineElapsedRealtimeMs
        ) - now
        if (remaining <= 0L) {
            if (now >= overallDeadlineElapsedRealtimeMs) throw OverallSearchDeadlineException("Overall search deadline exceeded")
            throw SourceSearchDeadlineException("${work.source.name} source deadline exceeded")
        }
        val retryAfter = (failure as? HttpStatusException)?.retryAfterMs
        val baseDelay = retryAfter ?: RETRY_DELAYS_MS[retry]
        val jitter = if (retryAfter != null) 0L else (baseDelay * 0.2 * kotlin.random.Random.nextDouble()).toLong()
        setOperationStatus(work, operation, SourceSearchStatus.RETRYING)
        delay(minOf(baseDelay + jitter, remaining))
    }

    private fun isTransientFailure(error: Throwable): Boolean = when (error) {
        is SSLException, is CloudflareException,
        is SourceSearchDeadlineException, is OverallSearchDeadlineException -> false
        is HttpStatusException -> isTransientHttpCode(error.code)
        is RequestTimedOutException, is UnknownHostException, is SocketTimeoutException, is ConnectException -> true
        is SocketException -> error !is SSLException
        else -> httpStatusCode(error)?.let(::isTransientHttpCode) ?: false
    }

    private fun failureCategory(error: Throwable): String = when (error) {
        is HttpStatusException -> "HTTP ${error.code}"
        is OverallSearchDeadlineException -> "overall search deadline"
        is SourceSearchDeadlineException -> "source search deadline"
        is RequestTimedOutException -> "request timeout"
        is SSLException -> "TLS certificate or handshake error"
        is UnknownHostException -> "DNS resolution error"
        is SocketTimeoutException, is TimeoutCancellationException -> "connection timeout"
        is ConnectException, is SocketException -> "connection error"
        is CloudflareException -> "website protection challenge"
        else -> when {
            httpStatusCode(error) != null -> "HTTP ${httpStatusCode(error)}"
            error is IOException -> "network or source error"
            else -> "source parsing or search error"
        }
    }

    private fun isTransientHttpCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    private fun httpStatusCode(error: Throwable): Int? =
        Regex("""\bHTTP\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
            .find(error.message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun logSearchFailure(sourceId: String, operation: String, error: Throwable) {
        Log.w("GlobalSearch", "$sourceId $operation failed (${failureCategory(error)}): ${error.message}", error)
    }

    // ---------------------------------------------------------------- searching

    private fun beginSearch(q: String) {
        if (migrationMode) {
            runSearch(q, extensions = migrationExtensions, selectedIds = migrationSourceIds)
            return
        }
        if (q.isBlank() && !hasSearchFilters()) {
            runSearch(q, app.prefs.globalSearchExtensions, emptySet())
            return
        }

        var selectedTarget = if (app.prefs.globalSearchExtensions) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Search in")
            .setSingleChoiceItems(arrayOf("Sources", "Extensions"), selectedTarget) { _, which ->
                selectedTarget = which
            }
            .setPositiveButton("Choose") { _, _ ->
                val extensions = selectedTarget == 1
                app.prefs.globalSearchExtensions = extensions
                showSearchCatalogs(q, extensions)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSearchCatalogs(q: String, extensions: Boolean) {
        val catalogs = if (extensions) {
            BookSources.all.filter { it.inGlobalSearch }.map { it.id to it.name }
        } else {
            app.sources.enabled().map { it.id to it.name }
        }
        if (catalogs.isEmpty()) {
            toast(if (extensions) "No searchable extensions available" else "No enabled sources available")
            return
        }

        val savedSelection = (if (extensions) {
            app.prefs.globalSearchExtensionIds
        } else {
            app.prefs.globalSearchSourceIds
        }).intersect(catalogs.mapTo(HashSet()) { it.first })
        val selected = catalogs.map { it.first in savedSelection }.toBooleanArray()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        content.addView(TextView(this).apply {
            text = "Choose which ${if (extensions) "extensions" else "sources"} to query."
            textSize = 14f
            setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@GlobalSearchActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        val filterInput = EditText(this).apply {
            hint = "Filter ${if (extensions) "extensions" else "sources"}"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(12), 0, dp(12), 0)
        }
        val filterParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            topMargin = dp(12)
        }
        content.addView(filterInput, filterParams)

        val selectionCount = TextView(this).apply {
            textSize = 12f
            setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@GlobalSearchActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(4), dp(4), 0, dp(4))
        }
        val selectAll = MaterialCheckBox(this).apply {
            text = "Select all"
            textSize = 14f
            setPadding(0, dp(2), 0, dp(2))
        }
        content.addView(selectAll)
        content.addView(selectionCount)

        val listContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val listScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                listContent,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        content.addView(
            listScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(300)
            ).apply { topMargin = dp(4) }
        )

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        var searchButton: android.widget.Button? = null
        fun refreshSelectionControls() {
            val count = selected.count { it }
            selectionCount.text = "$count of ${catalogs.size} selected"
            selectAll.isChecked = count == catalogs.size
            searchButton?.isEnabled = count > 0
        }

        val rowChecks = catalogs.mapIndexed { index, catalog ->
            MaterialCheckBox(this).apply {
                text = catalog.second
                textSize = 14f
                isChecked = selected[index]
                setPadding(dp(4), dp(2), dp(4), dp(2))
                setOnCheckedChangeListener { _, checked ->
                    selected[index] = checked
                    refreshSelectionControls()
                }
            }.also { check ->
                listContent.addView(
                    check,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(2) }
                )
            }
        }

        filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val filter = s?.toString()?.trim().orEmpty()
                catalogs.forEachIndexed { index, catalog ->
                    rowChecks[index].visibility =
                        if (catalog.second.contains(filter, ignoreCase = true)) View.VISIBLE else View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        selectAll.setOnClickListener {
            val shouldSelectAll = selected.any { !it }
            selected.indices.forEach { selected[it] = shouldSelectAll }
            rowChecks.forEach { it.isChecked = shouldSelectAll }
            refreshSelectionControls()
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (extensions) "Choose extensions" else "Choose sources")
            .setView(content)
            .setPositiveButton("Search", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            searchButton = button
            refreshSelectionControls()
            button.setOnClickListener {
                val selectedIds = catalogs.filterIndexed { index, _ -> selected[index] }
                    .mapTo(LinkedHashSet()) { it.first }
                if (extensions) {
                    app.prefs.globalSearchExtensionIds = selectedIds
                } else {
                    app.prefs.globalSearchSourceIds = selectedIds
                }
                dialog.dismiss()
                runSearch(q, extensions, selectedIds)
            }
        }
        dialog.show()
    }

    private fun runSearch(q: String, extensions: Boolean, selectedIds: Set<String>) {
        if (q.isBlank() && !hasSearchFilters()) {
            searchJob?.cancel()
            retryJob?.cancel()
            searchGeneration++
            initialSearchInProgress = false
            retryInProgress = false
            searchDeadlineMessage = null
            sourceSearchWork.clear()
            sections.value = emptyList()
            groups.value = emptyList()
            synchronized(searchResultsLock) {
                hits.clear()
                stats.clear()
            }
            sprite.stop()
            refreshSearchProgress()
            return
        }
        searchJob?.cancel()
        retryJob?.cancel()
        retryInProgress = false
        val generation = ++searchGeneration
        currentQuery = q
        synchronized(searchResultsLock) {
            hits.clear()
            stats.clear()
        }
        sourceSearchWork.clear()
        groups.value = emptyList()
        val combine = migrationCombinedMode || app.prefs.searchCombine
        val overallDeadlineMs = app.prefs.globalSearchOverallDeadlineMs
        overallDeadlineElapsedRealtimeMs = SystemClock.elapsedRealtime() + overallDeadlineMs
        searchDeadlineMessage = null

        initialSearchInProgress = true
        searchJob = lifecycleScope.launch {
            sprite.start()
            try {
                withTimeout(overallDeadlineMs) {
                    searchAll(q, combine, extensions, selectedIds, generation)
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                markSearchTimedOut(generation, "Overall search deadline reached; partial results were kept.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "Search failed: ${e.message ?: "unexpected error"}"
                if (sections.value.isEmpty()) {
                    sections.value = listOf(
                        SourceResult(
                            sourceId = "search-error",
                            sourceName = "Global Search",
                            loading = false,
                            error = message,
                            status = SourceSearchStatus.FAILED
                        )
                    )
                } else {
                    sections.update { results ->
                        results.map { result ->
                            if (result.loading) {
                                result.copy(loading = false, error = message, status = SourceSearchStatus.FAILED)
                            } else result
                        }
                    }
                }
            } finally {
                if (generation == searchGeneration) {
                    initialSearchInProgress = false
                    sprite.stop()
                    refreshSearchProgress()
                }
            }
        }
    }

    private fun markSearchTimedOut(generation: Int, message: String) {
        if (generation != searchGeneration) return
        searchDeadlineMessage = message
        sourceSearchWork.values.forEach { work ->
            work.operationStates.toMap().forEach { (operation, status) ->
                if (status in setOf(
                        SourceSearchStatus.PENDING,
                        SourceSearchStatus.SEARCHING,
                        SourceSearchStatus.RETRYING
                    )
                ) {
                    work.operationErrors[operation] = message
                    work.operationStates[operation] = SourceSearchStatus.TIMED_OUT
                }
            }
            publishSourceStatus(work)
        }
        update("epub") {
            if (!it.loading) it
            else it.copy(
                loading = false,
                error = listOfNotNull(it.error, message).distinct().joinToString("\n")
            )
        }
        refreshSearchProgress()
    }

    private suspend fun searchAll(
        q: String,
        combine: Boolean,
        extensions: Boolean,
        selectedIds: Set<String>,
        generation: Int
    ) {
        searchProgress.visibility = if (extensions) View.GONE else View.VISIBLE
        retryFailedButton.visibility = if (extensions) View.GONE else View.VISIBLE
        coroutineScope {
            val initial = mutableListOf<SourceResult>()
            if (!migrationMode) {
                val libraryMatches = if (q.isBlank()) {
                    app.db.mangaDao().getFavoritesList()
                } else {
                    val titleMatches = app.db.mangaDao().searchLibrary("%$q%")
                    val genreMatches = app.db.mangaDao().getFavoritesList()
                        .filter { it.genre?.contains(q, ignoreCase = true) == true }
                    (titleMatches + genreMatches).distinctBy { it.id }
                }.filter(::matchesFilters)
                if (libraryMatches.isNotEmpty()) {
                    initial += SourceResult(
                        sourceId = "library", sourceName = "In your library", loading = false, kind = KIND_LIBRARY,
                        cards = libraryMatches.map {
                            CardItem(
                                key = "lib${it.id}", title = it.title, cover = it.displayCoverUrl,
                                headers = app.sources.get(it.source)?.imageHeaders() ?: emptyMap(), tag = it.id
                            )
                        }
                    )
                }
            }
            if (generation != searchGeneration) return@coroutineScope
            val extensionSources = if (extensions) {
                BookSources.all.filter { it.inGlobalSearch && it.id in selectedIds }
            } else {
                emptyList()
            }
            val sources = if (extensions) {
                emptyList()
            } else {
                app.sources.enabled().filter { it.id in selectedIds }
            }
            if (extensions) {
                initial += SourceResult(sourceId = "epub", sourceName = "Books & Web Novels", kind = KIND_EPUB)
            }
            sources.forEach { initial += SourceResult(sourceId = it.id, sourceName = it.name) }
            sections.value = initial

            sourceSearchWork.clear()
            coroutineScope {
                if (extensions) launch { searchEpubs(q, extensionSources, generation) }
                sources.forEach { source ->
                    val work = SourceSearchWork(
                        source = source,
                        query = q,
                        combine = combine,
                        generation = generation
                    )
                    work.operations["titles"] = SourceSearchOperation(
                        if (q.isBlank()) 1 else MAX_SOURCE_SEARCH_PAGES
                    ) { page -> if (q.isBlank()) source.getPopular(page) else source.search(q, page) }
                    if (q.length >= 2) {
                        work.operations["genres"] = SourceSearchOperation(MAX_METADATA_SEARCH_PAGES) { page ->
                            source.searchByGenre(q, page)
                        }
                    }
                    if (isAuthorQuery(q)) {
                        work.operations["authors"] = SourceSearchOperation(MAX_METADATA_SEARCH_PAGES) { page ->
                            source.searchByAuthor(q, page)
                        }
                    }
                    work.operations.keys.forEach { work.operationStates[it] = SourceSearchStatus.PENDING }
                    if (hasSearchFilters()) work.operationStates["metadata"] = SourceSearchStatus.PENDING
                    sourceSearchWork[source.id] = work
                }
                refreshSearchProgress()
                sources.map { s ->
                    async {
                        val work = sourceSearchWork.getValue(s.id)
                        try {
                            runSourceSearch(work, retryOnlyFailed = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logSearchFailure(s.id, "source search", e)
                            val operation = work.operations.keys.firstOrNull {
                                work.operationStates[it] in setOf(
                                    SourceSearchStatus.PENDING,
                                    SourceSearchStatus.SEARCHING,
                                    SourceSearchStatus.RETRYING
                                )
                            } ?: work.operations.keys.first()
                            work.operationErrors[operation] = e.message ?: failureCategory(e)
                            setOperationStatus(work, operation, SourceSearchStatus.FAILED)
                            publishWorkResults(work)
                        }
                        // per-source ranking is only needed when the combined view is off
                        val found = hits[s.id].orEmpty()
                        if (!combine && found.isNotEmpty()) {
                            val best = found.firstOrNull { it.title.equals(q, ignoreCase = true) } ?: found.first()
                            val chapters = try {
                                app.searchConcurrency.withPermit {
                                    withTimeout(app.prefs.globalSearchRequestTimeoutMs) { s.getChapters(best) }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logSearchFailure(s.id, "chapter count", e)
                                null
                            }
                            if (chapters != null) {
                                update(s.id) {
                                    it.copy(chapterCount = chapters.size, latest = chapters.maxOfOrNull { c -> c.dateUpload } ?: 0L)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            if (combine && !extensions && generation == searchGeneration) computeStats(generation)
        }
    }

    private suspend fun runSourceSearch(work: SourceSearchWork, retryOnlyFailed: Boolean) {
        val selected = work.operations.filter { (operation, _) ->
            !retryOnlyFailed || operation in work.operationErrors
        }
        selected.forEach { (name, spec) ->
            if (work.generation != searchGeneration) return
            try {
                setOperationStatus(work, name, SourceSearchStatus.PENDING)
                val startPage = work.nextPages[name] ?: 1
                val batch = collectSourcePages(
                    work = work,
                    operation = name,
                    startPage = startPage,
                    pageCount = spec.maxPages,
                    fetchPage = spec.fetchPage,
                    onPage = { results ->
                        work.results[name] = results
                        publishWorkResults(work)
                    }
                )
                work.results[name] = batch.mangas
                batch.nextPage?.let { work.nextPages[name] = it } ?: work.nextPages.remove(name)
                if (batch.error == null) {
                    work.operationErrors.remove(name)
                    work.challengeUrls.remove(name)
                    if (batch.mangas.isEmpty()) {
                        Log.d("GlobalSearch", "${work.source.id} $name completed successfully with no results")
                    }
                    setOperationStatus(work, name, SourceSearchStatus.COMPLETED)
                } else {
                    work.operationErrors[name] = batch.error
                    setOperationStatus(work, name, batch.status)
                }
                publishWorkResults(work)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is OverallSearchDeadlineException) throw e
                logSearchFailure(work.source.id, name, e)
                if (e is CloudflareException) work.challengeUrls[name] = e.url
                work.operationErrors[name] = e.message ?: failureCategory(e)
                setOperationStatus(
                    work,
                    name,
                    if (e is SourceSearchDeadlineException) SourceSearchStatus.TIMED_OUT else SourceSearchStatus.FAILED
                )
            }
        }
        if (hasSearchFilters() && work.generation == searchGeneration) {
            val sourceDeadline = work.deadlineBudget.sourceDeadlineElapsedRealtimeMs
            if (sourceDeadline != null && SystemClock.elapsedRealtime() >= sourceDeadline) {
                work.operationStates.toMap().forEach { (operation, status) ->
                    if (status in setOf(
                            SourceSearchStatus.PENDING,
                            SourceSearchStatus.SEARCHING,
                            SourceSearchStatus.RETRYING
                        )
                    ) {
                        work.operationErrors[operation] = "${work.source.name} source deadline exceeded"
                        work.operationStates[operation] = SourceSearchStatus.TIMED_OUT
                    }
                }
                publishSourceStatus(work)
            } else {
                runMetadataFilter(work, retryOnlyFailed)
            }
        }
        publishWorkResults(work)
    }

    private suspend fun runMetadataFilter(work: SourceSearchWork, retryOnlyFailed: Boolean) {
        val candidates = work.results.values.flatten().distinctBy { it.url }
            .sortedWith(compareBy<SManga> { searchRelevance(it.title, work.query) }.thenBy { it.title })
        val toCheck = if (retryOnlyFailed) {
            (
                work.metadataFailures.values +
                    candidates.filter { it.url !in work.metadataChecked && it.url !in work.metadataFailures }
                ).distinctBy { it.url }
        } else {
            candidates.filter { it.url !in work.metadataChecked && it.url !in work.metadataFailures }
        }
        if (toCheck.isEmpty() && work.metadataFailures.isEmpty()) {
            work.operationErrors.remove("metadata")
            setOperationStatus(work, "metadata", SourceSearchStatus.COMPLETED)
            return
        }
        setOperationStatus(work, "metadata", SourceSearchStatus.PENDING)
        for (candidate in toCheck) {
            if (work.generation != searchGeneration) return
            try {
                val details = searchRequest(work, "metadata") {
                    work.source.getMangaDetails(candidate)
                }
                work.metadataChecked += candidate.url
                work.metadataFailures.remove(candidate.url)
                if (matchesFilters(details)) work.metadataMatches[candidate.url] = details
                else work.metadataMatches.remove(candidate.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logSearchFailure(work.source.id, "metadata filter", e)
                work.metadataFailures[candidate.url] = candidate
                if (e is CloudflareException) work.challengeUrls["metadata"] = e.url
                if (e is OverallSearchDeadlineException) throw e
                if (e is SourceSearchDeadlineException) {
                    work.metadataTimedOut = true
                    break
                }
            }
            publishWorkResults(work)
        }
        if (work.metadataFailures.isEmpty()) {
            work.operationErrors.remove("metadata")
            work.challengeUrls.remove("metadata")
            setOperationStatus(work, "metadata", SourceSearchStatus.COMPLETED)
        } else {
            work.operationErrors["metadata"] =
                "${work.metadataFailures.size} result(s) could not be checked against metadata filters"
            setOperationStatus(
                work,
                "metadata",
                if (work.metadataTimedOut) SourceSearchStatus.TIMED_OUT else SourceSearchStatus.FAILED
            )
        }
        publishWorkResults(work)
    }

    private fun setOperationStatus(
        work: SourceSearchWork,
        operation: String,
        status: SourceSearchStatus
    ) {
        if (work.generation != searchGeneration) return
        work.operationStates[operation] = status
        publishSourceStatus(work)
    }

    private fun publishSourceStatus(work: SourceSearchWork) {
        if (work.generation != searchGeneration) return
        val states = work.operationStates.values
        val status = when {
            SourceSearchStatus.RETRYING in states -> SourceSearchStatus.RETRYING
            SourceSearchStatus.SEARCHING in states -> SourceSearchStatus.SEARCHING
            SourceSearchStatus.PENDING in states -> SourceSearchStatus.PENDING
            SourceSearchStatus.TIMED_OUT in states -> SourceSearchStatus.TIMED_OUT
            work.operationErrors.isNotEmpty() &&
                states.any { it == SourceSearchStatus.COMPLETED } -> SourceSearchStatus.COMPLETED_WITH_ERRORS
            work.operationErrors.isNotEmpty() -> SourceSearchStatus.FAILED
            else -> SourceSearchStatus.COMPLETED
        }
        val error = work.operationErrors.entries.joinToString("\n") { (name, message) ->
            "${name.replaceFirstChar(Char::uppercase)}: $message"
        }.ifBlank { null }
        update(work.source.id) {
            it.copy(
                loading = status in setOf(
                    SourceSearchStatus.PENDING,
                    SourceSearchStatus.SEARCHING,
                    SourceSearchStatus.RETRYING
                ),
                status = status,
                error = error,
                resultCount = synchronized(searchResultsLock) { hits[work.source.id].orEmpty().size },
                challengeUrl = work.challengeUrls.values.firstOrNull()
            )
        }
        refreshSearchProgress()
    }

    private fun publishWorkResults(work: SourceSearchWork) {
        if (work.generation != searchGeneration) return
        val titleUrls = (work.results["titles"].orEmpty() + work.results["authors"].orEmpty())
            .mapTo(HashSet()) { it.url }
        val candidates = (if (hasSearchFilters()) work.metadataMatches.values else work.results.values.flatten())
            .distinctBy { it.url }
            .sortedWith(
                compareBy<SManga> { searchRelevance(it.title, work.query) }
                    .thenBy { if (it.url in titleUrls) 0 else 1 }
                    .thenBy { it.title }
            )
        synchronized(searchResultsLock) {
            if (work.generation != searchGeneration) return
            hits[work.source.id] = candidates
            rebuildGroupsLocked()
        }
        val headers = work.source.imageHeaders()
        update(work.source.id) {
            it.copy(
                cards = candidates.map { manga ->
                    CardItem(
                        key = "${work.source.id}${manga.url}",
                        title = manga.title,
                        cover = manga.thumbnailUrl,
                        headers = headers,
                        tag = manga
                    )
                },
                resultCount = candidates.size
            )
        }
    }

    private fun refreshSearchProgress() {
        if (!::searchProgress.isInitialized || !::retryFailedButton.isInitialized ||
            !::openChallengeButton.isInitialized
        ) return
        val results = sections.value.filter { it.kind == KIND_SOURCE }
        val completed = results.count {
            it.status == SourceSearchStatus.COMPLETED ||
                it.status == SourceSearchStatus.COMPLETED_WITH_ERRORS ||
                it.status == SourceSearchStatus.FAILED ||
                it.status == SourceSearchStatus.TIMED_OUT
        }
        val active = results.count { it.status == SourceSearchStatus.SEARCHING }
        val queued = results.count { it.status == SourceSearchStatus.PENDING }
        val retrying = results.count { it.status == SourceSearchStatus.RETRYING }
        val failed = results.count {
            it.status in setOf(
                SourceSearchStatus.FAILED,
                SourceSearchStatus.COMPLETED_WITH_ERRORS,
                SourceSearchStatus.TIMED_OUT
            )
        }
        searchProgress.text = buildString {
            append("$completed/${results.size} finished • $active active • $queued queued • $retrying retrying • $failed incomplete")
            searchDeadlineMessage?.let { append("\n$it") }
        }
        retryFailedButton.isEnabled = !initialSearchInProgress && !retryInProgress &&
            results.none { it.loading } &&
            sourceSearchWork.values.any { it.operationErrors.isNotEmpty() }
        openChallengeButton.visibility =
            if (sourceSearchWork.values.any { it.challengeUrls.isNotEmpty() }) View.VISIBLE else View.GONE
        openChallengeButton.isEnabled = !initialSearchInProgress && !retryInProgress
    }

    private fun retryFailedSources() {
        if (retryInProgress || retryJob?.isActive == true) return
        val failed = sourceSearchWork.values.filter { it.operationErrors.isNotEmpty() }
        if (failed.isEmpty()) {
            toast("No failed or incomplete sources to retry")
            return
        }
        val generation = searchGeneration
        retryInProgress = true
        searchDeadlineMessage = null
        refreshSearchProgress()
        retryJob = lifecycleScope.launch {
            failed.forEach { work ->
                work.operationErrors.keys.forEach { operation ->
                    work.operationStates[operation] = SourceSearchStatus.PENDING
                }
                work.deadlineBudget.sourceDeadlineElapsedRealtimeMs = null
                work.metadataTimedOut = false
                publishSourceStatus(work)
            }
            val retryDeadlineMs = app.prefs.globalSearchOverallDeadlineMs
            overallDeadlineElapsedRealtimeMs = SystemClock.elapsedRealtime() + retryDeadlineMs
            var retryTimedOut = false
            try {
                withTimeout(retryDeadlineMs) {
                    failed.map { work ->
                        async {
                            if (work.generation == generation) {
                                runSourceSearch(work, retryOnlyFailed = true)
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                retryTimedOut = true
                failed.forEach { work ->
                    work.operationStates.toMap().forEach { (operation, state) ->
                        if (state in setOf(
                                SourceSearchStatus.PENDING,
                                SourceSearchStatus.SEARCHING,
                                SourceSearchStatus.RETRYING
                            )
                        ) {
                            work.operationErrors[operation] = "Overall retry deadline reached; partial results were kept."
                            work.operationStates[operation] = SourceSearchStatus.TIMED_OUT
                        }
                    }
                    publishSourceStatus(work)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logSearchFailure("manual-retry", "failed source recovery", e)
                toast("Retry could not finish: ${e.message ?: "unexpected error"}")
                return@launch
            } finally {
                retryInProgress = false
                refreshSearchProgress()
            }
            if (generation == searchGeneration) {
                if (!retryTimedOut && failed.any { it.combine }) computeStats(generation)
                val recovered = failed.count { it.operationErrors.isEmpty() }
                val stillIncomplete = failed.size - recovered
                toast("Retry finished: $recovered recovered, $stillIncomplete still incomplete.")
            }
        }
    }

    private fun update(id: String, transform: (SourceResult) -> SourceResult) {
        sections.update { list -> list.map { if (it.sourceId == id) transform(it) else it } }
    }

    private suspend fun searchEpubs(q: String, sources: List<BookSource>, generation: Int) {
        val results = Channel<Pair<List<BookItem>, String?>>(Channel.UNLIMITED)
        val found = mutableListOf<BookItem>()
        val errors = mutableListOf<String>()
        var completed = 0
        coroutineScope {
            sources.forEach { source ->
                launch {
                    val result = try {
                        val deadline = SearchDeadlineBudget()
                        var titleError: String? = null
                        val titles = try {
                            bookSearchRequest(source, "title", deadline) {
                                if (q.isBlank()) source.popular(1) else source.search(q, 1)
                            }
                        } catch (e: TimeoutCancellationException) {
                            logSearchFailure(source.id, "book title", e)
                            titleError = "Title search timed out"
                            emptyList()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logSearchFailure(source.id, "book title", e)
                            titleError = e.message ?: "Title search failed"
                            emptyList()
                        }
                        var genreError: String? = null
                        val genres = if (q.length >= 2) {
                            try {
                                bookSearchRequest(source, "genre", deadline) { source.searchByGenre(q, 1) }
                            } catch (e: TimeoutCancellationException) {
                                logSearchFailure(source.id, "book genre", e)
                                genreError = "Genre search timed out"
                                emptyList()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logSearchFailure(source.id, "book genre", e)
                                genreError = e.message ?: "Genre search failed"
                                emptyList()
                            }
                        } else emptyList()
                        var authorError: String? = null
                        val authors = if (isAuthorQuery(q)) {
                            try {
                                bookSearchRequest(source, "author", deadline) { source.searchByAuthor(q, 1) }
                            } catch (e: TimeoutCancellationException) {
                                logSearchFailure(source.id, "book author", e)
                                authorError = "Author search timed out"
                                emptyList()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logSearchFailure(source.id, "book author", e)
                                authorError = e.message ?: "Author search failed"
                                emptyList()
                            }
                        } else emptyList()
                        val candidateItems = (titles + genres + authors).distinctBy { it.key }.take(15)
                        val sourceName = source.name
                        if (!hasSearchFilters()) {
                            candidateItems to listOfNotNull(
                                titleError?.let { "$sourceName title search: $it" },
                                genreError?.let { "$sourceName genre search: $it" },
                                authorError?.let { "$sourceName author search: $it" }
                            ).joinToString("\n").ifBlank { null }
                        } else {
                            var failures = 0
                            val books = candidateItems.mapNotNull { item ->
                                try {
                                    val detailed = bookSearchRequest(source, "metadata", deadline) {
                                        source.details(item)
                                    }
                                    detailed.takeIf(::matchesFilters)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logSearchFailure(source.id, "book metadata", e)
                                    failures++
                                    null
                                }
                            }
                            val metadataError = if (failures > 0) {
                                "${source.name}: $failures result(s) could not be checked against metadata filters"
                            } else null
                            books to listOfNotNull(
                                titleError?.let { "$sourceName title search: $it" },
                                genreError?.let { "$sourceName genre search: $it" },
                                authorError?.let { "$sourceName author search: $it" },
                                metadataError
                            ).joinToString("\n").ifBlank { null }
                        }
                    } catch (e: TimeoutCancellationException) {
                        logSearchFailure(source.id, "book search", e)
                        emptyList<BookItem>() to "${source.name}: timed out"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logSearchFailure(source.id, "book search", e)
                        emptyList<BookItem>() to "${source.name}: ${e.message ?: "search failed"}"
                    }
                    results.send(result)
                }
            }
            repeat(sources.size) {
                val (books, error) = results.receive()
                completed++
                found += books
                error?.let(errors::add)
                if (generation == searchGeneration) {
                    val cards = found.distinctBy { it.key }
                        .sortedWith(compareBy<BookItem> { searchRelevance(it.title, q) }.thenBy { it.title })
                        .map { item ->
                        val badge = when {
                            BookSources.get(item.sourceId)?.isWebNovel == true -> "NOVEL"
                            item.pdfUrl != null -> "PDF"
                            else -> "EPUB"
                        }
                        CardItem(key = item.key, title = item.title, cover = item.coverUrl, badge1 = badge, tag = item)
                    }
                    val errorText = errors.take(3).joinToString("\n")
                        .let { summary -> if (errors.size > 3) "$summary\n+${errors.size - 3} more source errors" else summary }
                        .ifBlank { null }
                    update("epub") {
                        it.copy(loading = completed < sources.size, cards = cards, error = errorText)
                    }
                }
            }
        }
        results.close()
        if (sources.isEmpty() && generation == searchGeneration) update("epub") { it.copy(loading = false) }
    }

    private suspend fun <T> bookSearchRequest(
        source: BookSource,
        operation: String,
        deadline: SearchDeadlineBudget,
        request: suspend () -> T
    ): T {
        var retry = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            if (overallDeadlineElapsedRealtimeMs - now <= 0L) {
                throw OverallSearchDeadlineException("Overall search deadline exceeded")
            }
            try {
                return app.searchConcurrency.withPermit(
                    onAcquired = {
                        if (deadline.sourceDeadlineElapsedRealtimeMs == null) {
                            deadline.sourceDeadlineElapsedRealtimeMs =
                                SystemClock.elapsedRealtime() + app.prefs.globalSearchSourceDeadlineMs
                        }
                    }
                ) {
                    val started = SystemClock.elapsedRealtime()
                    val sourceDeadline = deadline.sourceDeadlineElapsedRealtimeMs
                        ?: (started + app.prefs.globalSearchSourceDeadlineMs).also {
                            deadline.sourceDeadlineElapsedRealtimeMs = it
                        }
                    val sourceRemaining = sourceDeadline - started
                    val overallRemaining = overallDeadlineElapsedRealtimeMs - started
                    if (sourceRemaining <= 0L) {
                        throw SourceSearchDeadlineException("${source.name} source deadline exceeded")
                    }
                    if (overallRemaining <= 0L) {
                        throw OverallSearchDeadlineException("Overall search deadline exceeded")
                    }
                    try {
                        withTimeout(minOf(app.prefs.globalSearchRequestTimeoutMs, sourceRemaining, overallRemaining)) {
                            request()
                        }
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        val timedOutAt = SystemClock.elapsedRealtime()
                        when {
                            timedOutAt >= overallDeadlineElapsedRealtimeMs ->
                                throw OverallSearchDeadlineException("Overall search deadline exceeded", e)
                            timedOutAt >= sourceDeadline ->
                                throw SourceSearchDeadlineException("${source.name} source deadline exceeded", e)
                            else -> throw RequestTimedOutException("${source.name} $operation timed out", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (e is OverallSearchDeadlineException || e is SourceSearchDeadlineException) throw e
                if (!isTransientFailure(e) || retry >= RETRY_DELAYS_MS.size) throw e
                val remaining = minOf(
                    overallDeadlineElapsedRealtimeMs,
                    deadline.sourceDeadlineElapsedRealtimeMs ?: overallDeadlineElapsedRealtimeMs
                ) - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    if (SystemClock.elapsedRealtime() >= overallDeadlineElapsedRealtimeMs) {
                        throw OverallSearchDeadlineException("Overall search deadline exceeded", e)
                    }
                    throw SourceSearchDeadlineException("${source.name} source deadline exceeded", e)
                }
                val retryAfter = (e as? HttpStatusException)?.retryAfterMs
                val backoff = if (retryAfter != null) {
                    retry++
                    retryAfter
                } else {
                    delayWithJitter(retry++)
                }
                delay(minOf(backoff, remaining))
            }
        }
    }

    private fun delayWithJitter(retry: Int): Long {
        val baseDelay = RETRY_DELAYS_MS[retry]
        val jitter = (baseDelay * 0.2 * kotlin.random.Random.nextDouble()).toLong()
        return baseDelay + jitter
    }

    /** Groups every source's hits by normalised title so the same series shows up once. */
    private fun rebuildGroups() = synchronized(searchResultsLock) {
        rebuildGroupsLocked()
    }

    private fun rebuildGroupsLocked() {
        val byKey = LinkedHashMap<String, MutableList<GroupMember>>()
        for ((sid, list) in hits) {
            for (m in list) {
                val key = normalizeTitle(m.title)
                if (key.isBlank()) continue
                val members = byKey.getOrPut(key) { mutableListOf() }
                if (members.none { it.sourceId == sid }) members.add(GroupMember(sid, m))
            }
        }
        groups.value = byKey.map { (key, members) ->
            val title = members.groupingBy { it.manga.title }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
                .first().key
            val coverMember = members.firstOrNull { it.manga.thumbnailUrl != null && it.sourceId == "mangadex" }
                ?: members.firstOrNull { it.manga.thumbnailUrl != null }
                ?: members.first()
            TitleGroup(
                key = key, title = title, members = members, cover = coverMember.manga.thumbnailUrl,
                coverHeaders = app.sources.get(coverMember.sourceId)?.imageHeaders() ?: emptyMap(),
                stats = stats[key]
            )
        }
    }

    /** Fetches the chapter lists of every source for the top titles and merges them. */
    private suspend fun computeStats(generation: Int) {
        val top = visibleGroups(groups.value).take(10)
        coroutineScope {
            top.map { g ->
                async {
                    val result: GroupStats = try {
                        val lists = CombinedSource.fetchAll(
                            g.members.map { CombinedSource.Member(it.sourceId, it.manga.url) },
                            g.title,
                            sharedLimiter = app.searchConcurrency
                        )
                        val merged = CombinedSource.merge(lists)
                        GroupStats(
                            count = merged.size,
                            latestNumber = merged.maxOfOrNull { it.number } ?: -1f,
                            latestDate = merged.maxOfOrNull { it.date } ?: 0L,
                            memberCounts = lists.associate { it.first.sourceId to it.second.size },
                            error = if (merged.isEmpty()) CombinedSource.failureSummary(
                                g.members.map { CombinedSource.Member(it.sourceId, it.manga.url) }
                            )
                            else null
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        GroupStats(0, -1f, 0L, emptyMap(), e.message)
                    }
                    if (generation == searchGeneration) {
                        synchronized(searchResultsLock) {
                            if (generation == searchGeneration) {
                                stats[g.key] = result
                                rebuildGroupsLocked()
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // ---------------------------------------------------------------- actions

    private fun openGroup(group: TitleGroup, addToLibrary: Boolean) {
        lifecycleScope.launch {
            try {
                val counts = group.stats?.memberCounts.orEmpty()
                val members = group.members
                    .sortedByDescending { counts[it.sourceId] ?: -1 }
                    .map { CombinedSource.Member(it.sourceId, it.manga.url) }
                val manga = app.repo.getOrCreateCombined(group.title, members, group.cover)
                if (addToLibrary) {
                    if (manga.favorite) toast("Already in your library")
                    else if (LibraryActions.addToLibrary(this@GlobalSearchActivity, manga)) toast("Added “${group.title}” to your library")
                } else {
                    startActivity(MangaDetailsActivity.intent(this@GlobalSearchActivity, manga.id))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't open: ${e.message}")
            }
        }
    }

    private fun openMigrationGroup(group: TitleGroup) {
        if (!migrationCombinedMode) return
        lifecycleScope.launch {
            val from = app.db.mangaDao().getById(migrationFromId)
            if (from == null) {
                toast("The entry being migrated is no longer available")
                finish()
                return@launch
            }
            val members = group.members
                .map { CombinedSource.Member(it.sourceId, it.manga.url) }
                .distinct()
            if (members.isEmpty()) {
                toast("No source entries were found to combine")
                return@launch
            }
            showMigrationConfirmation(
                from = from,
                targetSourceId = CombinedSource.ID,
                target = SManga(
                    url = CombinedSource.encode(members),
                    title = group.title,
                    thumbnailUrl = group.cover
                ),
                combinedMembers = members
            )
        }
    }

    private fun showMembers(group: TitleGroup) {
        val counts = group.stats?.memberCounts.orEmpty()
        val lines = group.members.map {
            val name = app.sources.get(it.sourceId)?.name ?: it.sourceId
            val c = counts[it.sourceId]
            if (c != null) "$name — $c chapters" else name
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(group.title)
            .setItems(lines.toTypedArray(), null)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun onCardClick(section: SourceResult, card: CardItem) {
        when (val tag = card.tag) {
            is BookItem -> if (migrationMode) {
                val source = BookSources.get(tag.sourceId)
                if (source?.isWebNovel != true) {
                    toast("Only compatible web-novel extensions can be used for this migration")
                } else {
                    lifecycleScope.launch {
                        val from = app.db.mangaDao().getById(migrationFromId)
                        if (from == null) {
                            toast("The entry being migrated is no longer available")
                            finish()
                        } else {
                            showMigrationConfirmation(
                                from = from,
                                targetSourceId = source.id,
                                target = SManga(
                                    url = tag.id,
                                    title = tag.title,
                                    thumbnailUrl = tag.coverUrl,
                                    author = tag.author,
                                    artist = tag.artist,
                                    description = tag.description,
                                    genre = tag.genre
                                )
                            )
                        }
                    }
                }
            } else {
                BookActions.show(this, tag)
            }
            is Long -> startActivity(MangaDetailsActivity.intent(this, tag))
            is SManga -> if (migrationMode) {
                val source = app.sources.get(section.sourceId)
                if (source == null) {
                    toast("That source is no longer available")
                } else {
                    lifecycleScope.launch {
                        val from = app.db.mangaDao().getById(migrationFromId)
                        if (from == null) {
                            toast("The entry being migrated is no longer available")
                            finish()
                        } else {
                            showMigrationConfirmation(from, source.id, tag)
                        }
                    }
                }
            } else {
                lifecycleScope.launch {
                    val id = app.repo.getOrCreate(section.sourceId, tag).id
                    startActivity(MangaDetailsActivity.intent(this@GlobalSearchActivity, id))
                }
            }
        }
    }

    private fun showMigrationConfirmation(
        from: MangaEntity,
        targetSourceId: String,
        target: SManga,
        combinedMembers: List<CombinedSource.Member>? = null
    ) {
        val savedFlags = app.prefs.migrationFlags
        val chapterProgress = MaterialCheckBox(this).apply {
            text = "Chapters and reading progress"
            isChecked = "chapters" in savedFlags
        }
        val category = MaterialCheckBox(this).apply {
            text = "Category"
            isChecked = "category" in savedFlags
        }
        val history = MaterialCheckBox(this).apply {
            text = "History"
            isChecked = "history" in savedFlags
        }
        val readerSettings = MaterialCheckBox(this).apply {
            text = "Reader settings"
            isChecked = "reader_settings" in savedFlags
        }
        val previewActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val targetPreview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(0, padding, 0, padding)
        }
        targetPreview.addView(android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            loadCover(
                target.thumbnailUrl,
                app.sources.get(targetSourceId)?.imageHeaders() ?: emptyMap()
            )
        }, LinearLayout.LayoutParams(
            (64 * resources.displayMetrics.density).toInt(),
            (88 * resources.displayMetrics.density).toInt()
        ))
        targetPreview.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, 0, 0)
            addView(TextView(this@GlobalSearchActivity).apply {
                text = target.title
                textSize = 15f
                setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@GlobalSearchActivity, com.google.android.material.R.attr.colorOnSurface))
            })
            listOfNotNull(target.author, target.artist)
                .filter(String::isNotBlank)
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { creators ->
                    addView(TextView(this@GlobalSearchActivity).apply {
                        text = creators.joinToString(" • ")
                        textSize = 12f
                        setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@GlobalSearchActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
                        setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
                    })
                }
            target.description?.takeIf(String::isNotBlank)?.let { description ->
                addView(TextView(this@GlobalSearchActivity).apply {
                    text = description
                    maxLines = 3
                    textSize = 12f
                    setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@GlobalSearchActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val showEntry = MaterialButton(this).apply {
            text = "Show entry"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val compare = MaterialButton(this).apply {
            text = "Compare"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(targetPreview)
            addView(chapterProgress)
            addView(category)
            addView(history)
            addView(readerSettings)
            previewActions.addView(showEntry)
            previewActions.addView(compare)
            addView(previewActions)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("What to include?")
            .setMessage("${from.title} → ${target.title}")
            .setView(content)
            .setNeutralButton("Copy", null)
            .setPositiveButton("Migrate", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            showEntry.setOnClickListener {
                app.prefs.migrationFlags = buildSet {
                    if (chapterProgress.isChecked) add("chapters")
                    if (category.isChecked) add("category")
                    if (history.isChecked) add("history")
                    if (readerSettings.isChecked) add("reader_settings")
                }
                dialog.dismiss()
                showMigrationEntry(from, targetSourceId, target, combinedMembers)
            }
            compare.setOnClickListener {
                saveMigrationFlags(chapterProgress.isChecked, category.isChecked, history.isChecked, readerSettings.isChecked)
                dialog.dismiss()
                showChapterComparison(from, targetSourceId, target, combinedMembers)
            }
            fun start(replaceOriginal: Boolean) {
                dialog.dismiss()
                saveMigrationFlags(
                    chapterProgress.isChecked,
                    category.isChecked,
                    history.isChecked,
                    readerSettings.isChecked
                )
                performMigration(
                    from = from,
                    targetSourceId = targetSourceId,
                    target = target,
                    combinedMembers = combinedMembers,
                    options = MangaMigrationOptions(
                        chapters = chapterProgress.isChecked,
                        category = category.isChecked,
                        history = history.isChecked,
                        readerSettings = readerSettings.isChecked
                    ),
                    replaceOriginal = replaceOriginal
                )
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener { start(replaceOriginal = false) }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener { start(replaceOriginal = true) }
        }
        dialog.show()
    }

    private fun saveMigrationFlags(chapters: Boolean, category: Boolean, history: Boolean, readerSettings: Boolean) {
        app.prefs.migrationFlags = buildSet {
            if (chapters) add("chapters")
            if (category) add("category")
            if (history) add("history")
            if (readerSettings) add("reader_settings")
        }
    }

    private fun showChapterComparison(
        from: MangaEntity,
        targetSourceId: String,
        target: SManga,
        combinedMembers: List<CombinedSource.Member>?
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ProgressBar(this@GlobalSearchActivity))
            addView(TextView(this@GlobalSearchActivity).apply {
                text = "Comparing latest chapters…"
                setPadding(padding, 0, 0, 0)
            })
        }
        val progress = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            try {
                val destination = if (combinedMembers != null) {
                    app.repo.getOrCreateCombined(target.title, combinedMembers, target.thumbnailUrl)
                } else {
                    app.repo.getOrCreate(targetSourceId, target)
                }
                app.repo.refresh(destination)
                val fromLatest = latestChapterLabel(from.id)
                val targetLatest = latestChapterLabel(destination.id)
                progress.dismiss()
                MaterialAlertDialogBuilder(this@GlobalSearchActivity)
                    .setTitle("Latest chapter comparison")
                    .setMessage(
                        "${from.title}: $fromLatest\n" +
                            "${destination.title}: $targetLatest"
                    )
                    .setPositiveButton("Back to migration") { _, _ ->
                        showMigrationConfirmation(from, targetSourceId, target, combinedMembers)
                    }
                    .show()
            } catch (e: CancellationException) {
                progress.dismiss()
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                toast("Could not compare latest chapters: ${e.message}")
                showMigrationConfirmation(from, targetSourceId, target, combinedMembers)
            }
        }
    }

    private suspend fun latestChapterLabel(mangaId: Long): String {
        val latest = app.db.chapterDao().getAll(mangaId).firstOrNull()
            ?: return "No chapters"
        val number = latest.chapterNumber.takeIf { it >= 0f }
            ?: parseChapterNumber(latest.name)
        return if (number >= 0f) "Chapter ${CombinedSource.formatNumber(number)}"
        else latest.name.ifBlank { "Latest chapter unavailable" }
    }

    private fun showMigrationEntry(
        from: MangaEntity,
        targetSourceId: String,
        target: SManga,
        combinedMembers: List<CombinedSource.Member>?
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ProgressBar(this@GlobalSearchActivity))
            addView(TextView(this@GlobalSearchActivity).apply {
                text = "Loading entry…"
                setPadding(padding, 0, 0, 0)
            })
        }
        val progress = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            var entry: com.imperialreader.data.db.MangaEntity? = null
            try {
                val resolvedEntry = if (combinedMembers != null) {
                    app.repo.getOrCreateCombined(target.title, combinedMembers, target.thumbnailUrl)
                } else {
                    app.repo.getOrCreate(targetSourceId, target)
                }
                entry = resolvedEntry
                app.repo.refresh(resolvedEntry)
            } catch (e: CancellationException) {
                progress.dismiss()
                throw e
            } catch (e: Exception) {
                toast("Could not refresh entry: ${e.message}")
            } finally {
                progress.dismiss()
            }
            entry?.let {
                pendingMigrationConfirmation = PendingMigrationConfirmation(
                    from,
                    targetSourceId,
                    target,
                    combinedMembers
                )
                startActivityForResult(
                    MangaDetailsActivity.intent(this@GlobalSearchActivity, it.id),
                    REQUEST_SHOW_MIGRATION_ENTRY
                )
            }
        }
    }

    private fun performMigration(
        from: MangaEntity,
        targetSourceId: String,
        target: SManga,
        combinedMembers: List<CombinedSource.Member>?,
        options: MangaMigrationOptions,
        replaceOriginal: Boolean
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ProgressBar(this@GlobalSearchActivity))
            addView(TextView(this@GlobalSearchActivity).apply {
                text = "Migrating…"
                setPadding(padding, 0, 0, 0)
            })
        }
        val progress = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            try {
                val result = MangaMigrationManager(app.repo).migrate(
                    from = from,
                    target = target,
                    targetSourceId = targetSourceId,
                    combinedMembers = combinedMembers,
                    options = options,
                    replaceOriginal = replaceOriginal
                )
                progress.dismiss()
                toast(
                    "${if (replaceOriginal) "Migrated" else "Copied"} to ${result.manga.title}; " +
                        "copied progress for ${result.copiedChapters} chapters" +
                        if (result.unmatchedChapters > 0) {
                            " (${result.unmatchedChapters} progress items had no chapter match)"
                        } else ""
                )
                startActivity(MangaDetailsActivity.intent(this@GlobalSearchActivity, result.manga.id))
                finish()
            } catch (e: CancellationException) {
                progress.dismiss()
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                toast("Migration failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- adapter

    private class SectionAdapter(
        val onClick: (SourceResult, CardItem) -> Unit
    ) : ListAdapter<SourceResult, SectionAdapter.VH>(Diff) {

        class VH(v: View, val cards: MangaCardAdapter) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.sectionTitle)
            val info: TextView = v.findViewById(R.id.sectionInfo)
            val progress: ProgressBar = v.findViewById(R.id.sectionProgress)
            val error: TextView = v.findViewById(R.id.sectionError)
            val recycler: RecyclerView = v.findViewById(R.id.sectionRecycler)
            var current: SourceResult? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_section, parent, false)
            lateinit var holder: VH
            val cards = MangaCardAdapter(fixedWidthDp = 110, onClick = { card ->
                holder.current?.let { onClick(it, card) }
            })
            holder = VH(v, cards)
            holder.recycler.layoutManager =
                LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            holder.recycler.adapter = cards
            return holder
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = getItem(position)
            h.current = s
            h.title.text = s.sourceName
            h.info.text = buildString {
                when (s.kind) {
                    KIND_EPUB -> if (s.cards.isNotEmpty()) append("${s.cards.size} books and novels available")
                    KIND_LIBRARY -> {}
                    else -> {
                        if (s.resultCount > 0) append("${s.resultCount} results")
                        if (s.chapterCount >= 0) append(" • ${s.chapterCount} chapters")
                        if (s.latest > 0) append(" • updated ${relativeTime(s.latest)}")
                        append(
                            when (s.status) {
                                SourceSearchStatus.PENDING -> " • queued"
                                SourceSearchStatus.SEARCHING -> " • searching"
                                SourceSearchStatus.RETRYING -> " • retrying"
                                SourceSearchStatus.COMPLETED -> " • complete"
                                SourceSearchStatus.COMPLETED_WITH_ERRORS,
                                SourceSearchStatus.TIMED_OUT,
                                SourceSearchStatus.FAILED -> ""
                            }
                        )
                    }
                }
            }.trimStart(' ', '•')
            h.progress.visibility = if (s.loading) View.VISIBLE else View.GONE
            h.error.visibility = View.GONE
            h.recycler.visibility = if (s.cards.isEmpty()) View.GONE else View.VISIBLE
            h.cards.submitList(s.cards)
        }

        private object Diff : DiffUtil.ItemCallback<SourceResult>() {
            override fun areItemsTheSame(a: SourceResult, b: SourceResult) = a.sourceId == b.sourceId
            override fun areContentsTheSame(a: SourceResult, b: SourceResult) = a == b
        }
    }
}
