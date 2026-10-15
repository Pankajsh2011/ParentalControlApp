const http = require('http');
const express = require('express');
const cors = require('cors');
const { WebSocketServer, WebSocket } = require('ws');
const os = require('os');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 5050;

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

// Static snapshot upload folder
const uploadDir = path.join(__dirname, 'uploads');
if (!fs.existsSync(uploadDir)) {
    fs.mkdirSync(uploadDir, { recursive: true });
}
app.use('/uploads', express.static(uploadDir));

// In-Memory Pro Child Device State
let childState = {
    isOnline: false,
    lastSeen: null,
    battery: 100,
    isCharging: false,
    deviceModel: "Android Device",
    osVersion: "Android 14",
    location: {
        lat: 28.6139,
        lng: 77.2090,
        accuracy: 10.0,
        time: new Date().toLocaleTimeString(),
        speedKmh: 0
    },
    screenTimeMinutes: 0,
    topApps: [],
    notifications: [],
    alerts: [],
    snapshots: [],
    blockedApps: [
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.instagram.android",
        "com.facebook.katana",
        "com.snapchat.android"
    ],
    monitoredKeywords: [
        "urgent", "otp", "code", "bank", "password",
        "violence", "gambling", "drugs", "threat", "bully",
        "meet me", "suicide", "secret", "cheat"
    ],
    geofence: {
        name: "Home Safe Zone",
        lat: 28.6139,
        lng: 77.2090,
        radiusMeters: 300,
        isEnabled: false
    },
    isFlashlightOn: false
};

// Queue of pending commands for Child
let pendingCommands = [];

// Active WebSocket connections
const clients = new Map(); // ws -> { role: 'parent' | 'child' }

function getLocalIpAddresses() {
    const interfaces = os.networkInterfaces();
    const addresses = [];
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal) {
                addresses.push({ name, address: iface.address });
            }
        }
    }
    return addresses;
}

function broadcastToParents(data) {
    const payload = JSON.stringify(data);
    for (const [ws, info] of clients.entries()) {
        if (info.role === 'parent' && ws.readyState === WebSocket.OPEN) {
            ws.send(payload);
        }
    }
}

function broadcastToChild(data) {
    const payload = JSON.stringify(data);
    for (const [ws, info] of clients.entries()) {
        if (info.role === 'child' && ws.readyState === WebSocket.OPEN) {
            ws.send(payload);
        }
    }
}

// ---------------- REST API ----------------

// Child Sync Endpoint (Child telemetry & updates)
app.post('/api/child/sync', (req, res) => {
    const body = req.body;
    childState.isOnline = true;
    childState.lastSeen = Date.now();

    if (body.battery !== undefined) childState.battery = body.battery;
    if (body.isCharging !== undefined) childState.isCharging = body.isCharging;
    if (body.deviceModel) childState.deviceModel = body.deviceModel;
    if (body.osVersion) childState.osVersion = body.osVersion;
    if (body.isFlashlightOn !== undefined) childState.isFlashlightOn = body.isFlashlightOn;

    if (body.location) {
        childState.location = {
            lat: body.location.lat,
            lng: body.location.lng,
            accuracy: body.location.accuracy || 10,
            speedKmh: body.location.speedKmh || 0,
            time: new Date().toLocaleTimeString()
        };
    }
    if (body.screenTimeMinutes !== undefined) childState.screenTimeMinutes = body.screenTimeMinutes;
    if (Array.isArray(body.topApps)) childState.topApps = body.topApps;

    if (body.newNotification) {
        childState.notifications.unshift(body.newNotification);
        if (childState.notifications.length > 50) childState.notifications.pop();
        broadcastToParents({ type: 'notification_alert', data: body.newNotification });
    }

    if (body.newAlert) {
        childState.alerts.unshift(body.newAlert);
        if (childState.alerts.length > 50) childState.alerts.pop();
        broadcastToParents({ type: 'content_alert', data: body.newAlert });
    }

    // Broadcast updated state to all connected parent devices & web dashboard
    broadcastToParents({ type: 'state_update', data: childState });

    // Return pending commands and latest safety configs for Child
    const commandsToSend = [...pendingCommands];
    pendingCommands = [];
    res.json({
        success: true,
        commands: commandsToSend,
        blockedApps: childState.blockedApps,
        monitoredKeywords: childState.monitoredKeywords,
        geofence: childState.geofence
    });
});

