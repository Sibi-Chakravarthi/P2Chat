package com.p2chat.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active GATT client connections capped at MAX_ACTIVE_CONNECTIONS (4),
 * queuing remaining peers (NET-2, NET-14, T2.8).
 */
interface ConnectionPool {
    companion object {
        const val MAX_ACTIVE_CONNECTIONS = 4
    }

    val connectionStates: StateFlow<Map<String, PeerConnectionState>>

    fun setConnectionState(address: String, state: PeerConnectionState)
    fun getConnectionState(address: String): PeerConnectionState
    fun getActiveConnectionCount(): Int
    fun removeConnection(address: String)
    fun clear()
}

/**
 * Thread-safe default in-memory ConnectionPool implementation.
 */
class DefaultConnectionPool : ConnectionPool {
    private val statesMap = ConcurrentHashMap<String, PeerConnectionState>()
    private val _connectionStates = MutableStateFlow<Map<String, PeerConnectionState>>(emptyMap())
    override val connectionStates: StateFlow<Map<String, PeerConnectionState>> = _connectionStates.asStateFlow()

    override fun setConnectionState(address: String, state: PeerConnectionState) {
        val activeCount = getActiveConnectionCount()
        val finalState = if (state == PeerConnectionState.CONNECTING || state == PeerConnectionState.CONNECTED) {
            if (activeCount >= ConnectionPool.MAX_ACTIVE_CONNECTIONS && statesMap[address] != PeerConnectionState.CONNECTED) {
                PeerConnectionState.QUEUED
            } else {
                state
            }
        } else {
            state
        }

        statesMap[address] = finalState
        _connectionStates.value = statesMap.toMap()
    }

    override fun getConnectionState(address: String): PeerConnectionState {
        return statesMap[address] ?: PeerConnectionState.DISCONNECTED
    }

    override fun getActiveConnectionCount(): Int {
        return statesMap.values.count { it == PeerConnectionState.CONNECTED || it == PeerConnectionState.CONNECTING }
    }

    override fun removeConnection(address: String) {
        statesMap.remove(address)
        _connectionStates.value = statesMap.toMap()
    }

    override fun clear() {
        statesMap.clear()
        _connectionStates.value = emptyMap()
    }
}
