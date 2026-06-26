package com.esp32ide.compiler

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.esp32ide.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
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

    // ⚡ THE PROOT KERNEL INTERCEPTOR
    private val prootPath = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    private val dataDir = File(filesDir, "arduino-data").absolutePath

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(900, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/91.0.4472.124 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    private data class ToolDef(
        val name: String,
        val version: String,
        val url: String,
        val destSubPath: String,
        val isZip: Boolean = false,
        val optional: Boolean = false,
        val minSizeBytes: Long = 100_000L
    )

    private val ALL_TOOLS = listOf(
        // 1. THE TITANIUM PRU-STATIC COMPILER (Guaranteed v4.0 Tag link)
        ToolDef(
            name = "xtensa-esp-elf",
            version = "esp-14.2.0_20260121",
            url = "https://github.com/ralyiko41-sketch/esp32-android-toolchain/releases/download/v4.0/Titanium-v1.tar.gz",
            destSubPath = "xtensa-esp-elf/esp-14.2.0_20260121",
            minSizeBytes = 25_000_000L
        ),

        // 2. ESPTOOL (Universal Auto-Tarball — Will never 404)
        ToolDef(
            name = "esptool_py",
            version = "4.8.1",
            url = "https://github.com/espressif/esptool/archive/refs/tags/v4.8.1.tar.gz",
            destSubPath = "esptool_py/4.8.1",
            isZip = false,
            optional = true
        ),

        // 3. OPENOCD (Left untouched — this successfully extracted 1033 files!)
        ToolDef(
            name = "openocd-esp32",
            version = "v0.12.0-esp32-20240318",
            url = "https://github.com/espressif/openocd-esp32/releases/download/v0.12.0-esp32-20240318/openocd-esp32-linux-arm64-0.12.0-esp32-20240318.tar.gz",
            destSubPath = "openocd-esp32/v0.12.0-esp32-20240318",
            optional = true
        ),

        // 4. SPIFFS (Universal Auto-Tarball)
        ToolDef(
            name = "mkspiffs",
            version = "0.2.3-arduino-esp32",
            url = "https://github.com/igrr/mkspiffs/archive/refs/tags/0.2.3.tar.gz",
            destSubPath = "mkspiffs/0.2.3-arduino-esp32",
            optional = true
        ),

        // 5. LITTLEFS (The real, existing 3.2.0 Tag)
        ToolDef(
            name = "mklittlefs",
            version = "3.2.0", // <-- Back to reality
            url = "https://github.com/earlephilhower/mklittlefs/archive/refs/tags/3.2.0.tar.gz",
            destSubPath = "mklittlefs/3.2.0",
            optional = true
        )
    )

    fun isCliReady(): Boolean = File(cliPath).exists()
    fun isEsp32CoreInstalled(): Boolean = prefs.esp32CoreInstalled

    fun isLocalCompileSupported(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || File(nativeCliPath).exists()
    }

    private fun hammerKernelPermissions(dir: File, onProgress: (String) -> Unit) {
        onProgress("🔨 Forcing POSIX execution & verifying PIE ELF headers...")
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                try {
                    Os.chmod(file.absolutePath, 511)
                    val raf = RandomAccessFile(file, "rw")
                    val magic = ByteArray(4)
                    if (raf.read(magic) == 4) {
                        if (magic[0] == 0x7F.toByte() && magic[1] == 0x45.toByte() &&
                            magic[2] == 0x4C.toByte() && magic[3] == 0x46.toByte()) {
                            raf.seek(16)
                            val eType = raf.readByte().toInt() and 0xFF
                            if (eType == 2) {
                                raf.seek(16)
                                raf.writeByte(3)
                            }
                        }
                    }
                    raf.close()
                } catch (e: Exception) {
                    file.setExecutable(true, false)
                }
            }
        }
    }

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

    suspend fun installEsp32Core(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val dataFolder = File(dataDir)
            val stagingFolder = File(filesDir, "staging")
            dataFolder.mkdirs()
            stagingFolder.mkdirs()

            val oldCore = File(dataDir, "packages/esp32/hardware/esp32/3.2.0")
            if (oldCore.exists()) {
                onProgress("Cleaning old core for fresh install...")
                oldCore.deleteRecursively()
            }

            onProgress("Downloading board indexes...")
            val indexes = mapOf(
                "https://downloads.arduino.cc/packages/package_index.json" to "package_index.json",
                "https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json" to "package_esp32_index.json",
                "https://arduino.esp8266.com/stable/package_esp8266com_index.json" to "package_esp8266com_index.json",
                "https://downloads.arduino.cc/libraries/library_index.json" to "library_index.json"
            )
            for ((url, name) in indexes) {
                onProgress("Downloading $name...")
                downloadFileWithProgress(url, File(dataFolder, name), onProgress)
            }
            onProgress("✓ Board indexes ready")

            onProgress("Downloading ESP32 core scripts (~24MB)...")
            val coreZip = File(stagingFolder, "esp32-core.zip")
            val coreOk = downloadFileWithProgress(
                "https://github.com/espressif/arduino-esp32/releases/download/3.2.0/esp32-3.2.0.zip",
                coreZip, onProgress
            )
            if (!coreOk || coreZip.length() < 100_000) {
                onProgress("✗ Failed to download ESP32 core scripts")
                return@withContext false
            }
            val coreDir = File(dataDir, "packages/esp32/hardware/esp32/3.2.0")
            coreDir.mkdirs()
            onProgress("Extracting ESP32 core scripts...")
            extractZipStrippingRoot(coreZip, coreDir, onProgress)
            onProgress("✓ Core scripts extracted")

            val toolsBaseDir = File(dataDir, "packages/esp32/tools")
            var requiredToolsMissing = false

            for ((index, tool) in ALL_TOOLS.withIndex()) {
                val toolDestDir = File(toolsBaseDir, tool.destSubPath)
                val markerFile = File(toolDestDir, ".installed")

                if (markerFile.exists()) {
                    onProgress("✓ ${tool.name} already installed, skipping")
                    continue
                }

                onProgress("[${ index + 1}/${ALL_TOOLS.size}] Downloading ${tool.name}...")
                val ext = if (tool.isZip) "zip" else "tar.gz"
                val toolFile = File(stagingFolder, "${tool.name}.$ext")

                val ok = downloadFileWithProgress(tool.url, toolFile, onProgress)

                if (!ok || toolFile.length() < tool.minSizeBytes) {
                    if (tool.optional) {
                        onProgress("⚠ ${tool.name} download failed — optional, skipping safely")
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
                    extractTarGz(toolFile, toolDestDir, onProgress)
                }

                markerFile.writeText("installed")
                onProgress("✓ ${tool.name} ready")
                toolFile.delete()
            }

            if (requiredToolsMissing) {
                onProgress("✗ Required tools missing — cannot compile")
                return@withContext false
            }

            hammerKernelPermissions(toolsBaseDir, onProgress)

            onProgress("Setting up Android environment shims...")
            setupFakeEnv()

            onProgress("Patching platform.txt...")
            val xtensaDir = File(dataDir, "packages/esp32/tools/xtensa-esp-elf/esp-14.2.0_20260121")
            val esptoolDir = File(dataDir, "packages/esp32/tools/esptool_py/4.8.1")
            patchPlatformTxt(coreDir, xtensaDir, onProgress)

            onProgress("Patching all scripts for Android compatibility...")
            patchAllScripts(onProgress)

            onProgress("Writing toolchain config...")
            writePlatformLocalTxt(coreDir, xtensaDir, esptoolDir, onProgress)

            prefs.esp32CoreInstalled = true
            onProgress("✓ ESP32 core installed successfully!")
            onProgress("🎉 Setup complete! You can now compile offline.")
            true

        } catch (e: Exception) {
            onProgress("✗ Error: ${e.message}")
            Log.e("ArduinoCompiler", "installEsp32Core failed", e)
            false
        } finally {
            onProgress("🧹 Sweeping up empty staging archives...")
            try {
                val staging = File(filesDir, "staging")
                if (staging.exists()) staging.deleteRecursively()

                filesDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".tar.gz") || file.name.endsWith(".zip")) {
                        file.delete()
                    }
                }
                onProgress("✨ Storage cleanup complete!")
            } catch (e: Exception) {
                Log.w("ArduinoCompiler", "Cleanup warning: ${e.message}")
            }
        }
    }

    private fun patchPlatformTxt(coreDir: File, xtensaDir: File, onProgress: (String) -> Unit) {
        val platformTxt = File(coreDir, "platform.txt")
        if (!platformTxt.exists()) {
            onProgress("✗ platform.txt not found")
            return
        }
        try {
            var content = platformTxt.readText()

            content.lines()
                .filter { it.contains("/usr/bin/env") || it.contains("recipe.hooks") }
                .forEach { onProgress("  Patching: ${it.take(80)}") }

            content = content
                .replace("/usr/bin/env bash", "/system/bin/sh")
                .replace("/usr/bin/env sh", "/system/bin/sh")
                .replace("/usr/bin/env python3", "/system/bin/sh")
                .replace("/usr/bin/env python", "/system/bin/sh")

            content = content.replace(
                Regex("(recipe\\.hooks\\.[^\\n]*/usr/bin/env[^\\n]*)"), ""
            )

            if (xtensaDir.exists()) {
                val hasBinFolder = File(xtensaDir, "bin").exists()
                val binaryPrefix = if (hasBinFolder) "${xtensaDir.absolutePath}/bin/" else "${xtensaDir.absolutePath}/"
                content = content.replace(
                    Regex("compiler\\.path=\\{runtime\\.tools\\.xtensa-esp-elf\\.path\\}/bin/"),
                    "compiler.path=$binaryPrefix"
                )
            }

            platformTxt.writeText(content)
            onProgress("✓ Patched platform.txt")
        } catch (e: Exception) {
            onProgress("✗ Failed to patch platform.txt: ${e.message}")
        }
    }

    private fun writePlatformLocalTxt(
        coreDir: File,
        xtensaDir: File,
        esptoolDir: File,
        onProgress: (String) -> Unit
    ) {
        try {
            val mkspiffsDir = File(dataDir, "packages/esp32/tools/mkspiffs/0.2.3-arduino-esp32")
            val mklittlefsDir = File(dataDir, "packages/esp32/tools/mklittlefs/3.4.1")
            val openocdDir = File(dataDir, "packages/esp32/tools/openocd-esp32/v0.12.0-esp32-20240318")

            val hasBinFolder = File(xtensaDir, "bin").exists()
            val binaryPrefix = if (hasBinFolder) "${xtensaDir.absolutePath}/bin/" else "${xtensaDir.absolutePath}/"

            File(coreDir, "platform.local.txt").writeText("""
compiler.path=$binaryPrefix
tools.xtensa-esp-elf.path=${xtensaDir.absolutePath}
tools.esptool_py.path=${esptoolDir.absolutePath}
tools.mkspiffs.path=${mkspiffsDir.absolutePath}
tools.mklittlefs.path=${mklittlefsDir.absolutePath}
tools.openocd-esp32.path=${openocdDir.absolutePath}
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

    private fun patchAllScripts(onProgress: (String) -> Unit) {
        val rootDir = File(dataDir, "packages/esp32")
        if (!rootDir.exists()) return

        var patchedTotal = 0
        rootDir.walkTopDown().forEach { file ->
            if (file.isFile &&
                !file.name.endsWith(".bin") &&
                !file.name.endsWith(".a") &&
                !file.name.endsWith(".o") &&
                !file.name.endsWith(".elf") &&
                !file.name.endsWith(".so") &&
                file.length() < 512_000
            ) {
                try {
                    val content = file.readText()
                    if (content.contains("#!/usr/bin/env") || content.contains("/usr/bin/env ")) {
                        val patched = content
                            .replace("#!/usr/bin/env python3", "#!/system/bin/sh")
                            .replace("#!/usr/bin/env python", "#!/system/bin/sh")
                            .replace("#!/usr/bin/env bash", "#!/system/bin/sh")
                            .replace("#!/usr/bin/env sh", "#!/system/bin/sh")
                            .replace("/usr/bin/env python3 ", "/system/bin/sh ")
                            .replace("/usr/bin/env python ", "/system/bin/sh ")
                            .replace("/usr/bin/env bash ", "/system/bin/sh ")
                        if (patched != content) {
                            file.writeText(patched)
                            file.setExecutable(true, false)
                            patchedTotal++
                        }
                    }
                } catch (e: Exception) { }
            }
        }
        onProgress("✓ Patched $patchedTotal scripts total")
    }

    fun patchEsp32CoreScripts(onProgress: (String) -> Unit = {}) = patchAllScripts(onProgress)

    private fun setupFakeEnv() {
        // ⚡ THE TALLOC RESCUE OPERATION
        val libDir = File(filesDir, "lib").also { it.mkdirs() }
        val tallocDest = File(libDir, "libtalloc.so.2")
        if (!tallocDest.exists()) {
            val tallocSrc = File(context.applicationInfo.nativeLibraryDir, "libtalloc.so")
            if (tallocSrc.exists()) {
                tallocSrc.copyTo(tallocDest, overwrite = true)
            }
        }

        val binDir = File(filesDir, "bin")
        binDir.mkdirs()
        val usrBinDir = File(filesDir, "usr/bin")
        usrBinDir.mkdirs()

        val sh = File("/system/bin/sh")
        if (!sh.exists()) return

        val names = listOf("env", "bash", "python3", "python", "sh")
        for (dir in listOf(binDir, usrBinDir)) {
            for (name in names) {
                val target = File(dir, name)
                if (!target.exists()) {
                    try {
                        sh.copyTo(target, overwrite = false)
                        target.setExecutable(true, false)
                        target.setReadable(true, false)
                    } catch (e: Exception) {
                        Log.w("ArduinoCompiler", "Could not create $name shim: ${e.message}")
                    }
                }
            }
        }
    }

    private fun buildEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        val tmpDir = File(context.cacheDir, "tmp").also { it.mkdirs() }
        val binDir = File(filesDir, "bin")
        val xtensaRoot = File(dataDir, "packages/esp32/tools/xtensa-esp-elf/esp-14.2.0_20260121")
        val xtensaBin = File(xtensaRoot, "bin")

        val pathParts = mutableListOf(
            binDir.absolutePath,
            File(filesDir, "usr/bin").absolutePath,
            context.applicationInfo.nativeLibraryDir
        )
        if (xtensaBin.exists()) pathParts.add(xtensaBin.absolutePath)
        pathParts.add("/system/bin")
        pathParts.add("/system/xbin")
        env["PATH"] = pathParts.joinToString(":")

        // 📡 THE .SO RADAR
        val soDirs = mutableSetOf(
            File(filesDir, "lib").absolutePath, // <--- ADDED TALLOC RESCUE PATH
            context.applicationInfo.nativeLibraryDir,
            File(xtensaRoot, "lib").absolutePath,
            File(xtensaRoot, "lib64").absolutePath
        )
        if (xtensaRoot.exists()) {
            xtensaRoot.walkTopDown().forEach { f ->
                if (f.isFile && (f.name.endsWith(".so") || f.name.contains(".so."))) {
                    soDirs.add(f.parentFile.absolutePath)
                }
            }
        }
        val currentLd = env["LD_LIBRARY_PATH"] ?: ""
        env["LD_LIBRARY_PATH"] = soDirs.joinToString(":") + if (currentLd.isNotEmpty()) ":$currentLd" else ""

        // ⚡ THE PROOT DESK & SECCOMP ANTI-VENOM
        val prootScratch = File(filesDir, "proot_scratch").also { it.mkdirs() }
        env["PROOT_TMP_DIR"] = prootScratch.absolutePath
        env["PROOT_NO_SECCOMP"] = "1"

        env.remove("HTTP_PROXY"); env.remove("HTTPS_PROXY")
        env["NO_PROXY"] = "*"
        env["GOPROXY"] = "direct"

        env["HOME"] = filesDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["ARDUINO_DIRECTORIES_DATA"] = dataDir
        env["ARDUINO_DIRECTORIES_DOWNLOADS"] = File(filesDir, "staging").absolutePath
        env["ARDUINO_DIRECTORIES_USER"] = File(filesDir, "sketchbook").absolutePath
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
            proc.inputStream.bufferedReader().forEachLine { line ->
                sb.appendLine(line)
                onProgress(line)
            }
            proc.waitFor(300, TimeUnit.SECONDS)
            proc.exitValue() == 0
        } catch (e: Exception) {
            // ⚡ THE TRUTH SERUM: If the kernel snipes us, write the autopsy to the screen!
            sb.appendLine("CRITICAL KERNEL ABORT:\n${e.message}\n${Log.getStackTraceString(e)}")
            onProgress("✗ Command failed: ${e.message}")
            false
        }
    }

    suspend fun compile(
        sketchContent: String,
        fqbn: String,
        onProgress: (String) -> Unit
    ): CompileResult = withContext(Dispatchers.IO) {

        if (!isCliReady() || !isEsp32CoreInstalled()) {
            val cloudUrl = prefs.cloudCompilerUrl
            if (cloudUrl.isNotEmpty()) {
                onProgress("arduino-cli not ready — using cloud compiler...")
                return@withContext CloudCompiler(context).compile(sketchContent, fqbn, onProgress)
            }
            return@withContext CompileResult(
                success = false,
                error = "arduino-cli not installed.\n\nGo to Settings → Setup Compiler\nto download arduino-cli and ESP32 core.\n\nOr set a Cloud Compiler URL in Settings."
            )
        }

        try {
            setupFakeEnv()

            // ⚡ THE FINAL JAILBREAK: Copy arduino-cli to private storage and force +x execute permissions!
            val cliFile = File(filesDir, "arduino-cli")
            if (!cliFile.exists() || cliFile.length() == 0L) {
                File(context.applicationInfo.nativeLibraryDir, "libarduino-cli.so").copyTo(cliFile, overwrite = true)
                cliFile.setExecutable(true, false) // Force chmod 755/777
            }
            val cliPath = cliFile.absolutePath

            val coreDir = File(dataDir, "packages/esp32/hardware/esp32/3.2.0")
            val xtensaDir = File(dataDir, "packages/esp32/tools/xtensa-esp-elf/esp-14.2.0_20260121")
            val esptoolDir = File(dataDir, "packages/esp32/tools/esptool_py/4.8.1")
            if (coreDir.exists()) {
                writePlatformLocalTxt(coreDir, xtensaDir, esptoolDir, {})
            }

            val tmpDir = File(context.cacheDir, "sketch_${System.currentTimeMillis()}")
            val sketchDir = File(tmpDir, "sketch")
            val buildDir = File(tmpDir, "build")
            sketchDir.mkdirs(); buildDir.mkdirs()

            File(sketchDir, "sketch.ino").writeText(sketchContent)
            onProgress("Compiling for $fqbn...")

            // 🚀 THE ARMED PROOT EXECUTION WRAPPER WITH STORAGE PASSTHROUGH
            val cmd = listOf(
                prootPath,
                "--link2symlink",
                "-b", "/system:/system",
                "-b", "/dev:/dev",
                "-b", "/proc:/proc",
                "-b", "/data:/data",
                "-b", "/storage:/storage",
                "-b", "/sdcard:/sdcard",
                cliPath, "compile",
                "--fqbn", fqbn,
                "--verbose",
                "--build-path", buildDir.absolutePath,
                "--config-file", getConfigPath(),
                sketchDir.absolutePath
            )

            val sb = StringBuilder()
            val success = runCommandWithOutput(cmd, onProgress, sb)
            val output = sb.toString()

            if (!success) {
                tmpDir.deleteRecursively()
                return@withContext CompileResult(success = false, error = output, output = output)
            }

            val binFile = buildDir.listFiles()?.find {
                it.name.endsWith(".bin") &&
                        !it.name.contains("bootloader") &&
                        !it.name.contains("partitions")
            }

            if (binFile == null) {
                tmpDir.deleteRecursively()
                return@withContext CompileResult(success = false, error = "Build ok but .bin not found\n$output")
            }

            val outBin = File(filesDir, "firmware.bin")
            binFile.copyTo(outBin, overwrite = true)
            buildDir.listFiles()?.find { it.name.contains("bootloader.bin") }
                ?.copyTo(File(filesDir, "bootloader.bin"), overwrite = true)
            buildDir.listFiles()?.find { it.name.contains("partitions.bin") }
                ?.copyTo(File(filesDir, "partitions.bin"), overwrite = true)

            prefs.lastBinPath = outBin.absolutePath
            prefs.lastBinSize = outBin.length().toInt()

            val size = Regex("""Sketch uses (\d+) bytes""").find(output)
                ?.groupValues?.get(1)?.toIntOrNull() ?: outBin.length().toInt()

            tmpDir.deleteRecursively()
            onProgress("✓ Compiled! ${outBin.length() / 1024} KB")

            CompileResult(success = true, binPath = outBin.absolutePath, binSize = size, output = output)

        } catch (e: Exception) {
            CompileResult(success = false, error = "Compile exception: ${e.message}")
        }
    }

    private fun getConfigPath(): String {
        val data = File(dataDir)
        val staging = File(filesDir, "staging")
        val user = File(filesDir, "sketchbook")
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

    private fun extractTarGz(tarGz: File, destDir: File, onProgress: (String) -> Unit) {
        var count = 0
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(tarGz)))
        ).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val name = entry.name.substringAfter("/")
                if (name.isNotEmpty()) {
                    val outFile = File(destDir, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { tar.copyTo(it) }
                        // ⚡ THE SINGLE DOT OF DESTINY:
                        if (!outFile.name.contains(".") ||
                            outFile.name.endsWith(".sh") ||
                            outFile.name.endsWith(".py")
                        ) {
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
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                        if (!outFile.name.contains(".") || outFile.name.endsWith(".sh"))
                            outFile.setExecutable(true, false)
                    }
                    count++
                    if (count % 300 == 0) onProgress("  Extracting... ($count files)")
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        onProgress("  ✓ Extracted $count files")
    }

    private suspend fun downloadFileWithProgress(
        url: String,
        targetFile: File,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                onProgress("  HTTP ${response.code} for ${targetFile.name}")
                return@withContext false
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
            true
        } catch (e: Exception) {
            onProgress("  Download error: ${e.message}")
            false
        }
    }
}