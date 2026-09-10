// ============================================================
// ⚙️ 全局配置
// ============================================================
var API_BASE = '';  // 不再需要服务器地址

// ============================================================
// 💾 本地存储（通过 Android 加密存储）
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
// 🔗 官方 API 调用（通过 Android 原生层）
// ============================================================
var _apiCallbackId = 0;
var _apiCallbacks = {};

function callApi(path, method, body) {
    return new Promise(function(resolve, reject) {
        var id = ++_apiCallbackId;
        _apiCallbacks[id] = resolve;
        window._apiCallback = function(cid, data) {
            if (_apiCallbacks[cid]) {
                try {
                    _apiCallbacks[cid](JSON.parse(data));
                } catch(e) {
                    _apiCallbacks[cid](data);
                }
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
// 📋 日志系统
// ============================================================
function addLog(message, type) {
    var logBox = document.getElementById('logBox');
    if (!logBox) { console.log(message); return; }
    var entry = document.createElement('div');
    var time = new Date().toLocaleTimeString();
    var className = 'log-entry';
    if (type === 'hit') className += ' log-hit';
    else if (type === 'error') className += ' log-error';
    else if (type === 'success') className += ' log-success';
    else if (type === 'system') className += ' log-system';
    else if (type === 'warning') className += ' log-warning';
    entry.className = className;
    entry.innerHTML = '<span class="time">[' + time + ']</span>' + message;
    logBox.appendChild(entry);
    logBox.scrollTop = logBox.scrollHeight;
    if (logBox.children.length > 200) logBox.removeChild(logBox.firstChild);
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
// 🔐 Token 有效性检测
// ============================================================
async function checkTokenValidity() {
    var token = loadData('token', '');
    var uuid = loadData('uuid', '');
    if (!token || !uuid) {
        addLog('⚠️ 未检测到 Token 或 UUID，请先配置', 'warning');
        return false;
    }
    if (window.AndroidBridge && window.AndroidBridge.setAuth) {
        window.AndroidBridge.setAuth(token, uuid);
    }
    try {
        var result = await callApi('/v2/userApi/order/getMyTicketOrderList?tab=10&page=1&limit=20', 'GET');
        if (result.code === '000') return true;
        addLog('❌ Token 已过期，请到"配置"页面重新设置', 'error');
        return false;
    } catch(e) {
        return true;  // 网络错误不阻断
    }
}

// ============================================================
// 座位映射表
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
