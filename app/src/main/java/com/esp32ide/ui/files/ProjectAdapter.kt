package com.esp32ide.ui.files

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.esp32ide.data.Project
import com.esp32ide.databinding.ItemProjectBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectAdapter(
    private val onProjectClick: (Project) -> Unit,
    private val onDeleteClick: (Project) -> Unit
) : ListAdapter<Project, ProjectAdapter.VH>(DiffCallback()) {

    inner class VH(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                onProjectClick(getItem(adapterPosition))
            }
            binding.root.setOnLongClickListener {
                onDeleteClick(getItem(adapterPosition))
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val project = getItem(position)
        holder.binding.tvProjectName.text = project.name
        
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        holder.binding.tvProjectInfo.text = "Updated: ${sdf.format(Date(project.updatedAt))}"
    }

    class DiffCallback : DiffUtil.ItemCallback<Project>() {
        override fun areItemsTheSame(oldItem: Project, newItem: Project): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Project, newItem: Project): Boolean = oldItem == newItem
    }
}
