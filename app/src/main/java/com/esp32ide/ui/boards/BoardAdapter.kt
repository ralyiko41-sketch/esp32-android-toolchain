package com.esp32ide.ui.boards

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.esp32ide.R
import com.esp32ide.utils.BoardEntry

class BoardAdapter(
    private val onSelect: (BoardEntry) -> Unit
) : ListAdapter<BoardEntry, BoardAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_board_name)
        val tvFqbn: TextView = view.findViewById(R.id.tv_board_fqbn)
        init {
            view.setOnClickListener { onSelect(getItem(adapterPosition)) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_board, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val board = getItem(position)
        holder.tvName.text = board.name
        holder.tvFqbn.text = board.fqbn
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<BoardEntry>() {
            override fun areItemsTheSame(a: BoardEntry, b: BoardEntry) = a.fqbn == b.fqbn
            override fun areContentsTheSame(a: BoardEntry, b: BoardEntry) = a == b
        }
    }
}
