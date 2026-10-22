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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
        private const val WRITE_TIMEOUT_MS = 2000L
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

    // Active in-flight connection tracking to prevent redundant connectGatt storms (Issue 2)
    private val inFlightConnections = ConcurrentHashMap<String, Long>()
    private val inFlightTimeouts = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Resilient connection retry and exponential backoff tracking (Issue 4)
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private val connectionCooldowns = ConcurrentHashMap<String, Long>()

    // Tie-breaker passive wait tracking with timeout fallback (Issue 3)
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
     * Determines whether local node has tie-breaker priority (Higher Peer ID) over remote node.
     * The node with the Higher Peer ID maintains its GATT Client connection.
     * The node with the Lower Peer ID yields its Client connection and maintains its GATT Server role.
     */
    fun isLocalTieBreakerHigher(address: String, remotePeerIdHint: Long? = null): Boolean {
        val myId = localPeerId
        val remoteId = remotePeerIdHint
            ?: remotePeerIds[address]
            ?: MeshService.sharedScanner?.discoveredPeers?.value?.firstOrNull { it.address == address }?.senderFp

        if (remoteId != null) {
            remotePeerIds[address] = remoteId
        }

        return if (myId != null && remoteId != null) {
            myId > remoteId
        } else if (myId != null) {
            true
        } else {
            false
        }
    }
    private val activeSinks = ConcurrentHashMap<String, PeerSink>()
    private val writeLocks = ConcurrentHashMap<String, Mutex>()
    private val negotiatedMtuMap = ConcurrentHashMap<String, Int>()

    // Pending sequential write/notify completions per peer
    private val clientWriteCompletions = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val serverNotifyCompletions = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    // LRU deduplication cache: msg_id -> seen
    private val seenMsgIds = Collections.synchronizedSet(LinkedHashSet<Long>(MAX_SEEN_CACHE_SIZE))

    private val reassembler = Reassembler()

    fun evictSeenPacket(msgId: Long) {
        synchronized(seenMsgIds) {
            seenMsgIds.remove(msgId)
        }
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
                // Bug 5: Always respond immediately if responseNeeded to avoid hanging the client
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
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, descriptor.value ?: ByteArray(0))
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                // Bug 4: Complete the pending server notification for this peer
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

        // 2. Guard against in-flight connection storm (Issue 2)
        if (inFlightConnections.containsKey(address)) {
            SecureLog.d(TAG, "Connection already in-flight for $address; skipping redundant connect")
            return
        }

        // 3. Guard against cooldown period after failed connections (Issue 4)
        val cooldownUntil = connectionCooldowns[address] ?: 0L
        if (now < cooldownUntil) {
            SecureLog.d(TAG, "Connection to $address in cooldown for another ${cooldownUntil - now}ms; skipping")
            return
        }

        // 4. Deterministic Tie-Breaking with Passive Wait Timeout Fallback (Issue 3)
        val myId = localPeerId
        val targetPeerId = remotePeerId ?: remotePeerIds[address]
        if (myId != null && targetPeerId != null) {
            if (myId <= targetPeerId) {
                val waitStart = passiveWaitStarts.getOrPut(address) { now }
                val elapsed = now - waitStart
                if (elapsed < PASSIVE_WAIT_TIMEOUT_MS) {
                    SecureLog.d(TAG, "Deterministic tie-break: local peer ID ($myId) <= remote ($targetPeerId); awaiting incoming GATT connection (elapsed: ${elapsed}ms)")
                    return
                } else {
                    SecureLog.w(TAG, "Tie-break passive wait timed out for $address (${elapsed}ms >= ${PASSIVE_WAIT_TIMEOUT_MS}ms); actively falling back to initiate connection")
                }
            } else {
                passiveWaitStarts.remove(address)
            }
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

        // 6. Schedule connection timeout (Issue 2)
        val timeoutJob = scope.launch {
            kotlinx.coroutines.delay(CONNECTION_TIMEOUT_MS)
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
                        // Lower Peer ID yields its Client connection in favor of existing Server connection
                        SecureLog.d(TAG, "Duplicate connection on client: local peer is lower. Yielding client connection to $addr, preserving server connection.")
                        try {
                            gatt.disconnect()
                            gatt.close()
                        } catch (_: Throwable) {}
                        return
                    }

                    if (hasServer && isHigher) {
                        // Higher Peer ID keeps Client connection and terminates duplicate Server connection
                        SecureLog.d(TAG, "Duplicate connection on client: local peer is higher. Keeping client connection to $addr, closing duplicate server connection.")
                        val serverDevice = serverCentrals.remove(addr)
                        if (serverDevice != null) {
                            try {
                                gattServer?.cancelConnection(serverDevice)
                            } catch (_: Throwable) {}
                        }
                    }

                    clientGattMap[addr] = gatt
                    refreshGattCache(gatt)

                    val requested = gatt.requestMtu(517)
                    if (!requested) {
                        gatt.discoverServices()
                    }
                } else {
                    SecureLog.w(TAG, "GATT Client disconnected/failed for $addr (status: $status, newState: $newState)")
                    clientGattMap.remove(addr)
                    try {
                        gatt.disconnect()
                        gatt.close()
                    } catch (_: Throwable) {}

                    // Only unregister sink and update pool if NO active Server connection exists
                    if (!serverCentrals.containsKey(addr)) {
                        unregisterSink(addr)
                        connectionPool.setConnectionState(addr, PeerConnectionState.DISCONNECTED)

                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            handleConnectionFailure(gatt.device, addr, remotePeerId, "GATT_STATUS_$status")
                        }
                    } else {
                        SecureLog.d(TAG, "Client disconnected from $addr, but Server connection remains active. Preserving link.")
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val addr = gatt.device.address
                negotiatedMtuMap[addr] = mtu
                SecureLog.d(TAG, "GATT Client: MTU changed to $mtu for $addr (status $status)")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    handleConnectionFailure(gatt.device, gatt.device.address, remotePeerId, "SERVICE_DISCOVERY_STATUS_$status")
                    return
                }
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)

                if (characteristic != null) {
                    SecureLog.d(TAG, "GATT Client discovered P2Chat service on ${gatt.device.address}")
                    gatt.setCharacteristicNotification(characteristic, true)

                    val cccd = characteristic.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    } else {
                        registerClientSink(gatt, characteristic)
                        connectionPool.setConnectionState(gatt.device.address, PeerConnectionState.CONNECTED)
                    }
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                    val addr = gatt.device.address
                    val char = descriptor.characteristic ?: return
                    SecureLog.d(TAG, "CCCD descriptor written successfully; client sink ready for $addr")
                    registerClientSink(gatt, char)
                    connectionPool.setConnectionState(addr, PeerConnectionState.CONNECTED)
                }
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

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                clientWriteCompletions[gatt.device.address]?.complete(status)
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
        val gatt = clientGattMap.remove(address)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Throwable) {}
        unregisterSink(address)
        connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)

        val currentRetries = retryCounts.compute(address) { _, count -> (count ?: 0) + 1 } ?: 1

        if (currentRetries <= MAX_RETRY_ATTEMPTS) {
            val jitter = (0..500).random()
            val backoffMs = (BASE_RETRY_DELAY_MS * (1L shl (currentRetries - 1))) + jitter
            connectionCooldowns[address] = System.currentTimeMillis() + backoffMs
            SecureLog.w(TAG, "Connection failed to $address ($reason). Scheduling retry $currentRetries/$MAX_RETRY_ATTEMPTS in ${backoffMs}ms")

            scope.launch {
                kotlinx.coroutines.delay(backoffMs)
                if (!clientGattMap.containsKey(address) && !serverCentrals.containsKey(address)) {
                    SecureLog.d(TAG, "Executing scheduled retry $currentRetries for $address...")
                    connectToPeer(device, remotePeerId)
                }
            }
        } else {
            connectionCooldowns[address] = System.currentTimeMillis() + EXTENDED_COOLDOWN_MS
            SecureLog.w(TAG, "Connection to $address failed after $MAX_RETRY_ATTEMPTS attempts ($reason). Entering extended cooldown of ${EXTENDED_COOLDOWN_MS}ms")
            scope.launch {
                kotlinx.coroutines.delay(EXTENDED_COOLDOWN_MS)
                retryCounts.remove(address)
            }
        }
    }

    /**
     * Clears Android's hidden internal GATT service cache using reflection on [BluetoothGatt.refresh] (Phase 3).
     * Prevents the OS from caching outdated or stale GATT service/characteristic tables across connections.
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
        clientGattMap.keys.toList().forEach { disconnectPeer(it) }
        activeSinks.clear()
        clientWriteCompletions.clear()
        serverNotifyCompletions.clear()
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

                // Mesh Deduplication (Bug 6, NET-9)
                val isDuplicate = synchronized(seenMsgIds) {
                    if (seenMsgIds.contains(header.msgId)) {
                        true
                    } else {
                        if (seenMsgIds.size >= MAX_SEEN_CACHE_SIZE) {
                            val iter = seenMsgIds.iterator()
                            if (iter.hasNext()) {
                                iter.next()
                                iter.remove()
                            }
                        }
                        seenMsgIds.add(header.msgId)
                        false
                    }
                }
                if (isDuplicate) {
                    return@launch
                }

                val pipeline = receivePipeline ?: MeshService.sharedReceivePipeline
                val verdict = if (pipeline == null) {
                    SecureLog.w(TAG, "receivePipeline is null, dropping frame from $peerAddress")
                    null
                } else {
                    pipeline.processFrame(completeFrame)
                }

                // D-6 Eviction: On bad signature or auth failure, evict from seenMsgIds
                if (verdict is PacketVerdict.DropBadSignature || verdict is PacketVerdict.DropAuthFail) {
                    synchronized(seenMsgIds) { seenMsgIds.remove(header.msgId) }
                    return@launch
                }

                // Forwarding / Relaying (Bug 6, NET-6, SEC-5)
                // Epidemic flooding forwards a new packet to every connected peer except the one it arrived from.
                if (header.ttl > 1) {
                    val relayedFrame = completeFrame.clone()
                    relayedFrame[BleConstants.OFFSET_TTL] = (header.ttl - 1).toByte()
                    meshTransport.flood(relayedFrame, excludePeerId = peerAddress)
                    SecureLog.d(TAG, "Forwarded packet ${header.msgId} (relayed TTL: ${header.ttl - 1})")
                } else {
                    SecureLog.d(TAG, "Packet ${header.msgId} TTL expired for forwarding")
                }
            } catch (t: Throwable) {
                SecureLog.w(TAG, "Error in receivePipeline from $peerAddress: ${t.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerClientSink(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        val existing = activeSinks[address]
        if (existing != null) {
            meshTransport.unregisterSink(existing)
        }

        val mutex = writeLocks.getOrPut(address) { Mutex() }
        val sink = object : PeerSink {
            override val peerId: String = address

            override suspend fun sendFrame(frameBytes: ByteArray): Boolean = mutex.withLock {
                try {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    val negotiatedMtu = negotiatedMtuMap[address] ?: 517
                    val maxChunkSize = (negotiatedMtu - 3).coerceIn(20, 480)
                    val chunks = Chunker.chunk(frameBytes, maxChunkSize = maxChunkSize)
                    var allOk = true

                    for (chunk in chunks) {
                        val deferred = CompletableDeferred<Int>()
                        clientWriteCompletions[address] = deferred

                        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(
                                characteristic,
                                chunk,
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            ) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = chunk
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }

                        if (!ok) {
                            clientWriteCompletions.remove(address)
                            allOk = false
                            break
                        }

                        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
                        clientWriteCompletions.remove(address)
                        if (status != null && status != BluetoothGatt.GATT_SUCCESS) {
                            allOk = false
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
        SecureLog.d(TAG, "Registered client PeerSink for $address")
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
                    val negotiatedMtu = negotiatedMtuMap[address] ?: 517
                    val maxChunkSize = (negotiatedMtu - 3).coerceIn(20, 480)
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
                        if (status != null && status != BluetoothGatt.GATT_SUCCESS) {
                            allOk = false
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
        clientWriteCompletions.remove(address)?.cancel()
        serverNotifyCompletions.remove(address)?.cancel()
    }
}
