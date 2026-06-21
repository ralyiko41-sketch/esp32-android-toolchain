package com.esp32ide

import android.app.Application
import android.util.Log
import com.esp32ide.compiler.ArduinoToolsInstaller
import com.esp32ide.data.AppPreferences
import com.esp32ide.data.SketchDatabase
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import java.io.File

class ESP32IDEApp : Application() {

    companion object {
        lateinit var instance: ESP32IDEApp
            private set
        lateinit var db: SketchDatabase
            private set
        lateinit var prefs: AppPreferences
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = SketchDatabase.getInstance(this)
        prefs = AppPreferences(this)

        // Initialize TextMate Registries for Syntax Highlighting
        try {
            // This prepares the engine to load grammars from assets or storage
            ThemeRegistry.getInstance()
            GrammarRegistry.getInstance()
        } catch (e: Exception) {
            Log.e("ESP32IDE", "Failed to initialize TextMate registries: ${e.message}")
        }

        // Move tools installation OFF the main thread to avoid skipped frames
        Thread {
            // Try to create env wrapper via direct shell command for robustness
            try {
                val procShell = Runtime.getRuntime().exec(arrayOf(
                    "/system/bin/sh", "-c",
                    "mkdir -p ${filesDir.absolutePath}/bin && " +
                    "echo '#!/system/bin/sh' > ${filesDir.absolutePath}/bin/env && " +
                    "echo 'exec \"\$@\"' >> ${filesDir.absolutePath}/bin/env && " +
                    "chmod 755 ${filesDir.absolutePath}/bin/env"
                ))
                procShell.waitFor()
            } catch (e: Exception) {
                Log.e("ESP32IDE", "Shell env creation failed: ${e.message}")
            }

            // Fix existing bad directory structure if it exists (one-time fix)
            val baseDir = File(filesDir, "arduino-data/packages/esp32/hardware/esp32")
            val wrongDir = File(baseDir, "esp32-3.2.0")
            val correctDir = File(baseDir, "3.2.0")
            if (wrongDir.exists() && !correctDir.exists()) {
                wrongDir.renameTo(correctDir)
            }

            val toolsReady = ArduinoToolsInstaller.installIfNeeded(this)
            if (!toolsReady) {
                Log.w("ESP32IDE", "Some tools failed to install: " +
                        ArduinoToolsInstaller.getMissingTools(this).joinToString())
            }
        }.start()
    }
}
