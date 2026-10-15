package com.parental.control.app.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.parental.control.app.screentime.ScreenTimeManager
import java.io.File

class ChildSyncForegroundService : Service() {

    private val tag = "ChildSyncService"
    private val channelId = "PARENTAL_CHILD_SYNC_CHANNEL"
    private lateinit var syncManager: NetworkSyncManager
    private lateinit var screenTimeManager: ScreenTimeManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var activeRingtone: Ringtone? = null

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            performSync()
            handler.postDelayed(this, 8000) // Sync every 8 seconds
        }
    }

    override fun onCreate() {
        super.onCreate()
        syncManager = NetworkSyncManager(this)
        SyncHub.networkSyncManager = syncManager
        screenTimeManager = ScreenTimeManager(this)

        createNotificationChannel()
        startForeground(2001, buildServiceNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParentalControl:SyncWakeLock").apply {
            acquire(60 * 60 * 1000L) // 1 hour at a time
        }

        // Set up command listener
        syncManager.onCommandReceived = { cmd ->
            handleRemoteCommand(cmd)
        }

        // Connect WebSocket for instant commands
        syncManager.connectWebSocket("child")

        isRunning = true
        handler.post(syncRunnable)
        Log.d(tag, "Child Sync Foreground Service launched.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Child Protection Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Child protection telemetry and remote safety commands active."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Child Safety Shield Active")
            .setContentText("Connected to Parental Control Cloud Hub.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun performSync() {
        // 1. Battery status
        var batteryPercent = 100
        var isCharging = false
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPercent = (level * 100 / scale.toFloat()).toInt()
            val status: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }

        // 2. Location
        val location = SyncHub.latestLocation

        // 3. Screen time
        val screenTimeMinutes = try {
            screenTimeManager.getTotalDailyScreenTimeMinutes()
        } catch (e: Exception) {
            0L
        }

        // 4. Pending notification or alert
        val notification = SyncHub.pendingNotification
        SyncHub.pendingNotification = null

        val alert = SyncHub.pendingAlert
        SyncHub.pendingAlert = null

        // 5. Send to Server
        syncManager.syncChildState(
            battery = batteryPercent,
            isCharging = isCharging,
            location = location,
            screenTimeMinutes = screenTimeMinutes,
            topApps = emptyList(),
            newNotification = notification,
            newAlert = alert,
            callback = { success, commands ->
                if (success) {
                    Log.d(tag, "Child telemetry synced successfully. Commands: ${commands.size}")
                }
            }
        )
    }

    private fun handleRemoteCommand(cmd: CommandItem) {
        Log.i(tag, "EXECUTING REMOTE COMMAND: ${cmd.command}")
        when (cmd.command.uppercase()) {
            "RING_ALARM" -> {
                playLoudAlarm()
            }
            "STOP_ALARM" -> {
                stopAlarm()
            }
            "LOCK_DEVICE" -> {
                lockChildDevice()
            }
            "TAKE_SNAPSHOT" -> {
                requestSnapshot()
            }
            else -> {
                Log.d(tag, "Unknown command: ${cmd.command}")
            }
        }
    }

    private fun playLoudAlarm() {
        try {
            stopAlarm()
            var alertUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            activeRingtone = RingtoneManager.getRingtone(applicationContext, alertUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activeRingtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            activeRingtone?.play()

            // Vibrate phone
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(2000)
            }

            // Stop alarm after 15 seconds
            handler.postDelayed({ stopAlarm() }, 15000)
            Toast.makeText(this, "⚠️ Parental Loud Alarm Triggered!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(tag, "Error triggering alarm: ${e.message}")
        }
    }

    private fun stopAlarm() {
        try {
            activeRingtone?.stop()
            activeRingtone = null
        } catch (e: Exception) {}
    }

    private fun lockChildDevice() {
        // Send user to Home screen and display parental alert
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        Toast.makeText(this, "🔒 Device Locked by Parental Control", Toast.LENGTH_LONG).show()
    }

    private fun requestSnapshot() {
        // Upload most recent saved snapshot if available
        val snapshotsDir = File(filesDir, "snapshots")
        val latest = snapshotsDir.listFiles()?.maxByOrNull { it.lastModified() }
        if (latest != null && latest.exists()) {
            syncManager.uploadSnapshot(latest) { success, name ->
                Log.d(tag, "Remote snapshot upload: success=$success, file=$name")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(syncRunnable)
        syncManager.closeWebSocket()
        stopAlarm()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {}
        Log.d(tag, "Child Sync Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
