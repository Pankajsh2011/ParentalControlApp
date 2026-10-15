package com.parental.control.app.network

import android.location.Location
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

object SyncHub {
    private const val TAG = "SyncHub"

    @Volatile
    var networkSyncManager: NetworkSyncManager? = null

    @Volatile
    var latestLocation: LocationData? = null

    @Volatile
    var pendingNotification: NotificationEntry? = null

    @Volatile
    var pendingAlert: AlertEntry? = null

    // Pro: Dynamic Blocked Apps List
    @Volatile
    var dynamicBlockedApps = mutableSetOf(
        "com.google.android.youtube",
        "com.zhiliaoapp.musically", // TikTok
        "com.instagram.android",
        "com.facebook.katana",
        "com.snapchat.android"
    )

    // Pro: Dynamic Sensitive Keywords List
    @Volatile
    var dynamicKeywords = mutableListOf(
        "urgent", "otp", "code", "bank", "password",
        "violence", "gambling", "drugs", "threat", "bully",
        "meet me", "suicide", "secret", "cheat", "weapon", "porn"
    )

    // Pro: Active Geofence Safe Zone
    @Volatile
    var activeGeofence: GeofenceZone? = null

    @Volatile
    var isGeofenceInside = true

    @Volatile
    var isFlashlightActive = false

    fun onLocationUpdated(lat: Double, lng: Double, accuracy: Float, speedKmh: Float = 0f) {
        val timeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
        latestLocation = LocationData(lat, lng, accuracy, timeStr, speedKmh)

        // Evaluate Geofence boundary
        evaluateGeofence(lat, lng)
    }

    private fun evaluateGeofence(lat: Double, lng: Double) {
        val fence = activeGeofence ?: return
        if (!fence.isEnabled || fence.lat == 0.0 || fence.lng == 0.0) return

        val results = FloatArray(1)
        Location.distanceBetween(lat, lng, fence.lat, fence.lng, results)
        val distance = results[0]

        if (distance > fence.radiusMeters) {
            if (isGeofenceInside) {
                isGeofenceInside = false
                Log.w(TAG, "GEOFENCE BREACH: Child has left ${fence.name} (Distance: ${distance.toInt()}m)")
                onAlertTriggered(
                    type = "geofence_breach",
                    keyword = "OUT OF BOUNDS",
                    content = "Child exited ${fence.name} (Distance from center: ${distance.toInt()}m)"
                )
            }
        } else {
            if (!isGeofenceInside) {
                isGeofenceInside = true
                Log.i(TAG, "GEOFENCE ENTRY: Child has entered ${fence.name}")
                onAlertTriggered(
                    type = "geofence_entry",
                    keyword = "SAFE ZONE",
                    content = "Child returned inside ${fence.name} (Within ${fence.radiusMeters.toInt()}m)"
                )
            }
        }
    }

    fun onNotificationCaptured(app: String, title: String, text: String, isCritical: Boolean = false) {
        pendingNotification = NotificationEntry(app, title, text, System.currentTimeMillis(), isCritical)
    }

    fun onAlertTriggered(type: String, keyword: String, content: String, appSource: String = "") {
        pendingAlert = AlertEntry(type, keyword, content, System.currentTimeMillis(), appSource)
    }
}
