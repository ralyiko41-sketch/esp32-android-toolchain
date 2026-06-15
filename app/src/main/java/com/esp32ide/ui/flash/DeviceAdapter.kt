package com.esp32ide.ui.flash

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.esp32ide.R
import com.esp32ide.serial.SerialDevice

class DeviceAdapter(
    private val onSelect: (SerialDevice) -> Unit
) : ListAdapter<SerialDevice, DeviceAdapter.VH>(DIFF) {

    private var selectedPos = -1

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_device_name)
        val tvVid: TextView  = view.findViewById(R.id.tv_device_vid)
        init {
            view.setOnClickListener {
                val prev = selectedPos
                selectedPos = adapterPosition
                notifyItemChanged(prev)
                notifyItemChanged(selectedPos)
                onSelect(getItem(adapterPosition))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val dev = getItem(position)
        holder.tvName.text = dev.name
        holder.tvVid.text  = "VID:${dev.vid.toString(16).uppercase()}  PID:${dev.pid.toString(16).uppercase()}"
        holder.itemView.isSelected = position == selectedPos
        holder.itemView.alpha = if (position == selectedPos) 1f else 0.75f
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SerialDevice>() {
            override fun areItemsTheSame(a: SerialDevice, b: SerialDevice) =
                a.device.deviceId == b.device.deviceId
            override fun areContentsTheSame(a: SerialDevice, b: SerialDevice) = a == b
        }
    }
}
