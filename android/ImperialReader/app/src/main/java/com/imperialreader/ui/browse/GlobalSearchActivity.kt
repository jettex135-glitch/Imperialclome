package com.imperialreader.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.book.BookItem
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.source.CombinedSource
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.normalizeTitle
import com.imperialreader.ui.books.BookActions
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.LibraryActions
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.common.RowAdapter
import com.imperialreader.ui.common.RowItem
import com.imperialreader.ui.common.StarSpriteView
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.relativeTime
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

const val KIND_SOURCE = 0
const val KIND_LIBRARY = 1
const val KIND_EPUB = 2

data class SourceResult(
    val sourceId: String,
    val sourceName: String,
    val cards: List<CardItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val chapterCount: Int = -1,
    val latest: Long = 0L,
    val kind: Int = KIND_SOURCE
)

private data class GroupMember(val sourceId: String, val manga: SManga)

private data class GroupStats(
    val count: Int,
    val latestNumber: Float,
    val latestDate: Long,
    val memberCounts: Map<String, Int>,
    val error: String? = null
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
class GlobalSearchActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_QUERY = "query"
        fun intent(ctx: Context, query: String): Intent =
            Intent(ctx, GlobalSearchActivity::class.java).putExtra(EXTRA_QUERY, query)
    }

    private val sections = MutableStateFlow<List<SourceResult>>(emptyList())
    private val groups = MutableStateFlow<List<TitleGroup>>(emptyList())
    private val tick = MutableStateFlow(0)
    private val hits = LinkedHashMap<String, List<SManga>>()
    private val stats = HashMap<String, GroupStats>()
    private var currentQuery = ""
    private var genreFilter = ""
    private var artistFilter = ""
    private var searchJob: Job? = null

    private lateinit var emptyText: TextView
    private lateinit var sprite: StarSpriteView
    private lateinit var sectionAdapter: SectionAdapter
    private lateinit var titleAdapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_search)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.global_search_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_search_filters) {
                showSearchFilters()
                true
            } else false
        }
        val input = findViewById<EditText>(R.id.queryInput)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        emptyText = findViewById(R.id.emptyText)
        sprite = findViewById(R.id.searchSprite)

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
            onClick = { row -> openGroup(row.tag as TitleGroup, addToLibrary = false) },
            onLongClick = { row -> showMembers(row.tag as TitleGroup) },
            onAction = { row -> openGroup(row.tag as TitleGroup, addToLibrary = true) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ConcatAdapter(sectionAdapter, titleAdapter)

        lifecycleScope.launch {
            combine(sections, groups, tick) { s, g, _ -> s to g }.collect { (s, g) -> render(s, g) }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(input.text.toString().trim())
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                true
            } else false
        }

        val initial = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        if (initial.isNotBlank()) {
            input.setText(initial)
            runSearch(initial)
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun render(all: List<SourceResult>, grouped: List<TitleGroup>) {
        val combine = app.prefs.searchCombine
        val onlyResults = app.prefs.searchOnlyResults

        val extra = all.filter { it.kind != KIND_SOURCE && (it.cards.isNotEmpty() || it.error != null) }
        val sourceErrors = if (combine) all.filter { it.kind == KIND_SOURCE && it.error != null } else emptyList()
        val perSource = if (combine) emptyList() else {
            val list = all.filter { it.kind == KIND_SOURCE }
            sortSources(if (onlyResults) list.filter { it.cards.isNotEmpty() } else list)
        }
        sectionAdapter.submitList(extra + sourceErrors + perSource)

        val rows = if (combine) visibleGroups(grouped).map { toRow(it) } else emptyList()
        titleAdapter.submitList(rows)

        val searching = all.any { it.loading }
        val anything = extra.isNotEmpty() || sourceErrors.isNotEmpty() ||
            rows.isNotEmpty() || perSource.any { it.cards.isNotEmpty() }
        emptyText.visibility = if (all.isNotEmpty() && !searching && !anything) View.VISIBLE else View.GONE
        val errors = all.count { it.error != null }
        emptyText.text = if (errors > 0) "No results • $errors source(s) could not be reached" else "No results"
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
        val close = sorted.filter { relevance(it) <= 2 }
        return (if (close.isNotEmpty()) close else sorted).take(30)
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
                runSearch(findViewById<EditText>(R.id.queryInput).text.toString().trim())
            }
            .setNeutralButton("Clear") { _, _ ->
                genreFilter = ""
                artistFilter = ""
                runSearch(findViewById<EditText>(R.id.queryInput).text.toString().trim())
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

    // ---------------------------------------------------------------- searching

    private fun runSearch(q: String) {
        if (q.isBlank() && !hasSearchFilters()) {
            searchJob?.cancel()
            sections.value = emptyList()
            groups.value = emptyList()
            hits.clear()
            stats.clear()
            return
        }
        searchJob?.cancel()
        currentQuery = q
        hits.clear()
        stats.clear()
        groups.value = emptyList()
        val combine = app.prefs.searchCombine

        searchJob = lifecycleScope.launch {
            sprite.start()
            try {
                searchAll(q, combine)
            } finally {
                sprite.stop()
            }
        }
    }

    private suspend fun searchAll(q: String, combine: Boolean) {
        coroutineScope {
            val initial = mutableListOf<SourceResult>()
            val libraryMatches = (if (q.isBlank()) {
                app.db.mangaDao().getFavoritesList()
            } else {
                app.db.mangaDao().searchLibrary("%$q%")
            }).filter(::matchesFilters)
            if (libraryMatches.isNotEmpty()) {
                initial += SourceResult(
                    sourceId = "library", sourceName = "In your library", loading = false, kind = KIND_LIBRARY,
                    cards = libraryMatches.map {
                        CardItem(
                            key = "lib${it.id}", title = it.title, cover = it.coverUrl,
                            headers = app.sources.get(it.source)?.imageHeaders() ?: emptyMap(), tag = it.id
                        )
                    }
                )
            }
            initial += SourceResult(sourceId = "epub", sourceName = "Books & Web Novels", kind = KIND_EPUB)
            val sources = app.sources.enabled()
            sources.forEach { initial += SourceResult(sourceId = it.id, sourceName = it.name) }
            sections.value = initial

            val searchSem = Semaphore(5)
            val rankSem = Semaphore(3)
            coroutineScope {
                launch { searchEpubs(q) }
                sources.map { s ->
                    async {
                        val found: List<SManga> = try {
                            val titleCandidates = searchSem.withPermit {
                                withTimeout(12_000L) {
                                    (if (q.isBlank()) s.getPopular(1) else s.search(q, 1)).mangas.take(15)
                                }
                            }
                            val metadataCandidates = if (q.isBlank() || titleCandidates.size >= 15) emptyList() else try {
                                searchSem.withPermit { withTimeout(8_000L) { s.searchMetadata(q, 1).mangas.take(15) } }
                            } catch (_: TimeoutCancellationException) {
                                emptyList()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                            val candidates = (titleCandidates + metadataCandidates).distinctBy { it.url }
                            if (!hasSearchFilters()) {
                                candidates
                            } else {
                                var failures = 0
                                val matched = candidates.mapNotNull { candidate ->
                                    try {
                                        val details = searchSem.withPermit { s.getMangaDetails(candidate) }
                                        details.takeIf(::matchesFilters)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        failures++
                                        null
                                    }
                                }
                                if (failures > 0) {
                                    update(s.id) {
                                        it.copy(error = "$failures result(s) could not be checked against metadata filters")
                                    }
                                }
                                matched
                            }
                        } catch (e: TimeoutCancellationException) {
                            update(s.id) { it.copy(loading = false, error = "Search timed out") }
                            return@async
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            update(s.id) { it.copy(loading = false, error = e.message ?: "Failed") }
                            return@async
                        }
                        val headers = s.imageHeaders()
                        hits[s.id] = found
                        rebuildGroups()
                        update(s.id) {
                            it.copy(
                                loading = false,
                                cards = found.map { m ->
                                    CardItem(key = "${s.id}${m.url}", title = m.title, cover = m.thumbnailUrl, headers = headers, tag = m)
                                }
                            )
                        }
                        // per-source ranking is only needed when the combined view is off
                        if (!combine && found.isNotEmpty()) {
                            val best = found.firstOrNull { it.title.equals(q, ignoreCase = true) } ?: found.first()
                            val chapters = runCatching { rankSem.withPermit { s.getChapters(best) } }.getOrNull()
                            if (chapters != null) {
                                update(s.id) {
                                    it.copy(chapterCount = chapters.size, latest = chapters.maxOfOrNull { c -> c.dateUpload } ?: 0L)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            if (combine) computeStats()
        }
    }

    private fun update(id: String, transform: (SourceResult) -> SourceResult) {
        sections.update { list -> list.map { if (it.sourceId == id) transform(it) else it } }
    }

    private suspend fun searchEpubs(q: String) {
        val sem = Semaphore(4)
        val sources = BookSources.all.filter { it.inGlobalSearch }
        val results = Channel<Pair<List<BookItem>, String?>>(Channel.UNLIMITED)
        val found = mutableListOf<BookItem>()
        val errors = mutableListOf<String>()
        var completed = 0
        coroutineScope {
            sources.forEach { source ->
                launch {
                    val result = try {
                        val titleCandidates = withTimeout(12_000L) {
                            sem.withPermit {
                                (if (q.isBlank()) source.popular(1) else source.search(q, 1)).take(10)
                            }
                        }
                        val metadataCandidates = if (q.isBlank() || titleCandidates.size >= 10) emptyList() else try {
                            withTimeout(8_000L) {
                                sem.withPermit { source.searchMetadata(q, 1).items.take(10) }
                            }
                        } catch (_: TimeoutCancellationException) {
                            emptyList()
                        }
                        val candidates = (titleCandidates + metadataCandidates).distinctBy { it.key }
                        if (!hasSearchFilters()) {
                            candidates to null
                        } else {
                            var failures = 0
                            val books = candidates.mapNotNull { item ->
                                try {
                                    val detailed = withTimeout(12_000L) {
                                        sem.withPermit { source.details(item) }
                                    }
                                    detailed.takeIf(::matchesFilters)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    failures++
                                    null
                                }
                            }
                            books to if (failures > 0) {
                                "${source.name}: $failures result(s) could not be checked against metadata filters"
                            } else null
                        }
                    } catch (e: TimeoutCancellationException) {
                        emptyList<BookItem>() to "${source.name}: timed out"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
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
                val cards = found.distinctBy { it.key }
                    .sortedWith(compareBy<BookItem> { searchRelevance(it.title, q) }.thenBy { it.title })
                    .map { item ->
                    val badge = if (BookSources.get(item.sourceId)?.isWebNovel == true) "NOVEL" else "EPUB"
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
        results.close()
        if (sources.isEmpty()) update("epub") { it.copy(loading = false) }
    }

    /** Groups every source's hits by normalised title so the same series shows up once. */
    private fun rebuildGroups() {
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
    private suspend fun computeStats() {
        val top = visibleGroups(groups.value).take(10)
        val sem = Semaphore(3)
        coroutineScope {
            top.map { g ->
                async {
                    val result: GroupStats = try {
                        sem.withPermit {
                            val lists = CombinedSource.fetchAll(
                                g.members.map { CombinedSource.Member(it.sourceId, it.manga.url) }, g.title
                            )
                            val merged = CombinedSource.merge(lists)
                            GroupStats(
                                count = merged.size,
                                latestNumber = merged.maxOfOrNull { it.number } ?: -1f,
                                latestDate = merged.maxOfOrNull { it.date } ?: 0L,
                                memberCounts = lists.associate { it.first.sourceId to it.second.size },
                                error = if (merged.isEmpty()) CombinedSource.failureSummary(
                                    g.members.map { CombinedSource.Member(it.sourceId, it.manga.url) }
                                ) else null
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        GroupStats(0, -1f, 0L, emptyMap(), e.message)
                    }
                    stats[g.key] = result
                    rebuildGroups()
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
            is BookItem -> BookActions.show(this, tag)
            is Long -> startActivity(MangaDetailsActivity.intent(this, tag))
            is SManga -> lifecycleScope.launch {
                val id = app.repo.getOrCreate(section.sourceId, tag).id
                startActivity(MangaDetailsActivity.intent(this@GlobalSearchActivity, id))
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
                        if (s.cards.isNotEmpty()) append("${s.cards.size} results")
                        if (s.chapterCount >= 0) append(" • ${s.chapterCount} chapters")
                        if (s.latest > 0) append(" • updated ${relativeTime(s.latest)}")
                    }
                }
            }.trimStart(' ', '•')
            h.progress.visibility = if (s.loading) View.VISIBLE else View.GONE
            h.error.visibility = if (s.error != null) View.VISIBLE else View.GONE
            h.error.text = s.error.orEmpty()
            h.recycler.visibility = if (s.cards.isEmpty()) View.GONE else View.VISIBLE
            h.cards.submitList(s.cards)
        }

        private object Diff : DiffUtil.ItemCallback<SourceResult>() {
            override fun areItemsTheSame(a: SourceResult, b: SourceResult) = a.sourceId == b.sourceId
            override fun areContentsTheSame(a: SourceResult, b: SourceResult) = a == b
        }
    }
}
