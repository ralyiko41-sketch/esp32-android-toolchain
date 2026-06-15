package com.esp32ide.ui.examples

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.esp32ide.R
import com.esp32ide.utils.Example

class ExamplesAdapter(
    private var items: List<Example>,
    private val onClick: (Example) -> Unit
) : RecyclerView.Adapter<ExamplesAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView  = view.findViewById(R.id.tv_example_name)
        val tvDesc: TextView  = view.findViewById(R.id.tv_example_desc)
        val tvCat: TextView   = view.findViewById(R.id.tv_example_cat)
        init { view.setOnClickListener { onClick(items[adapterPosition]) } }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_example, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ex = items[pos]
        h.tvName.text = ex.name
        h.tvDesc.text = ex.description
        h.tvCat.text  = ex.category
    }

    fun updateList(list: List<Example>) { items = list; notifyDataSetChanged() }
    override fun getItemCount() = items.size
}
