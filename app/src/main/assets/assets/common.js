// ============================================================
// ⚙️ 全局配置
// ============================================================
var API_BASE = '';

// ============================================================
// 💾 本地存储
// ============================================================
function saveData(key, value) {
    if (window.AndroidBridge && window.AndroidBridge.saveSecure) {
        window.AndroidBridge.saveSecure(key, value);
    } else {
        localStorage.setItem('lucky_' + key, value);
    }
}

function loadData(key, defaultValue) {
    if (window.AndroidBridge && window.AndroidBridge.loadSecure) {
        var val = window.AndroidBridge.loadSecure(key);
        return (val !== null && val !== '') ? val : defaultValue;
    }
    var val = localStorage.getItem('lucky_' + key);
    return val !== null ? val : defaultValue;
}

// ============================================================
// 🪟 原生风格弹窗（从各页面挪到公共）
// ============================================================
function appDialog(opts) {
    return new Promise(function(resolve) {
        var mask = document.createElement('div');
        mask.className = 'app-dialog-mask';
        var btnsHtml = '';
        opts.buttons.forEach(function(b, i) {
            btnsHtml += '<button class="app-dialog-btn ' + (b.type || 'secondary') + '" data-i="' + i + '">' + b.text + '</button>';
        });
        mask.innerHTML =
            '<div class="app-dialog-card">' +
                '<div class="app-dialog-title">' + (opts.title || '提示') + '</div>' +
                '<div class="app-dialog-msg">' + opts.message + '</div>' +
                '<div class="app-dialog-btns">' + btnsHtml + '</div>' +
            '</div>';
        document.body.appendChild(mask);
        requestAnimationFrame(function() { mask.classList.add('show'); });

        function close(val) {
            mask.classList.remove('show');
            setTimeout(function() { mask.remove(); }, 220);
            resolve(val);
        }
        mask.querySelectorAll('.app-dialog-btn').forEach(function(btn) {
            btn.addEventListener('click', function() {
                var idx = parseInt(btn.getAttribute('data-i'), 10);
                close(opts.buttons[idx].value);
            });
        });
    });
}

function appAlert(message, opts) {
    opts = opts || {};
    return appDialog({
        title: opts.title || '提示',
        message: message,
        buttons: [{ text: opts.okText || '知道了', type: 'primary', value: true }]
    });
}

function appConfirm(message, opts) {
    opts = opts || {};
    return appDialog({
        title: opts.title || '请确认',
        message: message,
        buttons: [
            { text: opts.cancelText || '取消', type: 'secondary', value: false },
            { text: opts.okText || '确定', type: opts.danger ? 'danger' : 'primary', value: true }
        ]
    });
}

// 注入弹窗样式（如果页面没有自己定义）
(function injectDialogStyle() {
    if (document.getElementById('global-dialog-style')) return;
    var style = document.createElement('style');
    style.id = 'global-dialog-style';
    style.textContent = `
        .app-dialog-mask { position: fixed; inset: 0; background: rgba(8,15,25,0.5); z-index: 3000; display: flex; align-items: center; justify-content: center; padding: 30px; opacity: 0; transition: opacity .2s ease; }
        .app-dialog-mask.show { opacity: 1; }
        .app-dialog-card { background: #fff; border-radius: 18px; width: 100%; max-width: 320px; padding: 26px 22px 18px; text-align: center; box-shadow: 0 20px 50px rgba(0,0,0,0.3); transform: translateY(20px) scale(.94); opacity: 0; transition: transform .28s cubic-bezier(.34,1.4,.64,1), opacity .22s ease; }
        .app-dialog-mask.show .app-dialog-card { transform: translateY(0) scale(1); opacity: 1; }
        .app-dialog-title { font-size: 16px; font-weight: 700; color: #1a2b3c; margin-bottom: 8px; }
        .app-dialog-msg { font-size: 13.5px; color: #67798c; line-height: 1.6; margin-bottom: 20px; white-space: pre-line; word-break: break-word; }
        .app-dialog-btns { display: flex; gap: 10px; }
        .app-dialog-btn { flex: 1; border: none; border-radius: 10px; padding: 12px 10px; font-size: 14.5px; font-weight: 600; cursor: pointer; min-height: 46px; transition: transform .12s; }
        .app-dialog-btn:active { transform: scale(.95); }
        .app-dialog-btn.primary { background: #3b82f6; color: #fff; }
        .app-dialog-btn.danger { background: #ef4444; color: #fff; }
        .app-dialog-btn.secondary { background: #eef2f6; color: #334455; }
    `;
    document.head.appendChild(style);
})();

