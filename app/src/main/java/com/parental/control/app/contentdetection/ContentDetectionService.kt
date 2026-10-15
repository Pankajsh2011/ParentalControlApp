package com.parental.control.app.contentdetection

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.parental.control.app.network.SyncHub

/**
 * Feature 5: AI Content & Social Media Detection + Feature 6: Pro Dynamic App Restriction Blocker
 * Accessibility service inspecting active window content, dynamic keywords, and restricted app limits.
 */
class ContentDetectionService : AccessibilityService() {

    private val tag = "ContentDetectionService"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedToastTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            // Feature 6: Window State Changed -> Check Dynamic App Restriction
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                handleAppLaunch(packageName)
            }

            // Feature 5: View Text Changed -> Dynamic Keyword & Social Media Content Detection
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                rootInActiveWindow?.let { rootNode ->
                    scanNodeForSensitiveContent(rootNode)
                }
            }
        }
    }

    private fun handleAppLaunch(packageName: String) {
        // Exclude system UI and self package
        if (packageName == applicationContext.packageName || packageName.contains("android.launcher") || packageName == "com.android.systemui") {
            return
        }

        if (SyncHub.dynamicBlockedApps.contains(packageName)) {
            Log.w(tag, "RESTRICTED APP LAUNCH DETECTED & BLOCKED: $packageName")
            
            // Immediately navigate away from the restricted app
            performGlobalAction(GLOBAL_ACTION_HOME)

            val now = System.currentTimeMillis()
            if (now - lastBlockedToastTime > 3000) {
                lastBlockedToastTime = now
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🔒 Parental Lock: This app is restricted by your parent.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // Notify Parent Hub of blocked launch attempt
            SyncHub.onAlertTriggered(
                type = "app_blocked",
                keyword = "RESTRICTED APP",
                content = "Child attempted to launch blocked app ($packageName)",
                appSource = packageName
            )
        }
    }

    private fun scanNodeForSensitiveContent(node: AccessibilityNodeInfo) {
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank()) {
            for (keyword in SyncHub.dynamicKeywords) {
                if (nodeText.contains(keyword, ignoreCase = true)) {
                    Log.w(tag, "[AI CONTENT ALERT] Flagged Keyword '$keyword' found in content: $nodeText")
                    SyncHub.onAlertTriggered(
                        type = "ai_keyword",
                        keyword = keyword.uppercase(),
                        content = "Detected on screen: \"$nodeText\"",
                        appSource = node.packageName?.toString() ?: "Unknown App"
                    )
                    break
                }
            }
        }

        // Recursively inspect child nodes
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { childNode ->
                scanNodeForSensitiveContent(childNode)
            }
        }
    }

    override fun onInterrupt() {
        Log.e(tag, "Accessibility Service interrupted.")
    }
}
