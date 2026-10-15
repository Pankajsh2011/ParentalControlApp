package com.parental.control.app.network

import java.text.SimpleDateFormat
import java.util.*

object SyncHub {
    @Volatile
    var networkSyncManager: NetworkSyncManager? = null

    @Volatile
    var latestLocation: LocationData? = null

    @Volatile
    var pendingNotification: NotificationEntry? = null

    @Volatile
    var pendingAlert: AlertEntry? = null

    fun onLocationUpdated(lat: Double, lng: Double, accuracy: Float) {
        val timeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
        latestLocation = LocationData(lat, lng, accuracy, timeStr)
    }

    fun onNotificationCaptured(app: String, title: String, text: String) {
        pendingNotification = NotificationEntry(app, title, text, System.currentTimeMillis())
    }

    fun onAlertTriggered(type: String, keyword: String, content: String) {
        pendingAlert = AlertEntry(type, keyword, content, System.currentTimeMillis())
    }
}
