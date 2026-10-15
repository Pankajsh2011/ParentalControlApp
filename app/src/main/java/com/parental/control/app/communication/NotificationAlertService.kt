package com.parental.control.app.communication

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Feature 3: Communication Security & Message Alerts
 * Intercepts notifications from messaging, calls, and social apps to detect critical communication events.
 */
class NotificationAlertService : NotificationListenerService() {

    private val tag = "NotificationMonitor"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        val extras = sbn.notification.extras ?: return

        // Extract Title (Sender/Caller) and Text (Message preview)
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val timestamp = sbn.postTime

        // Filter out system self-notifications
        if (packageName == applicationContext.packageName) return

        Log.d(tag, "[COMMUNICATION EVENT] App: $packageName | Title/Sender: $title | Preview: $text | Time: $timestamp")

        // Push intercepted communication to SyncHub for Parent Dashboard
        com.parental.control.app.network.SyncHub.onNotificationCaptured(packageName, title, text)

        // Trigger safety check for keywords or alert conditions
        processIncomingAlert(packageName, title, text)
    }

    private fun processIncomingAlert(app: String, sender: String, content: String) {
        val sensitiveKeywords = listOf("urgent", "otp", "code", "bank", "password")
        val isCritical = sensitiveKeywords.any { content.contains(it, ignoreCase = true) }

        if (isCritical) {
            Log.w(tag, "Critical alert flagged in communication from app: $app | Content: $content")
            com.parental.control.app.network.SyncHub.onAlertTriggered("critical_message", "Keyword detected in $app", "Sender: $sender | Content: $content")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
