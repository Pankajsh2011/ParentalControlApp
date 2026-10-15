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

        val matchingKeyword = com.parental.control.app.network.SyncHub.dynamicKeywords.firstOrNull { text.contains(it, ignoreCase = true) }
        val isCritical = matchingKeyword != null

        // Push intercepted communication to SyncHub for Parent Dashboard
        com.parental.control.app.network.SyncHub.onNotificationCaptured(packageName, title, text, isCritical)

        if (isCritical) {
            Log.w(tag, "Critical alert flagged in communication from app: $packageName | Content: $text")
            com.parental.control.app.network.SyncHub.onAlertTriggered(
                type = "critical_message",
                keyword = matchingKeyword!!.uppercase(),
                content = "From $title ($packageName): \"$text\"",
                appSource = packageName
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
