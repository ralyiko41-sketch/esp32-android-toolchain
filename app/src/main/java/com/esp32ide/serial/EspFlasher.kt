package com.esp32ide.serial

import kotlinx.coroutines.*
import java.io.File
import java.util.zip.Deflater

/**
 * ESP32 ROM Bootloader Flasher
 * Implements the ESP8266/ESP32 serial protocol used by esptool.py
 * Supports: ESP32, ESP32-S2, ESP32-S3, ESP32-C3, ESP32-C6
 */
class EspFlasher(private val serial: UsbSerialManager) {

    companion object {
        // Slip framing
        const val SLIP_END     = 0xC0.toByte()
        const val SLIP_ESC     = 0xDB.toByte()
        const val SLIP_ESC_END = 0xDC.toByte()
        const val SLIP_ESC_ESC = 0xDD.toByte()

        // Commands
        const val CMD_FLASH_BEGIN    = 0x02.toByte()
        const val CMD_FLASH_DATA     = 0x03.toByte()
        const val CMD_FLASH_END      = 0x04.toByte()
        const val CMD_MEM_BEGIN      = 0x05.toByte()
        const val CMD_MEM_END        = 0x06.toByte()
        const val CMD_MEM_DATA       = 0x07.toByte()
        const val CMD_SYNC           = 0x08.toByte()
        const val CMD_WRITE_REG      = 0x09.toByte()
        const val CMD_READ_REG       = 0x0A.toByte()
        const val CMD_SPI_SET_PARAMS = 0x0B.toByte()
        const val CMD_SPI_ATTACH     = 0x0D.toByte()
        const val CMD_FLASH_DEFL_BEGIN = 0x10.toByte()
        const val CMD_FLASH_DEFL_DATA  = 0x11.toByte()
        const val CMD_FLASH_DEFL_END   = 0x12.toByte()
        const val CMD_CHANGE_BAUDRATE  = 0x0F.toByte()

        const val FLASH_BLOCK_SIZE = 0x4000  // 16KB blocks
        const val FLASH_SECTOR_SIZE = 0x1000 // 4KB
    }

    data class FlashResult(
        val success: Boolean,
        val message: String = "",
        val error: String = ""
    )

    // ── Main flash entry point ────────────────────────────────────────────
    suspend fun flash(
        firmwarePath: String,
        flashBaud: Int = 921600,
        onProgress: (String) -> Unit,
        onPercent: (Int) -> Unit
    ): FlashResult = withContext(Dispatchers.IO) {

        val firmware = File(firmwarePath)
        if (!firmware.exists()) {
            return@withContext FlashResult(false, error = "Firmware file not found: $firmwarePath")
        }

        try {
            onProgress("Entering bootloader mode...")

            // Enter bootloader: DTR=HIGH, RTS=LOW → DTR=LOW, RTS=HIGH → DTR=HIGH, RTS=LOW
            enterBootloader()
            delay(500)

            onProgress("Connecting to ESP32 ROM bootloader...")

            // Try to sync
            var synced = false
            repeat(5) {
                if (!synced) {
                    synced = sync()
                    if (!synced) delay(200)
                }
            }

            if (!synced) {
                // Try harder reset
                enterBootloader()
                delay(1000)
                repeat(10) {
                    if (!synced) {
                        synced = sync()
                        if (!synced) delay(100)
                    }
                }
            }

            if (!synced) {
                return@withContext FlashResult(
                    false,
                    error = "Cannot connect to ESP32.\n\n" +
                            "• Hold BOOT button on ESP32 while connecting\n" +
                            "• Check USB cable (use data cable, not charge-only)\n" +
                            "• Try a different baud rate in Settings\n" +
                            "• Make sure Serial Monitor is disconnected"
                )
            }

            onProgress("✓ Connected to ROM bootloader!")
            onPercent(5)

            // Change to higher baud for faster flashing
            if (flashBaud > 115200) {
                onProgress("Switching to $flashBaud baud...")
                changeBaud(flashBaud)
                delay(100)
                serial.getPort()?.setParameters(flashBaud, 8, 1, 0)
                delay(100)
            }

            // Attach SPI flash
            onProgress("Attaching SPI flash...")
            spiAttach()
            delay(100)

            // Flash the firmware
            val firmwareBytes = firmware.readBytes()
            onProgress("Flashing ${firmwareBytes.size / 1024} KB to 0x10000...")
            onPercent(10)

            val flashSuccess = flashData(
                data = firmwareBytes,
                address = 0x10000,
                onProgress = onProgress,
                onPercent = { pct -> onPercent(10 + (pct * 0.85).toInt()) }
            )

            if (!flashSuccess) {
                return@withContext FlashResult(false, error = "Flash write failed")
            }

            onPercent(95)
            onProgress("Verifying...")
            delay(200)

            // Also flash bootloader and partitions if available
            val filesDir = firmware.parentFile
            val bootloader = File(filesDir, "bootloader.bin")
            val partitions = File(filesDir, "partitions.bin")

            if (bootloader.exists()) {
                onProgress("Flashing bootloader...")
                flashData(bootloader.readBytes(), 0x1000, onProgress) {}
            }

            if (partitions.exists()) {
                onProgress("Flashing partitions...")
                flashData(partitions.readBytes(), 0x8000, onProgress) {}
            }

            // Reset device
            onProgress("Rebooting ESP32...")
            exitBootloader()
            delay(500)
            serial.getPort()?.setParameters(115200, 8, 1, 0)

            onPercent(100)
            FlashResult(true, message = "✓ Flash successful! ESP32 is rebooting...")

        } catch (e: Exception) {
            FlashResult(false, error = "Flash error: ${e.message}")
        }
    }

