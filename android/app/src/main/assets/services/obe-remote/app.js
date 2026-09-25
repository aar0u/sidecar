/**
 * OBE Remote Control Client (Sidecar BLE Web App)
 */

(function () {
  'use strict';

  // Elements
  const statusBadge = document.getElementById('btn-device-status');
  const statusText = document.getElementById('status-text');
  const deviceHint = document.getElementById('device-hint');
  const textInput = document.getElementById('text-input');
  const btnSendText = document.getElementById('btn-send-text');

  // Modal Elements
  const deviceModal = document.getElementById('device-modal');
  const btnCloseModal = document.getElementById('btn-close-modal');
  const btnScan = document.getElementById('btn-scan');
  const scanStatusText = document.getElementById('scan-status-text');
  const deviceList = document.getElementById('device-list');

  // Audio Elements
  const audioSuccess = document.getElementById('audio-success');
  const audioFail = document.getElementById('audio-fail');

  // State
  let isConnected = false;
  let currentDeviceName = '';
  let currentDeviceAddress = '';
  let discoveredDevices = new Map();

  function playHaptic() {
    try {
      if (window.navigator && window.navigator.vibrate) {
        window.navigator.vibrate(25);
      }
    } catch (_) {}
  }

  function playAudio(success = true) {
    try {
      const audio = success ? audioSuccess : audioFail;
      if (audio) {
        audio.currentTime = 0;
        audio.play().catch(() => {});
      }
    } catch (_) {}
  }

  // Check Native Bridge
  const hasNativeBridge = () => typeof window.SidecarBle !== 'undefined';

  // Key Send Handler
  function sendKey(code, keyName, holdMs = 80) {
    playHaptic();
    playAudio(true);

    if (hasNativeBridge()) {
      try {
        window.SidecarBle.sendKey(parseInt(code, 10), holdMs);
        return;
      } catch (e) {
        console.error('SidecarBle.sendKey error:', e);
      }
    }

    // HTTP Fallback
    fetch('/api/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: parseInt(code, 10), key: keyName, hold_ms: holdMs })
    }).catch(err => {
      console.warn('HTTP send fallback failed:', err);
    });
  }

  // Text Send Handler
  function sendText(text) {
    if (!text || text.trim() === '') return;
    playHaptic();
    playAudio(true);

    if (hasNativeBridge()) {
      try {
        window.SidecarBle.sendText(text);
        textInput.value = '';
        return;
      } catch (e) {
        console.error('SidecarBle.sendText error:', e);
      }
    }

    // HTTP Fallback
    fetch('/api/text', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: text })
    }).then(() => {
      textInput.value = '';
    }).catch(err => {
      console.warn('HTTP send text fallback failed:', err);
    });
  }

  // Connection & Scan Handlers
  function startScan() {
    discoveredDevices.clear();
    renderDeviceList();
    scanStatusText.textContent = '正在扫描中...';
    btnScan.disabled = true;

    if (hasNativeBridge()) {
      try {
        window.SidecarBle.startScan();
        return;
      } catch (e) {
        console.error('SidecarBle.startScan error:', e);
      }
    }

    // HTTP Fallback
    fetch('/api/scan', { method: 'POST' })
      .then(res => res.json())
      .then(data => {
        if (data.devices) {
          data.devices.forEach(d => {
            discoveredDevices.set(d.address, d);
          });
          renderDeviceList();
        }
        scanStatusText.textContent = `发现 ${discoveredDevices.size} 个设备`;
        btnScan.disabled = false;
      })
      .catch(err => {
        scanStatusText.textContent = '扫描失败: ' + err.message;
        btnScan.disabled = false;
      });
  }

  function connectDevice(address, name) {
    scanStatusText.textContent = `正在连接 ${name || address}...`;
    if (hasNativeBridge()) {
      try {
        window.SidecarBle.connect(address);
        return;
      } catch (e) {
        console.error('SidecarBle.connect error:', e);
      }
    }

    fetch('/api/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ address: address, name: name })
    }).catch(err => {
      scanStatusText.textContent = '连接失败: ' + err.message;
    });
  }

  function disconnectDevice() {
    if (hasNativeBridge()) {
      try {
        window.SidecarBle.disconnect();
      } catch (e) {
        console.error('SidecarBle.disconnect error:', e);
      }
    } else {
      fetch('/api/disconnect', { method: 'POST' }).catch(() => {});
    }
  }

  function renderDeviceList() {
    deviceList.innerHTML = '';
    if (discoveredDevices.size === 0) {
      deviceList.innerHTML = '<li class="device-item" style="justify-content:center;color:#8a8f99;">暂未发现设备</li>';
      return;
    }

    discoveredDevices.forEach(device => {
      const li = document.createElement('li');
      li.className = 'device-item';
      li.innerHTML = `
        <div class="device-item-info">
          <span class="device-name">${device.name || '大眼橙投影仪'}</span>
          <span class="device-mac">${device.address}</span>
        </div>
        <span class="device-rssi">${device.rssi ? device.rssi + ' dBm' : ''}</span>
      `;
      li.addEventListener('click', () => {
        connectDevice(device.address, device.name);
      });
      deviceList.appendChild(li);
    });
  }

  function updateStatusUI(connected, name = '', address = '') {
    isConnected = connected;
    currentDeviceName = name;
    currentDeviceAddress = address;

    if (connected) {
      statusBadge.classList.add('connected');
      statusBadge.classList.remove('disconnected');
      statusText.textContent = name || '已连接';
      deviceHint.textContent = `${name || '投影仪'} (${address})`;
      scanStatusText.textContent = '已连接到 ' + (name || address);
    } else {
      statusBadge.classList.remove('connected');
      statusBadge.classList.add('disconnected');
      statusText.textContent = '未连接';
      deviceHint.textContent = '点击右上角连接设备';
    }
  }

  // Global Bridge Callbacks (Invoked from Kotlin SidecarBle)
  window.onBleConnectionStateChange = function (connected, name, address) {
    updateStatusUI(connected, name, address);
    if (connected) {
      deviceModal.classList.remove('show');
    }
  };

  window.onBleDeviceFound = function (name, address, rssi) {
    discoveredDevices.set(address, { name: name || '大眼橙投影仪', address: address, rssi: rssi });
    renderDeviceList();
    scanStatusText.textContent = `发现 ${discoveredDevices.size} 个设备`;
  };

  window.onBleScanFinished = function () {
    btnScan.disabled = false;
    scanStatusText.textContent = `扫描完成，发现 ${discoveredDevices.size} 个设备`;
  };

  // Wire Key Buttons
  const allButtons = document.querySelectorAll('[data-code]');
  allButtons.forEach(btn => {
    const code = btn.dataset.code;
    const keyName = btn.dataset.key;

    // Pointer events for instant tactile response
    btn.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      btn.classList.add('pressed');
      sendKey(code, keyName);
    });

    const releaseHandler = () => {
      btn.classList.remove('pressed');
    };

    btn.addEventListener('pointerup', releaseHandler);
    btn.addEventListener('pointercancel', releaseHandler);
    btn.addEventListener('pointerleave', releaseHandler);
  });

  // Text Input
  btnSendText.addEventListener('click', () => {
    sendText(textInput.value);
  });

  textInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      sendText(textInput.value);
    }
  });

  // Status Badge Click -> Open Modal
  statusBadge.addEventListener('click', () => {
    if (isConnected) {
      if (confirm(`当前已连接 ${currentDeviceName || currentDeviceAddress}，是否断开？`)) {
        disconnectDevice();
      }
    } else {
      deviceModal.classList.add('show');
      startScan();
    }
  });

  btnCloseModal.addEventListener('click', () => {
    deviceModal.classList.remove('show');
    if (hasNativeBridge()) {
      try { window.SidecarBle.stopScan(); } catch (_) {}
    }
  });

  btnScan.addEventListener('click', () => {
    startScan();
  });

  // Keyboard Navigation
  window.addEventListener('keydown', (e) => {
    if (document.activeElement === textInput) return;

    let targetId = null;
    switch (e.key) {
      case 'ArrowUp': targetId = 'key-up'; break;
      case 'ArrowDown': targetId = 'key-down'; break;
      case 'ArrowLeft': targetId = 'key-left'; break;
      case 'ArrowRight': targetId = 'key-right'; break;
      case 'Enter': targetId = 'key-ok'; break;
      case 'Backspace':
      case 'Escape': targetId = 'key-back'; break;
      case 'h':
      case 'H': targetId = 'key-home'; break;
      case 'm':
      case 'M': targetId = 'key-menu'; break;
      case 'p':
      case 'P': targetId = 'key-power'; break;
      case 'f':
      case 'F': targetId = 'key-focus'; break;
      case 's':
      case 'S': targetId = 'key-settings'; break;
      case '+':
      case '=': targetId = 'key-vol-up'; break;
      case '-': targetId = 'key-vol-down'; break;
    }

    if (targetId) {
      e.preventDefault();
      const btn = document.getElementById(targetId);
      if (btn) {
        btn.classList.add('pressed');
        sendKey(btn.dataset.code, btn.dataset.key);
        setTimeout(() => btn.classList.remove('pressed'), 120);
      }
    }
  });

  // Initial Sync Status with Native Bridge or HTTP
  setTimeout(() => {
    if (hasNativeBridge()) {
      try {
        const statusJson = window.SidecarBle.getStatus();
        if (statusJson) {
          const s = JSON.parse(statusJson);
          updateStatusUI(s.connected, s.name, s.address);
        }
      } catch (e) {
        console.warn('Initial getStatus failed:', e);
      }
    }
  }, 200);

})();
