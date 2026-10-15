package com.parental.control.app.contentdetection

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Feature 5: AI Content & Social Media Detection + Feature 6: App Restriction Blocker
 * Accessibility service inspecting active window content, keywords, and restricted app limits.
 */
class ContentDetectionService : AccessibilityService() {

    private val tag = "ContentDetectionService"

    // Restricted apps list (Package names)
    private val blockedApps = setOf(
        "com.google.android.youtube",
        "com.zhiliaoapp.musically", // TikTok
        "com.instagram.android"
    )

    // Keywords to monitor in social apps
    private val sensitiveKeywords = listOf("violence", "gambling", "drugs", "threat", "bully")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            // Feature 6: Window State Changed -> Check App Restriction
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                handleAppLaunch(packageName)
            }

            // Feature 5: View Text Changed -> Keyword & Social Media Content Detection
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                rootInActiveWindow?.let { rootNode ->
                    scanNodeForSensitiveContent(rootNode)
                }
            }
        }
    }

    private fun handleAppLaunch(packageName: String) {
        if (blockedApps.contains(packageName)) {
            Log.w(tag, "RESTRICTED APP LAUNCH DETECTED: $packageName")
            // Launch Home or Lock overlay
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun scanNodeForSensitiveContent(node: AccessibilityNodeInfo) {
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank()) {
            for (keyword in sensitiveKeywords) {
                if (nodeText.contains(keyword, ignoreCase = true)) {
                    Log.w(tag, "[AI CONTENT ALERT] Flagged Keyword '$keyword' found in content: $nodeText")
                    com.parental.control.app.network.SyncHub.onAlertTriggered("ai_keyword", keyword, nodeText)
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
