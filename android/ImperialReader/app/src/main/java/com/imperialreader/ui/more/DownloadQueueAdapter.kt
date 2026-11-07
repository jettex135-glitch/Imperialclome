package com.imperialreader.ui.more

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.imperialreader.R
import com.imperialreader.data.download.DownloadQueueItem
import com.imperialreader.util.dp

internal sealed interface QueueRow {
    val key: String

    data class Chapter(val item: DownloadQueueItem, val nested: Boolean) : QueueRow {
        override val key = item.taskKey
    }

    data class Group(
        val mangaId: Long,
        val title: String,
        val items: List<DownloadQueueItem>,
        val expanded: Boolean
    ) : QueueRow {
        override val key = "group:$mangaId"
    }
}

internal class DownloadQueueAdapter(
    private val onClick: (DownloadQueueItem) -> Unit,
    private val onActions: (DownloadQueueItem) -> Unit,
    private val onPauseAllForEntry: (mangaId: Long, allPaused: Boolean) -> Unit
) : ListAdapter<QueueRow, DownloadQueueAdapter.VH>(Diff) {

    private val expandedMangaIds = mutableSetOf<Long>()
    private var queueItems: List<DownloadQueueItem> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.queueIcon)
        val title: TextView = view.findViewById(R.id.queueTitle)
        val detail: TextView = view.findViewById(R.id.queueDetail)
        val progress: ProgressBar = view.findViewById(R.id.queueProgress)
        val actions: ImageButton = view.findViewById(R.id.queueActions)
    }

    fun submitQueue(items: List<DownloadQueueItem>) {
        queueItems = items
        val grouped = items.filter { it.mangaId != null }.groupBy { it.mangaId!! }
        val emittedGroups = mutableSetOf<Long>()
        val rows = buildList {
            for (item in items) {
                val mangaId = item.mangaId
                val group = mangaId?.let(grouped::get)
                if (mangaId != null && group != null && group.size > GROUP_THRESHOLD) {
                    if (emittedGroups.add(mangaId)) {
                        val expanded = mangaId in expandedMangaIds
                        add(QueueRow.Group(mangaId, item.mangaTitle, group, expanded))
                        if (expanded) group.forEach { add(QueueRow.Chapter(it, nested = true)) }
                    }
                } else {
                    add(QueueRow.Chapter(item, nested = false))
                }
            }
        }
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download_queue, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        when (val row = getItem(position)) {
            is QueueRow.Group -> bindGroup(holder, row)
            is QueueRow.Chapter -> bindChapter(holder, row)
        }
    }

    private fun bindGroup(holder: VH, group: QueueRow.Group) {
        holder.title.text = group.title
        val pausedCount = group.items.count { it.paused }
        val activeCount = group.items.count { it.downloading && !it.paused }
        holder.detail.text = when {
            pausedCount == group.items.size -> "${group.items.size} chapters · Paused"
            pausedCount > 0 -> "${group.items.size} chapters · $activeCount downloading · $pausedCount paused"
            else -> "${group.items.size} chapters · $activeCount downloading"
        }
        holder.icon.visibility = View.VISIBLE
        holder.icon.setImageResource(R.drawable.ic_folder)
        holder.icon.contentDescription = if (group.expanded) "Collapse chapter group" else "Expand chapter group"
        holder.progress.visibility = View.GONE
        holder.actions.visibility = View.VISIBLE
        holder.actions.setOnClickListener {
            showGroupActions(holder.actions, group.mangaId, group.items.all { it.paused })
        }
        holder.itemView.setPadding(
            holder.itemView.context.dp(16),
            holder.itemView.context.dp(8),
            holder.itemView.context.dp(8),
            holder.itemView.context.dp(8)
        )
        holder.itemView.setOnClickListener {
            if (group.expanded) expandedMangaIds -= group.mangaId else expandedMangaIds += group.mangaId
            submitQueue(queueItems)
        }
        holder.itemView.setOnLongClickListener {
            showGroupActions(holder.itemView, group.mangaId, group.items.all { it.paused })
            true
        }
    }

    private fun bindChapter(holder: VH, row: QueueRow.Chapter) {
        val item = row.item
        holder.title.text = item.mangaTitle
        holder.detail.text = buildString {
            append(item.chapterName)
            append(" • ")
            when {
                item.paused -> append(if (item.downloading) "Paused · ${item.progress}%" else "Paused · Queued")
                item.downloading -> append(if (item.progress > 0) "Downloading · ${item.progress}%" else "Starting download…")
                else -> append("Queued")
            }
        }
        holder.icon.visibility = if (row.nested) View.VISIBLE else View.GONE
        if (row.nested) {
            holder.icon.setImageResource(R.drawable.ic_book)
            holder.icon.contentDescription = "Chapter"
        }
        holder.progress.visibility = if (item.downloading && !item.paused) View.VISIBLE else View.GONE
        holder.progress.isIndeterminate = item.downloading && item.progress == 0
        if (item.progress > 0) holder.progress.progress = item.progress
        holder.actions.visibility = if (item.chapterId != null) View.VISIBLE else View.GONE
        holder.actions.setOnClickListener {
            if (item.chapterId != null) onActions(item)
        }
        holder.itemView.setOnClickListener { if (item.mangaId != null) onClick(item) }
        holder.itemView.setOnLongClickListener {
            if (item.chapterId == null) false else {
                onActions(item)
                true
            }
        }
        holder.itemView.setPadding(
            holder.itemView.context.dp(16) + if (row.nested) holder.itemView.context.dp(20) else 0,
            holder.itemView.context.dp(8),
            holder.itemView.context.dp(8),
            holder.itemView.context.dp(8)
        )
    }

    private fun showGroupActions(anchor: View, mangaId: Long, allPaused: Boolean) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(if (allPaused) "Resume all for this entry" else "Pause all for this entry")
            setOnMenuItemClickListener {
                onPauseAllForEntry(mangaId, allPaused)
                true
            }
            show()
        }
    }

    private object Diff : DiffUtil.ItemCallback<QueueRow>() {
        override fun areItemsTheSame(oldItem: QueueRow, newItem: QueueRow) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: QueueRow, newItem: QueueRow) = oldItem == newItem
    }

    private companion object {
        const val GROUP_THRESHOLD = 5
    }
}
