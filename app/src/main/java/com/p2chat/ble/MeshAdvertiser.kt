package com.p2chat.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Manages BLE peripheral advertising for P2Chat mesh presence (NET-1, T2.7).
 * Avoids the 31-byte advertising payload limit by placing device name and peer ID strictly in scanResponse.
 */
class MeshAdvertiser(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: BluetoothAdapter.getDefaultAdapter(),
    var localPeerId: Long? = null
) {
    companion object {
        private const val TAG = "MeshAdvertiser"
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _advertisingFailureReason = MutableStateFlow<String?>(null)
    val advertisingFailureReason: StateFlow<String?> = _advertisingFailureReason.asStateFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _isAdvertising.value = true
            _advertisingFailureReason.value = null
            SecureLog.d(TAG, "BLE advertising started successfully (mode: ${settingsInEffect?.mode})")
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE (1)"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS (2)"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED (3)"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR (4)"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED (5)"
                else -> "UNKNOWN ($errorCode)"
            }
            _advertisingFailureReason.value = errorMsg
            SecureLog.e(TAG, "BLE advertising failed: $errorMsg")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(peerId: Long? = localPeerId) {
        if (peerId != null) {
            localPeerId = peerId
        }
        if (_isAdvertising.value) return
        if (!BlePermissions.hasPermissions(context)) {
            _advertisingFailureReason.value = "MISSING_PERMISSIONS"
            SecureLog.w(TAG, "Cannot start advertising: missing required runtime permissions")
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _advertisingFailureReason.value = "BLUETOOTH_DISABLED"
            SecureLog.w(TAG, "Cannot start advertising: Bluetooth disabled or unavailable")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            _advertisingFailureReason.value = "ADVERTISER_NULL"
            SecureLog.w(TAG, "BluetoothLeAdvertiser is null on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val serviceUuid = ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID))

        // PRIMARY ADVERTISE DATA (Strictly <= 31 bytes):
        // 128-bit UUID takes 18 bytes + flags (3 bytes).
        // setIncludeDeviceName(false) and setIncludeTxPowerLevel(false) to never exceed 31 bytes.
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(serviceUuid)
            .build()

        // SCAN RESPONSE DATA: Holds the device name and optional 8-byte peerId in the separate scan response packet
        val scanResponseBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(true)

        localPeerId?.let { id ->
            val peerBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(id).array()
            scanResponseBuilder.addManufacturerData(BleConstants.BLE_MANUFACTURER_ID, peerBytes)
        }
        val scanResponse = scanResponseBuilder.build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            SecureLog.d(TAG, "Initiated BLE advertising for service ${BleConstants.SERVICE_UUID} (peerId: $localPeerId)")
        } catch (t: Throwable) {
            _advertisingFailureReason.value = t.message
            SecureLog.e(TAG, "Exception starting advertising: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!_isAdvertising.value) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            SecureLog.d(TAG, "Stopped BLE advertising")
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Exception stopping advertising: ${t.message}")
        } finally {
            _isAdvertising.value = false
            _advertisingFailureReason.value = null
        }
    }
}
