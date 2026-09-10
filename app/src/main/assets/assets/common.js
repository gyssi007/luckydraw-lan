// ============================================================
// ⚙️ 全局配置
// ============================================================
var API_BASE = 'https://fishing.gysssi.com';
var sessionToken = loadData('session_token', null);

// ============================================================
// 💾 持久化存储
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
// 🎵 播放提示音
// ============================================================
function playHitSound() {
    try {
        if (window.AndroidBridge) {
            window.AndroidBridge.playHitSound();
        } else {
            var ctx = new (window.AudioContext || window.webkitAudioContext)();
            var osc = ctx.createOscillator();
            var gain = ctx.createGain();
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.frequency.value = 880;
            osc.type = 'sine';
            gain.gain.setValueAtTime(0.3, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.4);
            osc.start(ctx.currentTime);
            osc.stop(ctx.currentTime + 0.4);
        }
    } catch(e) {
        console.log('播放提示音失败:', e);
    }
}

// ============================================================
// 📋 日志系统
// ============================================================
function addLog(message, type) {
    var logBox = document.getElementById('logBox');
    if (!logBox) {
        console.log(message);
        return;
    }
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
    if (logBox.children.length > 200) {
        logBox.removeChild(logBox.firstChild);
    }
}

// ============================================================
// 🔐 请求头
// ============================================================
function getHeaders() {
    var token = loadData('token', '');
    var uuid = loadData('uuid', '');
    return {
        'Authorization': token,
        'uuid': uuid,
        'Content-Type': 'application/json'
    };
}

function getDetectiveHeaders() {
    var token = loadData('token', '');
    var uuid = loadData('uuid', '');
    return {
        'Authorization': token,
        'uuid': uuid,
        'Content-Type': 'application/json'
    };
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
    
    try {
        var resp = await fetch(API_BASE + '/api/available-orders?token=' + encodeURIComponent(token) + '&uuid=' + encodeURIComponent(uuid), {
            headers: {
                'Authorization': token,
                'uuid': uuid,
                'Content-Type': 'application/json'
            }
        });
        
        if (resp.status === 401) {
            addLog('❌ Token 已过期，请到"配置"页面重新设置', 'error');
            return false;
        }
        
        return true;
    } catch(e) {
        console.log('Token 检测失败:', e.message);
        // 网络错误时不阻断流程，让用户自行判断
        return true;
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

function navigateTo(path) {
    window.location.href = path;
}

function setStatusMsg(msg, type) {
    var el = document.getElementById('configStatus');
    if (!el) return;
    el.textContent = msg;
    el.className = 'status-msg ' + type;
}
