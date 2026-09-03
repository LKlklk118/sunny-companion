// 阳光专属陪伴 · H5 逻辑（经 AndroidBridge 与原生层通信）
(function () {
  'use strict';

  var state = {
    role: 'boy',           // boy=小七 girl=小妮
    busy: false,
    autoSpeak: true,
    config: null
  };

  var BOY = { name: '小七', avatar: '👦', tag: '男友' };
  var GIRL = { name: '小妮', avatar: '👧', tag: '闺蜜' };

  // ---------- 原生桥 ----------
  function hasBridge() {
    return typeof AndroidBridge !== 'undefined';
  }
  function nativeCall(fn) {
    try {
      if (!hasBridge()) return null;
      var args = Array.prototype.slice.call(arguments, 1);
      return AndroidBridge[fn].apply(AndroidBridge, args);
    } catch (e) { console.warn(fn, e); return null; }
  }

  // JS -> 原生注册回调（原生在事件发生时调用 window.__sunnyCallbacks.xxx）
  window.__sunnyCallbacks = {
    onDelta: function (text) { onDelta(text); },
    onDone: function (fullText) { onDone(fullText); },
    onError: function (msg) { onError(msg); },
    onTtsState: function (raw) { onTtsState(raw); },
    onVoiceResult: function (text) { onVoiceResult(text); },
    onConfigChanged: function (json) { loadConfigIntoUi(json ? JSON.parse(json) : null); }
  };

  function notify(fnName, payload) {
    try {
      if (!hasBridge()) return;
      var json = payload === undefined ? '{}' : JSON.stringify(payload);
      AndroidBridge.notify(fnName, json);
    } catch (e) { console.warn('notify fail', e); }
  }

  function $(id) { return document.getElementById(id); }

  // ---------- 聊天态 ----------
  // 收起开场 Hero、显示日期条
  function enterChat() {
    document.body.classList.add('has-chat');
    $('chatDate').classList.remove('hidden');
  }

  // ---------- 角色 ----------
  function roleInfo() { return state.role === 'girl' ? GIRL : BOY; }

  function setTheme(role) {
    var app = document.getElementById('app');
    app.classList.remove('theme-boy', 'theme-girl');
    app.classList.add(role === 'girl' ? 'theme-girl' : 'theme-boy');
  }

  function switchRole(role) {
    if (state.busy) return;
    state.role = role;
    document.querySelectorAll('.role-btn').forEach(function (b) {
      b.classList.toggle('active', b.dataset.role === role);
      if (b.dataset.role === role) b.setAttribute('aria-selected', 'true');
      else b.setAttribute('aria-selected', 'false');
    });
    document.querySelectorAll('.role-card').forEach(function (c) {
      c.classList.toggle('selected', c.dataset.role === role);
    });
    setTheme(role);
    var placeholder = $('msgInput');
    placeholder.placeholder = role === 'girl'
      ? '和小妮说说今天的事吧～'
      : '和小七聊聊今天的心情吧～';
  }

  function pickRole(role) {
    switchRole(role);
    enterChat();
    if (state.config && state.config.userName) {
      // 记住昵称时，让 TA 主动打个招呼（本地渲染，不耗接口）
      addMsg(roleInfo().avatar, roleInfo().name,
        '嗨，' + state.config.userName + '，我来啦～今天想聊点什么呀？' + (role === 'girl' ? '🌸' : '☀️'));
    }
  }

  // ---------- 消息渲染 ----------
  function addMsg(avatar, name, text, isUser) {
    enterChat();
    var area = $('messages');
    var row = document.createElement('div');
    row.className = 'row ' + (isUser ? 'user' : 'bot');
    var me = roleInfo();
    if (!isUser) {
      row.innerHTML = '<div class="avatar">' + (avatar || me.avatar) + '</div>';
    }
    var bubbleWrap = document.createElement('div');
    bubbleWrap.style.cssText = 'display:flex;flex-direction:column;align-items:' + (isUser ? 'flex-end' : 'flex-start');
    var bubble = document.createElement('div');
    bubble.className = 'bubble ' + (isUser ? 'user-bubble' : 'bot-bubble');
    bubble.textContent = text;
    bubbleWrap.appendChild(bubble);
    if (!isUser) {
      var tools = document.createElement('div');
      tools.className = 'bubble-tools';
      var ttsBtn = document.createElement('button');
      ttsBtn.className = 'tts-btn';
      ttsBtn.textContent = '🔊 朗读';
      ttsBtn.dataset.text = text;
      ttsBtn.addEventListener('click', function () {
        // 朗读最新内容：流式完成前读已生成部分，完成后读全文
        var txt = ttsBtn.dataset.text || text || '';
        if (txt.trim()) nativeCall('speak', txt, state.role);
        markPlaying(ttsBtn);
      });
      tools.appendChild(ttsBtn);
      bubbleWrap.appendChild(tools);
    }
    row.appendChild(bubbleWrap);
    area.appendChild(row);
    scrollBottom();
    return bubbleWrap;
  }

  function markPlaying(btn) {
    if (!btn) return;
    btn.classList.add('playing');
    btn.textContent = '⏹ 停止';
    var stop = function () {
      btn.classList.remove('playing');
      btn.textContent = '🔊 朗读';
    };
    btn.onclick = function () {
      nativeCall('stopSpeak');
      stop();
    };
    window.__stopPlaying = stop;
  }

  function scrollBottom() {
    var area = $('chatArea');
    area.scrollTop = area.scrollHeight;
  }

  // ---------- 对话 ----------
  function sendMessage(text) {
    var input = $('msgInput');
    var msg = (text !== undefined && text !== null) ? String(text) : input.value.trim();
    if (!msg || state.busy) return;
    if (text === undefined) input.value = '';
    addMsg(null, '我', msg, true);
    setTyping(true);
    notify('chat', { query: msg, companion: state.role });
    state.busy = true;
  }

  function setTyping(on) {
    $('typing').classList.toggle('hidden', !on);
    if (on) scrollBottom();
  }

  function onDelta(text) {
    if (!window.__acc) window.__acc = { raw: '', row: null };
    window.__acc.raw = (window.__acc.raw || '') + text;
    var me = roleInfo();
    if (!window.__acc.row) {
      window.__acc.row = addMsg(me.avatar, me.name, '', false);
    }
    var bubble = window.__acc.row.querySelector('.bubble');
    bubble.textContent = window.__acc.raw;
    // 同步朗读按钮的文本（流式中点朗读读已生成部分）
    var tts = window.__acc.row.querySelector('.tts-btn');
    if (tts) tts.dataset.text = window.__acc.raw;
    scrollBottom();
  }

  function onDone(fullText) {
    var finalText = (fullText || '').trim()
      || ((window.__acc && window.__acc.raw) || '').trim();
    // 完成时确保朗读按钮持有完整文本
    if (window.__acc && window.__acc.row) {
      var tts = window.__acc.row.querySelector('.tts-btn');
      if (tts && finalText) tts.dataset.text = finalText;
    }
    window.__acc = null;
    setTyping(false);
    state.busy = false;
    if (state.autoSpeak && finalText) {
      nativeCall('speak', finalText, state.role);
    }
  }

  function onError(msg) {
    window.__acc = null;
    setTyping(false);
    state.busy = false;
    addMsg('⚠️', '提示', (msg || '出错了，请检查网络或右上角设置') + '（⚙️ 可重新配置）', false);
  }

  // ---------- TTS / 语音输入状态 ----------
  function onTtsState(raw) {
    // 原生以 "state|message" 单字符串回传
    var i = typeof raw === 'string' ? raw.indexOf('|') : -1;
    var st = i < 0 ? raw : raw.slice(0, i);
    var msg = i < 0 ? '' : raw.slice(i + 1);
    if (window.__stopPlaying && (st === 'done' || st === 'error' || st === 'stopped')) {
      window.__stopPlaying();
    }
    if (st === 'error' && msg) {
      addMsg('⚠️', '提示', msg, false);
    }
  }

  function onVoiceResult(text) {
    if (text) $('msgInput').value = text;
  }

  // ---------- 设置 ----------
  function openSettings() {
    var cfg = nativeCall('getConfigJson');
    loadConfigIntoUi(cfg ? JSON.parse(cfg) : state.config);
    $('settingsPanel').classList.remove('hidden');
  }
  function closeSettings() {
    $('settingsPanel').classList.add('hidden');
  }

  function loadConfigIntoUi(cfg) {
    if (!cfg) return;
    state.config = cfg;
    state.autoSpeak = cfg.autoSpeak !== false;
    $('cfgEndpoint').value = cfg.difyEndpoint || '';
    $('cfgDifyKey').value = cfg.difyApiKey || '';
    $('cfgTtsProvider').value = cfg.ttsProvider || 'minimax';
    $('cfgTtsKey').value = cfg.ttsApiKey || '';
    $('cfgVoiceBoy').value = cfg.voiceBoy || '';
    $('cfgVoiceGirl').value = cfg.voiceGirl || '';
    $('cfgUserName').value = cfg.userName || '';
    $('cfgAutoSpeak').checked = cfg.autoSpeak !== false;
    $('btnSpeakToggle').textContent = state.autoSpeak ? '🔊' : '🔇';
  }

  function saveSettings() {
    var cfg = {
      difyEndpoint: $('cfgEndpoint').value.trim(),
      difyApiKey: $('cfgDifyKey').value.trim(),
      ttsProvider: $('cfgTtsProvider').value.trim() || 'minimax',
      ttsApiKey: $('cfgTtsKey').value.trim(),
      voiceBoy: $('cfgVoiceBoy').value.trim(),
      voiceGirl: $('cfgVoiceGirl').value.trim(),
      userName: $('cfgUserName').value.trim(),
      autoSpeak: $('cfgAutoSpeak').checked
    };
    state.config = cfg;
    state.autoSpeak = cfg.autoSpeak;
    notify('saveConfig', cfg);
    $('btnSpeakToggle').textContent = cfg.autoSpeak ? '🔊' : '🔇';
    closeSettings();
  }

  // ---------- 事件绑定 ----------
  function bind() {
    document.querySelectorAll('.role-btn').forEach(function (b) {
      b.addEventListener('click', function () { pickRole(b.dataset.role); });
    });
    document.querySelectorAll('.role-card').forEach(function (c) {
      c.addEventListener('click', function () { pickRole(c.dataset.role); });
    });
    document.querySelectorAll('.quick-chip').forEach(function (q) {
      q.addEventListener('click', function () {
        if (state.busy) return;
        // 若还停留在空态，先按当前选中角色收起 Hero
        enterChat();
        sendMessage(q.textContent);
      });
    });
    $('btnSend').addEventListener('click', function () { sendMessage(); });
    $('msgInput').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); sendMessage(); }
    });
    $('btnUnlock').addEventListener('click', doUnlock);
    $('lockInput').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); doUnlock(); }
    });
    $('btnSpeakToggle').addEventListener('click', function () {
      state.autoSpeak = !state.autoSpeak;
      $('btnSpeakToggle').textContent = state.autoSpeak ? '🔊' : '🔇';
      if (state.config) {
        state.config.autoSpeak = state.autoSpeak;
        notify('saveConfig', state.config);
      }
    });
    $('btnSettings').addEventListener('click', openSettings);
    $('btnSettingsClose').addEventListener('click', closeSettings);
    $('btnSaveConfig').addEventListener('click', saveSettings);
    $('btnVoice').addEventListener('click', function () {
      if (!hasBridge() || !AndroidBridge.startVoice) return;
      AndroidBridge.startVoice();
    });
    // 检查原生语音输入是否可用（不可用则隐藏麦克风）
    try {
      if (hasBridge() && AndroidBridge.voiceAvailable && AndroidBridge.voiceAvailable() === false) {
        $('btnVoice').classList.add('hidden');
      }
    } catch (e) { $('btnVoice').classList.add('hidden'); }
  }

  // ---------- 口令解锁 ----------
  function checkLock() {
    if (!hasBridge() || !AndroidBridge.getLockState) { showApp(); return; }
    var raw;
    try { raw = AndroidBridge.getLockState(); } catch (e) { showApp(); return; }
    var locked = false;
    try { locked = !!(raw && JSON.parse(raw).locked); } catch (e) { locked = false; }
    if (locked) {
      $('lockPanel').classList.remove('hidden');
      document.body.classList.add('locked');
      $('lockInput').focus();
      // 原生口令错误后保留锁页：错误提示由 unlock 处理
    } else {
      showApp();
    }
  }

  function doUnlock() {
    var code = $('lockInput').value.trim();
    if (!code) { showLockErr('请输入口令'); return; }
    if (!hasBridge() || !AndroidBridge.authorize) { showLockErr('当前环境不支持解锁'); return; }
    var raw;
    try { raw = AndroidBridge.authorize(code); } catch (e) { showLockErr('解锁失败，请重试'); return; }
    var ok = false, msg = '';
    try { var j = JSON.parse(raw); ok = !!j.ok; msg = j.msg || ''; } catch (e) {}
    if (ok) {
      // 解锁成功：清除锁态、重载配置、进入主界面
      document.body.classList.remove('locked');
      $('lockPanel').classList.add('hidden');
      $('lockInput').value = '';
      loadConfigIntoUi(JSON.parse(nativeCall('getConfigJson') || 'null') || null);
      switchRole(state.role);
      var hi = addMsg(roleInfo().avatar, roleInfo().name,
        '解锁成功，欢迎回来～ 想和小七还是小妮聊聊呀？' + (state.role === 'girl' ? '🌸' : '☀️'));
    } else {
      showLockErr(msg || '口令错误，请重新输入');
    }
  }

  function showApp() {
    document.body.classList.remove('locked');
    $('lockPanel').classList.add('hidden');
  }

  function showLockErr(msg) {
    var e = $('lockErr');
    e.textContent = msg || '口令错误，请重新输入';
    e.classList.remove('hidden');
    $('lockInput').classList.add('shake');
    setTimeout(function () { $('lockInput').classList.remove('shake'); }, 500);
  }

  // ---------- 初始化 ----------
  function init() {
    bind();
    try {
      var raw = nativeCall('getConfigJson');
      if (raw) loadConfigIntoUi(JSON.parse(raw));
    } catch (e) { /* 浏览器直开时无桥，跳过 */ }
    switchRole('boy');
    // 让 Hero 先展示（默认未进入聊天态）
    document.body.classList.remove('has-chat');
    $('chatDate').classList.add('hidden');
    checkLock();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
