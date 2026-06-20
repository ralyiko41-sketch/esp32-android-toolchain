package com.esp32ide.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("esp32ide_prefs", Context.MODE_PRIVATE)

    // ── Board ─────────────────────────────────────────────────────────────
    var selectedBoard: String
        get() = prefs.getString("board_name", "ESP32 Dev Module") ?: "ESP32 Dev Module"
        set(v) = prefs.edit().putString("board_name", v).apply()

    var boardFQBN: String
        get() = prefs.getString("board_fqbn", "esp32:esp32:esp32") ?: "esp32:esp32:esp32"
        set(v) = prefs.edit().putString("board_fqbn", v).apply()

    var importedBoardsTxt: String
        get() = prefs.getString("boards_txt", "") ?: ""
        set(v) = prefs.edit().putString("boards_txt", v).apply()

    // ── Serial ────────────────────────────────────────────────────────────
    var baudRate: Int
        get() = prefs.getInt("baud_rate", 115200)
        set(v) = prefs.edit().putInt("baud_rate", v).apply()

    var flashBaud: Int
        get() = prefs.getInt("flash_baud", 921600)
        set(v) = prefs.edit().putInt("flash_baud", v).apply()

    // ── Editor ────────────────────────────────────────────────────────────
    var fontSize: Int
        get() = prefs.getInt("font_size", 14)
        set(v) = prefs.edit().putInt("font_size", v).apply()

    var autoIndent: Boolean
        get() = prefs.getBoolean("auto_indent", true)
        set(v) = prefs.edit().putBoolean("auto_indent", v).apply()

    var wordWrap: Boolean
        get() = prefs.getBoolean("word_wrap", false)
        set(v) = prefs.edit().putBoolean("word_wrap", v).apply()

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(v) = prefs.edit().putBoolean("dark_theme", v).apply()

    // ── Compiler ──────────────────────────────────────────────────────────
    var arduinoCliReady: Boolean
        get() = prefs.getBoolean("cli_ready", false)
        set(v) = prefs.edit().putBoolean("cli_ready", v).apply()

    var esp32CoreInstalled: Boolean
        get() = prefs.getBoolean("esp32_core", false)
        set(v) = prefs.edit().putBoolean("esp32_core", v).apply()

    var cloudCompilerUrl: String
        get() = prefs.getString("cloud_url", "") ?: ""
        set(v) = prefs.edit().putString("cloud_url", v).apply()

    // ── Git ───────────────────────────────────────────────────────────────
    var gitRemote: String
        get() = prefs.getString("git_remote", "") ?: ""
        set(v) = prefs.edit().putString("git_remote", v).apply()

    var gitUser: String
        get() = prefs.getString("git_user", "") ?: ""
        set(v) = prefs.edit().putString("git_user", v).apply()

    var gitToken: String
        get() = prefs.getString("git_token", "") ?: ""
        set(v) = prefs.edit().putString("git_token", v).apply()

    var gitBranch: String
        get() = prefs.getString("git_branch", "main") ?: "main"
        set(v) = prefs.edit().putString("git_branch", v).apply()

    // ── OTA ───────────────────────────────────────────────────────────────
    var otaIP: String
        get() = prefs.getString("ota_ip", "") ?: ""
        set(v) = prefs.edit().putString("ota_ip", v).apply()

    var otaPort: Int
        get() = prefs.getInt("ota_port", 3232)
        set(v) = prefs.edit().putInt("ota_port", v).apply()

    var otaPassword: String
        get() = prefs.getString("ota_pass", "") ?: ""
        set(v) = prefs.edit().putString("ota_pass", v).apply()

    // ── Last compiled binary path ─────────────────────────────────────────
    var lastBinPath: String
        get() = prefs.getString("last_bin", "") ?: ""
        set(v) = prefs.edit().putString("last_bin", v).apply()

    var lastBinSize: Int
        get() = prefs.getInt("last_bin_size", 0)
        set(v) = prefs.edit().putInt("last_bin_size", v).apply()

    var lastSketchId: Int
        get() = prefs.getInt("last_sketch_id", 0)
        set(v) = prefs.edit().putInt("last_sketch_id", v).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit().putBoolean("onboarding_done", v).apply()
}