// Parent status polling endpoint
app.get('/api/parent/status', (req, res) => {
    // Check if child has timed out (> 45s without ping)
    if (childState.lastSeen && Date.now() - childState.lastSeen > 45000) {
        childState.isOnline = false;
    }
    res.json({
        success: true,
        data: childState,
        connectedParents: Array.from(clients.values()).filter(c => c.role === 'parent').length,
        connectedChild: Array.from(clients.values()).filter(c => c.role === 'child').length > 0 || childState.isOnline
    });
});

// Parent sends a remote command
app.post('/api/parent/command', (req, res) => {
    const { command, param } = req.body;
    if (!command) return res.status(400).json({ error: 'Command required' });

    const cmdObj = {
        id: Date.now().toString(),
        command,
        param: param || '',
        createdAt: Date.now()
    };

    pendingCommands.push(cmdObj);
    broadcastToChild({ type: 'command', data: cmdObj });

    console.log(`[COMMAND DISPATCHED] -> ${command} (Param: ${param || 'none'})`);
    res.json({ success: true, message: `Command ${command} sent to child.` });
});

// Pro: Configure Geofence Safe Zone
app.post('/api/parent/geofence', (req, res) => {
    const { name, lat, lng, radiusMeters, isEnabled } = req.body;
    childState.geofence = {
        name: name || "Home Safe Zone",
        lat: parseFloat(lat) || childState.location.lat,
        lng: parseFloat(lng) || childState.location.lng,
        radiusMeters: parseFloat(radiusMeters) || 300,
        isEnabled: isEnabled !== undefined ? isEnabled : true
    };
    broadcastToParents({ type: 'state_update', data: childState });
    broadcastToChild({ type: 'geofence_update', data: childState.geofence });
    res.json({ success: true, geofence: childState.geofence });
});

// Pro: Toggle Blocked App
app.post('/api/parent/block-app', (req, res) => {
    const { packageName, isBlocked } = req.body;
    if (!packageName) return res.status(400).json({ error: 'packageName required' });

    if (isBlocked) {
        if (!childState.blockedApps.includes(packageName)) {
            childState.blockedApps.push(packageName);
        }
    } else {
        childState.blockedApps = childState.blockedApps.filter(p => p !== packageName);
    }

    broadcastToParents({ type: 'state_update', data: childState });
    broadcastToChild({ type: 'blocked_apps_update', data: childState.blockedApps });
    res.json({ success: true, blockedApps: childState.blockedApps });
});

// Pro: Add or Remove Keyword
app.post('/api/parent/keyword', (req, res) => {
    const { keyword, action } = req.body; // action: 'add' | 'remove'
    if (!keyword) return res.status(400).json({ error: 'keyword required' });

    const cleanKeyword = keyword.trim().toLowerCase();
    if (action === 'remove') {
        childState.monitoredKeywords = childState.monitoredKeywords.filter(k => k.toLowerCase() !== cleanKeyword);
    } else {
        if (!childState.monitoredKeywords.map(k => k.toLowerCase()).includes(cleanKeyword)) {
            childState.monitoredKeywords.push(cleanKeyword);
        }
    }

    broadcastToParents({ type: 'state_update', data: childState });
    broadcastToChild({ type: 'keywords_update', data: childState.monitoredKeywords });
    res.json({ success: true, monitoredKeywords: childState.monitoredKeywords });
});

