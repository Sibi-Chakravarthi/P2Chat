package com.p2chat.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller interface for starting, stopping, and inspecting the BLE mesh foreground service (NET-13, T2.10).
 */
interface MeshController {
    val isMeshActive: StateFlow<Boolean>
    val isBeaconBroadcasting: StateFlow<Boolean>

    fun startMesh()
    fun stopMesh()
    fun setBeaconBroadcasting(enabled: Boolean)
}

/**
 * Default in-memory MeshController implementation.
 */
class DefaultMeshController(
    initialMeshActive: Boolean = true,
    initialBeaconActive: Boolean = true
) : MeshController {
    private val _isMeshActive = MutableStateFlow(initialMeshActive)
    override val isMeshActive: StateFlow<Boolean> = _isMeshActive.asStateFlow()

    private val _isBeaconBroadcasting = MutableStateFlow(initialBeaconActive)
    override val isBeaconBroadcasting: StateFlow<Boolean> = _isBeaconBroadcasting.asStateFlow()

    override fun startMesh() {
        _isMeshActive.value = true
    }

    override fun stopMesh() {
        _isMeshActive.value = false
    }

    override fun setBeaconBroadcasting(enabled: Boolean) {
        _isBeaconBroadcasting.value = enabled
    }
}
