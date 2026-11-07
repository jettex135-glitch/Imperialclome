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
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.source.Source
import com.imperialreader.ui.browse.GlobalSearchActivity
import com.imperialreader.ui.common.loadCover
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.launch

class MigrationSourceSelectionActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        private const val EXTRA_MANGA_ID = "migration_manga_id"

        fun intent(ctx: android.content.Context, mangaId: Long) =
            android.content.Intent(ctx, MigrationSourceSelectionActivity::class.java)
                .putExtra(EXTRA_MANGA_ID, mangaId)
    }

    private lateinit var from: MangaEntity
    private lateinit var sources: List<Source>
    private lateinit var selected: BooleanArray
    private lateinit var selectAll: MaterialCheckBox
    private lateinit var selectedCount: TextView
    private lateinit var sourceChecks: List<MaterialCheckBox>
    private lateinit var noSourcesMatch: TextView

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
            sources = app.sources.enabled().filter { it.id != from.source }
            if (sources.isEmpty()) {
                toast("No other sources are available")
                finish()
                return@launch
            }
            buildPage()
        }
    }

    private fun buildPage() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val availableIds = sources.mapTo(HashSet()) { it.id }
        val saved = app.prefs.migrationSourceIds.intersect(availableIds)
        selected = BooleanArray(sources.size) { saved.isEmpty() || sources[it].id in saved }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.black))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Migrate"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
            setBackgroundColor(getColor(R.color.black))
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
                setTextColor(getColor(R.color.on_surface))
            })
            addView(TextView(this@MigrationSourceSelectionActivity).apply {
                text = listOfNotNull(
                    app.sources.get(from.source)?.name ?: from.source,
                    from.author?.takeIf(String::isNotBlank)
                ).joinToString(" • ")
                textSize = 12f
                setTextColor(getColor(R.color.on_surface_variant))
                setPadding(0, dp(4), 0, dp(4))
            })
            from.description?.takeIf(String::isNotBlank)?.let { description ->
                addView(TextView(this@MigrationSourceSelectionActivity).apply {
                    text = description
                    maxLines = 3
                    textSize = 12f
                    setTextColor(getColor(R.color.on_surface_variant))
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(sourceContext)
        root.addView(TextView(this).apply {
            text = "Choose destination sources to search"
            textSize = 14f
            setTextColor(getColor(R.color.on_surface_variant))
            setPadding(dp(20), dp(4), dp(20), dp(4))
        })

        val sourceSearch = EditText(this).apply {
            hint = "Search sources"
            setSingleLine(true)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setTextColor(getColor(R.color.on_surface))
            setHintTextColor(getColor(R.color.on_surface_variant))
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
            setTextColor(getColor(R.color.on_surface))
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
            setTextColor(getColor(R.color.on_surface_variant))
            setPadding(dp(20), 0, dp(20), dp(4))
        }
        root.addView(selectedCount)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        sourceChecks = sources.mapIndexed { index, source ->
            MaterialCheckBox(this).apply {
                text = source.name
                textSize = 14f
                setTextColor(getColor(R.color.on_surface))
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
            setTextColor(getColor(R.color.on_surface_variant))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = ViewGroup.GONE
        }.also(list::addView)
        sourceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                var matching = 0
                sourceChecks.forEachIndexed { index, checkbox ->
                    val visible = sources[index].name.contains(query, ignoreCase = true)
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
        addAction("Create combined", 1.7f) { startSearch(combined = true) }
        addAction("Cancel", 1f) { finish() }
        root.addView(actions)

        setContentView(root)
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        val count = selected.count { it }
        selectedCount.text = "$count of ${sources.size} sources selected"
        selectAll.isChecked = count == sources.size
    }

    private fun startSearch(combined: Boolean) {
        val selectedIds = sources.filterIndexed { index, _ -> selected[index] }
            .mapTo(LinkedHashSet()) { it.id }
        if (selectedIds.isEmpty()) {
            toast("Select at least one source")
            return
        }
        app.prefs.migrationSourceIds = selectedIds
        val searchIntent = if (combined) {
            GlobalSearchActivity.combinedMigrationIntent(this, from, selectedIds)
        } else {
            GlobalSearchActivity.migrationIntent(this, from, selectedIds)
        }
        startActivity(searchIntent)
    }
}
