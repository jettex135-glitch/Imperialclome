package com.imperialreader.ui.updates

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.imperialreader.R
import com.imperialreader.data.db.UpdateItem
import com.imperialreader.data.update.LibraryRefreshNotification
import com.imperialreader.ui.common.RowAdapter
import com.imperialreader.ui.common.RowItem
import com.imperialreader.ui.common.StarSpriteView
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.ui.reader.ReaderActivity
import com.imperialreader.util.app
import com.imperialreader.util.relativeTime
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class UpdatesFragment : Fragment(R.layout.fragment_updates) {

    private lateinit var sprite: StarSpriteView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireContext().app

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val emptyText = view.findViewById<TextView>(R.id.emptyText)
        sprite = view.findViewById(R.id.updateSprite)

        val adapter = RowAdapter(
            actionIcon = R.drawable.ic_download,
            onClick = { row ->
                val u = row.tag as UpdateItem
                startActivity(ReaderActivity.intent(requireContext(), u.chapter.id))
            },
            onLongClick = { row ->
                val u = row.tag as UpdateItem
                startActivity(MangaDetailsActivity.intent(requireContext(), u.chapter.mangaId))
            },
            onAction = { row ->
                val u = row.tag as UpdateItem
                viewLifecycleOwner.lifecycleScope.launch {
                    val manga = app.db.mangaDao().getById(u.chapter.mangaId) ?: return@launch
                    app.downloads.enqueue(manga, listOf(u.chapter))
                    requireContext().toast("Queued: ${u.chapter.name}")
                }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        toolbar.inflateMenu(R.menu.updates_menu)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_refresh) refresh(swipe)
            true
        }
        swipe.setOnRefreshListener { refresh(swipe) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.db.chapterDao().observeUpdates().collect { list ->
                    adapter.submitList(list.map { u ->
                        RowItem(
                            key = "u${u.chapter.id}",
                            title = u.mangaTitle,
                            subtitle = u.chapter.name + " • " + relativeTime(u.chapter.dateFetch),
                            cover = u.mangaCover,
                            dim = u.chapter.read || u.chapter.updateSeen,
                            showAction = !u.chapter.downloaded,
                            headers = app.sources.get(u.mangaSource)?.imageHeaders() ?: emptyMap(),
                            tag = u
                        )
                    })
                    emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun refresh(swipe: SwipeRefreshLayout) {
        viewLifecycleOwner.lifecycleScope.launch {
            swipe.isRefreshing = true
            sprite.start(speed = 1.5f)
            LibraryRefreshNotification.show(requireContext(), 0, 0)
            try {
                val n = requireContext().app.repo.updateLibrary(onProgress = { done, total ->
                    LibraryRefreshNotification.show(requireContext(), done, total)
                })
                requireContext().toast(if (n > 0) "$n new chapter(s)" else "No new chapters")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                requireContext().toast("Update failed: ${e.message}")
            } finally {
                LibraryRefreshNotification.clear(requireContext())
                sprite.stop()
                swipe.isRefreshing = false
            }
        }
    }
}