// Snapshot Upload (Base64 JPEG/WebP)
app.post('/api/child/snapshot', (req, res) => {
    const { imageBase64, timestamp } = req.body;
    if (!imageBase64) return res.status(400).json({ error: 'imageBase64 required' });

    try {
        const filename = `snap_${Date.now()}.webp`;
        const filepath = path.join(uploadDir, filename);
        const buffer = Buffer.from(imageBase64, 'base64');
        fs.writeFileSync(filepath, buffer);

        const snapInfo = {
            id: Date.now().toString(),
            url: `/uploads/${filename}`,
            filename,
            time: new Date().toLocaleTimeString()
        };
        childState.snapshots.unshift(snapInfo);
        if (childState.snapshots.length > 20) childState.snapshots.pop();

        broadcastToParents({ type: 'new_snapshot', data: snapInfo });
        res.json({ success: true, snapshot: snapInfo });
    } catch (err) {
        console.error('Snapshot upload error:', err);
        res.status(500).json({ error: err.message });
    }
});

// ---------------- Pro Web Dashboard UI ----------------
app.get('/', (req, res) => {
    res.send(`
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Parental Control Pro Cloud Hub</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
        body { background: #090D16; color: #F1F5F9; padding: 20px; }
        header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #1E293B; padding-bottom: 16px; margin-bottom: 20px; flex-wrap: wrap; gap: 12px; }
        h1 { font-size: 22px; color: #38BDF8; font-weight: 700; display: flex; align-items: center; gap: 8px; }
        .badge { padding: 6px 14px; border-radius: 9999px; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
        .badge-online { background: rgba(16, 185, 129, 0.2); color: #10B981; border: 1px solid #10B981; }
        .badge-offline { background: rgba(239, 68, 68, 0.2); color: #EF4444; border: 1px solid #EF4444; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; margin-bottom: 20px; }
        .card { background: #131B2E; border: 1px solid #1E293B; border-radius: 14px; padding: 18px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); }
        .card h2 { font-size: 14px; margin-bottom: 12px; color: #94A3B8; text-transform: uppercase; letter-spacing: 0.05em; display: flex; align-items: center; gap: 6px; }
        .stat-val { font-size: 24px; font-weight: bold; color: #FFFFFF; }
        .stat-sub { font-size: 12px; color: #64748B; margin-top: 4px; }
        #map { height: 300px; width: 100%; border-radius: 10px; margin-top: 12px; z-index: 1; border: 1px solid #334155; }
        .btn-group { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
        button { background: #2563EB; color: white; border: none; padding: 9px 15px; border-radius: 8px; font-weight: 600; cursor: pointer; transition: 0.2s; font-size: 12px; display: inline-flex; align-items: center; gap: 6px; }
        button:hover { background: #1D4ED8; }
        button.danger { background: #DC2626; }
        button.danger:hover { background: #B91C1C; }
        button.warning { background: #D97706; }
        button.warning:hover { background: #B45309; }
        button.secondary { background: #334155; color: #E2E8F0; }
        button.secondary:hover { background: #475569; }
        .log-box { max-height: 220px; overflow-y: auto; background: #0B1120; border-radius: 8px; padding: 10px; font-size: 12px; color: #CBD5E1; border: 1px solid #1E293B; }
        .log-item { padding: 8px 0; border-bottom: 1px solid #1E293B; }
        .log-item:last-child { border-bottom: none; }
        .tag-keyword { background: #EF4444; color: white; padding: 2px 6px; border-radius: 4px; font-weight: bold; font-size: 10px; }
        .tag-pill { background: #1E293B; color: #94A3B8; border: 1px solid #334155; padding: 3px 8px; border-radius: 6px; font-size: 11px; display: inline-flex; align-items: center; gap: 4px; margin: 3px; }
        .tag-pill.active { background: rgba(239, 68, 68, 0.2); border-color: #EF4444; color: #EF4444; }
        .input-inline { background: #0B1120; border: 1px solid #334155; color: #FFFFFF; padding: 7px 10px; border-radius: 6px; font-size: 12px; }
    </style>
</head>
<body>
    <header>
        <div>
            <h1>🛡️ Parental Control Pro Hub</h1>
            <p style="color: #94A3B8; font-size: 12px; margin-top: 4px;">Real-Time 6-Module Monitoring &amp; Child Protection System</p>
        </div>
        <div style="display: flex; align-items: center; gap: 12px;">
            <div id="deviceInfo" style="font-size: 12px; color: #64748B;">Device: Connecting...</div>
            <span id="childBadge" class="badge badge-offline">● Child Device Offline</span>
        </div>
    </header>

    <div class="grid">
        <!-- Live GPS Map Card -->
        <div class="card" style="grid-column: span 2;">
            <h2>📍 Live GPS Tracking &amp; Geofencing Safe Zone</h2>
            <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px;">
                <div>
                    <div id="gpsCoords" class="stat-val" style="font-size: 18px;">Latitude: --, Longitude: --</div>
                    <div id="gpsTime" class="stat-sub">Speed: 0 km/h | Accuracy: --m | Time: --</div>
                </div>
                <div class="btn-group" style="margin-top: 0;">
                    <button class="secondary" onclick="openGoogleMaps()">📍 Google Maps</button>
                    <button onclick="setSafeZoneHere()">🎯 Set Safe Zone Here</button>
                </div>
            </div>
            <div id="map"></div>
        </div>

        <!-- Telemetry & Instant Remote Actions -->
        <div class="card">
            <h2>🔋 Device Health &amp; Screen Time</h2>
            <div style="display: flex; justify-content: space-between; margin-top: 8px;">
                <div>
                    <div id="batteryVal" class="stat-val">--%</div>
                    <div id="chargingVal" class="stat-sub">Battery Status</div>
                </div>
                <div>
                    <div id="screenTimeVal" class="stat-val">--m</div>
                    <div class="stat-sub">Today's Screen Time</div>
                </div>
            </div>

            <h2 style="margin-top: 20px;">⚡ Pro Remote Control Actions</h2>
            <div class="btn-group">
                <button class="warning" onclick="sendCommand('RING_ALARM')">🔔 Ring Siren</button>
                <button class="secondary" onclick="sendCommand('STOP_ALARM')">🛑 Stop Alarm</button>
                <button class="danger" onclick="sendCommand('LOCK_DEVICE')">🔒 Lock Phone</button>
                <button onclick="sendCommand('TAKE_SNAPSHOT')">📸 Screen Snap</button>
                <button class="secondary" onclick="sendCommand('TOGGLE_FLASHLIGHT')">🔦 Flashlight</button>
            </div>
        </div>
    </div>

    <div class="grid">
        <!-- Feature 6: Dynamic App Blocker -->
        <div class="card">
            <h2>🚫 Restricted App Blocker (Feature 6)</h2>
            <p style="font-size: 12px; color: #94A3B8; margin-bottom: 10px;">Apps blocked on child's phone in real-time:</p>
            <div id="blockedAppsContainer" style="margin-bottom: 12px;"></div>
            <div style="display: flex; gap: 8px;">
                <input id="inputAppPackage" class="input-inline" style="flex: 1;" placeholder="e.g. com.google.android.youtube" />
                <button onclick="addBlockedApp()">+ Block App</button>
            </div>
        </div>

        <!-- Feature 5: Monitored Keywords -->
        <div class="card">
            <h2>⚠️ Monitored Safety Keywords (Feature 5)</h2>
            <p style="font-size: 12px; color: #94A3B8; margin-bottom: 10px;">Alerts triggered when typed or viewed on screen:</p>
            <div id="keywordsContainer" style="margin-bottom: 12px;"></div>
            <div style="display: flex; gap: 8px;">
                <input id="inputKeyword" class="input-inline" style="flex: 1;" placeholder="e.g. cheat, weapons, drugs" />
                <button onclick="addKeyword()">+ Add Keyword</button>
            </div>
        </div>
    </div>

    <div class="grid">
        <!-- Feature 3: Communication Monitor -->
        <div class="card">
            <h2>💬 Communication &amp; SMS Monitor (Feature 3)</h2>
            <div id="notifList" class="log-box">
                <div class="log-item" style="color: #64748B;">No intercepted messages yet...</div>
            </div>
        </div>

        <!-- Flagged AI Alerts -->
        <div class="card">
            <h2>🚨 Real-Time Security Alerts</h2>
            <div id="alertList" class="log-box">
                <div class="log-item" style="color: #64748B;">No flagged activity detected yet...</div>
            </div>
        </div>
    </div>

    <script>
        let map = L.map('map').setView([28.6139, 77.2090], 14);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
        let marker = L.marker([28.6139, 77.2090]).addTo(map).bindPopup("Child Device Location");
        let geofenceCircle = null;

        let currentLat = 28.6139, currentLng = 77.2090;

        function updateUI(data) {
            const badge = document.getElementById('childBadge');
            if (data.isOnline) {
                badge.className = 'badge badge-online';
                badge.innerHTML = '● Child Device Online';
            } else {
                badge.className = 'badge badge-offline';
                badge.innerHTML = '● Child Device Offline';
            }

            if (data.deviceModel) {
                document.getElementById('deviceInfo').innerText = data.deviceModel + ' | ' + (data.osVersion || 'Android');
            }

            if (data.location && data.location.lat) {
                currentLat = data.location.lat;
                currentLng = data.location.lng;
                document.getElementById('gpsCoords').innerText = 'Lat: ' + currentLat.toFixed(5) + ' | Lng: ' + currentLng.toFixed(5);
                document.getElementById('gpsTime').innerText = 'Speed: ' + (data.location.speedKmh || 0).toFixed(0) + ' km/h | Accuracy: ' + (data.location.accuracy || 10).toFixed(0) + 'm | ' + data.location.time;
                marker.setLatLng([currentLat, currentLng]);
            }

            // Geofence Circle
            if (data.geofence && data.geofence.isEnabled) {
                if (!geofenceCircle) {
                    geofenceCircle = L.circle([data.geofence.lat, data.geofence.lng], {
                        radius: data.geofence.radiusMeters || 300,
                        color: '#10B981',
                        fillColor: '#10B981',
                        fillOpacity: 0.15
                    }).addTo(map);
                } else {
                    geofenceCircle.setLatLng([data.geofence.lat, data.geofence.lng]);
                    geofenceCircle.setRadius(data.geofence.radiusMeters || 300);
                }
            }

            document.getElementById('batteryVal').innerText = data.battery ? data.battery + '%' : '--%';
            document.getElementById('chargingVal').innerText = data.isCharging ? 'Charging ⚡' : 'Discharging';

            const hrs = Math.floor(data.screenTimeMinutes / 60);
            const mins = data.screenTimeMinutes % 60;
            document.getElementById('screenTimeVal').innerText = hrs > 0 ? hrs + 'h ' + mins + 'm' : mins + 'm';

            // Blocked Apps
            if (data.blockedApps) {
                document.getElementById('blockedAppsContainer').innerHTML = data.blockedApps.map(p => \`
                    <span class="tag-pill active">
                        🚫 \${p.split('.').pop()}
                        <a href="javascript:removeBlockedApp('\${p}')" style="color: #EF4444; font-weight: bold; margin-left: 4px; text-decoration: none;">&times;</a>
                    </span>
                \`).join('');
            }

            // Monitored Keywords
            if (data.monitoredKeywords) {
                document.getElementById('keywordsContainer').innerHTML = data.monitoredKeywords.map(k => \`
                    <span class="tag-pill">
                        \${k}
                        <a href="javascript:removeKeyword('\${k}')" style="color: #94A3B8; font-weight: bold; margin-left: 4px; text-decoration: none;">&times;</a>
                    </span>
                \`).join('');
            }

            // Notifications
            if (data.notifications && data.notifications.length > 0) {
                const html = data.notifications.map(n => \`
                    <div class="log-item">
                        <strong style="color: #38BDF8;">[\${n.app}] \${n.title}</strong>: \${n.text}
                    </div>
                \`).join('');
                document.getElementById('notifList').innerHTML = html;
            }

            // Security Alerts
            if (data.alerts && data.alerts.length > 0) {
                const html = data.alerts.map(a => \`
                    <div class="log-item">
                        <span class="tag-keyword">\${a.keyword || 'ALERT'}</span> \${a.content}
                    </div>
                \`).join('');
                document.getElementById('alertList').innerHTML = html;
            }
        }

        function openGoogleMaps() {
            window.open('https://maps.google.com/?q=' + currentLat + ',' + currentLng, '_blank');
        }

        async function setSafeZoneHere() {
            try {
                await fetch('/api/parent/geofence', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: 'Safe Zone', lat: currentLat, lng: currentLng, radiusMeters: 300, isEnabled: true })
                });
                alert('Safe Zone set at current child position (Radius: 300m)!');
            } catch(e) {
                alert('Error: ' + e.message);
            }
        }

        async function sendCommand(cmd) {
            try {
                const res = await fetch('/api/parent/command', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ command: cmd })
                });
                const data = await res.json();
                alert(data.message || 'Command dispatched!');
            } catch(e) {
                alert('Error: ' + e.message);
            }
        }

        async function addBlockedApp() {
            const input = document.getElementById('inputAppPackage');
            const pkg = input.value.trim();
            if (!pkg) return;
            await fetch('/api/parent/block-app', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ packageName: pkg, isBlocked: true })
            });
            input.value = '';
        }

        async function removeBlockedApp(pkg) {
            await fetch('/api/parent/block-app', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ packageName: pkg, isBlocked: false })
            });
        }

        async function addKeyword() {
            const input = document.getElementById('inputKeyword');
            const kw = input.value.trim();
            if (!kw) return;
            await fetch('/api/parent/keyword', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ keyword: kw, action: 'add' })
            });
            input.value = '';
        }

        async function removeKeyword(kw) {
            await fetch('/api/parent/keyword', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ keyword: kw, action: 'remove' })
            });
        }

        // Live WebSocket
        const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = new WebSocket(proto + '//' + window.location.host + '/ws?role=parent');
        ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'state_update') updateUI(msg.data);
            } catch(e) {}
        };

        // Polling fallback every 3s
        setInterval(async () => {
            try {
                const res = await fetch('/api/parent/status');
                const json = await res.json();
                if (json.success && json.data) updateUI(json.data);
            } catch(e) {}
        }, 3000);
    </script>
</body>
</html>
    `);
});

