package com.imperialreader.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.imperialreader.R

data class RowItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val cover: String?,
    val dim: Boolean = false,
    val showAction: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val tag: Any? = null
)

/** Small-cover list row used by Updates and History. */
class RowAdapter(
    @DrawableRes private val actionIcon: Int,
    private val onClick: (RowItem) -> Unit,
    private val onLongClick: (RowItem) -> Unit,
    private val onAction: (RowItem) -> Unit
) : ListAdapter<RowItem, RowAdapter.VH>(Diff) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.rowCover)
        val title: TextView = view.findViewById(R.id.rowTitle)
        val sub: TextView = view.findViewById(R.id.rowSub)
        val action: ImageButton = view.findViewById(R.id.rowAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.sub.text = item.subtitle
        holder.cover.loadCover(item.cover, item.headers)
        holder.itemView.alpha = if (item.dim) 0.5f else 1f
        holder.action.setImageResource(actionIcon)
        holder.action.visibility = if (item.showAction) View.VISIBLE else View.INVISIBLE
        holder.action.setOnClickListener { onAction(item) }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    private object Diff : DiffUtil.ItemCallback<RowItem>() {
        override fun areItemsTheSame(a: RowItem, b: RowItem) = a.key == b.key
        override fun areContentsTheSame(a: RowItem, b: RowItem) = a == b
    }
}
