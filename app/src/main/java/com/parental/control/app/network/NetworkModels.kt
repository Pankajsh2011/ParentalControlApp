package com.parental.control.app.network

data class LocationData(
    val lat: Double,
    val lng: Double,
    val accuracy: Float = 0f,
    val time: String = ""
)

data class AppUsageEntry(
    val name: String,
    val minutes: Long
)

data class NotificationEntry(
    val app: String,
    val title: String,
    val text: String,
    val time: Long
)

data class AlertEntry(
    val type: String,
    val keyword: String,
    val content: String,
    val time: Long
)

data class CommandItem(
    val id: String,
    val command: String,
    val param: String? = null,
    val createdAt: Long = 0
)

data class ChildState(
    val isOnline: Boolean = false,
    val lastSeen: Long? = null,
    val battery: Int = 100,
    val isCharging: Boolean = false,
    val location: LocationData? = null,
    val screenTimeMinutes: Long = 0,
    val topApps: List<AppUsageEntry> = emptyList(),
    val notifications: List<NotificationEntry> = emptyList(),
    val alerts: List<AlertEntry> = emptyList()
)

data class ParentStatusResponse(
    val success: Boolean,
    val data: ChildState?,
    val connectedParents: Int = 0,
    val connectedChild: Boolean = false
)

data class ChildSyncResponse(
    val success: Boolean,
    val commands: List<CommandItem>? = null
)
