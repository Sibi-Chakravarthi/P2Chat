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
import android.os.ParcelUuid
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of MeshScanner with hardware filter fallback, software filtering,
 * MAC address rotation tracking, and stale peer pruning (NET-2, T2.7).
 */
class AndroidMeshScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: BluetoothAdapter.getDefaultAdapter(),
    var onPeerDiscovered: ((DiscoveredPeer, BluetoothDevice) -> Unit)? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : MeshScanner {

    companion object {
        private const val TAG = "AndroidMeshScanner"
        const val STALE_PEER_TIMEOUT_MS = 30_000L
        const val PRUNE_INTERVAL_MS = 10_000L
    }

    private val targetUuid = ParcelUuid.fromString(BleConstants.SERVICE_UUID)
    private val peersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanFailureReason = MutableStateFlow<String?>(null)
    val scanFailureReason: StateFlow<String?> = _scanFailureReason.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private var pruneJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val scanRecord = result.scanRecord ?: return

            // Dual match: check 128-bit service UUID OR our registered BLE manufacturer ID
            val serviceUuids = scanRecord.serviceUuids
            val hasServiceUuid = serviceUuids?.contains(targetUuid) == true
            val mfgData = scanRecord.getManufacturerSpecificData(BleConstants.BLE_MANUFACTURER_ID)
            val hasMfgData = mfgData != null
            val hasServiceData = scanRecord.serviceData.containsKey(targetUuid)

            val matches = hasServiceUuid || hasMfgData || hasServiceData

            if (matches) {
                val address = result.device.address
                val deviceName = scanRecord.deviceName ?: try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                }

                val remoteSenderFp = if (mfgData != null && mfgData.size >= 8) {
                    ByteBuffer.wrap(mfgData, 0, 8).order(ByteOrder.BIG_ENDIAN).long
                } else {
                    null
                }

                // Handle MAC address randomization: if remoteSenderFp matches an existing peer with a different MAC address,
                // prune the old MAC address so we do not display duplicate peers in the UI.
                if (remoteSenderFp != null) {
                    val staleEntry = peersMap.entries.firstOrNull { it.value.senderFp == remoteSenderFp && it.key != address }
                    if (staleEntry != null) {
                        peersMap.remove(staleEntry.key)
                    }
                }

                val existing = peersMap[address]
                val senderFp = remoteSenderFp ?: existing?.senderFp
                val peer = DiscoveredPeer(
                    address = address,
                    alias = deviceName ?: existing?.alias,
                    senderFp = senderFp,
                    rssi = result.rssi,
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
            _scanFailureReason.value = reason
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        if (_isScanning.value) return
        if (!BlePermissions.hasPermissions(context)) {
            _scanFailureReason.value = "MISSING_PERMISSIONS"
            SecureLog.w(TAG, "Cannot start scan: missing required runtime permissions")
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _scanFailureReason.value = "BLUETOOTH_DISABLED"
            SecureLog.w(TAG, "Cannot start scan: Bluetooth disabled or unavailable")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            _scanFailureReason.value = "SCANNER_NULL"
            SecureLog.w(TAG, "BluetoothLeScanner is null")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // Using emptyList<ScanFilter>() avoids hardware filter truncation and OEM 128-bit UUID matching bugs.
            // All filtering is robustly handled in software in onScanResult.
            val filters = emptyList<ScanFilter>()

            scanner?.startScan(filters, settings, scanCallback)
            _isScanning.value = true
            _scanFailureReason.value = null
            startPruneTimer()
            SecureLog.d(TAG, "BLE scanner started (mode: LOW_LATENCY)")
        } catch (t: Throwable) {
            _scanFailureReason.value = t.message
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
            _scanFailureReason.value = null
            stopPruneTimer()
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

    /**
     * Purges disconnected peers that have not been seen for longer than [maxAgeMs].
     */
    override fun purgeStalePeers(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        var changed = false
        peersMap.entries.removeIf { entry ->
            val peer = entry.value
            val isStale = (now - peer.lastSeenMs > maxAgeMs) &&
                    (peer.connectionState == PeerConnectionState.DISCONNECTED)
            if (isStale) {
                changed = true
                SecureLog.d(TAG, "Pruning stale peer ${peer.address} (last seen ${now - peer.lastSeenMs}ms ago)")
            }
            isStale
        }
        if (changed) {
            _discoveredPeers.value = peersMap.values.toList().sortedByDescending { it.rssi }
        }
    }

    private fun startPruneTimer() {
        pruneJob?.cancel()
        pruneJob = coroutineScope.launch {
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                purgeStalePeers()
            }
        }
    }

    private fun stopPruneTimer() {
        pruneJob?.cancel()
        pruneJob = null
    }
}
