package com.parental.control.app.network

data class LocationData(
    val lat: Double,
    val lng: Double,
    val accuracy: Float = 0f,
    val time: String = "",
    val speedKmh: Float = 0f
)

data class AppUsageEntry(
    val name: String,
    val packageName: String = "",
    val minutes: Long,
    val isBlocked: Boolean = false
)

data class NotificationEntry(
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val isCritical: Boolean = false
)

data class AlertEntry(
    val type: String,
    val keyword: String,
    val content: String,
    val time: Long,
    val appSource: String = ""
)

data class SnapshotItem(
    val id: String = "",
    val url: String = "",
    val filename: String = "",
    val time: String = ""
)

data class GeofenceZone(
    val name: String = "Home Safe Zone",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val radiusMeters: Float = 300f,
    val isInside: Boolean = true,
    val isEnabled: Boolean = false
)

data class CommandItem(
    val id: String = "",
    val command: String = "",
    val param: String? = null,
    val createdAt: Long = 0
)

data class ChildState(
    val isOnline: Boolean = false,
    val lastSeen: Long? = null,
    val battery: Int = 100,
    val isCharging: Boolean = false,
    val deviceModel: String = "Android Device",
    val osVersion: String = "Android 14",
    val location: LocationData? = null,
    val screenTimeMinutes: Long = 0,
    val topApps: List<AppUsageEntry> = emptyList(),
    val notifications: List<NotificationEntry> = emptyList(),
    val alerts: List<AlertEntry> = emptyList(),
    val snapshots: List<SnapshotItem> = emptyList(),
    val geofence: GeofenceZone? = null,
    val blockedApps: List<String> = emptyList(),
    val monitoredKeywords: List<String> = emptyList(),
    val isFlashlightOn: Boolean = false
)

data class ParentStatusResponse(
    val success: Boolean = false,
    val data: ChildState? = null,
    val connectedParents: Int = 0,
    val connectedChild: Boolean = false
)

data class ChildSyncResponse(
    val success: Boolean = false,
    val commands: List<CommandItem>? = null,
    val blockedApps: List<String>? = null,
    val monitoredKeywords: List<String>? = null,
    val geofence: GeofenceZone? = null
)
