package com.imperialreader.ui.library

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.withTransaction
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.imperialreader.R
import com.imperialreader.data.AppPrefs
import com.imperialreader.data.db.CategoryEntity
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.LibraryItem
import com.imperialreader.ui.browse.GlobalSearchActivity
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.LibraryActions
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.common.StarSpriteView
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.dp
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

class LibraryFragment : Fragment(R.layout.fragment_library) {

    private val query = MutableStateFlow("")
    private val tick = MutableStateFlow(0)
    private val selectedCategory = MutableStateFlow(-1L) // -1 = no category filter, 0 = Default, else category id
    private var shownCount = 0
    private var rebuildingTabs = false
    private var categories: List<CategoryEntity> = emptyList()
    private var visibleItems: List<LibraryItem> = emptyList()
    private var selectionMode = false
    private val selectedIds = linkedSetOf<Long>()

    private lateinit var adapter: MangaCardAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var tabs: TabLayout
    private lateinit var sprite: StarSpriteView
    private lateinit var toolbar: MaterialToolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireContext().app
        val prefs = app.prefs
        selectedCategory.value = prefs.libraryCategory

        toolbar = view.findViewById(R.id.toolbar)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe)
        val emptyView = view.findViewById<LinearLayout>(R.id.emptyView)
        val emptyText = view.findViewById<TextView>(R.id.emptyText)
        val btnGlobal = view.findViewById<Button>(R.id.btnGlobalSearch)
        recycler = view.findViewById(R.id.recycler)
        tabs = view.findViewById(R.id.categoryTabs)
        sprite = view.findViewById(R.id.updateSprite)

        adapter = MangaCardAdapter(
            onClick = { item ->
                val id = item.tag as Long
                if (selectionMode) toggleSelection(id)
                else startActivity(MangaDetailsActivity.intent(requireContext(), id))
            },
            onLongClick = { item ->
                val id = item.tag as Long
                if (id in selectedIds) showSelectionActions() else toggleSelection(id)
            },
            onTitleLongClick = { item ->
                val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Title", item.title))
                requireContext().toast("Title copied")
            }
        )
        recycler.layoutManager = GridLayoutManager(requireContext(), prefs.libraryColumns)
        recycler.adapter = adapter
        val categorySwipe = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < requireContext().dp(48) || abs(dx) <= abs(dy)) return false
                moveCategory(if (dx < 0) 1 else -1)
                return true
            }
        })
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                categorySwipe.onTouchEvent(e)
                return false
            }
        })

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!rebuildingTabs) setSelectedCategory(tab.tag as Long)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (rebuildingTabs) return
                rebuildingTabs = true
                tabs.selectTab(null)
                rebuildingTabs = false
                setSelectedCategory(-1L)
            }
        })

        toolbar.inflateMenu(R.menu.library_menu)
        val searchView = toolbar.menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = "Search library"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                val text = q.orEmpty().trim()
                query.value = text
                // Nothing matched locally → fall through to a global search automatically.
                if (text.isNotBlank() && shownCount == 0) openGlobal(text)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                query.value = newText.orEmpty().trim()
                return true
            }
        })
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_filter -> showFilterDialog()
                R.id.action_sort -> showSortDialog()
                R.id.action_categories -> manageCategories()
                R.id.action_select -> if (selectionMode) cancelSelection() else {
                    selectionMode = true
                    updateSelectionMenu()
                }
                R.id.action_select_all -> selectVisible()
                R.id.action_bulk_category -> moveSelectedToCategory()
                R.id.action_bulk_read -> markSelectedAsRead()
                R.id.action_bulk_unread -> markSelectedAsUnread()
                R.id.action_bulk_remove -> confirmRemoveSelected()
                R.id.action_cancel_selection -> cancelSelection()
            }
            true
        }
        updateSelectionMenu()

        btnGlobal.setOnClickListener { openGlobal(query.value) }

        swipe.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch {
                sprite.start()
                try {
                    val n = app.repo.updateLibrary()
                    requireContext().toast(if (n > 0) "$n new chapter(s)" else "Library is up to date")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    requireContext().toast("Update failed: ${e.message}")
                } finally {
                    sprite.stop()
                    swipe.isRefreshing = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.db.categoryDao().observeAll().collect { rebuildTabs(it) } }
                launch {
                    combine(app.db.mangaDao().observeLibrary(), query, tick, selectedCategory) { items, q, _, cat ->
                        Triple(items, q, process(items, q, cat))
                    }.collect { (all, q, shown) ->
                        shownCount = shown.size
                        visibleItems = shown
                        adapter.submitList(shown.map { toCard(it) })
                        updateSelectionMenu()
                        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
                        when {
                            all.isEmpty() -> {
                                emptyText.text = "Your library is empty.\nAdd manga from Browse, or put files in the local/ folder and scan from More."
                                btnGlobal.visibility = View.GONE
                            }
                            q.isNotBlank() -> {
                                emptyText.text = "No library results for \"$q\""
                                btnGlobal.visibility = View.VISIBLE
                            }
                            else -> {
                                emptyText.text = "Nothing here with the current filters / category."
                                btnGlobal.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (recycler.layoutManager as? GridLayoutManager)?.spanCount = requireContext().app.prefs.libraryColumns
        tick.value = tick.value + 1
        updateSelectionMenu()
    }

    // ---------------------------------------------------------------- categories

    private fun rebuildTabs(list: List<CategoryEntity>) {
        categories = list
        rebuildingTabs = true
        tabs.removeAllTabs()
        tabs.visibility = View.VISIBLE
        val entries = listOf(0L to "Default") + list.map { it.id to it.name }
        if (selectedCategory.value >= 0 && entries.none { it.first == selectedCategory.value }) {
            setSelectedCategory(-1L)
        }
        for ((id, name) in entries) {
            val tab = tabs.newTab().setText(name).setTag(id)
            tabs.addTab(tab, id == selectedCategory.value)
        }
        if (selectedCategory.value == -1L) tabs.selectTab(null)
        rebuildingTabs = false
    }

    private fun setSelectedCategory(categoryId: Long) {
        selectedCategory.value = categoryId
        requireContext().app.prefs.libraryCategory = categoryId
    }

    private fun moveCategory(offset: Int) {
        val filters = listOf(-1L, 0L) + categories.map { it.id }
        val current = filters.indexOf(selectedCategory.value).coerceAtLeast(0)
        val target = (current + offset).coerceIn(0, filters.lastIndex)
        if (target == current) return

        val categoryId = filters[target]
        rebuildingTabs = true
        if (categoryId == -1L) tabs.selectTab(null) else tabs.selectTab(tabs.getTabAt(target - 1))
        rebuildingTabs = false
        setSelectedCategory(categoryId)
    }

    private fun manageCategories() {
        val ctx = requireContext()
        val names = categories.map { it.name }.toTypedArray() + "＋ New category"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Categories")
            .setItems(names) { _, which ->
                if (which == categories.size) askCategoryName(null) else categoryOptions(categories[which])
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun categoryOptions(category: CategoryEntity) {
        val index = categories.indexOfFirst { it.id == category.id }
        val actions = buildList {
            if (index > 0) add("Move up")
            if (index in 0 until categories.lastIndex) add("Move down")
            add("Rename")
            add("Delete")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(category.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Move up" -> moveCategory(category, -1)
                    "Move down" -> moveCategory(category, 1)
                    "Rename" -> askCategoryName(category)
                    "Delete" -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete “${category.name}”?")
                        .setMessage("Entries in it move back to Default. Nothing is removed from your library.")
                        .setPositiveButton("Delete") { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                val db = requireContext().app.db
                                db.withTransaction {
                                    db.mangaDao().clearCategory(category.id)
                                    db.categoryDao().delete(category.id)
                                    db.categoryDao().getAll().forEachIndexed { order, remaining ->
                                        db.categoryDao().setSortOrder(remaining.id, order)
                                    }
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    }
                }
            }
            .show()
    }

    private fun moveCategory(category: CategoryEntity, offset: Int) {
        val ordered = categories.toMutableList()
        val from = ordered.indexOfFirst { it.id == category.id }
        val to = from + offset
        if (from < 0 || to !in ordered.indices) return
        val moved = ordered.removeAt(from)
        ordered.add(to, moved)
        viewLifecycleOwner.lifecycleScope.launch {
            val db = requireContext().app.db
            db.withTransaction {
                ordered.forEachIndexed { order, item ->
                    db.categoryDao().setSortOrder(item.id, order)
                }
            }
        }
    }

    private fun askCategoryName(existing: CategoryEntity?) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Category name"
            setSingleLine()
            setText(existing?.name.orEmpty())
        }
        val box = LinearLayout(ctx).apply {
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
            addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) "New category" else "Rename category")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val dao = ctx.app.db.categoryDao()
                    if (existing == null) {
                        val nextOrder = (categories.maxOfOrNull { it.sortOrder } ?: -1) + 1
                        dao.insert(CategoryEntity(name = name, sortOrder = nextOrder))
                    }
                    else dao.update(existing.copy(name = name))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- long-press menu

    private fun updateSelectionMenu() {
        if (!::toolbar.isInitialized || toolbar.menu.size() == 0) return
        val menu = toolbar.menu
        val selecting = selectionMode
        toolbar.title = when {
            !selecting -> "Library"
            selectedIds.isEmpty() -> "Select entries"
            else -> "${selectedIds.size} selected"
        }
        menu.findItem(R.id.action_search)?.isVisible = !selecting
        menu.findItem(R.id.action_filter)?.isVisible = !selecting
        menu.findItem(R.id.action_sort)?.isVisible = !selecting
        menu.findItem(R.id.action_categories)?.isVisible = !selecting
        menu.findItem(R.id.action_select)?.isVisible = !selecting
        menu.findItem(R.id.action_select_all)?.apply {
            isVisible = selecting
            title = if (visibleItems.isNotEmpty() && visibleItems.all { it.manga.id in selectedIds }) {
                "Clear visible selection"
            } else {
                "Select visible"
            }
        }
        menu.findItem(R.id.action_bulk_category)?.isVisible = selecting && selectedIds.isNotEmpty()
        menu.findItem(R.id.action_bulk_read)?.apply {
            isVisible = selecting && selectedIds.isNotEmpty()
            title = "Mark selected as read"
        }
        menu.findItem(R.id.action_bulk_unread)?.isVisible = selecting && selectedIds.isNotEmpty()
        menu.findItem(R.id.action_bulk_remove)?.isVisible = selecting && selectedIds.isNotEmpty()
        menu.findItem(R.id.action_cancel_selection)?.isVisible = selecting
    }

    private fun toggleSelection(id: Long) {
        if (!selectionMode) selectionMode = true
        if (!selectedIds.add(id)) selectedIds.remove(id)
        if (selectedIds.isEmpty()) selectionMode = false
        updateSelectionMenu()
        tick.value = tick.value + 1
    }

    private fun selectVisible() {
        val ids = visibleItems.map { it.manga.id }
        if (ids.isNotEmpty() && ids.all { it in selectedIds }) selectedIds.removeAll(ids.toSet())
        else selectedIds.addAll(ids)
        if (selectedIds.isEmpty()) selectionMode = false
        updateSelectionMenu()
        tick.value = tick.value + 1
    }

    private fun showSelectionActions() {
        MaterialAlertDialogBuilder(requireContext())
            .setItems(arrayOf("Mark as read", "Mark as unread", "Select all", "Cancel selection")) { _, which ->
                when (which) {
                    0 -> markSelectedReadState(true)
                    1 -> markSelectedReadState(false)
                    2 -> {
                        selectedIds.addAll(visibleItems.map { it.manga.id })
                        selectionMode = selectedIds.isNotEmpty()
                        updateSelectionMenu()
                        tick.value = tick.value + 1
                    }
                    3 -> cancelSelection()
                }
            }
            .show()
    }

    private fun cancelSelection() {
        selectionMode = false
        selectedIds.clear()
        updateSelectionMenu()
        tick.value = tick.value + 1
    }

    private fun moveSelectedToCategory() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val category = LibraryActions.chooseCategory(ctx, 0L) ?: return@launch
            ids.chunked(400).forEach { ctx.app.db.mangaDao().setCategory(it, category) }
            cancelSelection()
            ctx.toast("Moved ${ids.size} entries")
        }
    }

    private fun confirmRemoveSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        showRemoveOptions(ids.size) { deleteDownloads ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    removeEntries(ids, deleteDownloads)
                    cancelSelection()
                    requireContext().toast("Removed ${ids.size} entries from the library")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    requireContext().toast("Could not remove entries: ${e.message}")
                }
            }
        }
    }

    private fun showRemoveOptions(count: Int, onRemove: (Boolean) -> Unit) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Remove $count entr${if (count == 1) "y" else "ies"} from library?")
            .setItems(arrayOf("Remove and keep downloaded files", "Delete downloaded files and remove")) { _, which ->
                onRemove(which == 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markSelectedAsRead() {
        markSelectedReadState(true)
    }

    private fun markSelectedAsUnread() {
        markSelectedReadState(false)
    }

    private fun markSelectedReadState(read: Boolean) {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            ids.chunked(400).forEach { requireContext().app.db.chapterDao().setReadForManga(it, read) }
            requireContext().toast("Marked ${ids.size} entries as ${if (read) "read" else "unread"}")
            cancelSelection()
        }
    }

    private suspend fun removeEntries(ids: List<Long>, deleteDownloads: Boolean) {
        val app = requireContext().app
        if (deleteDownloads) {
            ids.forEach { mangaId ->
                val manga = app.db.mangaDao().getById(mangaId) ?: return@forEach
                app.db.chapterDao().getAll(mangaId).filter { it.downloaded }.forEach { chapter ->
                    app.downloads.deleteDownload(manga, chapter)
                }
            }
        }
        ids.chunked(400).forEach { app.db.mangaDao().removeFromLibrary(it) }
    }

    private fun showItemMenu(mangaId: Long, title: String) {
        val ctx = requireContext()
        val options = arrayOf("Select for bulk actions", "Mark as read", "Mark as unread", "Change category", "Remove from library")
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setItems(options) { _, which ->
                if (which == 0) {
                    selectionMode = true
                    selectedIds.add(mangaId)
                    updateSelectionMenu()
                    tick.value = tick.value + 1
                    return@setItems
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val db = ctx.app.db
                    val manga = db.mangaDao().getById(mangaId) ?: return@launch
                    when (which) {
                        1 -> {
                            db.chapterDao().setReadForManga(mangaId, true)
                            ctx.toast("Marked all chapters as read")
                        }
                        2 -> {
                            db.chapterDao().setReadForManga(mangaId, false)
                            ctx.toast("Marked all chapters as unread")
                        }
                        3 -> {
                            val picked = LibraryActions.chooseCategory(ctx, manga.categoryId)
                            if (picked != null) db.mangaDao().setCategory(mangaId, picked)
                        }
                        4 -> showRemoveOptions(1) { deleteDownloads ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    removeEntries(listOf(mangaId), deleteDownloads)
                                    ctx.toast("Removed from library")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    ctx.toast("Could not remove entry: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- list

    private fun process(items: List<LibraryItem>, q: String, category: Long): List<LibraryItem> {
        val prefs = requireContext().app.prefs
        var list = items.filter { q.isBlank() || it.manga.title.contains(q, ignoreCase = true) }
        if (category >= 0) list = list.filter { it.manga.categoryId == category }
        val isUnread = { item: LibraryItem -> item.unread > 0 }
        val isBookmarked = { item: LibraryItem -> item.bookmarked > 0 }
        val isDownloaded = { item: LibraryItem -> item.downloadedCount > 0 || item.manga.source == LOCAL_SOURCE_ID }
        list = applyFilterMode(list, prefs.filterUnreadMode, isUnread)
        list = applyFilterMode(list, prefs.filterDownloadedMode, isDownloaded)
        list = applyFilterMode(list, prefs.filterBookmarkedMode, isBookmarked)
        if (prefs.downloadedOnly) list = list.filter(isDownloaded)
        return when (prefs.sortMode) {
            1 -> list.sortedByDescending { it.lastRead ?: 0L }
            2 -> list.sortedByDescending { it.unread }
            3 -> list.sortedByDescending { it.manga.dateAdded }
            4 -> list.sortedByDescending { it.lastUpdated ?: 0L }
            else -> list.sortedBy { it.manga.title.lowercase() }
        }
    }

    private fun toCard(item: LibraryItem): CardItem {
        val prefs = requireContext().app.prefs
        val m = item.manga
        return CardItem(
            key = "m${m.id}",
            title = m.title,
            cover = m.coverUrl,
            badge1 = if (prefs.showUnreadBadge && item.unread > 0) item.unread.toString() else null,
            badge2 = if (!prefs.showDownloadBadge) null else when {
                m.source == LOCAL_SOURCE_ID -> "Local"
                item.downloadedCount > 0 -> "↓${item.downloadedCount}"
                else -> null
            },
            headers = requireContext().app.sources.get(m.source)?.imageHeaders() ?: emptyMap(),
            selected = m.id in selectedIds,
            tag = m.id
        )
    }

    private fun openGlobal(q: String) {
        if (q.isBlank()) return
        startActivity(GlobalSearchActivity.intent(requireContext(), q))
    }

    private fun showFilterDialog() {
        val prefs = requireContext().app.prefs
        val context = requireContext()
        val filters = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
        }
        fun addFilter(label: String, initial: Int, update: (Int) -> Unit) {
            var mode = initial
            val drawable = FilterStateDrawable(context.dp(24), mode)
            val checkbox = MaterialCheckBox(context).apply {
                text = label
                buttonDrawable = drawable
                buttonTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                isChecked = false
                setOnClickListener {
                    mode = when (mode) {
                        AppPrefs.FILTER_NONE -> AppPrefs.FILTER_INCLUDE
                        AppPrefs.FILTER_INCLUDE -> AppPrefs.FILTER_EXCLUDE
                        else -> AppPrefs.FILTER_NONE
                    }
                    drawable.mode = mode
                    isChecked = false
                    update(mode)
                    tick.value = tick.value + 1
                }
            }
            filters.addView(checkbox, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        addFilter("Unread", prefs.filterUnreadMode) { prefs.filterUnreadMode = it }
        addFilter("Downloaded", prefs.filterDownloadedMode) { prefs.filterDownloadedMode = it }
        addFilter("Bookmarked", prefs.filterBookmarkedMode) { prefs.filterBookmarkedMode = it }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Filter")
            .setView(filters)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showSortDialog() {
        val prefs = requireContext().app.prefs
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sort by")
            .setSingleChoiceItems(
                arrayOf("Alphabetical", "Last read", "Unread count", "Date added", "Recently updated"), prefs.sortMode
            ) { dialog, which ->
                prefs.sortMode = which
                tick.value = tick.value + 1
                dialog.dismiss()
            }
            .show()
    }
}

private fun <T> applyFilterMode(items: List<T>, mode: Int, predicate: (T) -> Boolean): List<T> = when (mode) {
    AppPrefs.FILTER_INCLUDE -> items.filter(predicate)
    AppPrefs.FILTER_EXCLUDE -> items.filterNot(predicate)
    else -> items
}

private class FilterStateDrawable(
    private val sizePx: Int,
    mode: Int
) : Drawable() {
    var mode: Int = mode
        set(value) {
            field = value
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val side = minOf(bounds.width(), bounds.height(), sizePx).toFloat()
        if (side <= 0f) return
        val left = bounds.left + (bounds.width() - side) / 2f
        val top = bounds.top + (bounds.height() - side) / 2f
        val inset = side * 0.12f
        val box = RectF(left + inset, top + inset, left + side - inset, top + side - inset)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (side * 0.08f).coerceAtLeast(1f)
        paint.color = if (mode == AppPrefs.FILTER_NONE) 0xFF909090.toInt() else 0xFFC9A354.toInt()
        canvas.drawRoundRect(box, side * 0.12f, side * 0.12f, paint)
        if (mode == AppPrefs.FILTER_NONE) return

        val mark = Path()
        if (mode == AppPrefs.FILTER_INCLUDE) {
            mark.moveTo(left + side * 0.27f, top + side * 0.52f)
            mark.lineTo(left + side * 0.44f, top + side * 0.68f)
            mark.lineTo(left + side * 0.74f, top + side * 0.34f)
        } else {
            mark.moveTo(left + side * 0.34f, top + side * 0.34f)
            mark.lineTo(left + side * 0.66f, top + side * 0.66f)
            mark.moveTo(left + side * 0.66f, top + side * 0.34f)
            mark.lineTo(left + side * 0.34f, top + side * 0.66f)
        }
        paint.color = 0xFFFFF8E8.toInt()
        canvas.drawPath(mark, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
}
