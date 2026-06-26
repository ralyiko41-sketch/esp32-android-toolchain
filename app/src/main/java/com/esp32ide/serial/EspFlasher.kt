package com.esp32ide.serial

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class FlashResult(val success: Boolean, val message: String, val error: String = "")

class EspFlasher(private val serialManager: UsbSerialManager) {

    private val TAG = "EspFlasher"
    private val SECTOR_SIZE = 4096
    private val PACKET_SIZE = 1024

    private object Cmd {
        const val SYNC           = 0x08
        const val SPI_ATTACH     = 0x0D
        const val SPI_SET_PARAMS = 0x0B
        const val FLASH_BEGIN    = 0x02
        const val FLASH_DATA     = 0x03
        const val FLASH_END      = 0x04
    }

    private object Slip {
        const val END     = 0xC0.toByte()
        const val ESC     = 0xDB.toByte()
        const val ESC_END = 0xDC.toByte()
        const val ESC_ESC = 0xDD.toByte()
    }

    // =========================================================================
    // CALLED BY FlashFragment — matches existing signature exactly
    // =========================================================================
    suspend fun flash(
        firmwarePath: String,
        flashBaud: Int = 115200,           // kept for compatibility, not used
        onProgress: (String) -> Unit,
        onPercent: (Int) -> Unit
    ): FlashResult = flash(
        binFile     = File(firmwarePath),
        flashOffset = 0x10000,
        onProgress  = onProgress,
        onPercent   = onPercent
    )

    // =========================================================================
    // CORE FLASH LOGIC
    // =========================================================================
    suspend fun flash(
        binFile: File,
        flashOffset: Long = 0x10000,
        onProgress: (String) -> Unit = {},
        onPercent: (Int) -> Unit = {}
    ): FlashResult = withContext(Dispatchers.IO) {
        try {
            if (!binFile.exists())
                return@withContext FlashResult(false, "", "File missing: ${binFile.path}")

            val rawBytes = binFile.readBytes()
            if (rawBytes.isEmpty())
                return@withContext FlashResult(false, "", "File is empty.")

            // Step 1 — Pad to sector boundary (fixes status=1)
            val alignedBytes = padToSector(rawBytes)
            val totalPackets = alignedBytes.size / PACKET_SIZE

            onProgress("Entering bootloader mode...")
            enterBootloader()

            onProgress("Connecting to ESP32 ROM bootloader...")
            if (!handshakeSync()) {
                return@withContext FlashResult(false, "", "ESP32 did not answer SYNC.\nCheck USB cable and CH340 driver.")
            }
            onProgress("✓ Connected to ROM bootloader!")

            // SPI Attach
            onProgress("Attaching SPI flash...")
            sendCmd(Cmd.SPI_ATTACH, ByteArray(8) { 0 })
            getResp(Cmd.SPI_ATTACH, 2000)
                ?: return@withContext FlashResult(false, "", "SPI Attach failed.")

            // SPI Set Params — 4MB geometry
            val geom = ByteArray(24).also {
                writeIntLE(it,  0, 0)
                writeIntLE(it,  4, 4 * 1024 * 1024)  // 4MB total
                writeIntLE(it,  8, 64 * 1024)         // 64KB block
                writeIntLE(it, 12, 4096)              // 4KB sector
                writeIntLE(it, 16, 256)               // 256B page
                writeIntLE(it, 20, 0xFFFF)            // status mask
            }
            sendCmd(Cmd.SPI_SET_PARAMS, geom)
            getResp(Cmd.SPI_SET_PARAMS, 2000)

            // Flash Begin
            val sizeKb = rawBytes.size / 1024
            onProgress("Flashing ${sizeKb} KB to 0x${flashOffset.toString(16)}...")
            val beginPayload = ByteArray(16).also {
                writeIntLE(it,  0, alignedBytes.size)   // sector-aligned erase size
                writeIntLE(it,  4, totalPackets)
                writeIntLE(it,  8, PACKET_SIZE)
                writeIntLE(it, 12, flashOffset.toInt())
            }
            sendCmd(Cmd.FLASH_BEGIN, beginPayload)
            getResp(Cmd.FLASH_BEGIN, 20000)
                ?: return@withContext FlashResult(false, "", "Flash begin error: ROM rejected FLASH_BEGIN (status=1).\nPossible cause: offset not sector-aligned or flash locked.")

            // Flash Data — push 1KB packets
            for (seq in 0 until totalPackets) {
                val idx        = seq * PACKET_SIZE
                val packetData = alignedBytes.copyOfRange(idx, idx + PACKET_SIZE)

                val pktHeader = ByteArray(16).also {
                    writeIntLE(it,  0, PACKET_SIZE)
                    writeIntLE(it,  4, seq)
                    writeIntLE(it,  8, 0)
                    writeIntLE(it, 12, 0)
                }
                sendCmd(Cmd.FLASH_DATA, pktHeader + packetData, calcChecksum(packetData))
                getResp(Cmd.FLASH_DATA, 5000)
                    ?: return@withContext FlashResult(false, "", "Packet $seq dropped — write failed.")

                onPercent(((seq + 1) * 100) / totalPackets)
            }

            // Flash End — reboot
            sendCmd(Cmd.FLASH_END, ByteArray(4).also { writeIntLE(it, 0, 0) })
            try { getResp(Cmd.FLASH_END, 1000) } catch (_: Exception) {}

            FlashResult(true, "✓ Flash successful! ${sizeKb} KB written. Chip rebooting...")

        } catch (e: Exception) {
            Log.e(TAG, "Flash exception", e)
            FlashResult(false, "", e.message ?: "Unknown serial exception")
        }
    }

