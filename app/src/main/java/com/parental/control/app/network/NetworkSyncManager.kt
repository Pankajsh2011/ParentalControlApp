package com.parental.control.app.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkSyncManager(private val context: Context) {

    private val tag = "NetworkSyncManager"
    private val prefs = context.getSharedPreferences("parental_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    var onCommandReceived: ((CommandItem) -> Unit)? = null
    var onStateUpdated: ((ChildState) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    private var activeWebSocket: WebSocket? = null
    private var isWebSocketConnected = false

    fun getServerUrl(): String {
        return prefs.getString("server_url", "http://192.168.1.41:5050") ?: "http://192.168.1.41:5050"
    }

    fun setServerUrl(url: String) {
        var cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }
        if (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }
        prefs.edit().putString("server_url", cleanUrl).apply()
        closeWebSocket()
    }

    // ---------------- CHILD SYNC (HTTP POST) ----------------

    fun syncChildState(
        battery: Int,
        isCharging: Boolean,
        deviceModel: String = "Android Device",
        osVersion: String = "Android 14",
        isFlashlightOn: Boolean = false,
        location: LocationData?,
        screenTimeMinutes: Long,
        topApps: List<AppUsageEntry>,
        newNotification: NotificationEntry? = null,
        newAlert: AlertEntry? = null,
        callback: ((Boolean, List<CommandItem>) -> Unit)? = null
    ) {
        val payload = mutableMapOf<String, Any>(
            "battery" to battery,
            "isCharging" to isCharging,
            "deviceModel" to deviceModel,
            "osVersion" to osVersion,
            "isFlashlightOn" to isFlashlightOn,
            "screenTimeMinutes" to screenTimeMinutes,
            "topApps" to topApps
        )
        if (location != null) payload["location"] = location
        if (newNotification != null) payload["newNotification"] = newNotification
        if (newAlert != null) payload["newAlert"] = newAlert

        val json = gson.toJson(payload)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "${getServerUrl()}/api/child/sync"

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(tag, "Child sync failed: ${e.message}")
                mainHandler.post {
                    onConnectionChanged?.invoke(false)
                    callback?.invoke(false, emptyList())
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val respBody = response.body?.string() ?: ""
                        try {
                            val parsed = gson.fromJson(respBody, ChildSyncResponse::class.java)
                            val commands = parsed.commands ?: emptyList()

                            // Update dynamic settings from Server
                            parsed.blockedApps?.let {
                                SyncHub.dynamicBlockedApps = it.toMutableSet()
                            }
                            parsed.monitoredKeywords?.let {
                                SyncHub.dynamicKeywords = it.toMutableList()
                            }
                            parsed.geofence?.let {
                                SyncHub.activeGeofence = it
                            }

                            mainHandler.post {
                                onConnectionChanged?.invoke(true)
                                callback?.invoke(true, commands)
                                commands.forEach { cmd ->
                                    onCommandReceived?.invoke(cmd)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Error parsing sync response: ${e.message}")
                        }
                    } else {
                        mainHandler.post {
                            onConnectionChanged?.invoke(false)
                            callback?.invoke(false, emptyList())
                        }
                    }
                }
            }
        })
    }

    // ---------------- PARENT GET STATUS ----------------

    fun fetchChildStatus(callback: (Boolean, ChildState?) -> Unit) {
        val url = "${getServerUrl()}/api/parent/status"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    onConnectionChanged?.invoke(false)
                    callback(false, null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        try {
                            val parsed = gson.fromJson(body, ParentStatusResponse::class.java)
                            mainHandler.post {
                                onConnectionChanged?.invoke(true)
                                callback(true, parsed.data)
                            }
                        } catch (e: Exception) {
                            mainHandler.post { callback(false, null) }
                        }
                    } else {
                        mainHandler.post {
                            onConnectionChanged?.invoke(false)
                            callback(false, null)
                        }
                    }
                }
            }
        })
    }

    // ---------------- PARENT DISPATCH COMMAND ----------------

    fun sendParentCommand(command: String, param: String = "", callback: (Boolean, String) -> Unit) {
        val payload = mapOf("command" to command, "param" to param)
        val json = gson.toJson(payload)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "${getServerUrl()}/api/parent/command"

        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, e.message ?: "Network error") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    mainHandler.post {
                        if (response.isSuccessful) {
                            callback(true, "Command $command dispatched successfully")
                        } else {
                            callback(false, "Server error: ${response.code}")
                        }
                    }
                }
            }
        })
    }

    // ---------------- PRO GEOFENCE CONFIG ----------------

    fun setGeofence(geofence: GeofenceZone, callback: (Boolean) -> Unit) {
        val json = gson.toJson(geofence)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "${getServerUrl()}/api/parent/geofence"

        client.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                mainHandler.post { callback(response.isSuccessful) }
            }
        })
    }

    // ---------------- PRO APP BLOCKER TOGGLE ----------------

    fun toggleBlockApp(packageName: String, isBlocked: Boolean, callback: (Boolean) -> Unit) {
        val payload = mapOf("packageName" to packageName, "isBlocked" to isBlocked)
        val json = gson.toJson(payload)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "${getServerUrl()}/api/parent/block-app"

        client.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                mainHandler.post { callback(response.isSuccessful) }
            }
        })
    }

    // ---------------- PRO KEYWORD MANAGEMENT ----------------

    fun manageKeyword(keyword: String, action: String, callback: (Boolean) -> Unit) {
        val payload = mapOf("keyword" to keyword, "action" to action)
        val json = gson.toJson(payload)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "${getServerUrl()}/api/parent/keyword"

        client.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                mainHandler.post { callback(response.isSuccessful) }
            }
        })
    }

    // ---------------- SNAPSHOT UPLOAD ----------------

    fun uploadSnapshot(file: File, callback: (Boolean, String?) -> Unit) {
        try {
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val payload = mapOf("imageBase64" to base64, "timestamp" to System.currentTimeMillis())
            val json = gson.toJson(payload)
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val url = "${getServerUrl()}/api/child/snapshot"

            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    mainHandler.post { callback(false, e.message) }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        mainHandler.post {
                            callback(response.isSuccessful, if (response.isSuccessful) file.name else null)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            callback(false, e.message)
        }
    }

    // ---------------- LIVE WEBSOCKET ----------------

    fun connectWebSocket(role: String) {
        if (isWebSocketConnected) return

        val base = getServerUrl().replace("https://", "wss://").replace("http://", "ws://")
        val wsUrl = "$base/ws?role=$role"

        val request = Request.Builder().url(wsUrl).build()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isWebSocketConnected = true
                Log.d(tag, "WebSocket connected as role: $role")
                mainHandler.post { onConnectionChanged?.invoke(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val map = gson.fromJson(text, Map::class.java)
                    val type = map["type"] as? String
                    if (type == "command") {
                        val cmdDataJson = gson.toJson(map["data"])
                        val cmdItem = gson.fromJson(cmdDataJson, CommandItem::class.java)
                        mainHandler.post { onCommandReceived?.invoke(cmdItem) }
                    } else if (type == "state_update") {
                        val stateJson = gson.toJson(map["data"])
                        val childState = gson.fromJson(stateJson, ChildState::class.java)
                        mainHandler.post { onStateUpdated?.invoke(childState) }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing WS message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWebSocketConnected = false
                mainHandler.post { onConnectionChanged?.invoke(false) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWebSocketConnected = false
                Log.w(tag, "WebSocket connection failed: ${t.message}")
                mainHandler.post { onConnectionChanged?.invoke(false) }
            }
        })
    }

    fun closeWebSocket() {
        try {
            activeWebSocket?.close(1000, "Normal closure")
        } catch (e: Exception) {}
        activeWebSocket = null
        isWebSocketConnected = false
    }
}