// ============================================================
// 🔗 官方 API 调用（通过 Android 原生层）
// ============================================================
var _apiCallbackId = 0;
var _apiCallbacks = {};
var _tokenExpiredNotified = false;

function callApi(path, method, body) {
    return new Promise(function(resolve, reject) {
        var id = ++_apiCallbackId;
        _apiCallbacks[id] = resolve;
        window._apiCallback = function(cid, data) {
            if (_apiCallbacks[cid]) {
                var parsed;
                try { parsed = JSON.parse(data); } catch(e) { parsed = data; }

                // 【新增】全局拦截 401：Token 过期
                if (parsed && parsed.code === '401' && !_tokenExpiredNotified) {
                    _tokenExpiredNotified = true;
                    setTimeout(function() { _tokenExpiredNotified = false; }, 5000);
                    handleTokenExpired();
                }

                _apiCallbacks[cid](parsed);
                delete _apiCallbacks[cid];
            }
        };
        if (window.AndroidBridge && window.AndroidBridge.apiRequest) {
            window.AndroidBridge.apiRequest(path, method || 'GET', body ? JSON.stringify(body) : '', id);
        } else {
            reject(new Error('AndroidBridge not available'));
        }
    });
}

// 【新增】登录过期处理
function handleTokenExpired() {
    // 1. 停止所有循环
    try {
        if (window.AndroidBridge && window.AndroidBridge.stopAutoLoop) window.AndroidBridge.stopAutoLoop();
        if (window.AndroidBridge && window.AndroidBridge.stopLockLoop) window.AndroidBridge.stopLockLoop();
    } catch(e) {}

    // 2. 清空本地 token
    saveData('token', '');

    // 3. 弹窗提示 + 跳转
    appAlert('登录已过期，请重新登录', { title: '登录失效' }).then(function() {
        navigateTo('config.html');
    });
}

// ============================================================
// 🎵 播放提示音
// ============================================================
function playHitSound() {
    if (window.AndroidBridge && window.AndroidBridge.playHitSound) {
        window.AndroidBridge.playHitSound();
    }
}

// ============================================================
// 📳 震动
// ============================================================
function vibrate(duration) {
    if (window.AndroidBridge && window.AndroidBridge.vibrate) {
        window.AndroidBridge.vibrate(duration || 200);
    }
}

// ============================================================
// 📋 日志系统（持久化版）
// ============================================================
var LOG_STORAGE_KEY = 'lucky_log_entries';
var LOG_MAX_ENTRIES = 300;

function saveLogEntryToStorage(html, cls) {
    try {
        var arr = JSON.parse(localStorage.getItem(LOG_STORAGE_KEY) || '[]');
        arr.push({ html: html, cls: cls, ts: Date.now() });
        if (arr.length > LOG_MAX_ENTRIES) {
            arr = arr.slice(arr.length - LOG_MAX_ENTRIES);
        }
        localStorage.setItem(LOG_STORAGE_KEY, JSON.stringify(arr));
    } catch (e) {}
}

function restoreLogFromStorage() {
    try {
        var logBox = document.getElementById('logBox');
        if (!logBox) return;
        var arr = JSON.parse(localStorage.getItem(LOG_STORAGE_KEY) || '[]');
        if (!arr.length) return;
        for (var i = 0; i < arr.length; i++) {
            var item = arr[i];
            var entry = document.createElement('div');
            entry.className = 'log-entry ' + (item.cls || '');
            entry.innerHTML = item.html;
            logBox.appendChild(entry);
        }
        logBox.scrollTop = logBox.scrollHeight;
    } catch (e) {}
}

function clearLogStorage() {
    try {
        localStorage.removeItem(LOG_STORAGE_KEY);
    } catch (e) {}
}

