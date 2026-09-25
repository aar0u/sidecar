package com.github.aar0u.sidecar.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

@SuppressLint("MissingPermission")
class ObeBleBridge(
    private val activity: Activity,
    private val webView: WebView
) {

    companion object {
        private const val TAG = "ObeBleBridge"
        private const val PREFS_NAME = "obe_ble_prefs"
        private const val KEY_LAST_DEVICE_ADDR = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"

        val SERVICE_UUID_PRIMARY: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val SERVICE_UUID_FALLBACK: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

        private const val ACTION_PRESS: Byte = 0x00
        private const val ACTION_RELEASE: Byte = 0x01
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val bluetoothManager: BluetoothManager? by lazy {
        activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private var currentGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected: Boolean = false
    private var currentDeviceName: String = ""
    private var currentDeviceAddress: String = ""
    private var isScanning: Boolean = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val scanRecord = result.scanRecord
            val name = (scanRecord?.deviceName ?: device.name ?: "").trim()
            val address = device.address ?: return
            val rssi = result.rssi

            val isObe = isTargetObeDevice(name, scanRecord?.serviceUuids?.map { it.uuid })
            if (isObe || name.isNotEmpty()) {
                mainHandler.post {
                    val safeName = (if (name.isNotEmpty()) name else "OBE Projector").replace("'", "\\'")
                    webView.evaluateJavascript(
                        "window.onBleDeviceFound && window.onBleDeviceFound('$safeName', '$address', $rssi);",
                        null
                    )
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            isScanning = false
            mainHandler.post {
                webView.evaluateJavascript("window.onBleScanFinished && window.onBleScanFinished();", null)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                currentDeviceAddress = gatt?.device?.address ?: ""
                currentDeviceName = gatt?.device?.name ?: "OBE Projector"
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                writeCharacteristic = null
                notifyConnectionState(false, "", "")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                var foundChar: BluetoothGattCharacteristic? = null
                for (service in gatt.services) {
                    val sUuid = service.uuid.toString().lowercase()
                    if (sUuid.contains("fff0") || sUuid.contains("ffe0")) {
                        for (char in service.characteristics) {
                            val cUuid = char.uuid.toString().lowercase()
                            if (cUuid.contains("fff1") || cUuid.contains("ffe1")) {
                                foundChar = char
                                break
                            }
                        }
                    }
                    if (foundChar != null) break
                }

                writeCharacteristic = foundChar
                if (foundChar != null) {
                    Log.i(TAG, "Successfully bound OBE write characteristic: ${foundChar.uuid}")
                    saveLastDevice(currentDeviceAddress, currentDeviceName)
                    notifyConnectionState(true, currentDeviceName, currentDeviceAddress)
                } else {
                    Log.w(TAG, "OBE write characteristic not found in GATT table")
                }
            }
        }
    }

    init {
        // Auto-reconnect last known device in background if bluetooth is enabled
        mainHandler.postDelayed({
            val lastAddr = prefs.getString(KEY_LAST_DEVICE_ADDR, null)
            val lastName = prefs.getString(KEY_LAST_DEVICE_NAME, "OBE Projector") ?: "OBE Projector"
            if (!lastAddr.isNullOrEmpty() && hasPermissions() && bluetoothAdapter?.isEnabled == true) {
                Log.d(TAG, "Auto-connecting to previously paired device: $lastAddr")
                connect(lastAddr)
            }
        }, 800)
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        ActivityCompat.requestPermissions(activity, permissions, 1001)
    }

    private fun isTargetObeDevice(name: String, serviceUuids: List<UUID>?): Boolean {
        val lower = name.lowercase()
        if (lower.contains("obe") || lower.contains("orange") || lower.contains("大眼橙") || lower.contains("dayancheng")) {
            return true
        }
        if (serviceUuids != null) {
            for (u in serviceUuids) {
                val str = u.toString().lowercase()
                if (str.contains("fff0") || str.contains("ffe0")) return true
            }
        }
        return false
    }

    private fun notifyConnectionState(connected: Boolean, name: String, address: String) {
        mainHandler.post {
            val safeName = name.replace("'", "\\'")
            val safeAddr = address.replace("'", "\\'")
            webView.evaluateJavascript(
                "window.onBleConnectionStateChange && window.onBleConnectionStateChange($connected, '$safeName', '$safeAddr');",
                null
            )
        }
    }

    private fun saveLastDevice(address: String, name: String) {
        prefs.edit()
            .putString(KEY_LAST_DEVICE_ADDR, address)
            .putString(KEY_LAST_DEVICE_NAME, name)
            .apply()
    }

    // ─────────────────────────────────────────────
    // JavaScript Interface Methods
    // ─────────────────────────────────────────────

    @JavascriptInterface
    fun sendKey(keyCode: Int, holdMs: Int) {
        if (!isConnected || currentGatt == null || writeCharacteristic == null) {
            Log.w(TAG, "Cannot send key $keyCode: not connected")
            return
        }

        scope.launch {
            try {
                // 1. Send Press: [keyCode, 0x00]
                val pressPayload = byteArrayOf(keyCode.toByte(), ACTION_PRESS)
                writePayload(pressPayload)

                // 2. Hold delay
                delay(holdMs.toLong().coerceAtLeast(30L))

                // 3. Send Release: [keyCode, 0x01]
                val releasePayload = byteArrayOf(keyCode.toByte(), ACTION_RELEASE)
                writePayload(releasePayload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send key $keyCode", e)
            }
        }
    }

    @JavascriptInterface
    fun sendText(text: String) {
        if (!isConnected || currentGatt == null || writeCharacteristic == null) {
            Log.w(TAG, "Cannot send text: not connected")
            return
        }

        scope.launch {
            try {
                val payload = text.toByteArray(Charsets.UTF_8)
                writePayload(payload)
                Log.d(TAG, "Sent text payload: $text (${payload.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send text", e)
            }
        }
    }

    @JavascriptInterface
    fun startScan() {
        if (!hasPermissions()) {
            mainHandler.post { requestPermissions() }
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is unavailable")
            return
        }

        if (isScanning) {
            stopScan()
        }

        isScanning = true
        scanner.startScan(scanCallback)

        // Auto stop scan after 12 seconds
        mainHandler.postDelayed({
            stopScan()
        }, 12_000)
    }

    @JavascriptInterface
    fun stopScan() {
        if (isScanning) {
            isScanning = false
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            mainHandler.post {
                webView.evaluateJavascript("window.onBleScanFinished && window.onBleScanFinished();", null)
            }
        }
    }

    @JavascriptInterface
    fun connect(address: String) {
        if (!hasPermissions()) {
            mainHandler.post { requestPermissions() }
            return
        }

        disconnect()

        val adapter = bluetoothAdapter ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid Bluetooth address: $address", e)
            return
        }

        Log.i(TAG, "Connecting to BLE device: $address")
        currentGatt = device.connectGatt(activity, false, gattCallback)
    }

    @JavascriptInterface
    fun disconnect() {
        try {
            currentGatt?.disconnect()
            currentGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing gatt", e)
        } finally {
            currentGatt = null
            writeCharacteristic = null
            isConnected = false
            notifyConnectionState(false, "", "")
        }
    }

    @JavascriptInterface
    fun getStatus(): String {
        return JSONObject().apply {
            put("connected", isConnected)
            put("name", currentDeviceName)
            put("address", currentDeviceAddress)
        }.toString()
    }

    private fun writePayload(payload: ByteArray) {
        val gatt = currentGatt ?: return
        val char = writeCharacteristic ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    fun destroy() {
        stopScan()
        disconnect()
        scope.cancel()
    }
}
