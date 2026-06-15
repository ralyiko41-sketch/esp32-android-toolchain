package com.esp32ide.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.data.AppPreferences
import com.esp32ide.compiler.ArduinoCompiler
import com.esp32ide.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private val compiler by lazy { ArduinoCompiler(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupButtons()
        updateCompilerStatus()
    }

    private fun loadSettings() {
        binding.switchDark.isChecked   = prefs.darkTheme
        binding.etCloudUrl.setText(prefs.cloudCompilerUrl)
        binding.tvFontSize.text        = prefs.fontSize.toString()
        binding.switchAutoIndent.isChecked = prefs.autoIndent
        binding.switchWordWrap.isChecked   = prefs.wordWrap

        val flashBauds = listOf(115200, 230400, 460800, 921600)
        binding.spinnerFlashBaud.setSelection(flashBauds.indexOf(prefs.flashBaud).coerceAtLeast(0))
    }

    private fun setupButtons() {
        // Dark/light theme
        binding.switchDark.setOnCheckedChangeListener { _, checked ->
            prefs.darkTheme = checked
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // Font size
        binding.btnFontMinus.setOnClickListener {
            if (prefs.fontSize > 10) { prefs.fontSize--; binding.tvFontSize.text = prefs.fontSize.toString() }
        }
        binding.btnFontPlus.setOnClickListener {
            if (prefs.fontSize < 20) { prefs.fontSize++; binding.tvFontSize.text = prefs.fontSize.toString() }
        }

        // Editor toggles
        binding.switchAutoIndent.setOnCheckedChangeListener { _, v -> prefs.autoIndent = v }
        binding.switchWordWrap.setOnCheckedChangeListener { _, v -> prefs.wordWrap = v }

        // Save cloud URL
        binding.btnSaveUrl.setOnClickListener {
            prefs.cloudCompilerUrl = binding.etCloudUrl.text.toString().trim()
            Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show()
        }

        // Setup compiler (download arduino-cli + ESP32 core)
        binding.btnSetupCompiler.setOnClickListener { setupCompiler() }

        // Copy log
        binding.btnCopyLog.setOnClickListener {
            val logText = binding.tvSetupLog.text.toString()
            if (logText.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("ESP32IDE Setup Log", logText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // Flash baud
        val flashBauds = listOf(115200, 230400, 460800, 921600)
        binding.spinnerFlashBaud.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.flashBaud = flashBauds[pos]
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun updateCompilerStatus() {
        val cliReady  = compiler.isCliReady()
        val coreReady = compiler.isEsp32CoreInstalled()
        val localSupported = compiler.isLocalCompileSupported()

        binding.tvCompilerStatus.text = when {
            cliReady && coreReady -> "✓ Local Compiler Ready (Offline Mode Active)"
            cliReady && !coreReady -> "✓ Compiler found. Tap 'SETUP' to install ESP32 core components."
            localSupported && !cliReady -> "Local compile is possible. Tap 'SETUP COMPILER' below."
            !localSupported -> "⚠ Android security block. Please use Cloud Compiler or add compiler to jniLibs."
            else -> "arduino-cli not installed. Tap Setup Compiler below."
        }
        
        binding.tvCompilerStatus.setTextColor(
            resources.getColor(
                if (cliReady && coreReady) com.esp32ide.R.color.green 
                else if (cliReady) com.esp32ide.R.color.cyan
                else if (!localSupported) com.esp32ide.R.color.red
                else com.esp32ide.R.color.amber,
                null
            )
        )
        
        // Setup button behavior
        if (!localSupported) {
            binding.btnSetupCompiler.isEnabled = false
            binding.btnSetupCompiler.alpha = 0.5f
            binding.btnSetupCompiler.text = "Local Setup Restricted by Google"
        } else {
            binding.btnSetupCompiler.isEnabled = true
            binding.btnSetupCompiler.alpha = 1.0f
            binding.btnSetupCompiler.text = if (cliReady) "⬇ Install ESP32 Core" else "⬇ Setup Compiler"
        }
    }

    private fun setupCompiler() {
        Toast.makeText(context, "Starting Compiler Setup...", Toast.LENGTH_SHORT).show()
        binding.btnSetupCompiler.isEnabled = false
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
            updateCompilerStatus()
            finishSetup()
        }
    }

    private fun finishSetup() {
        binding.btnSetupCompiler.isEnabled = true
        binding.setupProgress.visibility = View.GONE
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
