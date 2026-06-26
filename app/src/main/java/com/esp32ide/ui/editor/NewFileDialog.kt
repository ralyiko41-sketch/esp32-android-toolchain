package com.esp32ide.ui.editor

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.esp32ide.R

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewFileDialog(private val onCreate: (String) -> Unit) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val sdf = SimpleDateFormat("dd/M/yy_HH:mm", Locale.getDefault())
        val defaultName = "sketch_${sdf.format(Date())}"
        
        val input = EditText(requireContext()).apply {
            hint = "sketch_name"
            setText(defaultName)
            setPadding(48, 32, 48, 16)
        }
        return AlertDialog.Builder(requireContext())
            .setTitle("New Sketch File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                onCreate(name)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
