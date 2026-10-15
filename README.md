# 🛡️ ParentalControlApp

A complete, production-grade 2-device Parental Control & Child Safety System inspired by enterprise parental management solutions. Built with Android (Kotlin, WebRTC, Foreground Services, Accessibility Engine) and a lightweight Node.js Real-Time Relay & Signaling Server.

---

## 📱 Direct Download Links (APK)

Download the pre-compiled, signed APKs directly on your devices:

- 👑 **[Download Parent Device APK (v1.0.0)](https://github.com/Pankajsh2011/ParentalControlApp/releases/download/v1.0.0/1_Parent_Device.apk)**
- 🛡️ **[Download Child Device APK (v1.0.0)](https://github.com/Pankajsh2011/ParentalControlApp/releases/download/v1.0.0/2_Child_Device.apk)**

Or visit the **[GitHub Releases Page](https://github.com/Pankajsh2011/ParentalControlApp/releases/tag/v1.0.0)**.

---

## ✨ The 6 Core Modules Implemented

1. **Live Remote Monitoring & WebRTC Audio/Video (`LiveStreamManager.kt`)**:
   - P2P encrypted low-latency live audio and video streaming using Google STUN servers and WebRTC.
2. **Snapshot & Screen Capture (`SnapshotCaptureManager.kt`)**:
   - Periodic and remote on-demand screen capture with lossless WebP compression.
3. **Communication Security & SMS/Call Monitor (`NotificationAlertService.kt`)**:
   - Background notification listener intercepting incoming calls, SMS messages, and chat previews (WhatsApp, Messages) with instant keyword flagging.
4. **Live GPS Location & Geofencing (`LocationGeofenceService.kt`)**:
   - Continuous foreground GPS tracking with 1-click Google Maps integration on the parent dashboard.
5. **AI Content & Social Media Detection (`ContentDetectionService.kt`)**:
   - Real-time Accessibility Service inspecting active window content for sensitive words and inappropriate content.
6. **Screen Time & App Restriction (`ScreenTimeManager.kt`)**:
   - Computes daily foreground app usage times and enforces restricted app locks.

---

## ☁️ 24/7 Cloud Deployment (No PC Needed)

The backend relay server can be deployed online for free in under 2 minutes:

### Option A: Render (Free 750 hrs/month)
1. Go to [render.com](https://render.com) and log in with GitHub (`Pankajsh2011`).
2. Click **New +** -> **Web Service**.
3. Select this repository: `ParentalControlApp`.
4. It will automatically detect `render.yaml` and configure everything. Click **Deploy Web Service**.
5. Copy your Render URL (e.g. `https://parentalcontrolapp.onrender.com`) and paste it into the **Cloud Server URL** field in both Android apps!

### Option B: Zeabur (No Sleep / Free Tier)
1. Go to [zeabur.com](https://zeabur.com) and log in with GitHub.
2. Click **Create Project** -> **Deploy from GitHub**.
3. Select `ParentalControlApp` and deploy the `server` directory.

### Option C: Instant Local / Ngrok Tunnel
- Run `start_server.bat` on your PC for local Wi-Fi sync.
- Run `start_server_internet.bat` for instant global 4G/5G tunnel via ngrok.

---

## 🛠️ Project Structure

```
├── APKs_For_Phones/          # Pre-built release APKs
│   ├── 1_Parent_Device.apk   # Parent Control Dashboard
│   └── 2_Child_Device.apk    # Child Protection Companion
├── app/                      # Android Application (Kotlin, Gradle 8.9, AGP 8.5.2)
│   └── src/main/java/com/parental/control/app/
│       ├── communication/    # Feature 3: Notification Monitor
│       ├── contentdetection/ # Feature 5: AI Keyword & Content Filter
│       ├── location/         # Feature 4: GPS Geofencing Service
│       ├── network/          # Real-time WebSocket & Telemetry Sync Hub
│       ├── screencap/        # Feature 2: Snapshot Buffer
│       ├── screentime/       # Feature 6: App Usage Manager
│       └── webrtc/           # Feature 1: Live WebRTC Streaming
├── server/                   # Node.js WebSocket & REST Relay Server
│   ├── server.js             # HTTP, WebSocket & Leaflet Web Dashboard
│   ├── Dockerfile            # Containerized deployment config
│   └── package.json
├── render.yaml               # 1-Click Render Cloud Blueprint
├── start_server.bat          # 1-Click Local Runner (Port 5050)
└── start_server_internet.bat # 1-Click Ngrok Tunnel Runner
```
