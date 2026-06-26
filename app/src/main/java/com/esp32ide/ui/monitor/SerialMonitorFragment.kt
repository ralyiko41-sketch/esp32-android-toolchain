package com.esp32ide.ui.monitor

import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.MainActivity
import com.esp32ide.R
import com.esp32ide.data.AppPreferences
import com.esp32ide.databinding.FragmentMonitorBinding
import com.esp32ide.serial.SerialState
import com.esp32ide.serial.UsbSerialManager
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.components.YAxis
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.*

class SerialMonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private lateinit var serialManager: UsbSerialManager
    private val logLines = mutableListOf<String>()
    private val BAUD_RATES = listOf(9600,19200,38400,57600,74880,115200,230400,460800,921600)

    // Plotter data — up to 4 series
    private val plotDataSets = mutableListOf<LineDataSet>()
    private val PLOT_COLORS = listOf(Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED)
    private val MAX_PLOT_POINTS = 100

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        serialManager = (activity as MainActivity).serialManager

        setupBaudSpinner()
        setupButtons()
        setupChart()
        observeSerialState()
        setupDataCallback()

        // Auto-connect if a device is attached while we are here
        serialManager.onDeviceAttached = { device ->
            autoConnect()
        }
    }

    override fun onResume() {
        super.onResume()
        autoConnect()
    }

    private fun autoConnect() {
        if (!serialManager.isConnected()) {
            val devices = serialManager.getAvailableDevices()
            if (devices.isNotEmpty()) {
                val baud = BAUD_RATES[binding.spinnerBaud.selectedItemPosition]
                serialManager.connect(devices.first(), baud)
            }
        }
    }

    private fun setupBaudSpinner() {
        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            BAUD_RATES.map { it.toString() }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBaud.adapter = adapter
        binding.spinnerBaud.setSelection(BAUD_RATES.indexOf(prefs.baudRate).coerceAtLeast(0))

        binding.spinnerBaud.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val baud = BAUD_RATES[pos]
                if (prefs.baudRate != baud) {
                    prefs.baudRate = baud
                    // Reconnect with new baud if already connected
                    if (serialManager.isConnected()) {
                        val devices = serialManager.getAvailableDevices()
                        if (devices.isNotEmpty()) {
                            serialManager.disconnect()
                            serialManager.connect(devices.first(), baud)
                        }
                    }
                }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupButtons() {
        binding.btnClear.setOnClickListener {
            logLines.clear()
            binding.tvLog.text = ""
            plotDataSets.clear()
            binding.chart.clear()
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etSend.text.toString()
            if (text.isNotEmpty() && serialManager.isConnected()) {
                serialManager.send(text)
                binding.etSend.setText("")
                appendLog("> $text", "sent")
            }
        }

        binding.etSend.setOnEditorActionListener { _, _, _ ->
            binding.btnSend.performClick()
            true
        }

        binding.tabMonitor.setOnClickListener { showMonitor() }
        binding.tabPlotter.setOnClickListener { showPlotter() }
    }

    private fun setupDataCallback() {
        serialManager.onDataReceived = { bytes ->
            val text = String(bytes, Charsets.UTF_8)
            // Split into lines
            val lines = text.split("\n")
            lifecycleScope.launch(Dispatchers.Main) {
                _binding?.let { b ->
                    lines.forEach { line ->
                        val clean = line.trim()
                        if (clean.isNotEmpty()) {
                            appendLog(clean, "data")
                            tryPlot(clean)
                        }
                    }
                }
            }
        }
    }

    private fun appendLog(text: String, type: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logLines.add("[$ts] $text")
        if (logLines.size > 2000) logLines.removeAt(0)

        _binding?.let { b ->
            b.tvLog.append("[$ts] $text\n")
            // Auto scroll
            b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun tryPlot(line: String) {
        // Try to parse CSV numbers e.g. "1.23,4.56,7.89"
        val parts = line.split(",")
        val nums = parts.mapNotNull { it.trim().toFloatOrNull() }
        if (nums.isEmpty()) return

        _binding?.let { b ->
            // Ensure we have enough datasets
            while (plotDataSets.size < nums.size) {
                val idx = plotDataSets.size
                val ds = LineDataSet(mutableListOf(), "Series ${idx + 1}").apply {
                    color = PLOT_COLORS[idx % PLOT_COLORS.size]
                    setDrawCircles(false)
                    lineWidth = 2f
                    setDrawValues(false)
                    axisDependency = YAxis.AxisDependency.LEFT
                }
                plotDataSets.add(ds)
            }

            nums.forEachIndexed { i, v ->
                val ds = plotDataSets[i]
                val x = ds.entryCount.toFloat()
                ds.addEntry(Entry(x, v))
                if (ds.entryCount > MAX_PLOT_POINTS) ds.removeFirst()
            }

            b.chart.data = LineData(plotDataSets.toList())
            b.chart.notifyDataSetChanged()
            b.chart.invalidate()
            b.chart.moveViewToX(plotDataSets.firstOrNull()?.entryCount?.toFloat() ?: 0f)
        }
    }

    private fun setupChart() {
        binding.chart.apply {
            description.isEnabled = false
            setBackgroundColor(Color.parseColor("#0d1220"))
            setGridBackgroundColor(Color.parseColor("#111827"))
            xAxis.textColor = Color.GRAY
            axisLeft.textColor = Color.GRAY
            axisRight.isEnabled = false
            legend.textColor = Color.GRAY
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
        }
    }

    private fun observeSerialState() {
        lifecycleScope.launch {
            serialManager.state.collect { state ->
                when (state) {
                    is SerialState.Disconnected -> {
                        // binding.tvStatus removed
                    }
                    is SerialState.Connecting -> {
                        // binding.tvStatus removed
                    }
                    is SerialState.Connected -> {
                        // binding.tvStatus removed
                        appendLog("Connected: ${state.device.name} @ ${state.baud} baud", "sys")
                    }
                    is SerialState.Error -> {
                        // binding.tvStatus removed
                        appendLog("Error: ${state.message}", "err")
                    }
                }
            }
        }
    }

    private fun showMonitor() {
        binding.scrollLog.visibility = View.VISIBLE
        binding.chart.visibility = View.GONE
        binding.tabMonitor.isSelected = true
        binding.tabPlotter.isSelected = false
    }

    private fun showPlotter() {
        binding.scrollLog.visibility = View.GONE
        binding.chart.visibility = View.VISIBLE
        binding.tabMonitor.isSelected = false
        binding.tabPlotter.isSelected = true
    }

    override fun onDestroyView() {
        serialManager.onDataReceived = null
        _binding = null
        super.onDestroyView()
    }
}