// ---------------- WebSocket Server ----------------
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const role = url.searchParams.get('role') || 'parent';
    clients.set(ws, { role });

    console.log(`[WS CONNECTED] Client connected as role: ${role}`);

    if (role === 'parent') {
        ws.send(JSON.stringify({ type: 'state_update', data: childState }));
    } else if (role === 'child') {
        childState.isOnline = true;
        childState.lastSeen = Date.now();
        broadcastToParents({ type: 'state_update', data: childState });
    }

    ws.on('message', (message) => {
        try {
            const parsed = JSON.parse(message);
            if (role === 'child') {
                childState.isOnline = true;
                childState.lastSeen = Date.now();

                if (parsed.type === 'telemetry') {
                    Object.assign(childState, parsed.data);
                    broadcastToParents({ type: 'state_update', data: childState });
                } else if (parsed.type === 'webrtc_answer' || parsed.type === 'webrtc_ice') {
                    broadcastToParents(parsed);
                }
            } else if (role === 'parent') {
                if (parsed.type === 'command') {
                    broadcastToChild(parsed);
                } else if (parsed.type === 'webrtc_offer' || parsed.type === 'webrtc_ice') {
                    broadcastToChild(parsed);
                }
            }
        } catch (err) {
            console.error('Error parsing WS message:', err);
        }
    });

    ws.on('close', () => {
        clients.delete(ws);
        console.log(`[WS DISCONNECTED] ${role} disconnected.`);
        if (role === 'child') {
            const hasOtherChild = Array.from(clients.values()).some(c => c.role === 'child');
            if (!hasOtherChild) {
                childState.isOnline = false;
                broadcastToParents({ type: 'state_update', data: childState });
            }
        }
    });
});

server.listen(PORT, '0.0.0.0', () => {
    console.log('\n======================================================');
    console.log('🚀 PARENTAL CONTROL PRO RELAY & SIGNALING SERVER RUNNING');
    console.log('======================================================');
    console.log(`Port: ${PORT}`);
    const ips = getLocalIpAddresses();
    ips.forEach(ip => {
        console.log(`👉 http://${ip.address}:${PORT}  (${ip.name})`);
    });
    console.log('======================================================\n');
});
