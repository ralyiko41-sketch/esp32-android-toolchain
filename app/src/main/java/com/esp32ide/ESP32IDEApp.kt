package com.esp32ide

import android.app.Application
import android.util.Log
import com.esp32ide.compiler.ArduinoToolsInstaller
import com.esp32ide.data.AppPreferences
import com.esp32ide.data.SketchDatabase

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
                Log.d("ESP32IDE", "Fixed version directory structure: renamed esp32-3.2.0 to 3.2.0")
            }

            val toolsReady = ArduinoToolsInstaller.installIfNeeded(this)
            if (!toolsReady) {
                Log.w("ESP32IDE", "Some tools failed to install: " +
                        ArduinoToolsInstaller.getMissingTools(this).joinToString())
            }
        }.start()
    }
}
