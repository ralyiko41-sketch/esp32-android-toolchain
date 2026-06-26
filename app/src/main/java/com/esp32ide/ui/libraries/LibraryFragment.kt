package com.esp32ide.ui.libraries

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.esp32ide.databinding.FragmentLibrariesBinding

data class LibraryItem(
    val name: String, val author: String, val version: String,
    val url: String, var installed: Boolean = false
)

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibrariesBinding? = null
    private val binding get() = _binding!!

    private val popularLibs = mutableListOf(
        LibraryItem("DHT sensor library",        "Adafruit",       "1.4.6",  "https://github.com/adafruit/DHT-sensor-library"),
        LibraryItem("Adafruit BME280 Library",   "Adafruit",       "2.2.4",  "https://github.com/adafruit/Adafruit_BME280_Library"),
        LibraryItem("ESP32Servo",                "Kevin Harrington","3.0.5",  "https://github.com/madhephaestus/ESP32Servo"),
        LibraryItem("ArduinoJson",               "Benoit Blanchon","7.1.0",  "https://github.com/bblanchon/ArduinoJson"),
        LibraryItem("PubSubClient (MQTT)",       "Nick O'Leary",   "2.8.0",  "https://github.com/knolleary/pubsubclient"),
        LibraryItem("FastLED",                   "Daniel Garcia",  "3.7.0",  "https://github.com/FastLED/FastLED"),
        LibraryItem("Adafruit NeoPixel",         "Adafruit",       "1.12.3", "https://github.com/adafruit/Adafruit_NeoPixel"),
        LibraryItem("TinyGPS++",                 "Mikal Hart",     "1.0.3",  "https://github.com/mikalhart/TinyGPSPlus"),
        LibraryItem("Adafruit SSD1306",          "Adafruit",       "2.5.9",  "https://github.com/adafruit/Adafruit_SSD1306"),
        LibraryItem("LiquidCrystal I2C",         "Frank de Brabander","1.1.2","https://github.com/johnrickman/LiquidCrystal_I2C"),
        LibraryItem("Adafruit MPU6050",          "Adafruit",       "2.2.6",  "https://github.com/adafruit/Adafruit_MPU6050"),
        LibraryItem("WebSockets",                "Markus Sattler", "2.4.1",  "https://github.com/Links2004/arduinoWebSockets"),
        LibraryItem("ESPAsyncWebServer",         "ESP32 Async",    "1.2.7",  "https://github.com/me-no-dev/ESPAsyncWebServer"),
        LibraryItem("OneWire",                   "Paul Stoffregen","2.3.8",  "https://github.com/PaulStoffregen/OneWire"),
        LibraryItem("DallasTemperature",         "Miles Burton",   "3.9.0",  "https://github.com/milesburton/Arduino-Temperature-Control-Library"),
        LibraryItem("Ticker",                    "Espressif",      "1.4.0",  "https://github.com/espressif/arduino-esp32"),
        LibraryItem("IRremote",                  "shirriff",       "4.4.0",  "https://github.com/Arduino-IRremote/Arduino-IRremote"),
        LibraryItem("Keypad",                    "Mark Stanley",   "3.1.1",  "https://github.com/Chris--A/Keypad"),
    )

    private lateinit var adapter: LibraryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentLibrariesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LibraryAdapter(popularLibs) { lib ->
            lib.installed = !lib.installed
            val msg = if (lib.installed) "INSTALLING ${lib.name}..." else "REMOVED ${lib.name}"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
        }
        binding.rvLibraries.adapter = adapter

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        binding.btnAddUrl.setOnClickListener { showAddUrlDialog() }
    }

    private fun filter(q: String) {
        val filtered = if (q.isEmpty()) popularLibs
        else popularLibs.filter { it.name.contains(q, true) || it.author.contains(q, true) }
        adapter.updateList(filtered)
    }

    private fun showAddUrlDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "https://github.com/author/LibraryName"
            setPadding(48, 32, 48, 16)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Add Library from URL")
            .setMessage("Paste GitHub URL or ZIP link:")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    val name = url.split("/").lastOrNull() ?: "Custom Library"
                    popularLibs.add(LibraryItem(name, "Custom", "latest", url, true))
                    adapter.notifyDataSetChanged()
                    Toast.makeText(context, "Added: $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
