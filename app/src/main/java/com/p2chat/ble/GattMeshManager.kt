package com.p2chat.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.pipeline.ReceivePipeline
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import com.p2chat.wire.PacketHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Strict sequential GATT operation queue per connected device (STEP 2.2).
 * Android BluetoothGatt fails silently if operations are invoked concurrently.
 * All operations wait for the corresponding BluetoothGattCallback with a 3000ms timeout.
 */
class GattDeviceQueue(
    val address: String,
    private val gatt: BluetoothGatt
) {
    private val opMutex = Mutex()
    @Volatile private var pendingMtu: CompletableDeferred<Int>? = null
    @Volatile private var pendingServices: CompletableDeferred<Int>? = null
    @Volatile private var pendingDescWrite: CompletableDeferred<Int>? = null
    @Volatile private var pendingCharWrite: CompletableDeferred<Int>? = null

    suspend fun requestMtu(mtu: Int): Int = opMutex.withLock {
        val def = CompletableDeferred<Int>()
        pendingMtu = def
        val ok = try {
            gatt.requestMtu(mtu)
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            pendingMtu = null
            return BluetoothGatt.GATT_FAILURE
        }
        val status = withTimeoutOrNull(3000L) { def.await() } ?: BluetoothGatt.GATT_FAILURE
        pendingMtu = null
        status
    }

    suspend fun discoverServices(): Int = opMutex.withLock {
        val def = CompletableDeferred<Int>()
        pendingServices = def
        val ok = try {
            gatt.discoverServices()
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            pendingServices = null
            return BluetoothGatt.GATT_FAILURE
        }
        val status = withTimeoutOrNull(3000L) { def.await() } ?: BluetoothGatt.GATT_FAILURE
        pendingServices = null
        status
    }

    suspend fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Int = opMutex.withLock {
        val def = CompletableDeferred<Int>()
        pendingDescWrite = def
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            pendingDescWrite = null
            return BluetoothGatt.GATT_FAILURE
        }
        val status = withTimeoutOrNull(3000L) { def.await() } ?: BluetoothGatt.GATT_FAILURE
        pendingDescWrite = null
        status
    }

    suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Int = opMutex.withLock {
        val def = CompletableDeferred<Int>()
        pendingCharWrite = def
        characteristic.writeType = writeType
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            pendingCharWrite = null
            return BluetoothGatt.GATT_FAILURE
        }
        val status = withTimeoutOrNull(3000L) { def.await() } ?: BluetoothGatt.GATT_FAILURE
        pendingCharWrite = null
        status
    }

    fun onMtuChanged(mtu: Int, status: Int) {
        pendingMtu?.complete(status)
    }

    fun onServicesDiscovered(status: Int) {
        pendingServices?.complete(status)
    }

    fun onDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int) {
        pendingDescWrite?.complete(status)
    }

    fun onCharacteristicWrite(characteristic: BluetoothGattCharacteristic, status: Int) {
        pendingCharWrite?.complete(status)
    }

    fun cancelAll(reason: String = "CANCELLED") {
        pendingMtu?.cancel(CancellationException(reason))
        pendingServices?.cancel(CancellationException(reason))
        pendingDescWrite?.cancel(CancellationException(reason))
        pendingCharWrite?.cancel(CancellationException(reason))
        pendingMtu = null
        pendingServices = null
        pendingDescWrite = null
        pendingCharWrite = null
    }
}

/**
 * Thread-safe LRU cache for mesh message deduplication (STEP 3.2, NET-9).
 */
class MsgIdLruCache(private val maxSize: Int = 1000) {
    private val map = object : LinkedHashMap<String, Long>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun containsOrPut(msgId: Long): Boolean {
        val key = msgId.toString()
        if (map.containsKey(key)) {
            return true
        }
        map[key] = System.currentTimeMillis()
        return false
    }

