package com.esp32ide.ui.flash

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.MainActivity
import com.esp32ide.R
import com.esp32ide.compiler.ArduinoCompiler
import com.esp32ide.data.AppPreferences
import com.esp32ide.databinding.FragmentFlashBinding
import com.esp32ide.serial.EspFlasher
import com.esp32ide.serial.SerialDevice
import com.esp32ide.serial.UsbSerialManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class FlashFragment : Fragment() {

    private var _binding: FragmentFlashBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private lateinit var serialManager: UsbSerialManager
    private var selectedDevice: SerialDevice? = null
    private var deviceAdapter: DeviceAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFlashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        serialManager = (activity as MainActivity).serialManager

        setupDeviceList()
        setupButtons()
        refreshDevices()
        updateCompileStatus()
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter { device ->
            selectedDevice = device
            binding.btnFlash.isEnabled = true
            binding.tvSelectedPort.text = "Selected: ${device.name}"
        }
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupButtons() {
        binding.btnRefresh.setOnClickListener { refreshDevices() }
        binding.btnCompileFlash.setOnClickListener { compileAndFlash() }
        binding.btnFlash.isEnabled = false
        binding.btnFlash.setOnClickListener { doFlash() }
        binding.btnSettings.setOnClickListener {
            (activity as? MainActivity)?.navigateTo(R.id.nav_settings)
        }
    }

    private fun refreshDevices() {
        val devices = serialManager.getAvailableDevices()
        deviceAdapter?.submitList(devices)

        if (devices.isEmpty()) {
            binding.tvEmptyDevices.visibility = View.VISIBLE
            binding.rvDevices.visibility = View.GONE
        } else {
            binding.tvEmptyDevices.visibility = View.GONE
            binding.rvDevices.visibility = View.VISIBLE
            // Auto-select first device
            if (selectedDevice == null) {
                selectedDevice = devices.first()
                binding.tvSelectedPort.text = "Selected: ${devices.first().name}"
                binding.btnFlash.isEnabled = true
            }
        }
    }

    private fun updateCompileStatus() {
        val activity = activity as? MainActivity ?: return
        _binding?.let { b ->
            if (activity.lastCompiledBinPath.isNotEmpty()) {
                val kb = activity.lastCompiledBinSize / 1024
                b.tvCompileStatus.text = "✓ Firmware ready: ${kb} KB"
                b.tvCompileStatus.setTextColor(resources.getColor(R.color.green, null))
            } else {
                b.tvCompileStatus.text = "No firmware compiled yet"
                b.tvCompileStatus.setTextColor(resources.getColor(R.color.text_secondary, null))
            }
        }
    }

    private fun compileAndFlash() {
        log("Compiling first...")
        val activity = activity as? MainActivity ?: return
        val context = context ?: return
        val compiler = ArduinoCompiler(context)

        // Get current sketch content
        lifecycleScope.launch {
            val sketch = getCurrentSketchContent()
            val fqbn = prefs.boardFQBN

            setFlashing(true)
            val result = withContext(Dispatchers.IO) {
                compiler.compile(sketch, fqbn) { line -> 
                    lifecycleScope.launch(Dispatchers.Main) { log(line) }
                }
            }
            if (result.success) {
                activity.lastCompiledBinPath = result.binPath
                activity.lastCompiledBinSize = result.binSize
                log("✓ Compiled! ${result.binSize / 1024} KB")
                doFlashInternal()
            } else {
                log("✗ Compile failed:")
                result.error.lines().forEach { log(it) }
                setFlashing(false)
            }
        }
    }

    private fun doFlash() {
        val activity = activity as? MainActivity ?: return
        if (activity.lastCompiledBinPath.isEmpty()) {
            Toast.makeText(context, "Compile first!", Toast.LENGTH_SHORT).show()
            return
        }
        setFlashing(true)
        lifecycleScope.launch { doFlashInternal() }
    }

    private suspend fun doFlashInternal() {
        val device = selectedDevice
        if (device == null) {
            log("No device selected!")
            setFlashing(false)
            return
        }

        val activity = activity as? MainActivity ?: run {
            setFlashing(false)
            return
        }
        val binPath = activity.lastCompiledBinPath

        log("Connecting to ${device.name}...")

        try {
            // Connect at low baud first for bootloader
            serialManager.connect(device, 115200)
            delay(800) // Slightly longer for stability

            if (!serialManager.isConnected()) {
                log("✗ Failed to connect. Check USB connection and grant permission.")
                setFlashing(false)
                return
            }

            // Stop serial reading during flash
            serialManager.stopReading()

            val flasher = EspFlasher(serialManager)
            val result = flasher.flash(
                firmwarePath = binPath,
                flashBaud = prefs.flashBaud,
                onProgress = { line -> lifecycleScope.launch(Dispatchers.Main) { log(line) } },
                onPercent = { pct ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        _binding?.let { b ->
                            b.flashProgress.progress = pct
                            b.tvFlashPercent.text = "$pct%"
                        }
                    }
                }
            )

            serialManager.disconnect()

            withContext(Dispatchers.Main) {
                setFlashing(false)
                if (result.success) {
                    log(result.message)
                    _binding?.let { b ->
                        b.flashProgress.progress = 100
                        b.tvFlashPercent.text = "100%"
                    }
                    Toast.makeText(context, "Flash successful! 🎉", Toast.LENGTH_LONG).show()
                    
                    // Safe navigation to Monitor
                    delay(1000)
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null && isAdded) {
                        mainActivity.navigateTo(R.id.nav_monitor)
                    }
                } else {
                    log("✗ Flash failed: ${result.error}")
                    Toast.makeText(context, "Flash failed!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            log("✗ Unexpected error: ${e.message}")
            setFlashing(false)
        }
    }

    private suspend fun getCurrentSketchContent(): String = withContext(Dispatchers.IO) {
        // Get content from editor fragment if available
        var contentFromEditor = ""
        withContext(Dispatchers.Main) {
            val editorFrag = parentFragmentManager.findFragmentByTag("editor")
            if (editorFrag is com.esp32ide.ui.editor.EditorFragment) {
                contentFromEditor = editorFrag.getEditorText()
            }
        }

        if (contentFromEditor.isNotEmpty()) {
            contentFromEditor
        } else {
            try {
                val dao = com.esp32ide.data.SketchDatabase.getInstance(requireContext()).sketchDao()
                val sketches = dao.getAllSketches().first()
                sketches.firstOrNull()?.content ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun log(line: String) {
        _binding?.let { b ->
            b.tvOutput.append("$line\n")
            b.scrollOutput.post { b.scrollOutput.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setFlashing(flashing: Boolean) {
        _binding?.let { b ->
            b.btnFlash.isEnabled = !flashing
            b.btnCompileFlash.isEnabled = !flashing
            b.btnRefresh.isEnabled = !flashing
            b.flashProgress.visibility = if (flashing) View.VISIBLE else View.GONE
            b.tvFlashPercent.visibility = if (flashing) View.VISIBLE else View.GONE
            if (flashing) {
                b.flashProgress.progress = 0
                b.tvFlashPercent.text = "0%"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
