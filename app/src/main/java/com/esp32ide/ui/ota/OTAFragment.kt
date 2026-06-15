package com.esp32ide.ui.ota

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.esp32ide.MainActivity
import com.esp32ide.data.AppPreferences
import com.esp32ide.databinding.FragmentOtaBinding
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class OTAFragment : Fragment() {

    private var _binding: FragmentOtaBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { AppPreferences(requireContext()) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentOtaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.etOtaIp.setText(prefs.otaIP)
        binding.etOtaPort.setText(prefs.otaPort.toString())
        binding.etOtaPassword.setText(prefs.otaPassword)
        binding.btnOtaFlash.setOnClickListener { startOTA() }
    }

    private fun log(msg: String) {
        activity?.runOnUiThread {
            binding.tvOtaOutput.append("$msg\n")
            binding.scrollOta.post { binding.scrollOta.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun startOTA() {
        val ip   = binding.etOtaIp.text.toString().trim()
        val port = binding.etOtaPort.text.toString().trim().toIntOrNull() ?: 3232
        val pass = binding.etOtaPassword.text.toString().trim()

        if (ip.isEmpty()) { Toast.makeText(context, "Enter ESP32 IP address", Toast.LENGTH_SHORT).show(); return }

        prefs.otaIP = ip; prefs.otaPort = port; prefs.otaPassword = pass
        binding.tvOtaOutput.text = ""
        binding.btnOtaFlash.isEnabled = false
        binding.otaProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Ensure compiled binary exists
            val binPath = (activity as MainActivity).lastCompiledBinPath
            if (binPath.isEmpty()) {
                log("No compiled firmware. Go to Code tab and tap COMPILE first.")
                binding.btnOtaFlash.isEnabled = true
                binding.otaProgress.visibility = View.GONE
                return@launch
            }

            log("Connecting to $ip:$port...")

            withContext(Dispatchers.IO) {
                try {
                    // Check device is reachable
                    val pingReq = Request.Builder().url("http://$ip:$port/").get().build()
                    try { client.newCall(pingReq).execute() } catch (_: Exception) {}

                    val binFile = File(binPath)
                    if (!binFile.exists()) {
                        log("✗ Firmware file not found: $binPath")
                        return@withContext
                    }
                    val binBytes = binFile.readBytes()

                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "firmware.bin",
                            binBytes.toRequestBody("application/octet-stream".toMediaType()))
                        .build()

                    val builder = Request.Builder()
                        .url("http://$ip:$port/update")
                        .post(requestBody)

                    if (pass.isNotEmpty()) {
                        val credential = okhttp3.Credentials.basic("", pass)
                        builder.header("Authorization", credential)
                    }

                    val resp = client.newCall(builder.build()).execute()
                    if (resp.isSuccessful) {
                        log("✓ OTA Flash successful! ESP32 is rebooting...")
                    } else {
                        log("✗ OTA failed: HTTP ${resp.code}")
                        log(resp.body?.string() ?: "")
                    }
                } catch (e: Exception) {
                    log("✗ OTA error: ${e.message}")
                    log("\nTroubleshooting:")
                    log("• Check ESP32 has ArduinoOTA in sketch")
                    log("• Check IP address is correct")
                    log("• Both devices must be on same WiFi")
                    log("• Check Serial Monitor for ESP32 IP")
                }
            }

            binding.btnOtaFlash.isEnabled = true
            binding.otaProgress.visibility = View.GONE
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