    @Synchronized
    fun evict(msgId: Long) {
        map.remove(msgId.toString())
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

/**
 * Manages dual-role BLE GATT operations (GATT Server + GATT Client)
 * coordinating active links, MTU negotiation, sequential chunked packet queues,
 * deterministic tie-breaking, and epidemic mesh packet forwarding (NET-1, NET-2, NET-3, NET-6, T2.2, T2.6, T2.8).
 */
class GattMeshManager(
    private val context: Context,
    private val connectionPool: ConnectionPool,
    private val meshTransport: MeshTransport,
    private val receivePipeline: ReceivePipeline?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    var localPeerId: Long? = null
) {
    companion object {
        private const val TAG = "GattMeshManager"
        private val SERVICE_UUID = UUID.fromString(BleConstants.SERVICE_UUID)
        private val CHAR_UUID = UUID.fromString(BleConstants.CHARACTERISTIC_UUID)
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val WRITE_TIMEOUT_MS = 3000L
        private const val MAX_SEEN_CACHE_SIZE = 1000
        const val CONNECTION_TIMEOUT_MS = 8000L
        const val PASSIVE_WAIT_TIMEOUT_MS = 8000L
        const val MAX_RETRY_ATTEMPTS = 3
        const val BASE_RETRY_DELAY_MS = 1000L
        const val EXTENDED_COOLDOWN_MS = 15_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private var gattServer: BluetoothGattServer? = null
    private var gattCharacteristic: BluetoothGattCharacteristic? = null

    // Active connected centrals (server role) and active client connections (client role)
    private val serverCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val clientGattMap = ConcurrentHashMap<String, BluetoothGatt>()

    // Sequential GATT operation queues per client device
    private val deviceQueues = ConcurrentHashMap<String, GattDeviceQueue>()

    // Active in-flight connection tracking to prevent redundant connectGatt storms (STEP 2.1)
    private val inFlightConnections = ConcurrentHashMap<String, Long>()
    private val inFlightTimeouts = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Resilient connection retry and exponential backoff tracking (STEP 2.3)
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private val connectionCooldowns = ConcurrentHashMap<String, Long>()

    // Tie-breaker passive wait tracking with timeout fallback (STEP 2.1)
    private val passiveWaitStarts = ConcurrentHashMap<String, Long>()

    // Map of discovered / known remote peer IDs (address -> senderFp)
    private val remotePeerIds = ConcurrentHashMap<String, Long>()

    // Mutex to serialize connectGatt invocations across simultaneous scan hits
    private val connectMutex = Mutex()

    fun setRemotePeerId(address: String, peerId: Long) {
        remotePeerIds[address] = peerId
    }

    fun getRemotePeerId(address: String): Long? = remotePeerIds[address]

    /**
     * Deterministic Dual-Role Tie-Breaking (STEP 2.1).
     * Prevents simultaneous connectGatt storms when two devices discover each other.
     * Compares numeric peer IDs if known, or deterministic string IDs (local vs remote MAC).
     * Only the side with priority initiates the Central connectGatt connection.
     */
    fun shouldInitiateConnection(address: String, remotePeerIdHint: Long? = null): Boolean {
        val myId = localPeerId
        val targetPeerId = remotePeerIdHint
            ?: remotePeerIds[address]
            ?: MeshService.sharedScanner?.discoveredPeers?.value?.firstOrNull { it.address == address }?.senderFp

        if (targetPeerId != null) {
            remotePeerIds[address] = targetPeerId
        }

        // 1. Both numeric peer IDs known: Higher unsigned ID initiates connection
        if (myId != null && targetPeerId != null) {
            return java.lang.Long.compareUnsigned(myId, targetPeerId) > 0
        }

        // 2. When targetPeerId is null, return true (let in-flight lock and duplicate-connection tie-breaker resolve collision)
        return true
    }

    fun isLocalTieBreakerHigher(address: String, remotePeerIdHint: Long? = null): Boolean {
        val myId = localPeerId
        val targetPeerId = remotePeerIdHint
            ?: remotePeerIds[address]
            ?: MeshService.sharedScanner?.discoveredPeers?.value?.firstOrNull { it.address == address }?.senderFp

        if (targetPeerId != null) {
            remotePeerIds[address] = targetPeerId
        }

        if (myId != null && targetPeerId != null) {
            return java.lang.Long.compareUnsigned(myId, targetPeerId) > 0
        }
        return true
    }

    private val activeSinks = ConcurrentHashMap<String, PeerSink>()
    private val writeLocks = ConcurrentHashMap<String, Mutex>()
    private val negotiatedMtuMap = ConcurrentHashMap<String, Int>()

    // Pending sequential notify completions per peer (Server role)
    private val serverNotifyCompletions = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    // Thread-safe LRU deduplication cache for mesh packets (STEP 3.2, NET-9)
    private val seenMsgLruCache = MsgIdLruCache(MAX_SEEN_CACHE_SIZE)

    private val reassembler = Reassembler()

    fun evictSeenPacket(msgId: Long) {
        seenMsgLruCache.evict(msgId)
    }

    fun recordSentPacket(msgId: Long) {
        seenMsgLruCache.containsOrPut(msgId)
    }

    // ── GATT Server ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startGattServer() {
        if (gattServer != null) return
        if (bluetoothManager == null) {
            SecureLog.w(TAG, "Cannot start GATT server: BluetoothManager is null")
            return
        }

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val address = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    SecureLog.d(TAG, "GATT Server: Central $address connected")
                    inFlightTimeouts.remove(address)?.cancel()
                    inFlightConnections.remove(address)
                    passiveWaitStarts.remove(address)
                    retryCounts.remove(address)
                    connectionCooldowns.remove(address)

                    val hasClient = clientGattMap.containsKey(address)
                    val isHigher = isLocalTieBreakerHigher(address)

                    if (hasClient && isHigher) {
                        // Higher Peer ID keeps its Client connection and rejects duplicate incoming Server connection
                        SecureLog.d(TAG, "Duplicate connection on server: local peer is higher. Keeping client connection to $address, rejecting duplicate incoming server connection.")
                        try {
                            gattServer?.cancelConnection(device)
                        } catch (_: Throwable) {}
                        return
                    }

                    if (hasClient && !isHigher) {
                        // Lower Peer ID yields its Client connection and accepts incoming Server connection
                        SecureLog.d(TAG, "Duplicate connection on server: local peer is lower. Yielding client connection to $address, accepting server connection.")
                        deviceQueues.remove(address)?.cancelAll("DUPLICATE_YIELD")
                        val existingClient = clientGattMap.remove(address)
                        try {
                            existingClient?.disconnect()
                            existingClient?.close()
                        } catch (_: Throwable) {}
                    }

                    serverCentrals[address] = device
                    connectionPool.setConnectionState(address, PeerConnectionState.CONNECTED)
                    registerServerSink(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    SecureLog.d(TAG, "GATT Server: Central $address disconnected")
                    serverCentrals.remove(address)
                    serverNotifyCompletions.remove(address)?.cancel()

                    // Only unregister sink and reset state if NO active Client connection exists for this peer
                    if (!clientGattMap.containsKey(address)) {
                        unregisterSink(address)
                        connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)
                    } else {
                        SecureLog.d(TAG, "Server connection closed for $address, but Client connection remains active. Preserving link.")
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }

                if (characteristic.uuid == CHAR_UUID && value != null && value.isNotEmpty()) {
                    handleIncomingFrame(value, device.address)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (responseNeeded(requestId)) {
                    @Suppress("DEPRECATION")
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value ?: ByteArray(0))
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor
            ) {
                if (responseNeeded(requestId)) {
                    @Suppress("DEPRECATION")
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, descriptor.value ?: ByteArray(0))
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                serverNotifyCompletions[device.address]?.complete(status)
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                SecureLog.d(TAG, "GATT Server: MTU changed to $mtu for ${device.address}")
                negotiatedMtuMap[device.address] = mtu
            }

            private fun responseNeeded(requestId: Int): Boolean = requestId != 0
        }

        try {
            gattServer = bluetoothManager.openGattServer(context, serverCallback)
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val char = BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            char.addDescriptor(cccd)
            service.addCharacteristic(char)
            gattServer?.addService(service)
            gattCharacteristic = char

            SecureLog.d(TAG, "GATT Server started and service registered")
        } catch (t: Throwable) {
            SecureLog.e(TAG, "Exception starting GATT Server: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopGattServer() {
        try {
            gattServer?.close()
            gattServer = null
            serverCentrals.clear()
            SecureLog.d(TAG, "GATT Server closed")
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Exception closing GATT Server: ${t.message}")
        }
    }

    // ── GATT Client ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: BluetoothDevice, remotePeerId: Long? = null) {
        val address = device.address
        val now = System.currentTimeMillis()

        if (remotePeerId != null) {
            remotePeerIds[address] = remotePeerId
        }

        // 1. Guard against connecting if already connected
        if (clientGattMap.containsKey(address) || serverCentrals.containsKey(address)) {
            return
        }

        // 2. Guard against in-flight connection storm (STEP 2.1)
        if (inFlightConnections.containsKey(address)) {
            SecureLog.d(TAG, "Connection already in-flight for $address; skipping redundant connect")
            return
        }

        // 3. Guard against cooldown period after failed connections (STEP 2.3)
        val cooldownUntil = connectionCooldowns[address] ?: 0L
        if (now < cooldownUntil) {
            SecureLog.d(TAG, "Connection to $address in cooldown for another ${cooldownUntil - now}ms; skipping")
            return
        }

        // 4. Deterministic Dual-Role Tie-Breaking (STEP 2.1)
        val shouldConnect = shouldInitiateConnection(address, remotePeerId)
        if (!shouldConnect) {
            val waitStart = passiveWaitStarts.getOrPut(address) { now }
            val elapsed = now - waitStart
            if (elapsed < PASSIVE_WAIT_TIMEOUT_MS) {
                SecureLog.d(TAG, "Tie-break: local peer yields Central role to remote $address; awaiting incoming Server connection (elapsed: ${elapsed}ms)")
                return
            } else {
                SecureLog.w(TAG, "Tie-break passive wait timed out for $address (${elapsed}ms >= ${PASSIVE_WAIT_TIMEOUT_MS}ms); falling back to initiate connection")
            }
        } else {
            passiveWaitStarts.remove(address)
        }

        // 5. Connection pool capacity check
        if (connectionPool.getActiveConnectionCount() >= ConnectionPool.MAX_ACTIVE_CONNECTIONS) {
            connectionPool.setConnectionState(address, PeerConnectionState.QUEUED)
            return
        }

        // Atomically claim in-flight state
        if (inFlightConnections.putIfAbsent(address, now) != null) {
            return
        }

        connectionPool.setConnectionState(address, PeerConnectionState.CONNECTING)
        SecureLog.d(TAG, "Connecting GATT client to $address (in-flight tracking registered)...")

        // 6. Schedule connection timeout
        val timeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (inFlightConnections.containsKey(address)) {
                SecureLog.w(TAG, "Connection attempt to $address timed out after ${CONNECTION_TIMEOUT_MS}ms; cancelling")
                handleConnectionFailure(device, address, remotePeerId, "CONNECTION_TIMEOUT")
            }
        }
        inFlightTimeouts[address] = timeoutJob

        val clientCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val addr = gatt.device.address
                inFlightTimeouts.remove(addr)?.cancel()
                inFlightConnections.remove(addr)

                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    SecureLog.d(TAG, "GATT Client connected to $addr")
                    retryCounts.remove(addr)
                    connectionCooldowns.remove(addr)
                    passiveWaitStarts.remove(addr)

                    val hasServer = serverCentrals.containsKey(addr)
                    val isHigher = isLocalTieBreakerHigher(addr, remotePeerId)

                    if (hasServer && !isHigher) {
                        SecureLog.d(TAG, "Duplicate connection on client: local peer is lower. Yielding client connection to $addr, preserving server connection.")
                        deviceQueues.remove(addr)?.cancelAll("DUPLICATE_YIELD")
                        try {
                            gatt.disconnect()
                            gatt.close()
                        } catch (_: Throwable) {}
                        return
                    }

                    if (hasServer && isHigher) {
                        SecureLog.d(TAG, "Duplicate connection on client: local peer is higher. Keeping client connection to $addr, closing duplicate server connection.")
                        val serverDevice = serverCentrals.remove(addr)
                        if (serverDevice != null) {
                            try {
                                gattServer?.cancelConnection(serverDevice)
                            } catch (_: Throwable) {}
                        }
                    }

                    clientGattMap[addr] = gatt
                    val queue = GattDeviceQueue(addr, gatt)
                    deviceQueues[addr] = queue
                    refreshGattCache(gatt)

                    // Strict sequential execution: MTU 512 -> Service Discovery -> Enable CCCD (STEP 2.2, STEP 2.4)
                    scope.launch {
                        // Request MTU 512 immediately after connection before service discovery/writes
                        val mtuStatus = queue.requestMtu(512)
                        SecureLog.d(TAG, "MTU negotiation for $addr completed with status $mtuStatus (effective MTU: ${negotiatedMtuMap[addr] ?: 23})")

                        val discStatus = queue.discoverServices()
                        if (discStatus != BluetoothGatt.GATT_SUCCESS) {
                            SecureLog.w(TAG, "Service discovery failed for $addr (status: $discStatus)")
                            handleConnectionFailure(gatt.device, addr, remotePeerId, "SERVICE_DISCOVERY_STATUS_$discStatus")
                            return@launch
                        }

                        val service = gatt.getService(SERVICE_UUID)
                        val characteristic = service?.getCharacteristic(CHAR_UUID)

                        if (characteristic != null) {
                            gatt.setCharacteristicNotification(characteristic, true)
                            val cccd = characteristic.getDescriptor(CCCD_UUID)
                            if (cccd != null) {
                                val descStatus = queue.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                if (descStatus == BluetoothGatt.GATT_SUCCESS) {
                                    SecureLog.d(TAG, "CCCD descriptor written successfully; client sink ready for $addr")
                                    registerClientSink(gatt, characteristic, queue)
                                    connectionPool.setConnectionState(addr, PeerConnectionState.CONNECTED)
                                } else {
                                    SecureLog.w(TAG, "Failed to write CCCD descriptor for $addr (status: $descStatus)")
                                    handleConnectionFailure(gatt.device, addr, remotePeerId, "CCCD_WRITE_STATUS_$descStatus")
                                }
                            } else {
                                registerClientSink(gatt, characteristic, queue)
                                connectionPool.setConnectionState(addr, PeerConnectionState.CONNECTED)
                            }
                        } else {
                            SecureLog.w(TAG, "P2Chat characteristic not found on $addr")
                            handleConnectionFailure(gatt.device, addr, remotePeerId, "CHAR_NOT_FOUND")
                        }
                    }
                } else {
                    // Clean Teardown & Status 133 Recovery (STEP 2.3)
                    SecureLog.w(TAG, "GATT Client disconnected/failed for $addr (status: $status, newState: $newState)")
                    deviceQueues.remove(addr)?.cancelAll("DISCONNECTED_STATUS_$status")
                    clientGattMap.remove(addr)
                    try {
                        gatt.disconnect()
                    } catch (_: Throwable) {}
                    try {
                        gatt.close()
                    } catch (_: Throwable) {}

                    if (!serverCentrals.containsKey(addr)) {
                        unregisterSink(addr)
                        connectionPool.setConnectionState(addr, PeerConnectionState.DISCONNECTED)
                        handleConnectionFailure(gatt.device, addr, remotePeerId, "GATT_STATUS_$status")
                    } else {
                        SecureLog.d(TAG, "Client disconnected from $addr, but Server connection remains active. Preserving link.")
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val addr = gatt.device.address
                negotiatedMtuMap[addr] = mtu
                SecureLog.d(TAG, "GATT Client: MTU changed to $mtu for $addr (status: $status)")
                deviceQueues[addr]?.onMtuChanged(mtu, status)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val addr = gatt.device.address
                SecureLog.d(TAG, "GATT Client: Services discovered for $addr (status: $status)")
                deviceQueues[addr]?.onServicesDiscovered(status)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val addr = gatt.device.address
                SecureLog.d(TAG, "GATT Client: Descriptor write completed for $addr (status: $status)")
                deviceQueues[addr]?.onDescriptorWrite(descriptor, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val addr = gatt.device.address
                deviceQueues[addr]?.onCharacteristicWrite(characteristic, status)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == CHAR_UUID && value.isNotEmpty()) {
                    handleIncomingFrame(value, gatt.device.address)
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == CHAR_UUID) {
                    val bytes = characteristic.value ?: return
                    handleIncomingFrame(bytes, gatt.device.address)
                }
            }
        }

        scope.launch {
            connectMutex.withLock {
                if (clientGattMap.containsKey(address) || serverCentrals.containsKey(address)) {
                    inFlightTimeouts.remove(address)?.cancel()
                    inFlightConnections.remove(address)
                    return@launch
                }

                try {
                    val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(context, false, clientCallback)
                    }
                    clientGattMap[address] = gatt
                    refreshGattCache(gatt)
                } catch (t: Throwable) {
                    SecureLog.e(TAG, "Exception initiating connectGatt to $address: ${t.message}")
                    handleConnectionFailure(device, address, remotePeerId, t.message ?: "CONNECT_EXCEPTION")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionFailure(
        device: BluetoothDevice,
        address: String,
        remotePeerId: Long?,
        reason: String
    ) {
        inFlightTimeouts.remove(address)?.cancel()
        inFlightConnections.remove(address)
        deviceQueues.remove(address)?.cancelAll("CONNECTION_FAILURE")
        val gatt = clientGattMap.remove(address)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Throwable) {}
        unregisterSink(address)
        connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)

        val currentRetries = retryCounts.compute(address) { _, count -> (count ?: 0) + 1 } ?: 1

        if (currentRetries <= MAX_RETRY_ATTEMPTS) {
            val jitter = ThreadLocalRandom.current().nextInt(0, 500)
            val backoffMs = (BASE_RETRY_DELAY_MS * (1L shl (currentRetries - 1))) + jitter
            connectionCooldowns[address] = System.currentTimeMillis() + backoffMs
            SecureLog.w(TAG, "Connection failed to $address ($reason). Scheduling retry $currentRetries/$MAX_RETRY_ATTEMPTS in ${backoffMs}ms")

            scope.launch {
                delay(backoffMs)
                if (!clientGattMap.containsKey(address) && !serverCentrals.containsKey(address)) {
                    SecureLog.d(TAG, "Executing scheduled retry $currentRetries for $address...")
                    connectToPeer(device, remotePeerId)
                }
            }
        } else {
            connectionCooldowns[address] = System.currentTimeMillis() + EXTENDED_COOLDOWN_MS
            SecureLog.w(TAG, "Connection to $address failed after $MAX_RETRY_ATTEMPTS attempts ($reason). Entering extended cooldown of ${EXTENDED_COOLDOWN_MS}ms")
            scope.launch {
                delay(EXTENDED_COOLDOWN_MS)
                retryCounts.remove(address)
            }
        }
    }

    /**
     * Clears Android's hidden internal GATT service cache using reflection on [BluetoothGatt.refresh].
     */
    fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            val success = refreshMethod.invoke(gatt) as? Boolean ?: false
            SecureLog.d(TAG, "GATT cache refresh for ${gatt.device?.address}: $success")
            success
        } catch (t: Throwable) {
            SecureLog.w(TAG, "GATT cache refresh reflection failed: ${t.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectPeer(address: String) {
        inFlightTimeouts.remove(address)?.cancel()
        inFlightConnections.remove(address)
        passiveWaitStarts.remove(address)
        retryCounts.remove(address)
        connectionCooldowns.remove(address)
        deviceQueues.remove(address)?.cancelAll("DISCONNECT_PEER")

        val client = clientGattMap.remove(address)
        if (client != null) {
            try {
                client.disconnect()
                client.close()
            } catch (_: Throwable) {}
        }
        val serverCentral = serverCentrals.remove(address)
        if (serverCentral != null) {
            try {
                gattServer?.cancelConnection(serverCentral)
            } catch (_: Throwable) {}
        }
        unregisterSink(address)
        connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)
    }

