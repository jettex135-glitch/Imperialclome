package com.imperialreader.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.source.Source
import com.imperialreader.ui.webview.WebViewActivity
import com.imperialreader.util.app
import com.imperialreader.util.dp
import com.imperialreader.util.toast

class SourcesFragment : Fragment(R.layout.fragment_sources) {

    private lateinit var adapter: SourceAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        adapter = SourceAdapter(
            onOpen = { s, latest -> startActivity(SourceBrowseActivity.intent(requireContext(), s.id, latest)) },
            onLong = { s -> showOptions(s) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        view.findViewById<MaterialButton>(R.id.btnAddSource).setOnClickListener { askCustomSource() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        if (!isAdded) return
        val sm = requireContext().app.sources
        adapter.submit(sm.visible(), requireContext().app.prefs.disabledSources)
    }

    private fun showOptions(s: Source) {
        val sm = requireContext().app.sources
        val disabled = s.id in requireContext().app.prefs.disabledSources
        val options = mutableListOf(
            if (disabled) "Enable" else "Disable",
            "Open in WebView"
        )
        if (sm.isCustom(s.id)) options.add("Remove")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(s.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Enable", "Disable" -> sm.setEnabled(s.id, disabled)
                    "Open in WebView" -> startActivity(WebViewActivity.intent(requireContext(), s.baseUrl))
                    "Remove" -> sm.removeCustom(s.id)
                }
                reload()
            }
            .show()
    }

    private fun askCustomSource() {
        val ctx = requireContext()
        val name = EditText(ctx).apply { hint = "Name"; setSingleLine() }
        val url = EditText(ctx).apply { hint = "example.com  (any domain: .com .org .cc …)"; setSingleLine() }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
            addView(name)
            addView(url)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Add a site by URL")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isBlank() || ctx.app.sources.normalizeBaseUrl(u) == null) {
                    ctx.toast("Enter a name and a valid site address")
                } else if (!ctx.app.sources.addCustom(n, u)) {
                    ctx.toast("That site is already built in")
                } else {
                    ctx.toast("Added — the site type is detected the first time you open it")
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class SourceAdapter(
        val onOpen: (Source, Boolean) -> Unit,
        val onLong: (Source) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.VH>() {

        private var items: List<Source> = emptyList()
        private var disabled: Set<String> = emptySet()

        fun submit(list: List<Source>, disabledIds: Set<String>) {
            items = list
            disabled = disabledIds
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.srcIcon)
            val name: TextView = v.findViewById(R.id.srcName)
            val lang: TextView = v.findViewById(R.id.srcLang)
            val latest: MaterialButton = v.findViewById(R.id.btnLatest)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = items[position]
            val off = s.id in disabled
            h.icon.text = s.name.take(1).uppercase()
            h.name.text = s.name
            h.lang.text = s.lang.uppercase() + (if (s.isAnime) " • ANIME" else "") + (if (s.nsfw) " • 18+" else "") + (if (off) " • disabled" else "")
            h.itemView.alpha = if (off) 0.4f else 1f
            h.itemView.setOnClickListener { onOpen(s, false) }
            h.itemView.setOnLongClickListener { onLong(s); true }
            h.latest.setOnClickListener { onOpen(s, true) }
        }
    }
}
