package com.esp32ide.compiler

import android.content.Context
import android.util.Log
import java.io.File

object ArduinoToolsInstaller {

    private const val TAG = "ArduinoToolsInstaller"

    // Matches exactly what arduino-cli looks for on ARM64 Android
    private data class ToolInfo(
        val packageName: String,   // folder name under packages/builtin/tools/
        val version: String,       // subfolder version
        val assetName: String,     // filename in assets/arduino-builtins/
        val binaryName: String     // final executable name arduino-cli expects
    )

    private val BUILTIN_TOOLS = listOf(
        ToolInfo("serial-discovery", "1.4.3",        "serial-discovery",  "serial-discovery"),
        ToolInfo("mdns-discovery",   "1.0.12",       "mdns-discovery",    "mdns-discovery"),
        ToolInfo("serial-monitor",   "0.15.0",       "serial-monitor",    "serial-monitor"),
        ToolInfo("dfu-discovery",    "0.1.2",        "dfu-discovery",     "dfu-discovery")
        // ctags removed — not needed for ESP32 compilation
    )

    /**
     * Call this once at app startup (e.g. in your splash or Application class).
     * Copies bundled ARM64 binaries from assets into the exact directory
     * structure arduino-cli expects, then makes them executable.
     */
    fun installIfNeeded(context: Context): Boolean {
        val dataDir = File(context.filesDir, "arduino-data")
        val toolsBase = File(dataDir, "packages/builtin/tools")
        var allOk = true

        for (tool in BUILTIN_TOOLS) {
            val toolDir  = File(toolsBase, "${tool.packageName}/${tool.version}")
            val toolFile = File(toolDir, tool.binaryName)

            if (toolFile.exists() && toolFile.canExecute()) {
                Log.d(TAG, "Already installed: ${tool.packageName}")
                continue
            }

            toolDir.mkdirs()

            try {
                context.assets.open("arduino-builtins/${tool.assetName}").use { input ->
                    toolFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                toolFile.setExecutable(true, false)
                toolFile.setReadable(true, false)
                Log.d(TAG, "Installed tool: ${tool.packageName} → ${toolFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "FAILED to install ${tool.packageName}: ${e.message}")
                allOk = false
                // Don't crash — partial installs still allow compilation
            }
        }

        // Create a minimal valid ELF binary stub for ctags
        val ctagsDir = File(toolsBase, "ctags/5.8-arduino11")
        val ctagsBin = File(ctagsDir, "ctags")
        if (!ctagsBin.exists()) {
            ctagsDir.mkdirs()
            try {
                // Minimal ARM64 ELF that just exits 0
                val elfBytes = byteArrayOf(
                    0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00, // ELF magic, 64-bit, LE
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // padding
                    0x02, 0x00, 0xb7.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, // ET_EXEC, AArch64
                    0x78, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, // entry point 0x400078
                    0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // phoff
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // shoff
                    0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x38, 0x00, // flags, ehsize, phentsize
                    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // phnum, shentsize, shnum, shstrndx
                    // Program header
                    0x01, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, // PT_LOAD, PF_R|PF_X
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // offset
                    0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, // vaddr
                    0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, // paddr
                    0x90.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // filesz
                    0x90.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // memsz
                    0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // align
                    // ARM64 code at 0x78: mov x8, #93 (exit syscall); mov x0, #0; svc #0
                    0x68, 0x0b, 0x80.toByte(), 0xd2.toByte(), // mov x8, #93
                    0x00, 0x00, 0x80.toByte(), 0xd2.toByte(), // mov x0, #0
                    0x01, 0x00, 0x00, 0xd4.toByte()  // svc #0
                )
                ctagsBin.writeBytes(elfBytes)
                ctagsBin.setExecutable(true, false)
                ctagsBin.setReadable(true, false)
                Log.d(TAG, "Created ctags stub ELF")
            } catch (e: Exception) {
                Log.e(TAG, "FAILED to create ctags stub: ${e.message}")
            }
        }

        installEnvWrapper(context)

        return allOk
    }

    private fun installEnvWrapper(context: Context) {
        // arduino-cli build scripts call /usr/bin/env which doesn't exist on Android
        // We create a wrapper in our app directory and override via PATH
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        
        val fakeEnv = File(binDir, "env")
        try {
            // Write a shell script that acts as /usr/bin/env
            // CRITICAL: escape $ so Kotlin doesn't eat $@
            fakeEnv.writeText("#!/system/bin/sh\nexec \"\$@\"\n")
            fakeEnv.setExecutable(true, false)
            fakeEnv.setReadable(true, false)
            Log.d(TAG, "Installed env wrapper: ${fakeEnv.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "FAILED to install env wrapper: ${e.message}")
        }
    }

    fun areAllInstalled(context: Context): Boolean {
        val toolsBase = File(context.filesDir, "arduino-data/packages/builtin/tools")
        return BUILTIN_TOOLS.all { tool ->
            val f = File(toolsBase, "${tool.packageName}/${tool.version}/${tool.binaryName}")
            f.exists() && f.canExecute()
        }
    }

    fun getMissingTools(context: Context): List<String> {
        val toolsBase = File(context.filesDir, "arduino-data/packages/builtin/tools")
        return BUILTIN_TOOLS
            .filter { tool ->
                val f = File(toolsBase, "${tool.packageName}/${tool.version}/${tool.binaryName}")
                !f.exists() || !f.canExecute()
            }
            .map { it.packageName }
    }
}
