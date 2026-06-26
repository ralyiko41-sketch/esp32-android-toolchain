package com.esp32ide.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.esp32ide.data.AppPreferences
import com.esp32ide.databinding.DialogBoardConfigBinding

class BoardConfigDialog : DialogFragment() {

    private var _binding: DialogBoardConfigBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    
    var onApplied: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = DialogBoardConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        loadCurrentConfig()
        
        binding.btnSaveConfig.setOnClickListener {
            saveConfig()
            dismiss()
            Toast.makeText(context, "Board configuration applied", Toast.LENGTH_SHORT).show()
            onApplied?.invoke()
        }
    }

    private fun loadCurrentConfig() {
        val cpuValues = listOf("240", "160", "80", "40", "20", "10")
        binding.spinnerCpuFreq.setSelection(cpuValues.indexOf(prefs.cpuFreq).coerceAtLeast(0))

        val flashFreqs = listOf("80", "40")
        binding.spinnerFlashFreq.setSelection(flashFreqs.indexOf(prefs.flashFreq).coerceAtLeast(0))

        val modes = listOf("qio", "qout", "dio", "dout")
        binding.spinnerFlashMode.setSelection(modes.indexOf(prefs.flashMode).coerceAtLeast(0))

        val partitions = listOf("default", "default_fat", "no_ota", "noota_3g", "huge_app", "min_spiffs", "minimal")
        binding.spinnerPartitions.setSelection(partitions.indexOf(prefs.partitionScheme).coerceAtLeast(0))

        val debugs = listOf("none", "error", "warning", "info", "debug", "verbose")
        binding.spinnerDebugLevel.setSelection(debugs.indexOf(prefs.coreDebugLevel).coerceAtLeast(0))
    }

    private fun saveConfig() {
        val cpuValues = listOf("240", "160", "80", "40", "20", "10")
        prefs.cpuFreq = cpuValues[binding.spinnerCpuFreq.selectedItemPosition]

        val flashFreqs = listOf("80", "40")
        prefs.flashFreq = flashFreqs[binding.spinnerFlashFreq.selectedItemPosition]

        val modes = listOf("qio", "qout", "dio", "dout")
        prefs.flashMode = modes[binding.spinnerFlashMode.selectedItemPosition]

        val partitions = listOf("default", "default_fat", "no_ota", "noota_3g", "huge_app", "min_spiffs", "minimal")
        prefs.partitionScheme = partitions[binding.spinnerPartitions.selectedItemPosition]

        val debugs = listOf("none", "error", "warning", "info", "debug", "verbose")
        prefs.coreDebugLevel = debugs[binding.spinnerDebugLevel.selectedItemPosition]
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
