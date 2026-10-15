package com.parental.control.app.location

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

/**
 * Feature 4: Live Location Tracking & Geofencing Service
 * Runs as a transparent foreground service providing real-time GPS coordinates and safe-zone monitoring.
 */
class LocationGeofenceService : Service() {

    private val tag = "LocationService"
    private val notificationChannelId = "LOCATION_TRACKING_CHANNEL"
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(1001, createServiceNotification())

        setupLocationUpdates()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Device Location Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps parental safety location tracking active."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Parental Protection Active")
            .setContentText("Location & safe-zone monitoring running in background.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .setMinUpdateDistanceMeters(10f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    onNewLocationCaptured(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.d(tag, "Location updates successfully registered.")
        } catch (e: SecurityException) {
            Log.e(tag, "Location permission missing: ${e.message}")
        }
    }

    private fun onNewLocationCaptured(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = location.accuracy
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        Log.d(tag, "[GPS UPDATE] Lat: $lat | Lng: $lng | Acc: ${accuracy}m | Speed: ${speedKmh.toInt()} km/h")

        // Broadcast to SyncHub for network transmission to Parent
        com.parental.control.app.network.SyncHub.onLocationUpdated(lat, lng, accuracy, speedKmh)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        Log.d(tag, "Location service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
