package com.esp32ide.compiler

import android.content.Context
import android.os.Build
import android.util.Log
import com.esp32ide.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class CompileResult(
    val success: Boolean,
    val binPath: String = "",
    val binSize: Int = 0,
    val output: String = "",
    val error: String = ""
)

class ArduinoCompiler(private val context: Context) {

    private val prefs = AppPreferences(context)
    private val filesDir = context.filesDir

    private val nativeCliPath = File(context.applicationInfo.nativeLibraryDir, "libarduino-cli.so").absolutePath
    private val downloadedCliPath = File(filesDir, "arduino-cli").absolutePath

    private val cliPath: String
        get() = if (File(nativeCliPath).exists()) nativeCliPath else downloadedCliPath

    private val dataDir = File(filesDir, "arduino-data").absolutePath

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(900, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "ESP32IDE-Android/2.0 (Android ${Build.VERSION.RELEASE})")
                .build()
            chain.proceed(request)
        }
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    // Prefer IPv4 to avoid broken IPv6 DNS on some Android devices
                    Dns.SYSTEM.lookup(hostname).filter { it.address.size == 4 }
                } catch (e: Exception) {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        })
        .build()

    // ── Tool definitions — ALL required for ESP32 2.0.17 ARM64 ────────────
    private data class ToolDef(
        val name: String,
        val version: String,
        val url: String,
        val destSubPath: String,   // relative to dataDir/packages/esp32/tools/
        val isZip: Boolean = false,
        val optional: Boolean = false,
        val minSizeBytes: Long = 100_000L
    )

    private val ALL_TOOLS = listOf(
        ToolDef(
            name = "xtensa-esp32-elf",
            version = "esp-2021r2-patch5",
            url = "https://github.com/espressif/crosstool-NG/releases/download/esp-2021r2-patch5/xtensa-esp32-elf-gcc8_4_0-esp-2021r2-patch5-linux-arm64.tar.gz",
            destSubPath = "xtensa-esp32-elf/esp-2021r2-patch5",
            minSizeBytes = 50_000_000L
        ),
        ToolDef(
            name = "esptool_py",
            version = "4.8.1",
            url = "https://github.com/espressif/esptool/releases/download/v4.8.1/esptool-v4.8.1-linux-arm64.zip",
            destSubPath = "esptool_py/4.8.1",
            isZip = true,
            optional = true
        )
    )

    // ── Public status checks ──────────────────────────────────────────────
    fun isCliReady(): Boolean = File(cliPath).exists()
    fun isEsp32CoreInstalled(): Boolean = prefs.esp32CoreInstalled

    fun isLocalCompileSupported(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || File(nativeCliPath).exists()
    }

    // ── Download arduino-cli ──────────────────────────────────────────────
    suspend fun downloadArduinoCli(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val arch = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "Linux_ARM64"
                Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "Linux_ARMv7"
                Build.SUPPORTED_ABIS.contains("x86_64") -> "Linux_64bit"
                else -> "Linux_ARM64"
            }
            val version = "1.1.0"
            val url = "https://github.com/arduino/arduino-cli/releases/download/v$version/arduino-cli_${version}_${arch}.tar.gz"
            onProgress("Downloading arduino-cli for $arch...")

            val tarGzFile = File(filesDir, "arduino-cli.tar.gz")
            if (!downloadFileWithProgress(url, tarGzFile, onProgress)) {
                onProgress("✗ Failed to download arduino-cli")
                return@withContext false
            }

            onProgress("Extracting arduino-cli...")
            TarArchiveInputStream(GzipCompressorInputStream(FileInputStream(tarGzFile))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    if (entry.name.contains("arduino-cli") && !entry.isDirectory) {
                        val outFile = File(cliPath)
                        FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                        outFile.setExecutable(true)
                        onProgress("✓ arduino-cli extracted")
                        break
                    }
                    entry = tar.nextTarEntry
                }
            }
            tarGzFile.delete()
            prefs.arduinoCliReady = File(cliPath).exists()
            onProgress(if (prefs.arduinoCliReady) "✓ arduino-cli ready!" else "✗ Extraction failed")
            prefs.arduinoCliReady
        } catch (e: Exception) {
            onProgress("✗ Download error: ${e.message}")
            false
        }
    }

    // ── Install ESP32 core 2.0.17 ─────────────────────────────────────────
    suspend fun installEsp32Core(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val dataFolder = File(dataDir)
            val stagingFolder = File(filesDir, "staging")
            dataFolder.mkdirs()
            stagingFolder.mkdirs()

            // Clean old core for fresh patching
            val oldCore = File(dataDir, "packages/esp32/hardware/esp32/2.0.17")
            if (oldCore.exists()) {
                onProgress("Cleaning old core for fresh install...")
                oldCore.deleteRecursively()
            }

            // ── Step 1: Board index files ─────────────────────────────
            onProgress("Downloading board indexes...")
            val indexes = mapOf(
                "https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json" to "package_esp32_index.json",
                "https://downloads.arduino.cc/packages/package_index.json" to "package_index.json",
                "https://arduino.esp8266.com/stable/package_esp8266com_index.json" to "package_esp8266com_index.json",
                "https://downloads.arduino.cc/libraries/library_index.json" to "library_index.json"
            )
            for ((url, name) in indexes) {
                onProgress("Downloading $name...")
                if (!downloadFileWithProgress(url, File(dataFolder, name), onProgress, retries = 2)) {
                    onProgress("⚠ Skipping $name (non-critical DNS error)")
                }
            }
            onProgress("✓ Board indexes ready")

            // ── Step 2: ESP32 Core zip 2.0.17 ────────────────────────
            onProgress("Downloading ESP32 core scripts (2.0.17)...")
            val coreZip = File(stagingFolder, "esp32-core.zip")
            val coreOk = downloadFileWithProgress(
                "https://github.com/espressif/arduino-esp32/releases/download/2.0.17/esp32-2.0.17.zip",
                coreZip, onProgress, retries = 3
            )
            if (!coreOk || coreZip.length() < 100_000) {
                onProgress("✗ Failed to download core (Network error). Try Wi-Fi.")
                return@withContext false
            }

            val coreDir = File(dataDir, "packages/esp32/hardware/esp32/2.0.17")
            coreDir.mkdirs()
            onProgress("Extracting ESP32 core...")
            extractZipStrippingRoot(coreZip, coreDir, onProgress)
            onProgress("✓ Core scripts extracted")

            // ── Step 3: Download ALL required tools ───────────────────
            val toolsBaseDir = File(dataDir, "packages/esp32/tools")
            var requiredToolsMissing = false

            for ((index, tool) in ALL_TOOLS.withIndex()) {
                val toolDestDir = File(toolsBaseDir, tool.destSubPath)
                val markerFile = File(toolDestDir, ".installed")

                if (markerFile.exists()) {
                    onProgress("✓ ${tool.name} already installed, skipping")
                    continue
                }

                onProgress("[${index + 1}/${ALL_TOOLS.size}] Downloading ${tool.name}...")
                val ext = if (tool.isZip) "zip" else "tar.gz"
                val toolFile = File(stagingFolder, "${tool.name}.$ext")

                val ok = downloadFileWithProgress(tool.url, toolFile, onProgress, retries = 3)

                if (!ok || toolFile.length() < tool.minSizeBytes) {
                    if (tool.optional) {
                        onProgress("⚠ ${tool.name} download failed — optional, skipping")
                        continue
                    } else {
                        onProgress("✗ ${tool.name} download failed — REQUIRED")
                        requiredToolsMissing = true
                        continue
                    }
                }

                toolDestDir.mkdirs()
                onProgress("Extracting ${tool.name}...")
                if (tool.isZip) {
                    extractZipStrippingRoot(toolFile, toolDestDir, onProgress)
                } else {
                    extractTarGzAndroid(toolFile, toolDestDir, onProgress)
                }

                // Make all binaries executable
                toolDestDir.walkTopDown().forEach { f ->
                    if (f.isFile && (f.name.startsWith("xtensa-esp32-elf-") || !f.name.contains("."))) {
                        f.setExecutable(true, false)
                    }
                }

                // Write marker so we skip next time
                markerFile.writeText("installed")
                onProgress("✓ ${tool.name} ready")

                // Clean up staging file to save space
                toolFile.delete()
            }

            if (requiredToolsMissing) {
                onProgress("✗ Required tools missing — cannot compile")
                return@withContext false
            }

            // ── Step 4: Setup Android environment shims ───────────────
            onProgress("Setting up Android environment shims...")
            setupFakeEnv()

            // ── Step 5: Patch platform.txt ────────────────────────────
            onProgress("Deep patching platform.txt...")
            val xtensaDir = File(dataDir, "packages/esp32/tools/xtensa-esp32-elf/esp-2021r2-patch5")
            val esptoolDir = File(dataDir, "packages/esp32/tools/esptool_py/4.8.1")
            patchPlatformTxt(coreDir, xtensaDir, onProgress)

            // ── Step 6: Patch all scripts ─────────────────────────────
            onProgress("Patching all tool scripts for Android compatibility...")
            patchEsp32CoreScripts(onProgress)

            // ── Step 7: Write platform.local.txt ─────────────────────
            onProgress("Writing toolchain config...")
            writePlatformLocalTxt(coreDir, xtensaDir, esptoolDir, onProgress)

            prefs.esp32CoreInstalled = true
            onProgress("✓ ESP32 core 2.0.17 installed successfully!")
            onProgress("🎉 Setup complete! You can now compile offline.")
            true

        } catch (e: Exception) {
            onProgress("✗ Error: ${e.message}")
            Log.e("ArduinoCompiler", "installEsp32Core failed", e)
            false
        }
    }

    private fun patchPlatformTxt(coreDir: File, gccDir: File, onProgress: (String) -> Unit) {
        val platformTxt = File(coreDir, "platform.txt")
        if (!platformTxt.exists()) {
            onProgress("✗ platform.txt not found")
            return
        }
        try {
            var content = platformTxt.readText()
            content = content
                .replace("/usr/bin/env bash", "/system/bin/sh")
                .replace("/usr/bin/env sh", "/system/bin/sh")
                .replace("/usr/bin/env python3", "/system/bin/sh")
                .replace("/usr/bin/env python", "/system/bin/sh")
            content = content.replace(Regex("(recipe\\.hooks\\.[^\\n]*/usr/bin/env[^\\n]*)"), "")
            content = content.replace(Regex("compiler\\.path=.*"), "compiler.path=${gccDir.absolutePath}/bin/")
            platformTxt.writeText(content)
            onProgress("✓ Patched platform.txt")
        } catch (e: Exception) {
            onProgress("✗ Failed to patch platform.txt: ${e.message}")
        }
    }

    private fun writePlatformLocalTxt(coreDir: File, xtensaDir: File, esptoolDir: File, onProgress: (String) -> Unit) {
        try {
            File(coreDir, "platform.local.txt").writeText("""
compiler.path=${xtensaDir.absolutePath}/bin/
tools.xtensa-esp32-elf.path=${xtensaDir.absolutePath}
tools.esptool_py.path=${esptoolDir.absolutePath}
recipe.hooks.prebuild.1.pattern=
recipe.hooks.prebuild.2.pattern=
recipe.hooks.prebuild.3.pattern=
recipe.hooks.prebuild.4.pattern=
recipe.hooks.prebuild.5.pattern=
recipe.hooks.prebuild.6.pattern=
recipe.hooks.prebuild.7.pattern=
recipe.hooks.prebuild.8.pattern=
recipe.hooks.core.combine.pattern=
recipe.hooks.linking.prelink.1.pattern=
recipe.hooks.linking.prelink.2.pattern=
recipe.hooks.objcopy.postobjcopy.1.pattern=
recipe.hooks.objcopy.postobjcopy.2.pattern=
recipe.hooks.objcopy.postobjcopy.3.pattern=
            """.trimIndent())
            onProgress("✓ Written platform.local.txt")
        } catch (e: Exception) {
            onProgress("✗ Failed to write platform.local.txt: ${e.message}")
        }
    }

    fun patchEsp32CoreScripts(onProgress: (String) -> Unit = {}) {
        val pathsToPatch = listOf(
            File(dataDir, "packages/esp32/hardware/esp32/2.0.17"),
            File(dataDir, "packages/esp32/tools/xtensa-esp32-elf"),
            File(dataDir, "packages/esp32/tools/esptool_py"),
            File(dataDir, "packages/esp32/tools")
        )
        var patchedTotal = 0
        for (basePath in pathsToPatch) {
            if (!basePath.exists()) continue
            var patchedCount = 0
            basePath.walkTopDown().forEach { file ->
                if (file.isFile && !file.name.endsWith(".bin") && !file.name.endsWith(".a") && !file.name.endsWith(".o") && !file.name.endsWith(".elf") && file.length() < 512000) {
                    try {
                        val content = file.readText()
                        if (content.contains("#!/usr/bin/env") || content.contains("/usr/bin/env ")) {
                            val patched = content
                                .replace("#!/usr/bin/env python3", "/system/bin/sh")
                                .replace("#!/usr/bin/env python", "/system/bin/sh")
                                .replace("#!/usr/bin/env bash", "/system/bin/sh")
                                .replace("#!/usr/bin/env sh", "/system/bin/sh")
                                .replace("/usr/bin/env python3 ", "/system/bin/sh ")
                                .replace("/usr/bin/env python ", "/system/bin/sh ")
                                .replace("/usr/bin/env bash ", "/system/bin/sh ")
                            if (patched != content) {
                                file.writeText(patched)
                                file.setExecutable(true, false)
                                patchedCount++
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
            patchedTotal += patchedCount
        }
        onProgress("✓ Total patched: $patchedTotal scripts")
    }

    private fun setupFakeEnv() {
        val binDir = File(filesDir, "bin")
        binDir.mkdirs()
        val sh = File("/system/bin/sh")
        if (!sh.exists()) return
        listOf("env", "bash", "python3", "python", "sh").forEach { name ->
            val target = File(binDir, name)
            if (!target.exists()) {
                try {
                    sh.copyTo(target, overwrite = false)
                    target.setExecutable(true, false)
                    target.setReadable(true, false)
                } catch (e: Exception) { }
            }
        }
        val usrBinDir = File(filesDir, "usr/bin")
        usrBinDir.mkdirs()
        listOf("env", "bash", "python3", "python").forEach { name ->
            val target = File(usrBinDir, name)
            if (!target.exists()) {
                try {
                    sh.copyTo(target, overwrite = false)
                    target.setExecutable(true, false)
                } catch (e: Exception) { }
            }
        }
    }

    private fun buildEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        val tmpDir = File(context.cacheDir, "tmp").also { it.mkdirs() }
        val binDir = File(filesDir, "bin")
        val xtensaBin = File(dataDir, "packages/esp32/tools/xtensa-esp32-elf/esp-2021r2-patch5/bin")
        env["PATH"] = "${binDir.absolutePath}:${filesDir.absolutePath}/usr/bin:${context.applicationInfo.nativeLibraryDir}:/system/bin:/system/xbin"
        env.remove("HTTP_PROXY"); env.remove("HTTPS_PROXY")
        env.remove("http_proxy"); env.remove("https_proxy")
        env.remove("ALL_PROXY"); env.remove("all_proxy")
        env["NO_PROXY"] = "*"; env["GOPROXY"] = "direct"
        env["HOME"] = filesDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["GODEBUG"] = "netdns=go"
        env["ARDUINO_DIRECTORIES_DATA"] = dataDir
        env["ARDUINO_DIRECTORIES_DOWNLOADS"] = File(filesDir, "staging").absolutePath
        env["ARDUINO_DIRECTORIES_USER"] = File(filesDir, "sketchbook").absolutePath
        if (xtensaBin.exists()) { env["PATH"] = "${xtensaBin.absolutePath}:${env["PATH"]}" }
    }

    private fun runCommand(cmd: List<String>, onProgress: (String) -> Unit): Boolean {
        if (cmd.isEmpty()) return false
        setupFakeEnv()
        return try {
            val pb = ProcessBuilder(cmd).directory(filesDir).redirectErrorStream(true)
            buildEnv(pb)
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { onProgress(it) }
            proc.waitFor(300, TimeUnit.SECONDS)
            proc.exitValue() == 0
        } catch (e: Exception) {
            onProgress("✗ Command failed: ${e.message}")
            false
        }
    }

    private fun runCommandWithOutput(cmd: List<String>, onProgress: (String) -> Unit, sb: StringBuilder): Boolean {
        if (cmd.isEmpty()) return false
        setupFakeEnv()
        return try {
            val pb = ProcessBuilder(cmd).directory(filesDir).redirectErrorStream(true)
            buildEnv(pb)
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { line -> sb.appendLine(line); onProgress(line) }
            proc.waitFor(300, TimeUnit.SECONDS)
            proc.exitValue() == 0
        } catch (e: Exception) {
            onProgress("✗ Command failed: ${e.message}")
            false
        }
    }

    suspend fun compile(sketchContent: String, fqbn: String, onProgress: (String) -> Unit): CompileResult = withContext(Dispatchers.IO) {
        if (!isCliReady() || !isEsp32CoreInstalled()) {
            val cloudUrl = prefs.cloudCompilerUrl
            if (cloudUrl.isNotEmpty()) {
                onProgress("arduino-cli not ready — using cloud compiler...")
                return@withContext CloudCompiler(context).compile(sketchContent, fqbn, onProgress)
            }
            return@withContext CompileResult(success = false, error = "arduino-cli not installed.")
        }
        try {
            setupFakeEnv()
            val coreDir = File(dataDir, "packages/esp32/hardware/esp32/2.0.17")
            val gccDir = File(dataDir, "packages/esp32/tools/xtensa-esp32-elf/esp-2021r2-patch5")
            val esptoolDir = File(dataDir, "packages/esp32/tools/esptool_py/4.8.1")
            if (coreDir.exists()) { writePlatformLocalTxt(coreDir, gccDir, esptoolDir, {}) }
            val tmpDir = File(context.cacheDir, "sketch_${System.currentTimeMillis()}")
            val sketchDir = File(tmpDir, "sketch"); val buildDir = File(tmpDir, "build")
            sketchDir.mkdirs(); buildDir.mkdirs()
            File(sketchDir, "sketch.ino").writeText(sketchContent)
            onProgress("Compiling for $fqbn...")
            val cmd = listOf(cliPath, "compile", "--fqbn", fqbn, "--verbose", "--build-path", buildDir.absolutePath, "--config-file", getConfigPath(), sketchDir.absolutePath)
            val sb = StringBuilder()
            val success = runCommandWithOutput(cmd, onProgress, sb)
            val output = sb.toString()
            if (!success) { tmpDir.deleteRecursively(); return@withContext CompileResult(success = false, error = output, output = output) }
            val binFile = buildDir.listFiles()?.find { it.name.endsWith(".bin") && !it.name.contains("bootloader") && !it.name.contains("partitions") }
            if (binFile == null) { tmpDir.deleteRecursively(); return@withContext CompileResult(success = false, error = "Build ok but .bin not found\n$output") }
            val outBin = File(filesDir, "firmware.bin")
            binFile.copyTo(outBin, overwrite = true)
            buildDir.listFiles()?.find { it.name.contains("bootloader.bin") }?.copyTo(File(filesDir, "bootloader.bin"), overwrite = true)
            buildDir.listFiles()?.find { it.name.contains("partitions.bin") }?.copyTo(File(filesDir, "partitions.bin"), overwrite = true)
            prefs.lastBinPath = outBin.absolutePath
            prefs.lastBinSize = outBin.length().toInt()
            val size = Regex("""Sketch uses (\d+) bytes""").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: outBin.length().toInt()
            tmpDir.deleteRecursively()
            onProgress("✓ Compiled! ${outBin.length() / 1024} KB")
            CompileResult(success = true, binPath = outBin.absolutePath, binSize = size, output = output)
        } catch (e: Exception) { CompileResult(success = false, error = "Compile exception: ${e.message}") }
    }

    private fun getConfigPath(): String {
        val data = File(dataDir); val staging = File(filesDir, "staging"); val user = File(filesDir, "sketchbook")
        data.mkdirs(); staging.mkdirs(); user.mkdirs()
        val config = File(filesDir, "arduino-cli.yaml")
        config.writeText("""
board_manager:
  additional_urls:
    - https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
    - https://arduino.esp8266.com/stable/package_esp8266com_index.json
directories:
  data: ${data.absolutePath}
  downloads: ${staging.absolutePath}
  user: ${user.absolutePath}
  builtin:
    tools: ${data.absolutePath}/packages/builtin/tools
network:
  proxy: "direct"
  connection_timeout: 60
library:
  enable_unsafe_install: true
logging:
  level: warn
  format: text
compile:
  warnings_level: none
sketch:
  always_export_binaries: false
        """.trimIndent())
        return config.absolutePath
    }

    private fun extractTarGzAndroid(tarGz: File, destDir: File, onProgress: (String) -> Unit) {
        var count = 0
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(tarGz)))).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val name = entry.name.substringAfter("/")
                if (name.isNotEmpty()) {
                    val outFile = File(destDir, name)
                    if (entry.isDirectory) { outFile.mkdirs() } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { tar.copyTo(it) }
                        if (outFile.name.startsWith("xtensa-esp32-elf-") || !outFile.name.contains(".") || outFile.name.endsWith(".sh")) {
                            outFile.setExecutable(true, false)
                        }
                    }
                    count++
                    if (count % 300 == 0) onProgress("  Extracting... ($count files)")
                }
                entry = tar.nextTarEntry
            }
        }
        onProgress("  ✓ Extracted $count files")
    }

    private fun extractZipStrippingRoot(zip: File, destDir: File, onProgress: (String) -> Unit) {
        var count = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfter("/")
                if (name.isNotEmpty()) {
                    val outFile = File(destDir, name)
                    if (entry.isDirectory) { outFile.mkdirs() } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                        if (!outFile.name.contains(".") || outFile.name.endsWith(".sh")) outFile.setExecutable(true, false)
                    }
                    count++
                    if (count % 300 == 0) onProgress("  Extracting... ($count files)")
                }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
        onProgress("  ✓ Extracted $count files")
    }

    private suspend fun downloadFileWithProgress(url: String, targetFile: File, onProgress: (String) -> Unit, retries: Int = 0): Boolean = withContext(Dispatchers.IO) {
        var currentAttempt = 0
        while (currentAttempt <= retries) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    onProgress("  HTTP ${response.code} for ${targetFile.name}")
                    currentAttempt++
                    if (currentAttempt <= retries) delay(2000)
                    continue
                }
                val body = response.body ?: return@withContext false
                val totalBytes = body.contentLength()
                targetFile.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        var downloaded = 0L
                        val buffer = ByteArray(16384)
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            downloaded += bytes
                            if (totalBytes > 1_024_000 && downloaded % (1024 * 1024) < 16384) {
                                val pct = (downloaded * 100 / totalBytes).toInt()
                                onProgress("  Progress: $pct% (${downloaded / 1024 / 1024}MB)")
                            }
                        }
                    }
                }
                return@withContext true
            } catch (e: Exception) {
                onProgress("  Attempt ${currentAttempt + 1} failed: ${e.message}")
                currentAttempt++
                if (currentAttempt <= retries) delay(2000)
            }
        }
        false
    }
}
