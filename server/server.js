'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');
const QRCode = require('qrcode');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const PUBLIC_DIR = path.join(__dirname, 'public');
const ROOM = (process.env.ROOM || crypto.randomBytes(3).toString('hex')).toUpperCase();

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon'
};

function ipScore(ip) {
  if (ip.startsWith('192.168.')) return 0;
  if (ip.startsWith('10.')) return 1;
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(ip)) return 2;
  return 3;
}

function lanAddresses() {
  const skip = /(vmware|virtualbox|vethernet|wsl|docker|loopback|tailscale|zerotier|hyper-v|tap|tun|虚拟|桥接)/i;
  const list = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    if (skip.test(name)) continue;
    for (const addr of addrs || []) {
      if (addr.family !== 'IPv4' || addr.internal) continue;
      list.push({ name, address: addr.address });
    }
  }
  const seen = new Set();
  return list
    .filter(x => !seen.has(x.address) && seen.add(x.address))
    .sort((a, b) => ipScore(a.address) - ipScore(b.address));
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

function serveStatic(req, res, pathname) {
  const rel = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const file = path.resolve(PUBLIC_DIR, rel);
  if (!file.startsWith(PUBLIC_DIR + path.sep) && file !== path.join(PUBLIC_DIR, 'index.html')) {
    res.writeHead(403); res.end('Forbidden'); return;
  }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); res.end('Not found'); return; }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache'
    });
    res.end(data);
  });
}

function guidePage(castUrl, wsUrl) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>局域网连通性检查</title><style>
body{font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;margin:0;padding:28px;background:#f6f8fa;color:#1f2328;line-height:1.7}
.card{max-width:560px;margin:0 auto;background:#fff;border:1px solid #d8dee4;border-radius:12px;padding:24px}
h1{font-size:18px;margin:0 0 12px}code{display:block;background:#f0f3f6;padding:10px;border-radius:6px;word-break:break-all;font-size:12px}
p{font-size:14px;color:#57606a}</style></head><body><div class="card">
<h1>网络已连通</h1>
<p>手机和电脑在同一个局域网内。这个二维码需要用「稳投屏」App 扫描，浏览器打开只是连通性自检页。</p>
<p>连接地址：</p><code>${wsUrl}</code>
<p>页面地址：${castUrl}</p>
</div></body></html>`;
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (u.pathname === '/favicon.ico') { res.writeHead(204); res.end(); return; }

  if (u.pathname === '/api/info') {
    const addresses = lanAddresses();
    const requested = u.searchParams.get('ip');
    const ip = addresses.some(a => a.address === requested)
      ? requested
      : (addresses[0]?.address || '127.0.0.1');
    const castUrl = `http://${ip}:${PORT}/cast?room=${ROOM}`;
    const wsUrl = `ws://${ip}:${PORT}/ws?role=sender&room=${ROOM}`;
    try {
      const qr = await QRCode.toDataURL(castUrl, {
        width: 280, margin: 1, errorCorrectionLevel: 'M',
        color: { dark: '#1f2328', light: '#ffffff' }
      });
      sendJson(res, 200, { ok: true, room: ROOM, port: PORT, ip, addresses, castUrl, wsUrl, qr });
    } catch (e) {
      sendJson(res, 500, { ok: false, error: String(e) });
    }
    return;
  }

  if (u.pathname === '/cast') {
    const room = (u.searchParams.get('room') || ROOM).toUpperCase();
    const ip = lanAddresses()[0]?.address || u.hostname;
    const castUrl = `http://${ip}:${PORT}/cast?room=${room}`;
    const wsUrl = `ws://${ip}:${PORT}/ws?role=sender&room=${room}`;
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(guidePage(castUrl, wsUrl));
    return;
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); res.end(); return; }
  serveStatic(req, res, u.pathname);
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 12 * 1024 * 1024 });

function roomStats(room) {
  let senders = 0, receivers = 0;
  for (const c of wss.clients) {
    if (c.room !== room || c.readyState !== WebSocket.OPEN) continue;
    if (c.role === 'sender') senders++; else receivers++;
  }
  return { senders, receivers };
}

function broadcastStatus(room) {
  const msg = JSON.stringify({ type: 'status', room, ...roomStats(room) });
  for (const c of wss.clients) {
    if (c.room === room && c.readyState === WebSocket.OPEN) c.send(msg);
  }
}

server.on('upgrade', (req, socket, head) => {
  let u;
  try { u = new URL(req.url, 'http://localhost'); } catch { socket.destroy(); return; }
  if (u.pathname !== '/ws') { socket.destroy(); return; }
  wss.handleUpgrade(req, socket, head, ws => {
    ws.role = u.searchParams.get('role') === 'sender' ? 'sender' : 'receiver';
    ws.room = (u.searchParams.get('room') || ROOM).toUpperCase();
    ws.isAlive = true;
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', ws => {
  if (ws.role === 'sender') {
    for (const c of wss.clients) {
      if (c !== ws && c.role === 'sender' && c.room === ws.room) {
        c.close(4001, '被新的发送端替换');
      }
    }
  }
  console.log(`[连接] ${ws.role} @ ${ws.room}`);
  broadcastStatus(ws.room);

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data, isBinary) => {
    if (ws.role !== 'sender') return;
    const payload = isBinary ? data : data.toString();
    for (const c of wss.clients) {
      if (c !== ws && c.role === 'receiver' && c.room === ws.room && c.readyState === WebSocket.OPEN) {
        try { c.send(payload, { binary: isBinary }); } catch { /* 忽略单个接收端异常 */ }
      }
    }
  });

  ws.on('close', () => {
    console.log(`[断开] ${ws.role} @ ${ws.room}`);
    broadcastStatus(ws.room);
  });
  ws.on('error', () => { /* 由 close 统一处理 */ });
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch { ws.terminate(); }
  }
}, 20000);
heartbeat.unref();

server.listen(PORT, HOST, () => {
  const addresses = lanAddresses();
  console.log('');
  console.log('  手机投屏到电脑 · 接收端已启动');
  console.log('  ------------------------------------------');
  console.log(`  电脑浏览器打开： http://localhost:${PORT}`);
  if (addresses.length) {
    for (const a of addresses) console.log(`  局域网地址：     http://${a.address}:${PORT}  (${a.name})`);
  } else {
    console.log('  未探测到局域网 IPv4，请检查网络连接');
  }
  console.log(`  房间号：         ${ROOM}`);
  console.log('  ------------------------------------------');
  console.log('  按 Ctrl+C 停止');
  console.log('');
});
