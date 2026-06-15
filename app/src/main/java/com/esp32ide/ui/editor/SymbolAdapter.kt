package com.esp32ide.ui.editor

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.esp32ide.R

class SymbolAdapter(
    private val symbols: List<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<SymbolAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tv: TextView = view.findViewById(R.id.tv_symbol)
        init { view.setOnClickListener { onClick(symbols[adapterPosition]) } }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_symbol, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = symbols[position]
    }

    override fun getItemCount() = symbols.size
}
