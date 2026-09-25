/**
 * OBE Remote Control Client
 * Business logic runs here and in Go backend.
 * Hardware Bluetooth I/O relies on generic SidecarBle HAL (Kotlin).
 */

(function () {
  'use strict';

  // OBE Protocol Constants
  const OBE_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb";
  const OBE_CHAR_WRITE_UUID = "0000fff1-0000-1000-8000-00805f9b34fb";

  // Elements
  const statusBadge = document.getElementById('btn-device-status');
  const statusText = document.getElementById('status-text');
  const deviceHint = document.getElementById('device-hint');

  // Modal Elements
  const deviceModal = document.getElementById('device-modal');
  const btnCloseModal = document.getElementById('btn-close-modal');
  const btnScan = document.getElementById('btn-scan');
  const scanStatusText = document.getElementById('scan-status-text');
  const deviceList = document.getElementById('device-list');
  const currentDeviceBar = document.getElementById('current-device-bar');
  const currentDeviceText = document.getElementById('current-device-text');
  const btnDisconnect = document.getElementById('btn-disconnect');

  // State
  let isConnected = false;
  let discoveredDevices = new Map();
  let lastKnownDevice = { address: '', name: '' };
  let reconnectTimeoutId = null;
  const RECONNECT_TIMEOUT_MS = 6000;

  function playHaptic() {
    try {
      if (hasNativeBridge()) {
        window.SidecarBle.vibrate(25);
      } else if (window.navigator && window.navigator.vibrate) {
        window.navigator.vibrate(25);
      }
    } catch (_) {}
  }

  // Soft click tone via Web Audio API — no asset, no native bridge needed.
  let audioCtx = null;
  function playClick() {
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();

      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.type = 'sine';
      osc.frequency.value = 700;
      gain.gain.setValueAtTime(0.12, audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + 0.06);
      osc.connect(gain).connect(audioCtx.destination);
      osc.start();
      osc.stop(audioCtx.currentTime + 0.06);
    } catch (_) {}
  }

  // Check Generic Native Driver
  const hasNativeBridge = () => typeof window.SidecarBle !== 'undefined';

  // Best-effort silent reconnect so a button press works even if the
  // on-load auto-reconnect hasn't kicked in yet (e.g. app resumed from
  // background). No-op if already connected/connecting or no saved device.
  function ensureConnected() {
    if (!isConnected && !reconnectTimeoutId && lastKnownDevice.address) {
      reconnectLastDevice();
    }
  }

  // Key Send Handler (Generates OBE 2-byte frame: [keyCode, action])
  function sendKey(code, keyName, holdMs = 80) {
    ensureConnected();
    playHaptic();
    playClick();

    const intCode = parseInt(code, 10);
    const hexCode = intCode.toString(16).padStart(2, '0');
    const pressHex = hexCode + "00";
    const releaseHex = hexCode + "01";

    if (hasNativeBridge()) {
      try {
        // 1. Send Press Frame
        window.SidecarBle.write(OBE_SERVICE_UUID, OBE_CHAR_WRITE_UUID, pressHex);

        // 2. Send Release Frame after holdMs
        setTimeout(() => {
          window.SidecarBle.write(OBE_SERVICE_UUID, OBE_CHAR_WRITE_UUID, releaseHex);
        }, holdMs);
        return;
      } catch (e) {
        console.error('SidecarBle.write error:', e);
      }
    }

    // HTTP Fallback to Go service
    fetch('/api/frame', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: intCode, key: keyName, hold_ms: holdMs })
    }).catch(err => {
      console.warn('HTTP frame request failed:', err);
    });
  }

  // Connection & Scan Handlers
  function startScan() {
    discoveredDevices.clear();
    renderDeviceList();
    scanStatusText.textContent = '正在扫描附近的蓝牙设备...';
    btnScan.disabled = true;

    if (hasNativeBridge()) {
      try {
        // No hardware-level UUID filter: many BLE peripherals (this projector
        // included) only expose their service UUID in the scan response, which
        // Android's native ScanFilter unreliably matches on some OEM chipsets.
        // Filtering happens in software below (onDeviceFound), matching the
        // behavior of the original working implementation.
        window.SidecarBle.scan(null, 10000);
        return;
      } catch (e) {
        console.error('SidecarBle.scan error:', e);
      }
    }

    btnScan.disabled = false;
    scanStatusText.textContent = '未检测到原生蓝牙驱动';
  }

  function connectDevice(address, name) {
    scanStatusText.textContent = `正在连接 ${name || address}...`;
    deviceHint.textContent = `正在连接 ${name || address}...`;
    if (hasNativeBridge()) {
      try {
        window.SidecarBle.connect(address);
      } catch (e) {
        console.error('SidecarBle.connect error:', e);
        scanStatusText.textContent = '连接失败: ' + e.message;
      }
    }
  }

  // Reconnect to the previously used device without scanning. Falls back to
  // opening the scan modal if it doesn't connect within RECONNECT_TIMEOUT_MS
  // (device off/out of range/replaced).
  function reconnectLastDevice() {
    clearTimeout(reconnectTimeoutId);
    connectDevice(lastKnownDevice.address, lastKnownDevice.name);
    reconnectTimeoutId = setTimeout(() => {
      reconnectTimeoutId = null;
      if (!isConnected) {
        deviceModal.classList.add('show');
        startScan();
      }
    }, RECONNECT_TIMEOUT_MS);
  }

  function openScanModal() {
    clearTimeout(reconnectTimeoutId);
    reconnectTimeoutId = null;
    deviceModal.classList.add('show');
    if (isConnected) {
      // Already connected: don't interrupt the active link with a scan.
      // User can hit "开始扫描" explicitly to look for another device.
      scanStatusText.textContent = '点击"开始扫描"以更换设备';
    } else {
      startScan();
    }
  }

  function disconnectDevice() {
    if (hasNativeBridge()) {
      try {
        window.SidecarBle.disconnect();
      } catch (e) {
        console.error('SidecarBle.disconnect error:', e);
      }
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

    if (connected) {
      statusBadge.classList.add('connected');
      statusBadge.classList.remove('disconnected');
      statusText.textContent = name || '已连接';
      deviceHint.textContent = `${name || '投影仪'} (${address})`;
      scanStatusText.textContent = '已连接到 ' + (name || address);
      currentDeviceText.textContent = `当前已连接: ${name || address}`;
      currentDeviceBar.hidden = false;
    } else {
      statusBadge.classList.remove('connected');
      statusBadge.classList.add('disconnected');
      statusText.textContent = '未连接';
      deviceHint.textContent = '点击右上角连接设备';
      currentDeviceBar.hidden = true;
    }
  }

  // Register Generic Driver Callbacks (Invoked from Kotlin SidecarBle)
  window.SidecarBleCallbacks = {
    onDeviceFound: function (name, address, rssi, uuidsJson) {
      const lower = (name || '').toLowerCase();
      const isObe = lower.includes('obe') || lower.includes('orange') || lower.includes('大眼橙') ||
                    (uuidsJson && uuidsJson.toLowerCase().includes('fff0'));
      if (isObe) {
        discoveredDevices.set(address, {
          name: name || '大眼橙投影仪',
          address: address,
          rssi: rssi
        });
        renderDeviceList();
        scanStatusText.textContent = `发现 ${discoveredDevices.size} 个设备`;
      }
    },

    onScanFinished: function () {
      btnScan.disabled = false;
      scanStatusText.textContent = `扫描完成，发现 ${discoveredDevices.size} 个设备`;
    },

    onConnectionStateChange: function (connected, name, address) {
      updateStatusUI(connected, name, address);
      if (connected) {
        clearTimeout(reconnectTimeoutId);
        reconnectTimeoutId = null;
        lastKnownDevice = { address: address, name: name };
        deviceModal.classList.remove('show');
      }
    }
  };

  // Wire Key Buttons
  const allButtons = document.querySelectorAll('[data-code]');
  allButtons.forEach(btn => {
    const code = btn.dataset.code;
    const keyName = btn.dataset.key;

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

  // Status Badge always opens the device modal: connected shows the current
  // device with a disconnect option, disconnected goes straight into scanning.
  // Actual key sends reconnect the known device on their own (ensureConnected),
  // so this is purely for managing/switching devices.
  statusBadge.addEventListener('click', () => {
    openScanModal();
  });

  btnDisconnect.addEventListener('click', () => {
    disconnectDevice();
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

  // Initial Sync Status with Native Driver
  setTimeout(() => {
    if (hasNativeBridge()) {
      try {
        const statusJson = window.SidecarBle.getStatus();
        if (statusJson) {
          const s = JSON.parse(statusJson);
          updateStatusUI(s.connected, s.name, s.address);

          if (s.connected) {
            lastKnownDevice = { address: s.address, name: s.name };
          } else if (s.lastAddress) {
            // Auto-reconnect to the previously used device instead of scanning
            lastKnownDevice = { address: s.lastAddress, name: s.lastName };
            reconnectLastDevice();
          }
        }
      } catch (e) {
        console.warn('Initial getStatus failed:', e);
      }
    }
  }, 200);

})();
