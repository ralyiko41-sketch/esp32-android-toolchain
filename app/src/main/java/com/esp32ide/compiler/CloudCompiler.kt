package com.esp32ide.compiler

import android.content.Context
import com.esp32ide.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudCompiler(private val context: Context) {

    private val prefs = AppPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun compile(
        sketchContent: String,
        fqbn: String,
        onProgress: (String) -> Unit
    ): CompileResult = withContext(Dispatchers.IO) {

        val url = prefs.cloudCompilerUrl.ifEmpty {
            "https://api2.arduino.cc/builder/v1/compile"
        }

        onProgress("Sending to cloud compiler...")
        onProgress("URL: $url")

        try {
            val json = JSONObject().apply {
                put("sketch", sketchContent)
                put("fqbn", fqbn)
                put("libraries", org.json.JSONArray())
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    404 -> "HTTP 404: Compiler endpoint not found. If using a local PC, ensure the URL ends with /compile (e.g. http://192.168.x.x:3000/compile)"
                    403 -> "HTTP 403: Forbidden. The cloud compiler might require an API key or Token."
                    500 -> "HTTP 500: Server error on the compiler side."
                    else -> "HTTP ${response.code}\n$responseBody"
                }
                onProgress("Cloud compile failed: $errorMsg")
                return@withContext CompileResult(
                    success = false,
                    error = "Cloud compiler error: $errorMsg\n\nTip: Ensure your PC is running the compiler server and is on the same WiFi."
                )
            }

            val result = JSONObject(responseBody)
            val status = result.optString("status", "")
            val errors = result.optString("errors", "")

            if (status == "error" || errors.isNotEmpty()) {
                errors.split("\n").filter { it.isNotBlank() }.forEach { onProgress(it) }
                return@withContext CompileResult(success = false, error = errors)
            }

            // Get binary
            val binBase64 = result.optString("bin", result.optString("hex", ""))
            if (binBase64.isEmpty()) {
                return@withContext CompileResult(
                    success = false,
                    error = "Cloud compiler returned no binary. Response: $responseBody"
                )
            }

            // Decode and save
            val binBytes = android.util.Base64.decode(binBase64, android.util.Base64.DEFAULT)
            val outBin = java.io.File(context.filesDir, "firmware.bin")
            outBin.writeBytes(binBytes)

            prefs.lastBinPath = outBin.absolutePath
            prefs.lastBinSize = binBytes.size

            val size = result.optInt("size", binBytes.size)
            onProgress("✓ Cloud compile done! ${binBytes.size / 1024} KB")

            CompileResult(
                success = true,
                binPath = outBin.absolutePath,
                binSize = size,
                output = responseBody
            )

        } catch (e: Exception) {
            onProgress("✗ Cloud compile error: ${e.message}")
            CompileResult(success = false, error = e.message ?: "Unknown error")
        }
    }
}
