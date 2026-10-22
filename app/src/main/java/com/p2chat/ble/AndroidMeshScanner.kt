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
        const val WATCHDOG_INTERVAL_MS = 13_000L // 12-15 seconds to prevent Android BLE scan throttling
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
    private var watchdogJob: Job? = null

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

                // Check if device already exists in the local collection (Issue 3: Ghost Nodes fix):
                // 1. By persistent remoteSenderFp (handles RPA MAC address rotation)
                // 2. By MAC address
                // 3. By matching device name/alias if present
                val existingEntry = when {
                    remoteSenderFp != null -> peersMap.entries.firstOrNull { it.value.senderFp == remoteSenderFp || it.key == address }
                    deviceName != null && deviceName.isNotBlank() -> peersMap.entries.firstOrNull { it.key == address || it.value.alias == deviceName }
                    else -> peersMap.entries.firstOrNull { it.key == address }
                }

                val existing = existingEntry?.value

                // If existing entry had a different MAC address because of RPA rotation, remove the stale key
                if (existingEntry != null && existingEntry.key != address) {
                    peersMap.remove(existingEntry.key)
                }

                val senderFp = remoteSenderFp ?: existing?.senderFp
                val peer = if (existing != null) {
                    // Update its lastSeen timestamp and RSSI rather than adding a new entry
                    existing.copy(
                        address = address,
                        alias = deviceName ?: existing.alias,
                        senderFp = senderFp,
                        rssi = result.rssi,
                        lastSeenMs = System.currentTimeMillis()
                    )
                } else {
                    DiscoveredPeer(
                        address = address,
                        alias = deviceName,
                        senderFp = senderFp,
                        rssi = result.rssi,
                        lastSeenMs = System.currentTimeMillis(),
                        mtu = 517,
                        connectionState = PeerConnectionState.DISCONNECTED,
                        isVerified = false,
                        roleDescription = "1-Hop direct"
                    )
                }

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
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                _isScanning.value = true
                _scanFailureReason.value = null
                return
            }
            _scanFailureReason.value = reason
            _isScanning.value = false
            stopPruneTimer()
            scheduleScanRecovery(errorCode)
        }
    }

    /**
     * Builds aggressive scan settings for real-time BLE mesh peer discovery (NET-2, Phase 3).
     * Prevents batching and ensures immediate dispatch of all advertising packets.
     */
    fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()
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

        val settings = buildScanSettings()

        try {
            // Using emptyList<ScanFilter>() avoids hardware filter truncation and OEM 128-bit UUID matching bugs.
            // All filtering is robustly handled in software in onScanResult.
            val filters = emptyList<ScanFilter>()

            scanner?.startScan(filters, settings, scanCallback)
            _isScanning.value = true
            _scanFailureReason.value = null
            startPruneTimer()
            stopRecoveryJob()
            SecureLog.d(TAG, "BLE scanner started (mode: LOW_LATENCY, AGGRESSIVE, MAX_MATCHES)")
        } catch (t: Throwable) {
            _scanFailureReason.value = t.message
            SecureLog.e(TAG, "Exception starting BLE scan: ${t.message}")
            _isScanning.value = false
            stopPruneTimer()
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
            stopRecoveryJob()
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

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private var recoveryJob: Job? = null

    /**
     * Resilient scanner failure recovery (Issue 5).
     * Schedules an automatic restart with backoff instead of leaving the node permanently blind.
     */
    fun scheduleScanRecovery(errorCode: Int) {
        recoveryJob?.cancel()
        recoveryJob = coroutineScope.launch {
            val delayMs = if (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) 2500L else 1500L
            SecureLog.w(TAG, "Scheduling scanner restart in ${delayMs}ms for error $errorCode...")
            delay(delayMs)
            if (!_isScanning.value) {
                SecureLog.d(TAG, "Attempting automatic scanner recovery restart...")
                startScan()
            }
        }
    }

    private fun stopRecoveryJob() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    @SuppressLint("MissingPermission")
    fun restartScanInternal() {
        if (!_isScanning.value) return
        val currentScanner = scanner ?: return
        try {
            currentScanner.stopScan(scanCallback)
            SecureLog.d(TAG, "BLE scan stopped for cycle")
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Error stopping BLE scan during cycle: ${t.message}")
        }

        if (!_isScanning.value) return

        try {
            val settings = buildScanSettings()
            val filters = emptyList<ScanFilter>()
            currentScanner.startScan(filters, settings, scanCallback)
            SecureLog.d(TAG, "BLE scan restarted successfully (mode: LOW_LATENCY, AGGRESSIVE)")
        } catch (t: Throwable) {
            SecureLog.e(TAG, "Error restarting BLE scan during cycle: ${t.message}")
        }
    }
}