    // =========================================================================
    // BOOTLOADER ENTRY — correct CH340 DTR/RTS sequence
    // =========================================================================
    private suspend fun enterBootloader() {
        // Flush stale bytes from async IO manager
        repeat(5) { serialManager.readRaw(30) }

        serialManager.setDTR(true);  serialManager.setRTS(true);  delay(250)
        serialManager.setDTR(true);  serialManager.setRTS(false); delay(600) // longer for CH340
        serialManager.setDTR(false); serialManager.setRTS(false); delay(150)

        // Flush bootloader splash bytes (ROM prints version info on reset)
        delay(200)
        repeat(5) { serialManager.readRaw(50) }
    }

    // =========================================================================
    // SYNC HANDSHAKE
    // =========================================================================
    private suspend fun handshakeSync(): Boolean {
        val syncMsg = ByteArray(36).also {
            it[0] = 0x07; it[1] = 0x07; it[2] = 0x12; it[3] = 0x20
            for (i in 4..35) it[i] = 0x55.toByte()
        }
        repeat(10) { attempt ->
            repeat(3) { serialManager.readRaw(30) } // flush before each attempt
            sendCmd(Cmd.SYNC, syncMsg)
            if (getResp(Cmd.SYNC, 400) != null) return true
            delay((attempt + 1) * 50L)
        }
        return false
    }

    // =========================================================================
    // SLIP FRAMING
    // =========================================================================
    private fun sendCmd(cmd: Int, payload: ByteArray, cs: Long = 0) {
        val frame = ByteArray(8 + payload.size)
        frame[0] = 0x00
        frame[1] = cmd.toByte()
        frame[2] = (payload.size and 0xFF).toByte()
        frame[3] = ((payload.size shr 8) and 0xFF).toByte()
        writeIntLE(frame, 4, cs.toInt())
        System.arraycopy(payload, 0, frame, 8, payload.size)
        serialManager.writeRaw(encodeSlip(frame))
    }

    private suspend fun getResp(expectCmd: Int, timeout: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeout
        val slipBuf  = ArrayList<Byte>(256)
        var inFrame  = false
        var esc      = false

        while (System.currentTimeMillis() < deadline) {
            val chunk = serialManager.readRaw(100)
            if (chunk.isEmpty()) { delay(10); continue }

            for (b in chunk) {
                if (!inFrame) {
                    if (b == Slip.END) inFrame = true
                    continue
                }
                if (b == Slip.END) {
                    if (slipBuf.size >= 8
                        && slipBuf[0] == 0x01.toByte()
                        && (slipBuf[1].toInt() and 0xFF) == expectCmd
                    ) {
                        val status = slipBuf[slipBuf.size - 2].toInt() and 0xFF
                        val errCode = slipBuf[slipBuf.size - 1].toInt() and 0xFF
                        if (status != 0) {
                            throw IllegalStateException(
                                "ROM error: status=$status code=0x${errCode.toString(16)}"
                            )
                        }
                        return slipBuf.toByteArray()
                    }
                    // Wrong command — reset buffer and keep looking
                    slipBuf.clear(); inFrame = false; esc = false
                    continue
                }
                if (b == Slip.ESC) { esc = true; continue }
                if (esc) {
                    esc = false
                    slipBuf.add(when (b) {
                        Slip.ESC_END -> Slip.END
                        Slip.ESC_ESC -> Slip.ESC
                        else         -> b
                    })
                } else {
                    slipBuf.add(b)
                }
            }
        }
        return null
    }

    private fun encodeSlip(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + 16)
        out.add(Slip.END)
        for (b in data) when (b) {
            Slip.END -> { out.add(Slip.ESC); out.add(Slip.ESC_END) }
            Slip.ESC -> { out.add(Slip.ESC); out.add(Slip.ESC_ESC) }
            else     -> out.add(b)
        }
        out.add(Slip.END)
        return out.toByteArray()
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
    private fun padToSector(b: ByteArray): ByteArray {
        val rem = b.size % SECTOR_SIZE
        return if (rem == 0) b else b + ByteArray(SECTOR_SIZE - rem) { 0xFF.toByte() }
    }

    private fun calcChecksum(b: ByteArray): Long {
        var c = 0xEF
        for (x in b) c = c xor (x.toInt() and 0xFF)
        return c.toLong() and 0xFF
    }

    private fun writeIntLE(buf: ByteArray, off: Int, v: Int) {
        buf[off]     = (v         and 0xFF).toByte()
        buf[off + 1] = ((v shr  8) and 0xFF).toByte()
        buf[off + 2] = ((v shr 16) and 0xFF).toByte()
        buf[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}