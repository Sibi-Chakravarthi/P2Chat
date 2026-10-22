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
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private var gattServer: BluetoothGattServer? = null
    private var gattCharacteristic: BluetoothGattCharacteristic? = null

    // Active connected centrals (server role) and active client connections (client role)
    private val serverCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val clientGattMap = ConcurrentHashMap<String, BluetoothGatt>()
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
                    serverCentrals[address] = device
                    connectionPool.setConnectionState(address, PeerConnectionState.CONNECTED)
                    registerServerSink(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    SecureLog.d(TAG, "GATT Server: Central $address disconnected")
                    serverCentrals.remove(address)
                    unregisterSink(address)
                    connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)
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
        if (clientGattMap.containsKey(address) || serverCentrals.containsKey(address)) {
            return
        }

        // Bug 2: Deterministic Tie-Breaking
        // If both nodes discover each other, only the node with the higher Peer ID initiates connectGatt().
        val myId = localPeerId
        if (myId != null && remotePeerId != null) {
            if (myId <= remotePeerId) {
                SecureLog.d(TAG, "Deterministic tie-break: local peer ID ($myId) <= remote ($remotePeerId); awaiting incoming GATT connection")
                return
            }
        }

        if (connectionPool.getActiveConnectionCount() >= ConnectionPool.MAX_ACTIVE_CONNECTIONS) {
            connectionPool.setConnectionState(address, PeerConnectionState.QUEUED)
            return
        }

        connectionPool.setConnectionState(address, PeerConnectionState.CONNECTING)
        SecureLog.d(TAG, "Connecting GATT client to $address...")

        val clientCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val addr = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    SecureLog.d(TAG, "GATT Client connected to $addr")
                    clientGattMap[addr] = gatt
                    refreshGattCache(gatt)

                    // Sequence step 1: Request 517 MTU before service discovery (Bug 4)
                    val requested = gatt.requestMtu(517)
                    if (!requested) {
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    SecureLog.d(TAG, "GATT Client disconnected from $addr")
                    clientGattMap.remove(addr)
                    unregisterSink(addr)
                    connectionPool.setConnectionState(addr, PeerConnectionState.DISCONNECTED)
                    try {
                        gatt.close()
                    } catch (_: Throwable) {}
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val addr = gatt.device.address
                negotiatedMtuMap[addr] = mtu
                SecureLog.d(TAG, "GATT Client: MTU changed to $mtu for $addr (status $status)")
                // Sequence step 2: Discover services after MTU change
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)

                if (characteristic != null) {
                    SecureLog.d(TAG, "GATT Client discovered P2Chat service on ${gatt.device.address}")
                    // Enable notifications locally
                    gatt.setCharacteristicNotification(characteristic, true)

                    // Sequence step 3: Write CCCD descriptor and await onDescriptorWrite before marking ready
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

            // Android 13+ (API 33+) callback
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
                // Bug 4: Complete the pending client write for this peer
                clientWriteCompletions[gatt.device.address]?.complete(status)
            }
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
            connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)
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
        val client = clientGattMap.remove(address)
        if (client != null) {
            try {
                client.disconnect()
                client.close()
            } catch (_: Throwable) {}
        }
        unregisterSink(address)
        connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)
    }

    fun stopAll() {
        stopGattServer()
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
        if (activeSinks.containsKey(address)) {
            return
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
        if (activeSinks.containsKey(address)) {
            return
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
