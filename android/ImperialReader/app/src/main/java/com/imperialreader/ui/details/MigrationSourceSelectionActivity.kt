package com.imperialreader.ui.details

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.TextWatcher
import android.text.Editable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.imperialreader.R
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.ui.browse.GlobalSearchActivity
import com.imperialreader.ui.common.loadCover
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.launch

class MigrationSourceSelectionActivity : com.imperialreader.ui.theme.ThemedAppCompatActivity() {
    private data class Destination(val id: String, val name: String)

    companion object {
        private const val EXTRA_MANGA_ID = "migration_manga_id"

        fun intent(ctx: android.content.Context, mangaId: Long) =
            android.content.Intent(ctx, MigrationSourceSelectionActivity::class.java)
                .putExtra(EXTRA_MANGA_ID, mangaId)
    }

    private lateinit var from: MangaEntity
    private lateinit var destinations: List<Destination>
    private lateinit var selected: BooleanArray
    private lateinit var selectAll: MaterialCheckBox
    private lateinit var selectedCount: TextView
    private lateinit var sourceChecks: List<MaterialCheckBox>
    private lateinit var noSourcesMatch: TextView
    private var bookMigration = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        if (mangaId < 0L) {
            finish()
            return
        }
        lifecycleScope.launch {
            from = app.db.mangaDao().getById(mangaId) ?: run {
                toast("The entry being migrated is no longer available")
                finish()
                return@launch
            }
            val bookSource = BookSources.get(from.source)
            bookMigration = bookSource?.isWebNovel == true
            destinations = when {
                bookMigration -> BookSources.all
                    .filter { it.inGlobalSearch && it.isWebNovel && it.id != from.source }
                    .map { Destination(it.id, it.name) }
                bookSource != null -> {
                    toast("Local EPUB/PDF entries cannot be migrated through manga sources. Browse compatible book catalogs instead.")
                    emptyList()
                }
                else -> app.sources.enabled()
                    .filter { it.id != from.source }
                    .map { Destination(it.id, it.name) }
            }
            if (destinations.isEmpty()) {
                if (!bookMigration && bookSource == null) toast("No other sources are available")
                finish()
                return@launch
            }
            buildPage()
        }
    }

    private fun buildPage() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val availableIds = destinations.mapTo(HashSet()) { it.id }
        val saved = app.prefs.migrationSourceIds.intersect(availableIds)
        selected = BooleanArray(destinations.size) { saved.isEmpty() || destinations[it].id in saved }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, android.R.attr.colorBackground))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Migrate"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
            setBackgroundColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, android.R.attr.colorBackground))
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val sourceContext = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        sourceContext.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            loadCover(from.displayCoverUrl, app.sources.get(from.source)?.imageHeaders() ?: emptyMap())
        }, LinearLayout.LayoutParams(dp(64), dp(88)))
        sourceContext.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MigrationSourceSelectionActivity).apply {
                text = from.title
                textSize = 16f
                setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurface))
            })
            addView(TextView(this@MigrationSourceSelectionActivity).apply {
                text = listOfNotNull(
                    app.sources.get(from.source)?.name ?: BookSources.get(from.source)?.name ?: from.source,
                    from.author?.takeIf(String::isNotBlank)
                ).joinToString(" • ")
                textSize = 12f
                setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(4), 0, dp(4))
            })
            from.description?.takeIf(String::isNotBlank)?.let { description ->
                addView(TextView(this@MigrationSourceSelectionActivity).apply {
                    text = description
                    maxLines = 3
                    textSize = 12f
                    setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(sourceContext)
        root.addView(TextView(this).apply {
            text = if (bookMigration) "Choose compatible web-novel extensions to search" else "Choose destination sources to search"
            textSize = 14f
            setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(20), dp(4), dp(20), dp(4))
        })

        val sourceSearch = EditText(this).apply {
            hint = "Search sources"
            setSingleLine(true)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurface))
            setHintTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        root.addView(sourceSearch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(16), dp(4), dp(16), dp(4))
        })

        selectAll = MaterialCheckBox(this).apply {
            text = "Select all"
            textSize = 15f
            setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setOnClickListener {
                val check = selected.any { !it }
                selected.indices.forEach { selected[it] = check }
                sourceChecks.forEach { it.isChecked = check }
                updateSelectionCount()
            }
        }
        root.addView(selectAll)
        selectedCount = TextView(this).apply {
            textSize = 12f
            setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(20), 0, dp(20), dp(4))
        }
        root.addView(selectedCount)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        sourceChecks = destinations.mapIndexed { index, source ->
            MaterialCheckBox(this).apply {
                text = source.name
                textSize = 14f
                setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurface))
                minHeight = dp(44)
                isChecked = selected[index]
                setOnCheckedChangeListener { _, checked ->
                    selected[index] = checked
                    updateSelectionCount()
                }
            }.also(list::addView)
        }
        noSourcesMatch = TextView(this).apply {
            text = "No sources match that search"
            textSize = 13f
            setTextColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MigrationSourceSelectionActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = ViewGroup.GONE
        }.also(list::addView)
        sourceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                var matching = 0
                sourceChecks.forEachIndexed { index, checkbox ->
                    val visible = destinations[index].name.contains(query, ignoreCase = true)
                    checkbox.visibility = if (visible) ViewGroup.VISIBLE else ViewGroup.GONE
                    if (visible) matching++
                }
                noSourcesMatch.visibility = if (matching == 0) ViewGroup.VISIBLE else ViewGroup.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        fun addAction(label: String, weight: Float, action: () -> Unit) {
            actions.addView(MaterialButton(this).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                setTextSize(12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                setOnClickListener { action() }
            })
        }
        addAction("Continue", 1f) { startSearch(combined = false) }
        if (!bookMigration) addAction("Create combined", 1.7f) { startSearch(combined = true) }
        addAction("Cancel", 1f) { finish() }
        root.addView(actions)

        setContentView(root)
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        val count = selected.count { it }
        selectedCount.text = "$count of ${destinations.size} sources selected"
        selectAll.isChecked = count == destinations.size
    }

    private fun startSearch(combined: Boolean) {
        val selectedIds = destinations.filterIndexed { index, _ -> selected[index] }
            .mapTo(LinkedHashSet()) { it.id }
        if (selectedIds.isEmpty()) {
            toast("Select at least one source")
            return
        }
        app.prefs.migrationSourceIds = selectedIds
        val searchIntent = if (bookMigration) {
            GlobalSearchActivity.bookMigrationIntent(this, from, selectedIds)
        } else if (combined) {
            GlobalSearchActivity.combinedMigrationIntent(this, from, selectedIds)
        } else {
            GlobalSearchActivity.migrationIntent(this, from, selectedIds)
        }
        startActivity(searchIntent)
    }
}
