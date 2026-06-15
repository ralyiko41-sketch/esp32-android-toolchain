package com.esp32ide.compiler

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.esp32ide.MainActivity
import com.esp32ide.R
import kotlinx.coroutines.*

class CompilerService : Service() {

    inner class CompilerBinder : Binder() {
        fun getService() = this@CompilerService
    }

    private val binder = CompilerBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val CHANNEL_ID = "compiler_channel"
    private val NOTIF_ID = 42

    var onLogLine: ((String) -> Unit)? = null
    var onDone: ((CompileResult) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startCompile(sketchContent: String, fqbn: String) {
        startForeground(NOTIF_ID, buildNotification("Compiling..."))
        scope.launch {
            val compiler = ArduinoCompiler(applicationContext)
            val result = compiler.compile(sketchContent, fqbn) { line ->
                onLogLine?.invoke(line)
                updateNotification(line.take(60))
            }
            withContext(Dispatchers.Main) {
                onDone?.invoke(result)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun startSetup(onProgress: (String) -> Unit, onDone: (Boolean) -> Unit) {
        startForeground(NOTIF_ID, buildNotification("Setting up compiler..."))
        scope.launch {
            val compiler = ArduinoCompiler(applicationContext)
            var ok = true
            if (!compiler.isCliReady()) {
                ok = compiler.downloadArduinoCli {
                    onProgress(it)
                    updateNotification(it.take(60))
                }
            }
            if (ok && !compiler.isEsp32CoreInstalled()) {
                ok = compiler.installEsp32Core {
                    onProgress(it)
                    updateNotification(it.take(60))
                }
            }
            withContext(Dispatchers.Main) { onDone(ok) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Compiler", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESP32 IDE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
