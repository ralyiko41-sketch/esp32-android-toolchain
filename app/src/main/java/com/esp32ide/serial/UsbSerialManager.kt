package com.esp32ide.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.*
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SerialDevice(
    val device: UsbDevice,
    val driver: UsbSerialDriver,
    val port: UsbSerialPort,
    val name: String,
    val vid: Int,
    val pid: Int
)

sealed class SerialState {
    object Disconnected : SerialState()
    object Connecting : SerialState()
    data class Connected(val device: SerialDevice, val baud: Int) : SerialState()
    data class Error(val message: String) : SerialState()
}

class UsbSerialManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.esp32ide.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<SerialState>(SerialState.Disconnected)
    val state: StateFlow<SerialState> = _state

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onDeviceAttached: ((SerialDevice) -> Unit)? = null
    var onDeviceDetached: (() -> Unit)? = null

    // ── Scan for connected ESP32/USB-serial devices ───────────────────────
    fun getAvailableDevices(): List<SerialDevice> {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        return drivers.flatMap { driver ->
            driver.ports.mapIndexed { _, port ->
                val dev = driver.device
                SerialDevice(
                    device = dev,
                    driver = driver,
                    port = port,
                    name = chipName(dev.vendorId, dev.productId),
                    vid = dev.vendorId,
                    pid = dev.productId
                )
            }
        }
    }

    private fun chipName(vid: Int, pid: Int): String = when (vid) {
        0x1A86 -> "CH340/CH341 (ESP32)"
        0x10C4 -> "CP210x (ESP32)"
        0x0403 -> "FTDI FT232 (ESP32)"
        0x067B -> "PL2303 (ESP32)"
        0x2341 -> "Arduino"
        0x303A -> "Espressif Native USB"
        else   -> "USB Serial Device (${vid.toString(16)}:${pid.toString(16)})"
    }

    // ── Request USB permission then connect ───────────────────────────────
    fun connect(device: SerialDevice, baud: Int) {
        _state.value = SerialState.Connecting

        if (!usbManager.hasPermission(device.device)) {
            val intent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
            // Register one-shot receiver
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    ctx.unregisterReceiver(this)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openPort(device, baud)
                    } else {
                        _state.value = SerialState.Error("USB permission denied")
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
            usbManager.requestPermission(device.device, intent)
        } else {
            openPort(device, baud)
        }
    }

    private fun openPort(device: SerialDevice, baud: Int) {
        try {
            val connection = usbManager.openDevice(device.device)
                ?: run {
                    _state.value = SerialState.Error("Cannot open USB device")
                    return
                }

            device.port.open(connection)
            device.port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            device.port.dtr = true
            device.port.rts = true

            serialPort = device.port

            // Start IO manager for async reads
            ioManager = SerialInputOutputManager(device.port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    onDataReceived?.invoke(data)
                }
                override fun onRunError(e: Exception) {
                    _state.value = SerialState.Error(e.message ?: "Serial error")
                    disconnect()
                }
            })
            ioManager?.start()

            _state.value = SerialState.Connected(device, baud)
        } catch (e: Exception) {
            _state.value = SerialState.Error("Connect failed: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            ioManager?.stop()
            ioManager = null
            serialPort?.close()
            serialPort = null
        } catch (_: Exception) {}
        _state.value = SerialState.Disconnected
    }

    fun send(data: ByteArray) {
        scope.launch {
            try { serialPort?.write(data, 2000) } catch (_: Exception) {}
        }
    }

    fun send(text: String) = send((text + "\n").toByteArray())

    fun isConnected() = _state.value is SerialState.Connected

    // ── For flashing — direct raw byte access ─────────────────────────────
    fun getPort(): UsbSerialPort? = serialPort

    fun setDTR(value: Boolean) { try { serialPort?.dtr = value } catch (_: Exception) {} }
    fun setRTS(value: Boolean) { try { serialPort?.rts = value } catch (_: Exception) {} }

    fun readRaw(timeoutMs: Int = 1000): ByteArray {
        val buf = ByteArray(4096)
        return try {
            val n = serialPort?.read(buf, timeoutMs) ?: 0
            buf.copyOf(n)
        } catch (_: Exception) { ByteArray(0) }
    }

    fun writeRaw(data: ByteArray, timeoutMs: Int = 3000) {
        try { serialPort?.write(data, timeoutMs) } catch (_: Exception) {}
    }

    fun stopReading() { ioManager?.stop(); ioManager = null }
    fun startReading() {
        val port = serialPort ?: return
        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) { onDataReceived?.invoke(data) }
            override fun onRunError(e: Exception) { disconnect() }
        })
        ioManager?.start()
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
