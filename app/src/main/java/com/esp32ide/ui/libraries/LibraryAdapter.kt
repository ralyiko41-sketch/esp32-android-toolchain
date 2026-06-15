package com.esp32ide.ui.libraries

import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.esp32ide.R

class LibraryAdapter(
    private var items: List<LibraryItem>,
    private val onToggle: (LibraryItem) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView    = view.findViewById(R.id.tv_lib_name)
        val tvAuthor: TextView  = view.findViewById(R.id.tv_lib_author)
        val tvVersion: TextView = view.findViewById(R.id.tv_lib_version)
        val btnToggle: Button   = view.findViewById(R.id.btn_install)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val lib = items[position]
        holder.tvName.text    = lib.name
        holder.tvAuthor.text  = "by ${lib.author}"
        holder.tvVersion.text = "v${lib.version}"
        holder.btnToggle.text = if (lib.installed) "Remove" else "Install"
        holder.btnToggle.setOnClickListener { onToggle(lib) }
    }

    fun updateList(newList: List<LibraryItem>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
}
