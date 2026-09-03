// ============================================================
// 阳光专属陪伴 · 本地预览服务器（模拟 App 内的原生能力）
//  - 托管 H5 界面
//  - 注入 demo 桥（demo-bridge.js）模拟 AndroidBridge：
//      对话 -> 转发 Dify Chatflow；朗读 -> 转发 MiniMax TTS 并播放
//  仅用于开发预览，不打包进 APK。
// 启动：node tools/preview-server.js   然后访问 http://127.0.0.1:8765
// ============================================================
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'chat');
const PORT = 8765;

// ---------- 读取本地密钥 ----------
function readSecrets() {
  const file = path.join(__dirname, '..', 'local.secrets.properties');
  const out = {};
  try {
    const txt = fs.readFileSync(file, 'utf8');
    for (const line of txt.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
      if (m) out[m[1]] = m[2].trim();
    }
  } catch (e) { console.error('读取 local.secrets.properties 失败', e.message); }
  return out;
}
const SECRETS = readSecrets();

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8'
};

// ---------- demo 桥（仅本地预览注入） ----------
const DEMO_BRIDGE = `
(function () {
  var cfg = {
    difyEndpoint: ${JSON.stringify(SECRETS.DIFY_ENDPOINT || '')},
    difyApiKey: ${JSON.stringify(SECRETS.DIFY_API_KEY || '')},
    ttsProvider: 'minimax',
    ttsApiKey: ${JSON.stringify(SECRETS.TTS_API_KEY || '')},
    voiceBoy: ${JSON.stringify(SECRETS.TTS_VOICE_BOY || 'male-qn-qingse')},
    voiceGirl: ${JSON.stringify(SECRETS.TTS_VOICE_GIRL || 'female-shaonv')},
    userName: '',
    autoSpeak: true
  };
  var conversationId = '';
  var audioEl = null;

  function jsonp(o){ return JSON.stringify(o); }

  window.AndroidBridge = {
    getConfigJson: function () { return jsonp(cfg); },
    voiceAvailable: function () { return false; }, // 桌面预览不显示麦克风

    notify: function (fn, payload) {
      if (fn === 'saveConfig') { try { cfg = JSON.parse(payload); } catch(e){} return; }
      if (fn !== 'chat') return;
      var p = JSON.parse(payload);
      var query = p.query || '';
      var companion = p.companion || 'boy';
      if (!cfg.difyApiKey) { cb('onError', '本地预览未读取到 Dify Key'); return; }
      fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: jsonp({ query: query, companion: companion, endpoint: cfg.difyEndpoint, apiKey: cfg.difyApiKey })
      }).then(function (r) {
        if (!r.ok) throw new Error('对话请求失败 ' + r.status);
        var reader = r.body.getReader();
        var decoder = new TextDecoder('utf-8');
        var buf = '';
        var full = '';
        function pump() {
          return reader.read().then(function (res) {
            if (res.done) { cb('onDone', full); return; }
            buf += decoder.decode(res.value, { stream: true });
            var lines = buf.split(/\\r?\\n/);
            buf = lines.pop();
            lines.forEach(function (line) {
              line = line.trim();
              if (line.indexOf('data:') !== 0) return;
              var data = line.slice(5).trim();
              if (!data) return;
              var j; try { j = JSON.parse(data); } catch(e){ return; }
              if (j.answer) { full += j.answer; cb('onDelta', j.answer); }
              if (j.conversation_id) conversationId = j.conversation_id;
            });
            return pump();
          });
        }
        return pump();
      }).catch(function (e) { cb('onError', e.message); });
    },

    speak: function (text, role) {
      if (!text || !String(text).trim()) { cb('onTtsState', 'done|'); return; }
      if (!cfg.ttsApiKey) { cb('onTtsState', 'error|本地预览未读取到 MiniMax Key'); return; }
      cb('onTtsState', 'started|');
      var voice = role === 'girl' ? cfg.voiceGirl : cfg.voiceBoy;
      fetch('/api/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: jsonp({ text: text, voice: voice, apiKey: cfg.ttsApiKey })
      }).then(function (r) {
        if (!r.ok) throw new Error('语音合成失败 ' + r.status);
        return r.blob();
      }).then(function (blob) {
        if (!audioEl) { audioEl = document.createElement('audio'); document.body.appendChild(audioEl); }
        audioEl.src = URL.createObjectURL(blob);
        audioEl.onended = function () { cb('onTtsState', 'done|'); };
        audioEl.onerror = function () { cb('onTtsState', 'error|播放失败'); };
        return audioEl.play();
      }).catch(function (e) { cb('onTtsState', 'error|' + e.message); });
    },

    stopSpeak: function () { if (audioEl) { audioEl.pause(); audioEl.currentTime = 0; cb('onTtsState', 'stopped|'); } }
  };

  function cb(name, arg) {
    if (window.__sunnyCallbacks && window.__sunnyCallbacks[name]) {
      window.__sunnyCallbacks[name](arg);
    }
  }
})();
`;

