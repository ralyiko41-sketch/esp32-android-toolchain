package com.esp32ide.utils

data class BoardEntry(
    val id: String,
    val name: String,
    val fqbn: String
)

object BoardsParser {

    fun parse(boardsTxt: String): List<BoardEntry> {
        val boards = mutableMapOf<String, String>()
        boardsTxt.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val match = Regex("""^([^.=]+)\.name=(.+)$""").find(trimmed)
            if (match != null) {
                val id = match.groupValues[1].trim()
                val name = match.groupValues[2].trim()
                boards[id] = name
            }
        }
        return boards.map { (id, name) ->
            BoardEntry(id = id, name = name, fqbn = "esp32:esp32:$id")
        }.sortedBy { it.name }
    }

    val FALLBACK: List<BoardEntry> = listOf(
        BoardEntry("esp32",              "ESP32 Dev Module",            "esp32:esp32:esp32"),
        BoardEntry("esp32s2",            "ESP32-S2 Dev Module",         "esp32:esp32:esp32s2"),
        BoardEntry("esp32s3",            "ESP32-S3 Dev Module",         "esp32:esp32:esp32s3"),
        BoardEntry("esp32c3",            "ESP32-C3 Dev Module",         "esp32:esp32:esp32c3"),
        BoardEntry("esp32c6",            "ESP32-C6 Dev Module",         "esp32:esp32:esp32c6"),
        BoardEntry("esp32h2",            "ESP32-H2 Dev Module",         "esp32:esp32:esp32h2"),
        BoardEntry("lolin_d32",          "LOLIN D32",                   "esp32:esp32:lolin_d32"),
        BoardEntry("lolin_d32_pro",      "LOLIN D32 Pro",               "esp32:esp32:lolin_d32_pro"),
        BoardEntry("lolin32",            "WEMOS LOLIN32",               "esp32:esp32:lolin32"),
        BoardEntry("nodemcu-32s",        "NodeMCU-32S",                 "esp32:esp32:nodemcu-32s"),
        BoardEntry("featheresp32",       "Adafruit Feather ESP32",      "esp32:esp32:featheresp32"),
        BoardEntry("esp32thing",         "SparkFun ESP32 Thing",        "esp32:esp32:esp32thing"),
        BoardEntry("ttgo-lora32-v1",     "TTGO LoRa32-OLED v1",        "esp32:esp32:ttgo-lora32-v1"),
        BoardEntry("ttgo-t-beam",        "TTGO T-Beam",                 "esp32:esp32:ttgo-t-beam"),
        BoardEntry("m5stack-core-esp32", "M5Stack Core",                "esp32:esp32:m5stack-core-esp32"),
        BoardEntry("m5stick-c",          "M5StickC",                    "esp32:esp32:m5stick-c"),
        BoardEntry("m5stick-c-plus",     "M5StickC Plus",               "esp32:esp32:m5stick-c-plus"),
        BoardEntry("m5stack-atom",       "M5Stack ATOM",                "esp32:esp32:m5stack-atom"),
        BoardEntry("m5stack-stamps3",    "M5Stack StampS3",             "esp32:esp32:m5stack-stamps3"),
        BoardEntry("heltec_wifi_kit_32", "Heltec WiFi Kit 32",          "esp32:esp32:heltec_wifi_kit_32"),
        BoardEntry("heltec_wifi_lora_32","Heltec WiFi LoRa 32",         "esp32:esp32:heltec_wifi_lora_32"),
        BoardEntry("ai_thinker_esp32-cam","AI Thinker ESP32-CAM",       "esp32:esp32:ai_thinker_esp32-cam"),
        BoardEntry("esp32doit-devkit-v1","DOIT ESP32 DevKit V1",        "esp32:esp32:esp32doit-devkit-v1"),
        BoardEntry("pico32",             "ESP32 Pico Kit",              "esp32:esp32:pico32"),
        BoardEntry("um_tinys3",          "UM TinyS3",                   "esp32:esp32:um_tinys3"),
        BoardEntry("um_feathers3",       "UM FeatherS3",                "esp32:esp32:um_feathers3"),
        BoardEntry("um_pros3",           "UM ProS3",                    "esp32:esp32:um_pros3"),
        BoardEntry("XIAO_ESP32C3",       "Seeed XIAO ESP32C3",          "esp32:esp32:XIAO_ESP32C3"),
        BoardEntry("XIAO_ESP32S3",       "Seeed XIAO ESP32S3",          "esp32:esp32:XIAO_ESP32S3"),
        BoardEntry("XIAO_ESP32C6",       "Seeed XIAO ESP32C6",          "esp32:esp32:XIAO_ESP32C6"),
        BoardEntry("esp32-evb",          "Olimex ESP32-EVB",            "esp32:esp32:esp32-evb"),
        BoardEntry("esp32wrover",        "ESP32 Wrover Kit",            "esp32:esp32:esp32wrover"),
        BoardEntry("firebeetle32",       "FireBeetle ESP32",            "esp32:esp32:firebeetle32"),
        BoardEntry("bpi_leaf_s3",        "BPI-Leaf-S3",                 "esp32:esp32:bpi_leaf_s3"),
        BoardEntry("esp32s3box",         "ESP32-S3 Box",                "esp32:esp32:esp32s3box"),
        BoardEntry("esp32s3camlcd",      "ESP32-S3-CAM-LCD",            "esp32:esp32:esp32s3camlcd"),
        BoardEntry("esp32c3_m5stamp",    "M5Stamp C3",                  "esp32:esp32:esp32c3_m5stamp"),
    )
}
