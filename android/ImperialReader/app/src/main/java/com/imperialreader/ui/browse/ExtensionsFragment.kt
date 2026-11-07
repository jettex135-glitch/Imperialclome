package com.imperialreader.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.button.MaterialButton
import com.imperialreader.R
import com.imperialreader.data.book.BookSource
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.LOCAL_SOURCE_ID

/** Browse → Extensions: the EPUB and web-novel catalogs (Project Gutenberg, Internet Archive, Royal Road, and more). */
class ExtensionsFragment : Fragment(R.layout.fragment_extensions) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val allSources = BookSources.all
        val search = view.findViewById<EditText>(R.id.searchExtensions)
        val empty = view.findViewById<TextView>(R.id.emptyExtensions)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val adapter = CatalogAdapter { source ->
            if (source.id == LOCAL_SOURCE_ID) {
                startActivity(LocalEntriesActivity.intent(requireContext()))
            } else {
                startActivity(BookBrowseActivity.intent(requireContext(), source.id))
            }
        }
        recycler.adapter = adapter
        fun filter(query: String) {
            val filtered = if (query.isBlank()) allSources else allSources.filter { source ->
                source.name.contains(query, ignoreCase = true) ||
                    source.description.contains(query, ignoreCase = true) ||
                    (if (source.isWebNovel) "web novel" else "epub pdf").contains(query, ignoreCase = true)
            }
            adapter.submitList(filtered)
            empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
        filter("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter(s?.toString()?.trim().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private class CatalogAdapter(
        val onOpen: (BookSource) -> Unit
    ) : ListAdapter<BookSource, CatalogAdapter.VH>(Diff) {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.extName)
            val info: TextView = v.findViewById(R.id.extInfo)
            val action: MaterialButton = v.findViewById(R.id.extAction)
        }

        private object Diff : DiffUtil.ItemCallback<BookSource>() {
            override fun areItemsTheSame(oldItem: BookSource, newItem: BookSource): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: BookSource, newItem: BookSource): Boolean =
                oldItem.name == newItem.name && oldItem.description == newItem.description
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_extension, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = getItem(position)
            h.name.text = s.name
            val type = when {
                s.id == LOCAL_SOURCE_ID -> "Local files"
                s.isWebNovel -> "Web novel"
                else -> "EPUB / PDF"
            }
            h.info.text = listOf(type, s.description).filter(String::isNotBlank).joinToString(" · ")
            h.action.text = "Browse"
            h.action.setOnClickListener { onOpen(s) }
            h.itemView.setOnClickListener { onOpen(s) }
        }
    }
}
