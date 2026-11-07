package com.imperialreader.ui.details

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.data.book.BookItem
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.repo.BookMigrationManager
import com.imperialreader.data.repo.MangaMigrationManager
import com.imperialreader.data.repo.MangaMigrationOptions
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.Source
import com.imperialreader.ui.books.BookActions
import com.imperialreader.ui.common.loadCover
import com.imperialreader.util.app
import com.imperialreader.util.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MassMigrationActivity : com.imperialreader.ui.theme.ThemedAppCompatActivity() {
    private enum class Kind { MANGA, WEB_NOVEL, EPUB }

    private sealed interface Candidate {
        val title: String
        val cover: String?
        data class Manga(val source: Source, val manga: SManga) : Candidate {
            override val title get() = manga.title
            override val cover get() = manga.thumbnailUrl
        }
        data class Book(val item: BookItem) : Candidate {
            override val title get() = item.title
            override val cover get() = item.coverUrl
        }
    }

    private data class QueueEntry(
        val original: MangaEntity,
        val kind: Kind,
        val sources: List<Pair<String, String>>,
        var sourceId: String? = null,
        var candidate: Candidate? = null,
        var status: String = "Choose a destination source",
        var active: Boolean = true,
        var statusView: TextView? = null,
        var candidateView: TextView? = null
    )

    companion object {
        private const val EXTRA_IDS = "mass_migration_ids"

        fun intent(context: android.content.Context, ids: List<Long>) =
            android.content.Intent(context, MassMigrationActivity::class.java)
                .putExtra(EXTRA_IDS, ids.toLongArray())
    }

    private val entries = mutableListOf<QueueEntry>()
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private lateinit var progress: ProgressBar
    private lateinit var migrateButton: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val ids = intent.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
            val dao = app.db.mangaDao()
            ids.distinct().forEach { id ->
                val manga = dao.getById(id) ?: return@forEach
                val bookSource = BookSources.get(manga.source)
                val kind = when {
                    bookSource?.isWebNovel == true -> Kind.WEB_NOVEL
                    bookSource != null -> Kind.EPUB
                    manga.source == LOCAL_SOURCE_ID &&
                        manga.url.substringBefore('?').endsWith(".epub", ignoreCase = true) -> Kind.EPUB
                    else -> Kind.MANGA
                }
                val sources = when (kind) {
                    Kind.MANGA -> app.sources.enabled().filter { it.id != manga.source }
                        .map { it.id to it.name }
                    Kind.WEB_NOVEL -> BookSources.all.filter { it.isWebNovel && it.id != manga.source }
                        .map { it.id to it.name }
                    Kind.EPUB -> BookSources.all.filter { !it.isWebNovel && it.inGlobalSearch }
                        .map { it.id to it.name }
                }
                entries += QueueEntry(manga, kind, sources)
            }
            if (entries.isEmpty()) {
                finish()
                return@launch
            }
            buildScreen()
        }
    }

    private fun buildScreen() {
        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Mass migration"
            textSize = 22f
        })
        count = TextView(this).apply { textSize = 14f }
        root.addView(count)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = entries.size
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        entries.forEach(::addEntryView)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        migrateButton = Button(this).apply {
            text = "Migrate matched"
            isEnabled = false
            setOnClickListener { processMigrations() }
        }
        actions.addView(migrateButton)
        root.addView(actions)
        setContentView(root)
        updateCount()
    }

    private fun addEntryView(entry: QueueEntry) {
        val pad = dp(10)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MassMigrationActivity, com.google.android.material.R.attr.colorSurfaceVariant))
                cornerRadius = dp(12).toFloat()
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Cover for ${entry.original.title}"
            loadCover(
                entry.original.displayCoverUrl,
                app.sources.get(entry.original.source)?.imageHeaders() ?: emptyMap()
            )
        }
        header.addView(cover, LinearLayout.LayoutParams(dp(54), dp(76)))
        header.addView(LinearLayout(this@MassMigrationActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(TextView(this@MassMigrationActivity).apply {
                text = entry.original.title
                textSize = 15f
            })
            addView(TextView(this@MassMigrationActivity).apply {
                text = when (entry.kind) {
                    Kind.MANGA -> "Manga"
                    Kind.WEB_NOVEL -> "Web novel"
                    Kind.EPUB -> "EPUB / PDF"
                }
                textSize = 12f
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener {
                if (!running) {
                    entry.active = false
                    card.visibility = View.GONE
                    updateCount()
                }
            }
        })
        card.addView(header)

        val sourceLabels = entry.sources.map { "${it.second} · ${it.first}" }
        val sourceInput = AutoCompleteTextView(this).apply {
            hint = "Search destination sources"
            threshold = 0
            setAdapter(ArrayAdapter(this@MassMigrationActivity, android.R.layout.simple_dropdown_item_1line, sourceLabels))
            setOnItemClickListener { _, _, _, _ ->
                entry.sourceId = entry.sources.firstOrNull {
                    "${it.second} · ${it.first}" == text.toString()
                }?.first
                entry.candidate = null
                entry.status = "Source selected · search and review a match"
                updateEntry(entry)
                updateCount()
            }
        }
        card.addView(sourceInput)
        val selectedSource = TextView(this).apply { textSize = 12f }
        entry.candidateView = selectedSource
        card.addView(selectedSource)
        val status = TextView(this).apply { textSize = 12f }
        entry.statusView = status
        card.addView(status)
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(Button(this).apply {
            text = "Find matches"
            isEnabled = entry.sources.isNotEmpty()
            setOnClickListener {
                val sourceId = entry.sourceId
                if (sourceId == null) {
                    sourceInput.showDropDown()
                    entry.status = "Select a compatible source first"
                    updateEntry(entry)
                } else {
                    findMatches(entry, sourceId)
                }
            }
        })
        card.addView(buttons)
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        updateEntry(entry)
    }

    private fun findMatches(entry: QueueEntry, sourceId: String) {
        if (running) return
        lifecycleScope.launch {
            entry.status = "Searching…"
            updateEntry(entry)
            try {
                val candidates = when (entry.kind) {
                    Kind.MANGA -> {
                        val source = app.sources.get(sourceId)
                            ?: throw IllegalStateException("Destination source is unavailable")
                        source.search(entry.original.title, 1).mangas.take(20)
                            .map { Candidate.Manga(source, it) }
                    }
                    Kind.WEB_NOVEL, Kind.EPUB -> {
                        val source = BookSources.get(sourceId)
                            ?: throw IllegalStateException("Destination catalog is unavailable")
                        val matches = source.search(entry.original.title, 1).take(20)
                        matches.map { Candidate.Book(it) }
                    }
                }
                if (candidates.isEmpty()) {
                    entry.candidate = null
                    entry.status = "No matches · needs manual migration"
                } else {
                    val labels = candidates.map { candidate ->
                        "${candidate.title}${(candidate as? Candidate.Book)?.item?.author?.let { " — $it" }.orEmpty()}"
                    }.toTypedArray()
                    MaterialAlertDialogBuilder(this@MassMigrationActivity)
                        .setTitle("Review match for ${entry.original.title}")
                        .setItems(labels) { _, selected ->
                            entry.candidate = candidates[selected]
                            entry.status = "Match selected · review before migrating"
                            updateEntry(entry)
                            updateCount()
                        }
                        .setNegativeButton("No match") { _, _ ->
                            entry.candidate = null
                            entry.status = "Skipped · needs manual migration"
                            updateEntry(entry)
                            updateCount()
                        }
                        .show()
                    entry.status = "${candidates.size} matches found · choose one"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entry.candidate = null
                entry.status = "Search failed: ${e.message ?: "unknown error"}"
            }
            updateEntry(entry)
            updateCount()
        }
    }

    private fun processMigrations() {
        val selected = entries.filter { it.active }
        val matched = selected.filter { it.candidate != null }
        if (matched.isEmpty() || running) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Migrate ${matched.size} entries?")
            .setMessage("Each reviewed match will be processed one at a time. Original entries will remain in your library.")
            .setPositiveButton("Start") { _, _ -> runMigrations(matched) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runMigrations(matched: List<QueueEntry>) {
        running = true
        migrateButton.isEnabled = false
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var completed = 0
            matched.forEachIndexed { index, entry ->
                entry.status = "Migrating ${index + 1} of ${matched.size}…"
                updateEntry(entry)
                try {
                    val result = when (val target = entry.candidate) {
                        is Candidate.Manga -> MangaMigrationManager(app.repo).migrate(
                            from = entry.original,
                            target = target.manga,
                            targetSourceId = target.source.id,
                            options = MangaMigrationOptions(),
                            replaceOriginal = false
                        ).manga.title
                        is Candidate.Book -> {
                            when (entry.kind) {
                                Kind.WEB_NOVEL -> BookMigrationManager(app.repo).migrate(entry.original, target.item).manga.title
                                Kind.EPUB -> {
                                    BookActions.importBook(
                                        this@MassMigrationActivity,
                                        target.item,
                                        categoryId = entry.original.categoryId,
                                        dateAdded = entry.original.dateAdded
                                    )
                                    "imported to local library"
                                }
                                Kind.MANGA -> throw IllegalStateException("Manga needs a manga-source match")
                            }
                        }
                        null -> throw IllegalStateException("Select a destination before migrating")
                    }
                    entry.status = "Succeeded · $result"
                    completed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    entry.status = "Failed: ${e.message ?: "unknown error"}"
                }
                progress.progress = index + 1
                updateEntry(entry)
            }
            running = false
            val failed = matched.size - completed
            MaterialAlertDialogBuilder(this@MassMigrationActivity)
                .setTitle(if (failed == 0) "Migration complete" else "Migration partially complete")
                .setMessage("$completed succeeded\n$failed failed or skipped\n${entries.count { it.active && it.candidate == null }} need manual matching\nOriginal entries were kept.")
                .setPositiveButton("Done") { _, _ -> finish() }
                .setNegativeButton("Review results", null)
                .show()
            updateCount()
        }
    }

    private fun updateEntry(entry: QueueEntry) {
        entry.statusView?.text = entry.status
        entry.candidateView?.text = buildString {
            val sourceName = entry.sourceId?.let { id ->
                entry.sources.firstOrNull { it.first == id }?.second
            }
            if (sourceName != null) append("Destination: ").append(sourceName).append('\n')
            entry.candidate?.let { append("Proposed match: ").append(it.title) }
        }
    }

    private fun updateCount() {
        val active = entries.filter { it.active }
        count.text = "${active.size} selected · ${active.count { it.candidate != null }} matches reviewed"
        migrateButton.isEnabled = !running && active.any { it.candidate != null }
    }
}
