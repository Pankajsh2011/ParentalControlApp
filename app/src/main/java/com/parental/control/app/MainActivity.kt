package com.parental.control.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.parental.control.app.location.LocationGeofenceService
import com.parental.control.app.network.ChildState
import com.parental.control.app.network.ChildSyncForegroundService
import com.parental.control.app.network.GeofenceZone
import com.parental.control.app.network.NetworkSyncManager
import com.parental.control.app.network.SyncHub
import com.parental.control.app.screencap.SnapshotCaptureManager
import com.parental.control.app.screentime.ScreenTimeManager
import com.parental.control.app.webrtc.LiveStreamManager

class MainActivity : AppCompatActivity() {

    private lateinit var liveStreamManager: LiveStreamManager
    private lateinit var snapshotManager: SnapshotCaptureManager
    private lateinit var screenTimeManager: ScreenTimeManager
    private lateinit var syncManager: NetworkSyncManager

    private val permissionRequestCode = 100
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isParentRole = false

    private var currentChildLat = 28.6139
    private var currentChildLng = 77.2090
    private var isFlashlightOnLocal = false

    private val parentPollRunnable = object : Runnable {
        override fun run() {
            if (isParentRole) {
                fetchParentData()
                mainHandler.postDelayed(this, 3500) // Poll every 3.5s
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        isParentRole = packageName.contains(".parent")

        // Initialize Managers
        liveStreamManager = LiveStreamManager(this)
        snapshotManager = SnapshotCaptureManager(this)
        screenTimeManager = ScreenTimeManager(this)
        syncManager = NetworkSyncManager(this)
        SyncHub.networkSyncManager = syncManager

        setupCommonUI()

        if (isParentRole) {
            setupParentUI()
        } else {
            setupChildUI()
        }

        checkAndRequestPermissions()
    }

    private fun setupCommonUI() {
        val editServerUrl = findViewById<EditText>(R.id.editServerUrl)
        val btnSaveServerUrl = findViewById<Button>(R.id.btnSaveServerUrl)
        val txtConnectionBadge = findViewById<TextView>(R.id.txtConnectionBadge)

        editServerUrl.setText(syncManager.getServerUrl())

        btnSaveServerUrl.setOnClickListener {
            val url = editServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                syncManager.setServerUrl(url)
                Toast.makeText(this, "Server URL updated!", Toast.LENGTH_SHORT).show()
                if (isParentRole) {
                    syncManager.connectWebSocket("parent")
                    fetchParentData()
                } else {
                    syncManager.connectWebSocket("child")
                }
            }
        }

        syncManager.onConnectionChanged = { isConnected ->
            if (isConnected) {
                txtConnectionBadge.text = "● Live"
                txtConnectionBadge.setTextColor(Color.parseColor("#10B981"))
                txtConnectionBadge.setBackgroundColor(Color.parseColor("#064E3B"))
            } else {
                txtConnectionBadge.text = "● Offline"
                txtConnectionBadge.setTextColor(Color.parseColor("#EF4444"))
                txtConnectionBadge.setBackgroundColor(Color.parseColor("#450A0A"))
            }
        }
    }

    private fun setupParentUI() {
        findViewById<View>(R.id.layoutParentDashboard).visibility = View.VISIBLE
        findViewById<View>(R.id.layoutChildDashboard).visibility = View.GONE
        findViewById<TextView>(R.id.txtAppTitle).text = "Parental Control Pro"

        val btnOpenGoogleMaps = findViewById<Button>(R.id.btnOpenGoogleMaps)
        val btnSetSafeZone = findViewById<Button>(R.id.btnSetSafeZone)
        val btnRingAlarm = findViewById<Button>(R.id.btnRingAlarm)
        val btnStopAlarm = findViewById<Button>(R.id.btnStopAlarm)
        val btnLockDevice = findViewById<Button>(R.id.btnLockDevice)
        val btnRemoteSnapshot = findViewById<Button>(R.id.btnRemoteSnapshot)
        val btnToggleFlashlight = findViewById<Button>(R.id.btnToggleFlashlight)
        val btnParentWebRtc = findViewById<Button>(R.id.btnParentWebRtc)

        val editBlockApp = findViewById<EditText>(R.id.editBlockApp)
        val btnToggleBlockApp = findViewById<Button>(R.id.btnToggleBlockApp)

        btnOpenGoogleMaps.setOnClickListener {
            val uri = Uri.parse("geo:$currentChildLat,$currentChildLng?q=$currentChildLat,$currentChildLng(Child Device)")
            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
            try {
                startActivity(mapIntent)
            } catch (e: Exception) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$currentChildLat,$currentChildLng"))
                startActivity(browserIntent)
            }
        }

        btnSetSafeZone.setOnClickListener {
            val zone = GeofenceZone(
                name = "Home Safe Zone",
                lat = currentChildLat,
                lng = currentChildLng,
                radiusMeters = 300f,
                isEnabled = true
            )
            syncManager.setGeofence(zone) { success ->
                Toast.makeText(this, if (success) "🎯 Safe Zone Geofence set at current position (Radius: 300m)!" else "Failed to set safe zone", Toast.LENGTH_SHORT).show()
            }
        }

        btnRingAlarm.setOnClickListener {
            syncManager.sendParentCommand("RING_ALARM") { success, msg ->
                Toast.makeText(this, if (success) "🔔 Alarm Siren command sent to Child!" else "Failed: $msg", Toast.LENGTH_SHORT).show()
            }
        }

        btnStopAlarm.setOnClickListener {
            syncManager.sendParentCommand("STOP_ALARM") { success, msg ->
                Toast.makeText(this, if (success) "🛑 Stop Alarm command sent!" else "Failed: $msg", Toast.LENGTH_SHORT).show()
            }
        }

        btnLockDevice.setOnClickListener {
            syncManager.sendParentCommand("LOCK_DEVICE") { success, msg ->
                Toast.makeText(this, if (success) "🔒 Lock command sent to Child!" else "Failed: $msg", Toast.LENGTH_SHORT).show()
            }
        }

        btnRemoteSnapshot.setOnClickListener {
            syncManager.sendParentCommand("TAKE_SNAPSHOT") { success, msg ->
                Toast.makeText(this, if (success) "📸 Snapshot requested from Child!" else "Failed: $msg", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleFlashlight.setOnClickListener {
            isFlashlightOnLocal = !isFlashlightOnLocal
            val cmd = if (isFlashlightOnLocal) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF"
            syncManager.sendParentCommand(cmd) { success, msg ->
                Toast.makeText(this, if (success) "🔦 Remote Flashlight command sent!" else "Failed: $msg", Toast.LENGTH_SHORT).show()
            }
        }

        btnParentWebRtc.setOnClickListener {
            val audioTrack = liveStreamManager.createAudioStream()
            if (audioTrack != null) {
                Toast.makeText(this, "Live WebRTC Audio Channel Active", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleBlockApp.setOnClickListener {
            val pkg = editBlockApp.text.toString().trim()
            if (pkg.isNotEmpty()) {
                val isCurrentlyBlocked = SyncHub.dynamicBlockedApps.contains(pkg)
                syncManager.toggleBlockApp(pkg, !isCurrentlyBlocked) { success ->
                    if (success) {
                        Toast.makeText(this, if (!isCurrentlyBlocked) "App Blocked: $pkg" else "App Unblocked: $pkg", Toast.LENGTH_SHORT).show()
                        editBlockApp.text.clear()
                        fetchParentData()
                    }
                }
            }
        }

        syncManager.onStateUpdated = { state ->
            updateParentDashboard(state)
        }

        syncManager.connectWebSocket("parent")
        mainHandler.post(parentPollRunnable)
    }

    private fun fetchParentData() {
        syncManager.fetchChildStatus { success, childState ->
            if (success && childState != null) {
                updateParentDashboard(childState)
            }
        }
    }

    private fun updateParentDashboard(state: ChildState) {
        val txtDeviceModel = findViewById<TextView>(R.id.txtDeviceModel)
        val txtParentBattery = findViewById<TextView>(R.id.txtParentBattery)
        val txtParentScreenTime = findViewById<TextView>(R.id.txtParentScreenTime)
        val txtParentChildOnline = findViewById<TextView>(R.id.txtParentChildOnline)
        val txtParentLocationCoords = findViewById<TextView>(R.id.txtParentLocationCoords)
        val txtParentLocationTime = findViewById<TextView>(R.id.txtParentLocationTime)
        val txtBlockedAppsList = findViewById<TextView>(R.id.txtBlockedAppsList)
        val txtParentNotifications = findViewById<TextView>(R.id.txtParentNotifications)
        val txtParentAlerts = findViewById<TextView>(R.id.txtParentAlerts)

        // Device Info
        txtDeviceModel.text = "${state.deviceModel} • ${state.osVersion}"

        // Battery
        txtParentBattery.text = "${state.battery}% ${if (state.isCharging) "⚡" else ""}"

        // Screen Time
        val hrs = state.screenTimeMinutes / 60
        val mins = state.screenTimeMinutes % 60
        txtParentScreenTime.text = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"

        // Online Badge
        if (state.isOnline) {
            txtParentChildOnline.text = "Live 🟢"
            txtParentChildOnline.setTextColor(Color.parseColor("#10B981"))
        } else {
            txtParentChildOnline.text = "Offline 🔴"
            txtParentChildOnline.setTextColor(Color.parseColor("#EF4444"))
        }

        // Location & Speed
        state.location?.let { loc ->
            currentChildLat = loc.lat
            currentChildLng = loc.lng
            txtParentLocationCoords.text = "Lat: ${String.format("%.5f", loc.lat)} | Lng: ${String.format("%.5f", loc.lng)}"
            txtParentLocationTime.text = "Speed: ${loc.speedKmh.toInt()} km/h | Acc: ${loc.accuracy.toInt()}m | Time: ${loc.time}"
        }

        // Blocked Apps
        if (state.blockedApps.isNotEmpty()) {
            val list = state.blockedApps.joinToString("  •  ") { it.split(".").lastOrNull() ?: it }
            txtBlockedAppsList.text = "🚫 Restricted: $list"
        } else {
            txtBlockedAppsList.text = "No apps currently restricted."
        }

        // Notifications
        if (state.notifications.isNotEmpty()) {
            val text = state.notifications.take(6).joinToString("\n\n") { n ->
                "[${n.app}] ${n.title}: ${n.text}"
            }
            txtParentNotifications.text = text
        }

        // Security Alerts
        if (state.alerts.isNotEmpty()) {
            val alertText = state.alerts.take(6).joinToString("\n\n") { a ->
                "⚠️ [${a.keyword}] ${a.content}"
            }
            txtParentAlerts.text = alertText
        }
    }

    private fun setupChildUI() {
        findViewById<View>(R.id.layoutParentDashboard).visibility = View.GONE
        findViewById<View>(R.id.layoutChildDashboard).visibility = View.VISIBLE
        findViewById<TextView>(R.id.txtAppTitle).text = "Parental Companion (Child)"
        findViewById<TextView>(R.id.txtDeviceModel).text = "Pro Protection Active & Silent"

        val btnGrantNotification = findViewById<Button>(R.id.btnGrantNotification)
        val btnGrantAccessibility = findViewById<Button>(R.id.btnGrantAccessibility)
        val btnGrantUsage = findViewById<Button>(R.id.btnGrantUsage)
        val btnIgnoreBatteryOptimization = findViewById<Button>(R.id.btnIgnoreBatteryOptimization)
        val btnTestAlarm = findViewById<Button>(R.id.btnTestAlarm)

        btnGrantNotification.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnGrantUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnIgnoreBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                } else {
                    Toast.makeText(this, "✅ Already whitelisted for 24/7 background operation!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnTestAlarm.setOnClickListener {
            Toast.makeText(this, "Testing alarm sound...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, ChildSyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // Start Foreground Location Service
        val locationIntent = Intent(this, LocationGeofenceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(locationIntent)
        } else {
            startService(locationIntent)
        }

        // Start Foreground Child Sync Service
        val syncIntent = Intent(this, ChildSyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(syncIntent)
        } else {
            startService(syncIntent)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(parentPollRunnable)
        if (isParentRole) {
            syncManager.closeWebSocket()
        }
        liveStreamManager.close()
    }
}
