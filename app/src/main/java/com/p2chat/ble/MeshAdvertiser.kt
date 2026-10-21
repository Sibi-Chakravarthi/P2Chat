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
import java.util.UUID

/**
 * Manages BLE peripheral advertising for P2Chat mesh presence (NET-1, T2.7).
 * Avoids the 31-byte advertising payload limit by placing device name strictly in scanResponse.
 */
class MeshAdvertiser(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: BluetoothAdapter.getDefaultAdapter()
) {
    companion object {
        private const val TAG = "MeshAdvertiser"
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _isAdvertising.value = true
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
            SecureLog.e(TAG, "BLE advertising failed: $errorMsg")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (_isAdvertising.value) return
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            SecureLog.w(TAG, "Cannot start advertising: Bluetooth disabled or unavailable")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
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

        // SCAN RESPONSE DATA: Holds the device name in the separate scan response packet
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            SecureLog.d(TAG, "Initiated BLE advertising for service ${BleConstants.SERVICE_UUID}")
        } catch (t: Throwable) {
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
        }
    }
}
