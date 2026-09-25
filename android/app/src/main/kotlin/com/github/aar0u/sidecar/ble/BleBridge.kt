package com.github.aar0u.sidecar.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Generic Android BLE Hardware HAL / Bridge.
 * Decoupled from any specific device or protocol logic.
 * Reusable across any Sidecar service (remotes, TVs, lights, sensors).
 */
@SuppressLint("MissingPermission")
class BleBridge(
    private val activity: Activity,
    private val webView: WebView
) {

    companion object {
        private const val TAG = "SidecarBleBridge"
        private const val PREFS_NAME = "sidecar_ble_prefs"
        private const val KEY_LAST_DEVICE_ADDR = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"

        val CLIENT_CHAR_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val bluetoothManager: BluetoothManager? by lazy {
        activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private var currentGatt: BluetoothGatt? = null
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

            val serviceUuids = JSONArray()
            scanRecord?.serviceUuids?.forEach { serviceUuids.put(it.uuid.toString()) }

            mainHandler.post {
                val safeName = (if (name.isNotEmpty()) name else "Unknown Device").replace("'", "\\'")
                val uuidsJson = serviceUuids.toString().replace("'", "\\'")
                evalJs("window.SidecarBleCallbacks && window.SidecarBleCallbacks.onDeviceFound('$safeName', '$address', $rssi, '$uuidsJson');")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error: $errorCode")
            isScanning = false
            mainHandler.post {
                evalJs("window.SidecarBleCallbacks && window.SidecarBleCallbacks.onScanFinished();")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                currentDeviceAddress = gatt?.device?.address ?: ""
                currentDeviceName = gatt?.device?.name ?: "Connected Device"
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                notifyConnectionState(false, "", "")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                saveLastDevice(currentDeviceAddress, currentDeviceName)
                notifyConnectionState(true, currentDeviceName, currentDeviceAddress)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                val data = characteristic.value ?: byteArrayOf()
                val hexStr = bytesToHex(data)
                val sUuid = characteristic.service?.uuid?.toString() ?: ""
                val cUuid = characteristic.uuid?.toString() ?: ""
                mainHandler.post {
                    evalJs("window.SidecarBleCallbacks && window.SidecarBleCallbacks.onCharacteristicRead('$sUuid', '$cUuid', '$hexStr');")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic ?: return
            val data = characteristic.value ?: byteArrayOf()
            val hexStr = bytesToHex(data)
            val sUuid = characteristic.service?.uuid?.toString() ?: ""
            val cUuid = characteristic.uuid?.toString() ?: ""
            mainHandler.post {
                evalJs("window.SidecarBleCallbacks && window.SidecarBleCallbacks.onCharacteristicChanged('$sUuid', '$cUuid', '$hexStr');")
            }
        }
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

    private fun notifyConnectionState(connected: Boolean, name: String, address: String) {
        mainHandler.post {
            val safeName = name.replace("'", "\\'")
            val safeAddr = address.replace("'", "\\'")
            evalJs("window.SidecarBleCallbacks && window.SidecarBleCallbacks.onConnectionStateChange($connected, '$safeName', '$safeAddr');")
        }
    }

    private fun saveLastDevice(address: String, name: String) {
        prefs.edit()
            .putString(KEY_LAST_DEVICE_ADDR, address)
            .putString(KEY_LAST_DEVICE_NAME, name)
            .apply()
    }

    private fun evalJs(script: String) {
        webView.evaluateJavascript(script, null)
    }

    // ─────────────────────────────────────────────
    // Generic JavaScript Interface API (window.SidecarBle)
    // ─────────────────────────────────────────────

    @JavascriptInterface
    fun scan(serviceUuidFilter: String?, timeoutMs: Long) {
        if (!hasPermissions()) {
            mainHandler.post { requestPermissions() }
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (isScanning) stopScan()

        isScanning = true
        val filters = mutableListOf<ScanFilter>()
        if (!serviceUuidFilter.isNullOrEmpty()) {
            try {
                val uuid = UUID.fromString(serviceUuidFilter)
                filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build())
            } catch (e: Exception) {
                Log.w(TAG, "Invalid filter UUID: $serviceUuidFilter")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)

        val duration = if (timeoutMs > 0) timeoutMs else 10_000L
        mainHandler.postDelayed({ stopScan() }, duration)
    }

    @JavascriptInterface
    fun stopScan() {
        if (isScanning) {
            isScanning = false
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            mainHandler.post {
                evalJs("window.SidecarBleCallbacks && window.SidecarBleCallbacks.onScanFinished();")
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

        Log.i(TAG, "Connecting to generic BLE device: $address")
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
            isConnected = false
            notifyConnectionState(false, "", "")
        }
    }

    @JavascriptInterface
    fun write(serviceUuid: String, charUuid: String, hexData: String): Boolean {
        val gatt = currentGatt ?: return false
        val sUuid = try { UUID.fromString(serviceUuid) } catch (e: Exception) { return false }
        val cUuid = try { UUID.fromString(charUuid) } catch (e: Exception) { return false }

        val service = gatt.getService(sUuid) ?: return false
        val char = service.getCharacteristic(cUuid) ?: return false
        val payload = hexToBytes(hexData)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    @JavascriptInterface
    fun read(serviceUuid: String, charUuid: String): Boolean {
        val gatt = currentGatt ?: return false
        val sUuid = try { UUID.fromString(serviceUuid) } catch (e: Exception) { return false }
        val cUuid = try { UUID.fromString(charUuid) } catch (e: Exception) { return false }

        val service = gatt.getService(sUuid) ?: return false
        val char = service.getCharacteristic(cUuid) ?: return false
        return gatt.readCharacteristic(char)
    }

    @JavascriptInterface
    fun setNotification(serviceUuid: String, charUuid: String, enable: Boolean): Boolean {
        val gatt = currentGatt ?: return false
        val sUuid = try { UUID.fromString(serviceUuid) } catch (e: Exception) { return false }
        val cUuid = try { UUID.fromString(charUuid) } catch (e: Exception) { return false }

        val service = gatt.getService(sUuid) ?: return false
        val char = service.getCharacteristic(cUuid) ?: return false

        gatt.setCharacteristicNotification(char, enable)
        val descriptor = char.getDescriptor(CLIENT_CHAR_CONFIG_UUID) ?: return false
        val value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @JavascriptInterface
    fun getStatus(): String {
        return JSONObject().apply {
            put("bluetoothEnabled", bluetoothAdapter?.isEnabled == true)
            put("connected", isConnected)
            put("name", currentDeviceName)
            put("address", currentDeviceAddress)
            put("lastAddress", prefs.getString(KEY_LAST_DEVICE_ADDR, ""))
            put("lastName", prefs.getString(KEY_LAST_DEVICE_NAME, ""))
        }.toString()
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
        val duration = durationMs.coerceIn(10L, 1000L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(duration)
            }
        } catch (_: Exception) {}
    }

    fun destroy() {
        stopScan()
        disconnect()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace(" ", "").replace("0x", "")
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
