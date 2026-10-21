package com.p2chat.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of MeshScanner with hardware filter fallback and software filtering (NET-2, T2.7).
 */
class AndroidMeshScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: BluetoothAdapter.getDefaultAdapter(),
    var onPeerDiscovered: ((DiscoveredPeer, BluetoothDevice) -> Unit)? = null
) : MeshScanner {

    companion object {
        private const val TAG = "AndroidMeshScanner"
    }

    private val targetUuid = ParcelUuid.fromString(BleConstants.SERVICE_UUID)
    private val peersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanner: BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val scanRecord = result.scanRecord ?: return

            // Hardware filter fallback (Check service UUID in software)
            val serviceUuids = scanRecord.serviceUuids
            val matches = serviceUuids?.contains(targetUuid) == true

            if (matches) {
                val address = result.device.address
                val deviceName = scanRecord.deviceName ?: try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                }

                val rssi = result.rssi
                val existing = peersMap[address]
                val peer = DiscoveredPeer(
                    address = address,
                    alias = deviceName ?: existing?.alias,
                    senderFp = existing?.senderFp,
                    rssi = rssi,
                    lastSeenMs = System.currentTimeMillis(),
                    mtu = existing?.mtu ?: 517,
                    connectionState = existing?.connectionState ?: PeerConnectionState.DISCONNECTED,
                    isVerified = existing?.isVerified ?: false,
                    roleDescription = existing?.roleDescription ?: "1-Hop direct"
                )

                updatePeer(peer)
                onPeerDiscovered?.invoke(peer, result.device)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED (1)"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "REGISTRATION_FAILED (2)"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR (3)"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED (4)"
                else -> "UNKNOWN ($errorCode)"
            }
            SecureLog.e(TAG, "BLE scan failed: $reason")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        if (_isScanning.value) return
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            SecureLog.w(TAG, "Cannot start scan: Bluetooth disabled or unavailable")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            SecureLog.w(TAG, "BluetoothLeScanner is null")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // Older phones (API <= 30) or controllers with buggy 128-bit hardware filter matching
            // scan without hardware filter, relying on our software filter in onScanResult.
            val filters = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(targetUuid)
                        .build()
                )
            } else {
                emptyList<ScanFilter>()
            }

            scanner?.startScan(filters, settings, scanCallback)
            _isScanning.value = true
            SecureLog.d(TAG, "BLE scanner started (mode: LOW_LATENCY, filterCount: ${filters.size})")
        } catch (t: Throwable) {
            SecureLog.e(TAG, "Exception starting BLE scan: ${t.message}")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!_isScanning.value) return
        try {
            scanner?.stopScan(scanCallback)
            SecureLog.d(TAG, "BLE scanner stopped")
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Exception stopping BLE scan: ${t.message}")
        } finally {
            _isScanning.value = false
        }
    }

    override fun updatePeer(peer: DiscoveredPeer) {
        peersMap[peer.address] = peer
        _discoveredPeers.value = peersMap.values.toList().sortedByDescending { it.rssi }
    }

    override fun removePeer(address: String) {
        peersMap.remove(address)
        _discoveredPeers.value = peersMap.values.toList().sortedByDescending { it.rssi }
    }

    override fun clear() {
        peersMap.clear()
        _discoveredPeers.value = emptyList()
    }
}