function addLog(message, type) {
    var logBox = document.getElementById('logBox');
    if (!logBox) { console.log(message); return; }

    var time = new Date().toLocaleTimeString();
    var className = 'log-entry';
    if (type === 'hit') className += ' log-hit';
    else if (type === 'error') className += ' log-error';
    else if (type === 'success') className += ' log-success';
    else if (type === 'system') className += ' log-system';
    else if (type === 'warning') className += ' log-warning';

    var html = '<span class="time">[' + time + ']</span>' + message;

    var entry = document.createElement('div');
    entry.className = className;
    entry.innerHTML = html;
    logBox.appendChild(entry);
    logBox.scrollTop = logBox.scrollHeight;

    saveLogEntryToStorage(html, className);

    if (logBox.children.length > LOG_MAX_ENTRIES) {
        logBox.removeChild(logBox.firstChild);
    }
}

// ============================================================
// 🧹 工具函数
// ============================================================
function escapeHtml(text) {
    if (!text) return '';
    return String(text).replace(/[&<>"']/g, function(m) {
        if (m === '&') return '&amp;';
        if (m === '<') return '&lt;';
        if (m === '>') return '&gt;';
        if (m === '"') return '&quot;';
        if (m === "'") return '&#39;';
        return m;
    });
}

function navigateTo(path) { window.location.href = path; }

function setStatusMsg(msg, type) {
    var el = document.getElementById('configStatus');
    if (!el) return;
    el.textContent = msg;
    el.className = 'status-msg ' + type;
}

// ============================================================
// 📋 钓场列表（本地）
// ============================================================
function loadVenuesFromLocal() {
    try {
        if (window.AndroidBridge && window.AndroidBridge.loadVenues) {
            var json = window.AndroidBridge.loadVenues();
            if (json && json !== '[]') return JSON.parse(json);
        }
        var stored = loadData('venues_data', '');
        if (stored) return JSON.parse(stored);
    } catch(e) {
        console.error('加载钓场失败:', e);
    }
    return null;
}

function saveVenuesToLocal(venues) {
    var json = JSON.stringify(venues);
    if (window.AndroidBridge && window.AndroidBridge.saveVenues) {
        window.AndroidBridge.saveVenues(json);
    }
    saveData('venues_data', json);
}

// ============================================================
// 📋 座位映射表（本地）
// ============================================================
function loadSeatMapFromLocal() {
    try {
        if (window.AndroidBridge && window.AndroidBridge.loadSeatMap) {
            var json = window.AndroidBridge.loadSeatMap();
            return JSON.parse(json);
        }
        var stored = localStorage.getItem('seat_map_data');
        if (stored) return JSON.parse(stored);
    } catch(e) {
        console.error('加载 seat_map 失败:', e);
    }
    return {};
}

function saveSeatMapToLocal(seatMap) {
    try {
        if (window.AndroidBridge && window.AndroidBridge.saveSeatMap) {
            window.AndroidBridge.saveSeatMap(JSON.stringify(seatMap));
        } else {
            localStorage.setItem('seat_map_data', JSON.stringify(seatMap));
        }
    } catch(e) {
        console.error('保存 seat_map 失败:', e);
    }
}

// ============================================================
// 🔐 Token 有效性检测
// ============================================================
async function checkTokenValidity() {
    var token = loadData('token', '');
    var uuid = loadData('uuid', '');

    if (!token || !uuid) {
        return { valid: false, reason: '未配置 Token 或 UUID' };
    }

    if (window.AndroidBridge && window.AndroidBridge.setAuth) {
        window.AndroidBridge.setAuth(token, uuid);
    }

    try {
        var result = await callApi('/v2/userApi/order/getMyTicketOrderList?tab=10&page=1&limit=1', 'GET');

        if (result && result.error !== undefined && result.code === undefined) {
            return { valid: true, networkError: true, reason: result.error };
        }

        if (result.code === '000') {
            return { valid: true };
        } else if (result.code === '401') {
            return { valid: false, reason: 'Token 已过期，请重新登录' };
        } else {
            return { valid: false, reason: result.msg || 'Token 无效' };
        }
    } catch(e) {
        return { valid: true, networkError: true, reason: e.message };
    }
}
