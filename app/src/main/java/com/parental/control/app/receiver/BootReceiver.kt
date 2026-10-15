package com.parental.control.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.parental.control.app.location.LocationGeofenceService
import com.parental.control.app.network.ChildSyncForegroundService

/**
 * Pro Feature: Stealth Auto-Start on Device Reboot
 * Automatically restores background tracking and parental sync whenever the phone restarts.
 */
class BootReceiver : BroadcastReceiver() {

    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d(tag, "Device reboot completed. Auto-starting protection services...")

            // Only launch Child services if this app instance is Child companion
            val isChildApp = context.packageName.contains(".child")
            if (isChildApp) {
                // 1. Launch GPS Foreground Service
                val locationIntent = Intent(context, LocationGeofenceService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(locationIntent)
                } else {
                    context.startService(locationIntent)
                }

                // 2. Launch Child Sync & Command Service
                val syncIntent = Intent(context, ChildSyncForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(syncIntent)
                } else {
                    context.startService(syncIntent)
                }

                Log.i(tag, "Parental Protection Services successfully auto-started on boot.")
            }
        }
    }
}
