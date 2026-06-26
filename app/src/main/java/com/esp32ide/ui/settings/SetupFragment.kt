package com.esp32ide.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.compiler.ArduinoCompiler
import com.esp32ide.databinding.FragmentSetupBinding
import kotlinx.coroutines.launch

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!
    private val compiler by lazy { ArduinoCompiler(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnStartSetup.setOnClickListener { startSetup() }
        
        binding.btnCopyLog.setOnClickListener {
            val logText = binding.tvSetupLog.text.toString()
            if (logText.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("ESP32IDE Setup Log", logText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startSetup() {
        Toast.makeText(context, "Starting Compiler Setup...", Toast.LENGTH_SHORT).show()
        binding.btnStartSetup.isEnabled = false
        binding.setupProgress.visibility = View.VISIBLE
        binding.layoutSetupLog.visibility = View.VISIBLE
        binding.tvSetupLog.text = ""

        fun log(msg: String) {
            activity?.runOnUiThread {
                binding.tvSetupLog.append("$msg\n")
                binding.scrollSetupLog.post { binding.scrollSetupLog.fullScroll(View.FOCUS_DOWN) }
            }
        }

        lifecycleScope.launch {
            if (!compiler.isCliReady()) {
                log("Downloading arduino-cli...")
                val ok = compiler.downloadArduinoCli { log(it) }
                if (!ok) { log("✗ Download failed"); finishSetup(); return@launch }
            } else {
                log("✓ arduino-cli already installed")
            }

            if (!compiler.isEsp32CoreInstalled()) {
                log("\nDownloading ESP32 core (~150MB, takes 3-5 min)...")
                log("Keep app open and connected to internet...")
                val ok = compiler.installEsp32Core { log(it) }
                if (!ok) { log("✗ Core install failed"); finishSetup(); return@launch }
            } else {
                log("✓ ESP32 core already installed")
            }

            log("\n🎉 Setup complete! You can now compile offline.")
            binding.tvSetupStatus.text = "Compiler is ready!"
            finishSetup()
        }
    }

    private fun finishSetup() {
        binding.btnStartSetup.isEnabled = true
        binding.setupProgress.visibility = View.GONE
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
