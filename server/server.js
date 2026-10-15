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
app.use(express.json({ limit: '25mb' }));
app.use(express.urlencoded({ extended: true, limit: '25mb' }));

// Static snapshot upload folder
const uploadDir = path.join(__dirname, 'uploads');
if (!fs.existsSync(uploadDir)) {
    fs.mkdirSync(uploadDir, { recursive: true });
}
app.use('/uploads', express.static(uploadDir));

// In-Memory Child Device State
let childState = {
    isOnline: false,
    lastSeen: null,
    battery: 100,
    isCharging: false,
    location: {
        lat: 28.6139,
        lng: 77.2090,
        accuracy: 10.0,
        time: new Date().toLocaleTimeString()
    },
    screenTimeMinutes: 0,
    topApps: [],
    notifications: [],
    alerts: [],
    snapshots: []
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

// Child Sync Endpoint (Child sends latest metrics)
app.post('/api/child/sync', (req, res) => {
    const body = req.body;
    childState.isOnline = true;
    childState.lastSeen = Date.now();

    if (body.battery !== undefined) childState.battery = body.battery;
    if (body.isCharging !== undefined) childState.isCharging = body.isCharging;
    if (body.location) {
        childState.location = {
            lat: body.location.lat,
            lng: body.location.lng,
            accuracy: body.location.accuracy || 10,
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

    // Return any commands waiting for Child
    const commandsToSend = [...pendingCommands];
    pendingCommands = [];
    res.json({ success: true, commands: commandsToSend });
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
    // Also push immediately over WebSocket if child is connected
    broadcastToChild({ type: 'command', data: cmdObj });

    console.log(`[COMMAND DISPATCHED] -> ${command} (Param: ${param || 'none'})`);
    res.json({ success: true, message: `Command ${command} sent to child.` });
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

// Web Dashboard UI
app.get('/', (req, res) => {
    res.send(`
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Parental Control Live Cloud Hub</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
        body { background: #0B1120; color: #F8FAFC; padding: 20px; }
        header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #1E293B; padding-bottom: 16px; margin-bottom: 20px; }
        h1 { font-size: 24px; color: #38BDF8; font-weight: 700; }
        .badge { padding: 6px 14px; border-radius: 9999px; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
        .badge-online { background: rgba(16, 185, 129, 0.2); color: #10B981; border: 1px solid #10B981; }
        .badge-offline { background: rgba(239, 68, 68, 0.2); color: #EF4444; border: 1px solid #EF4444; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; margin-bottom: 20px; }
        .card { background: #1E293B; border: 1px solid #334155; border-radius: 12px; padding: 18px; }
        .card h2 { font-size: 16px; margin-bottom: 12px; color: #94A3B8; text-transform: uppercase; letter-spacing: 0.05em; }
        .stat-val { font-size: 28px; font-weight: bold; color: #FFFFFF; }
        .stat-sub { font-size: 13px; color: #64748B; margin-top: 4px; }
        #map { height: 260px; width: 100%; border-radius: 8px; margin-top: 10px; z-index: 1; }
        .btn-group { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
        button { background: #2563EB; color: white; border: none; padding: 10px 16px; border-radius: 8px; font-weight: 600; cursor: pointer; transition: 0.2s; font-size: 13px; }
        button:hover { background: #1D4ED8; }
        button.danger { background: #DC2626; }
        button.danger:hover { background: #B91C1C; }
        button.warning { background: #D97706; }
        button.warning:hover { background: #B45309; }
        .log-box { max-height: 200px; overflow-y: auto; background: #0F172A; border-radius: 8px; padding: 10px; font-size: 12px; color: #CBD5E1; }
        .log-item { padding: 6px 0; border-bottom: 1px solid #1E293B; }
        .log-item:last-child { border-bottom: none; }
        .tag-keyword { background: #EF4444; color: white; padding: 2px 6px; border-radius: 4px; font-weight: bold; }
    </style>
</head>
<body>
    <header>
        <div>
            <h1>🛡️ Parental Control Relay Hub</h1>
            <p style="color: #94A3B8; font-size: 13px; margin-top: 4px;">Live Synchronization between Parent &amp; Child Devices</p>
        </div>
        <div>
            <span id="childBadge" class="badge badge-offline">● Child Device Offline</span>
        </div>
    </header>

    <div class="grid">
        <!-- Live GPS Map Card -->
        <div class="card" style="grid-column: span 2;">
            <h2>📍 Live GPS Tracking &amp; Geofence</h2>
            <div style="display: flex; justify-content: space-between; align-items: center;">
                <div>
                    <div id="gpsCoords" class="stat-val" style="font-size: 20px;">Latitude: --, Longitude: --</div>
                    <div id="gpsTime" class="stat-sub">Awaiting GPS signal from child phone...</div>
                </div>
                <button onclick="openGoogleMaps()">Open in Google Maps</button>
            </div>
            <div id="map"></div>
        </div>

        <!-- Telemetry & Screen Time -->
        <div class="card">
            <h2>🔋 Device Battery &amp; Screen Time</h2>
            <div style="display: flex; gap: 24px; margin-top: 10px;">
                <div>
                    <div id="batteryVal" class="stat-val">--%</div>
                    <div id="chargingVal" class="stat-sub">Battery Level</div>
                </div>
                <div>
                    <div id="screenTimeVal" class="stat-val">--m</div>
                    <div class="stat-sub">Today's Screen Time</div>
                </div>
            </div>

            <h2 style="margin-top: 20px;">⚡ Instant Remote Actions</h2>
            <div class="btn-group">
                <button class="warning" onclick="sendCommand('RING_ALARM')">🔔 Ring Loud Alarm</button>
                <button class="danger" onclick="sendCommand('LOCK_DEVICE')">🔒 Lock Child Phone</button>
                <button onclick="sendCommand('TAKE_SNAPSHOT')">📸 Request Snapshot</button>
            </div>
        </div>
    </div>

    <div class="grid">
        <!-- Notifications & Communication Monitor -->
        <div class="card">
            <h2>💬 Communication &amp; Messages (Feature 3)</h2>
            <div id="notifList" class="log-box">
                <div class="log-item" style="color: #64748B;">No intercepted messages yet...</div>
            </div>
        </div>

        <!-- AI Content & Keyword Alerts -->
        <div class="card">
            <h2>⚠️ AI Content &amp; Keyword Alerts (Feature 5)</h2>
            <div id="alertList" class="log-box">
                <div class="log-item" style="color: #64748B;">No flagged content detected yet...</div>
            </div>
        </div>
    </div>

    <script>
        let map = L.map('map').setView([28.6139, 77.2090], 13);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
        let marker = L.marker([28.6139, 77.2090]).addTo(map).bindPopup("Child Device Location");

        let currentLat = 28.6139, currentLng = 77.2090;

        function updateUI(data) {
            const badge = document.getElementById('childBadge');
            if (data.isOnline) {
                badge.className = 'badge badge-online';
                badge.innerHTML = '● Child Device Online (Live Sync)';
            } else {
                badge.className = 'badge badge-offline';
                badge.innerHTML = '● Child Device Offline';
            }

            if (data.location && data.location.lat) {
                currentLat = data.location.lat;
                currentLng = data.location.lng;
                document.getElementById('gpsCoords').innerText = 'Lat: ' + currentLat.toFixed(5) + ' | Lng: ' + currentLng.toFixed(5);
                document.getElementById('gpsTime').innerText = 'Accuracy: ' + (data.location.accuracy || 10) + 'm | Updated: ' + data.location.time;
                marker.setLatLng([currentLat, currentLng]);
                map.panTo([currentLat, currentLng]);
            }

            document.getElementById('batteryVal').innerText = data.battery ? data.battery + '%' : '--%';
            document.getElementById('chargingVal').innerText = data.isCharging ? 'Charging ⚡' : 'Discharging';

            const hrs = Math.floor(data.screenTimeMinutes / 60);
            const mins = data.screenTimeMinutes % 60;
            document.getElementById('screenTimeVal').innerText = hrs > 0 ? hrs + 'h ' + mins + 'm' : mins + 'm';

            // Notifications
            if (data.notifications && data.notifications.length > 0) {
                const html = data.notifications.map(n => \`
                    <div class="log-item">
                        <strong style="color: #38BDF8;">[\${n.app}] \${n.title}</strong>: \${n.text}
                    </div>
                \`).join('');
                document.getElementById('notifList').innerHTML = html;
            }

            // Keyword Alerts
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

        async function sendCommand(cmd) {
            try {
                const res = await fetch('/api/parent/command', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ command: cmd })
                });
                const data = await res.json();
                alert(data.message || 'Command sent!');
            } catch(e) {
                alert('Error sending command: ' + e.message);
            }
        }

        // Live WebSocket or Polling
        const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = new WebSocket(proto + '//' + window.location.host + '/ws?role=parent');
        ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'state_update') {
                    updateUI(msg.data);
                }
            } catch(e) {}
        };

        // Fallback polling every 3 seconds
        setInterval(async () => {
            try {
                const res = await fetch('/api/parent/status');
                const json = await res.json();
                if (json.success && json.data) {
                    updateUI(json.data);
                }
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

    // If parent connects, send initial state
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

                // WebRTC or Telemetry message from child
                if (parsed.type === 'telemetry') {
                    Object.assign(childState, parsed.data);
                    broadcastToParents({ type: 'state_update', data: childState });
                } else if (parsed.type === 'webrtc_answer' || parsed.type === 'webrtc_ice') {
                    broadcastToParents(parsed);
                }
            } else if (role === 'parent') {
                // Command or WebRTC offer from parent
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
    console.log('🚀 PARENTAL CONTROL RELAY & SIGNALING SERVER RUNNING');
    console.log('======================================================');
    console.log(`Port: ${PORT}`);
    console.log('Local Network Access URLs (Enter this in Child & Parent Apps):');
    const ips = getLocalIpAddresses();
    ips.forEach(ip => {
        console.log(`👉 http://${ip.address}:${PORT}  (${ip.name})`);
    });
    console.log('Local Machine Dashboard:');
    console.log(`👉 http://localhost:${PORT}`);
    console.log('======================================================\n');
});
