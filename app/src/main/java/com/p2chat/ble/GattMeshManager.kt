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
import android.content.Context
import android.os.Build
import com.p2chat.pipeline.ReceivePipeline
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages dual-role BLE GATT operations (GATT Server + GATT Client)
 * coordinating active links, MTU negotiation, and chunked packet routing (NET-1, NET-2, NET-3, T2.2, T2.6, T2.8).
 */
class GattMeshManager(
    private val context: Context,
    private val connectionPool: ConnectionPool,
    private val meshTransport: MeshTransport,
    private val receivePipeline: ReceivePipeline?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    companion object {
        private const val TAG = "GattMeshManager"
        private val SERVICE_UUID = UUID.fromString(BleConstants.SERVICE_UUID)
        private val CHAR_UUID = UUID.fromString(BleConstants.CHARACTERISTIC_UUID)
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
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
    private val reassembler = Reassembler()

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

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                SecureLog.d(TAG, "GATT Server: MTU changed to $mtu for ${device.address}")
            }
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
    fun connectToPeer(device: BluetoothDevice) {
        val address = device.address
        if (clientGattMap.containsKey(address) || serverCentrals.containsKey(address)) {
            return
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
                    connectionPool.setConnectionState(addr, PeerConnectionState.CONNECTED)

                    // Request 517-byte MTU (T2.8). If request fails, discover services directly.
                    if (!gatt.requestMtu(517)) {
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
                SecureLog.d(TAG, "GATT Client: MTU changed to $mtu for ${gatt.device.address} (status $status)")
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

                    // Enable CCCD descriptor
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
                    }

                    registerClientSink(gatt, characteristic)
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
                // Notifies write queue completion if needed
            }
        }

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, clientCallback)
            }
            clientGattMap[address] = gatt
        } catch (t: Throwable) {
            SecureLog.e(TAG, "Exception initiating connectGatt to $address: ${t.message}")
            connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)
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
    }

    // ── Internal Helpers ─────────────────────────────────────────────────────

    private fun handleIncomingFrame(bytes: ByteArray, peerAddress: String) {
        scope.launch {
            try {
                val completeFrame = reassembler.processIncomingPacket(peerAddress, bytes) ?: return@launch
                val pipeline = receivePipeline ?: MeshService.sharedReceivePipeline
                if (pipeline == null) {
                    SecureLog.w(TAG, "receivePipeline is null, dropping frame from $peerAddress")
                } else {
                    pipeline.processFrame(completeFrame)
                }
            } catch (t: Throwable) {
                SecureLog.w(TAG, "Error in receivePipeline from $peerAddress: ${t.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerClientSink(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        val mutex = writeLocks.getOrPut(address) { Mutex() }
        val sink = object : PeerSink {
            override val peerId: String = address

            override suspend fun sendFrame(frameBytes: ByteArray): Boolean = mutex.withLock {
                try {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    val chunks = Chunker.chunk(frameBytes, maxChunkSize = 480)
                    var allOk = true
                    for (chunk in chunks) {
                        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = chunk
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }
                        if (!ok) allOk = false
                        if (chunks.size > 1) {
                            delay(10)
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
        val char = gattCharacteristic ?: return
        val server = gattServer ?: return
        val mutex = writeLocks.getOrPut(address) { Mutex() }

        val sink = object : PeerSink {
            override val peerId: String = address

            override suspend fun sendFrame(frameBytes: ByteArray): Boolean = mutex.withLock {
                try {
                    val chunks = Chunker.chunk(frameBytes, maxChunkSize = 480)
                    var allOk = true
                    for (chunk in chunks) {
                        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(device, char, false, chunk) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            char.value = chunk
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(device, char, false)
                        }
                        if (!ok) allOk = false
                        if (chunks.size > 1) {
                            delay(10)
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
    }
}