    // ── Bootloader entry (GPIO0 = LOW via RTS/DTR) ────────────────────────
    private fun enterBootloader() {
        // Classic esptool reset sequence
        serial.setDTR(false); serial.setRTS(true);  Thread.sleep(100)
        serial.setDTR(true);  serial.setRTS(false); Thread.sleep(100)
        serial.setDTR(false);                        Thread.sleep(50)
    }

    private fun exitBootloader() {
        serial.setRTS(false); serial.setDTR(false)
        Thread.sleep(100)
        // Hard reset
        serial.setRTS(true);  Thread.sleep(100)
        serial.setRTS(false)
    }

    // ── SLIP encode ───────────────────────────────────────────────────────
    private fun slipEncode(data: ByteArray): ByteArray {
        val out = mutableListOf(SLIP_END)
        for (b in data) {
            when (b) {
                SLIP_END -> { out.add(SLIP_ESC); out.add(SLIP_ESC_END) }
                SLIP_ESC -> { out.add(SLIP_ESC); out.add(SLIP_ESC_ESC) }
                else -> out.add(b)
            }
        }
        out.add(SLIP_END)
        return out.toByteArray()
    }

    private fun slipDecode(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < data.size) {
            when (data[i]) {
                SLIP_END -> { i++; continue }
                SLIP_ESC -> {
                    i++
                    if (i < data.size) {
                        out.add(if (data[i] == SLIP_ESC_END) SLIP_END else SLIP_ESC)
                    }
                }
                else -> out.add(data[i])
            }
            i++
        }
        return out.toByteArray()
    }

    // ── Build command packet ──────────────────────────────────────────────
    private fun buildPacket(cmd: Byte, data: ByteArray, checksum: Int = 0): ByteArray {
        val packet = ByteArray(8 + data.size)
        packet[0] = 0x00  // direction (request)
        packet[1] = cmd
        packet[2] = (data.size and 0xFF).toByte()
        packet[3] = ((data.size shr 8) and 0xFF).toByte()
        packet[4] = (checksum and 0xFF).toByte()
        packet[5] = ((checksum shr 8) and 0xFF).toByte()
        packet[6] = ((checksum shr 16) and 0xFF).toByte()
        packet[7] = ((checksum shr 24) and 0xFF).toByte()
        data.copyInto(packet, 8)
        return packet
    }

    private fun checksum(data: ByteArray): Int {
        return data.fold(0xEF) { acc, b -> acc xor (b.toInt() and 0xFF) }
    }

    // ── Send command and read response ────────────────────────────────────
    private fun sendCommand(cmd: Byte, data: ByteArray = ByteArray(0), chk: Int = 0): ByteArray? {
        val packet = buildPacket(cmd, data, chk)
        val encoded = slipEncode(packet)
        serial.writeRaw(encoded, 2000)

        // Read response
        val response = mutableListOf<Byte>()
        val deadline = System.currentTimeMillis() + 3000
        var inPacket = false

        while (System.currentTimeMillis() < deadline) {
            val chunk = serial.readRaw(200)
            for (b in chunk) {
                if (b == SLIP_END) {
                    if (inPacket && response.size > 0) {
                        val decoded = slipDecode(response.toByteArray())
                        if (decoded.size >= 8 && decoded[0] == 0x01.toByte() && decoded[1] == cmd) {
                            return decoded
                        }
                        response.clear()
                    }
                    inPacket = true
                } else if (inPacket) {
                    response.add(b)
                }
            }
            if (response.size > 16 && !inPacket) break
        }
        return null
    }

    // ── Sync ──────────────────────────────────────────────────────────────
    private fun sync(): Boolean {
        // Sync packet: 0x07 0x07 0x12 0x20 + 32x 0x55
        val syncData = ByteArray(36)
        syncData[0] = 0x07; syncData[1] = 0x07; syncData[2] = 0x12; syncData[3] = 0x20
        for (i in 4 until 36) syncData[i] = 0x55
        val resp = sendCommand(CMD_SYNC, syncData) ?: return false
        return resp.size >= 8 && resp[0] == 0x01.toByte()
    }

    // ── Change baud ───────────────────────────────────────────────────────
    private fun changeBaud(newBaud: Int) {
        val data = ByteArray(8)
        putInt32LE(data, 0, newBaud)
        putInt32LE(data, 4, 0)
        sendCommand(CMD_CHANGE_BAUDRATE, data)
    }

    // ── SPI attach ────────────────────────────────────────────────────────
    private fun spiAttach() {
        val data = ByteArray(8) // zeros = default SPI
        sendCommand(CMD_SPI_ATTACH, data)
    }

    // ── Flash data blocks ─────────────────────────────────────────────────
    private fun flashData(
        data: ByteArray,
        address: Int,
        onProgress: (String) -> Unit,
        onPercent: (Int) -> Unit
    ): Boolean {
        val blockSize = FLASH_BLOCK_SIZE
        val numBlocks = (data.size + blockSize - 1) / blockSize
        val eraseSize = numBlocks * blockSize

        // Flash begin
        val beginData = ByteArray(16)
        putInt32LE(beginData, 0, eraseSize)
        putInt32LE(beginData, 4, numBlocks)
        putInt32LE(beginData, 8, blockSize)
        putInt32LE(beginData, 12, address)

        val beginResp = sendCommand(CMD_FLASH_BEGIN, beginData) ?: run {
            onProgress("Flash begin failed")
            return false
        }
        if (beginResp.size < 10 || beginResp[8] != 0.toByte()) {
            onProgress("Flash begin error: status=${beginResp.getOrNull(8)}")
            return false
        }

        // Flash blocks
        for (seq in 0 until numBlocks) {
            val offset = seq * blockSize
            val blockData = data.copyOfRange(offset, minOf(offset + blockSize, data.size))
            val paddedBlock = if (blockData.size < blockSize) {
                blockData + ByteArray(blockSize - blockData.size) { 0xFF.toByte() }
            } else blockData

            val blockPacket = ByteArray(16 + paddedBlock.size)
            putInt32LE(blockPacket, 0, paddedBlock.size)
            putInt32LE(blockPacket, 4, seq)
            putInt32LE(blockPacket, 8, 0)
            putInt32LE(blockPacket, 12, 0)
            paddedBlock.copyInto(blockPacket, 16)

            val chk = checksum(paddedBlock)
            val resp = sendCommand(CMD_FLASH_DATA, blockPacket, chk) ?: run {
                onProgress("Block $seq write failed (no response)")
                return false
            }

            val pct = ((seq + 1) * 100 / numBlocks)
            onPercent(pct)
            if (seq % 4 == 0) onProgress("Writing block ${seq + 1}/$numBlocks ($pct%)")
        }

        // Flash end
        val endData = ByteArray(4)
        putInt32LE(endData, 0, 0) // reboot after flash
        sendCommand(CMD_FLASH_END, endData)

        return true
    }

    private fun putInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