    fun stopAll() {
        stopGattServer()
        inFlightTimeouts.values.forEach { it.cancel() }
        inFlightTimeouts.clear()
        inFlightConnections.clear()
        passiveWaitStarts.clear()
        retryCounts.clear()
        connectionCooldowns.clear()
        deviceQueues.values.forEach { it.cancelAll("STOP_ALL") }
        deviceQueues.clear()
        clientGattMap.keys.toList().forEach { disconnectPeer(it) }
        activeSinks.clear()
        serverNotifyCompletions.clear()
        seenMsgLruCache.clear()
    }

    // ── Internal Helpers ─────────────────────────────────────────────────────

    private fun handleIncomingFrame(bytes: ByteArray, peerAddress: String) {
        scope.launch {
            try {
                val completeFrame = reassembler.processIncomingPacket(peerAddress, bytes) ?: return@launch
                if (completeFrame.size < BleConstants.HEADER_SIZE_BYTES) {
                    SecureLog.w(TAG, "Received frame shorter than header size from $peerAddress")
                    return@launch
                }

                val header = PacketHeader.decode(completeFrame, 0).getOrElse { err ->
                    SecureLog.w(TAG, "Failed to decode packet header from $peerAddress: ${err.message}")
                    return@launch
                }

                // Mesh Deduplication (STEP 3.2, NET-9)
                val isDuplicate = seenMsgLruCache.containsOrPut(header.msgId)
                if (isDuplicate) {
                    SecureLog.d(TAG, "Dropped duplicate packet ${header.msgId} from $peerAddress")
                    return@launch
                }

                val pipeline = receivePipeline ?: MeshService.sharedReceivePipeline
                val verdict = if (pipeline == null) {
                    SecureLog.w(TAG, "receivePipeline is null, dropping frame from $peerAddress")
                    null
                } else {
                    pipeline.processFrame(completeFrame)
                }

                // D-6 Eviction: On bad signature or auth failure, evict from deduplication cache
                if (verdict is PacketVerdict.DropBadSignature || verdict is PacketVerdict.DropAuthFail) {
                    seenMsgLruCache.evict(header.msgId)
                    return@launch
                }

                // Epidemic Flooding / Relaying with Jitter (STEP 3.2, NET-6, SEC-5)
                if (header.ttl > 1) {
                    val relayedFrame = completeFrame.clone()
                    relayedFrame[BleConstants.OFFSET_TTL] = (header.ttl - 1).toByte()

                    // STEP 3.2: 40ms - 100ms random delay before multi-hop relay to prevent radio collisions
                    val jitterMs = ThreadLocalRandom.current().nextLong(40L, 101L)
                    delay(jitterMs)

                    meshTransport.flood(relayedFrame, excludePeerId = peerAddress)
                    SecureLog.d(TAG, "Forwarded packet ${header.msgId} after ${jitterMs}ms jitter (relayed TTL: ${header.ttl - 1})")
                } else {
                    SecureLog.d(TAG, "Packet ${header.msgId} TTL expired for forwarding")
                }
            } catch (t: Throwable) {
                SecureLog.w(TAG, "Error in receivePipeline from $peerAddress: ${t.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerClientSink(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        queue: GattDeviceQueue
    ) {
        val address = gatt.device.address
        val existing = activeSinks[address]
        if (existing != null) {
            meshTransport.unregisterSink(existing)
        }

        val sink = object : PeerSink {
            override val peerId: String = address

            override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                return try {
                    val negotiatedMtu = negotiatedMtuMap[address] ?: 512
                    // STEP 2.4: If negotiated MTU is smaller than 505 bytes, slice into (negotiatedMtu - 3) chunks
                    val maxChunkSize = (negotiatedMtu - 3).coerceIn(20, 509)
                    val chunks = Chunker.chunk(frameBytes, maxChunkSize = maxChunkSize)
                    var allOk = true

                    for (chunk in chunks) {
                        val status = queue.writeCharacteristic(
                            characteristic,
                            chunk,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        )
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            allOk = false
                            break
                        }
                    }
                    allOk
                } catch (t: Throwable) {
                    SecureLog.w(TAG, "Client sink sendFrame failed to $address: ${t.message}")
                    false
                }
            }
        }

        activeSinks[address] = sink
        meshTransport.registerSink(sink)
        SecureLog.d(TAG, "Registered client PeerSink for $address (queue-backed)")
    }

    @SuppressLint("MissingPermission")
    private fun registerServerSink(device: BluetoothDevice) {
        val address = device.address
        val existing = activeSinks[address]
        if (existing != null) {
            meshTransport.unregisterSink(existing)
        }

        val char = gattCharacteristic ?: return
        val server = gattServer ?: return
        val mutex = writeLocks.getOrPut(address) { Mutex() }

        val sink = object : PeerSink {
            override val peerId: String = address

            override suspend fun sendFrame(frameBytes: ByteArray): Boolean = mutex.withLock {
                try {
                    val negotiatedMtu = negotiatedMtuMap[address] ?: 512
                    val maxChunkSize = (negotiatedMtu - 3).coerceIn(20, 509)
                    val chunks = Chunker.chunk(frameBytes, maxChunkSize = maxChunkSize)
                    var allOk = true

                    for (chunk in chunks) {
                        val deferred = CompletableDeferred<Int>()
                        serverNotifyCompletions[address] = deferred

                        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(
                                device,
                                char,
                                false,
                                chunk
                            ) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            char.value = chunk
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(device, char, false)
                        }

                        if (!ok) {
                            serverNotifyCompletions.remove(address)
                            allOk = false
                            break
                        }

                        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
                        serverNotifyCompletions.remove(address)
                        if (status == null || status != BluetoothGatt.GATT_SUCCESS) {
                            allOk = false
                            break
                        }
                    }
                    allOk
                } catch (t: Throwable) {
                    SecureLog.w(TAG, "Server sink sendFrame failed to $address: ${t.message}")
                    false
                }
            }
        }

        activeSinks[address] = sink
        meshTransport.registerSink(sink)
        SecureLog.d(TAG, "Registered server PeerSink for $address")
    }

    private fun unregisterSink(address: String) {
        val sink = activeSinks.remove(address)
        if (sink != null) {
            meshTransport.unregisterSink(sink)
        }
        writeLocks.remove(address)
        negotiatedMtuMap.remove(address)
        serverNotifyCompletions.remove(address)?.cancel()
    }
}