// ---------- HTTP 工具 ----------
function forwardTo(res, url, headers, method, body, isStream) {
  const u = new URL(url);
  const mod = u.protocol === 'https:' ? https : http;
  const req = mod.request(u, { method, headers }, (up) => {
    if (isStream) {
      res.writeHead(up.statusCode || 200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      up.pipe(res);
    } else {
      const chunks = [];
      up.on('data', c => chunks.push(c));
      up.on('end', () => {
        const buf = Buffer.concat(chunks);
        res.writeHead(up.statusCode || 200, { 'Content-Type': up.headers['content-type'] || 'application/json' });
        res.end(buf);
      });
    }
  });
  req.on('error', e => { try { res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end('proxy error: ' + e.message); } catch (_) {} });
  if (body) req.write(body);
  req.end();
}

const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);

  // 注入 demo 桥的入口页
  if (urlPath === '/') {
    const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
    const injected = html.replace('</body>', '<script>' + DEMO_BRIDGE + '</script></body>');
    res.writeHead(200, { 'Content-Type': MIME['.html'] });
    return res.end(injected);
  }

  // 静态资源
  const filePath = path.join(ROOT, urlPath === '/app.js' || urlPath === '/style.css' ? urlPath.slice(1) : urlPath);
  if (urlPath === '/app.js' || urlPath === '/style.css' || urlPath.endsWith('.js') || urlPath.endsWith('.css')) {
    if (fs.existsSync(filePath)) {
      res.writeHead(200, { 'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream' });
      return res.end(fs.readFileSync(filePath));
    }
  }

  // 对话代理
  if (urlPath === '/api/chat' && req.method === 'POST') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const p = JSON.parse(body);
        const payload = JSON.stringify({
          inputs: { companion: p.companion || 'boy' },
          query: p.query,
          response_mode: 'streaming',
          user: 'sunny-web-demo',
          auto_generate_name: false
        });
        forwardTo(res, (p.endpoint || 'https://api.dify.ai/v1') + '/chat-messages', {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + p.apiKey,
          'Accept': 'text/event-stream'
        }, 'POST', payload, true);
      } catch (e) { res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end('bad request'); }
    });
    return;
  }

  // TTS 代理：转发 MiniMax，将 hex 音频解码为 mp3 字节返回给浏览器直接播放
  if (urlPath === '/api/tts' && req.method === 'POST') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const p = JSON.parse(body);
        console.log('[tts] 请求: text=' + (p.text ? p.text.length : 0) + ' voice=' + (p.voice || '(空)') + ' apiKey=' + (p.apiKey ? p.apiKey.length : 0));
        const payload = JSON.stringify({
          model: 'speech-2.5-hd-preview',
          text: p.text,
          stream: false,
          voice_setting: { voice_id: p.voice, speed: 1.0, vol: 1.0, pitch: 0 },
          audio_setting: { sample_rate: 32000, bitrate: 128000, format: 'mp3', channel: 1 }
        });
        // 手动转发，以便把 hex 解码成音频字节
        const u = new URL('https://api.minimaxi.com/v1/t2a_v2');
        const req2 = https.request(u, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + p.apiKey
          }
        }, (up) => {
          const chunks = [];
          up.on('data', c => chunks.push(c));
          up.on('end', () => {
            try {
              const raw = Buffer.concat(chunks).toString('utf8');
              if (up.statusCode !== 200) {
                console.error('[tts] MiniMax HTTP', up.statusCode, raw.slice(0, 300));
                res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
                return res.end('MiniMax HTTP ' + up.statusCode + ': ' + raw.slice(0, 200));
              }
              const j = JSON.parse(raw);
              if (j.base_resp && j.base_resp.status_code !== 0) {
                console.error('[tts] MiniMax 业务错误', JSON.stringify(j.base_resp));
                res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
                return res.end('MiniMax: ' + (j.base_resp.status_msg || 'unknown'));
              }
              const hex = j.data && j.data.audio;
              if (!hex) {
                res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
                return res.end('MiniMax 未返回音频');
              }
              const mp3 = Buffer.from(hex, 'hex');
              res.writeHead(200, { 'Content-Type': 'audio/mpeg', 'Content-Length': mp3.length });
              res.end(mp3);
            } catch (e) {
              console.error('[tts] 解析失败', e.message);
              res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
              res.end('tts parse error: ' + e.message);
            }
          });
        });
        req2.on('error', e => { try { res.writeHead(502); res.end('proxy: ' + e.message); } catch (_) {} });
        req2.write(payload);
        req2.end();
      } catch (e) { res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end('bad request'); }
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('not found: ' + urlPath);
});

server.listen(PORT, () => {
  console.log('阳光专属陪伴 预览服务器已启动: http://127.0.0.1:' + PORT);
  console.log('  Dify Key: ' + (SECRETS.DIFY_API_KEY ? '已配置 ✓' : '未配置 ✗'));
  console.log('  MiniMax Key: ' + (SECRETS.TTS_API_KEY ? '已配置 ✓' : '未配置 ✗'));
});
