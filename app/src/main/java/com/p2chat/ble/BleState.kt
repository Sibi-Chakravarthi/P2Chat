package com.p2chat.ble

/**
 * Hardware and permission state for BLE mesh operations (ID-6, NET-1).
 */
enum class BleState {
    /**
     * Bluetooth is powered on, advertiser and scanner are available, and all permissions granted.
     */
    READY,

    /**
     * Bluetooth radio is powered off.
     */
    BLUETOOTH_OFF,

    /**
     * Required BLE runtime permissions (BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT) denied.
     */
    PERMISSIONS_DENIED,

    /**
     * Device lacks Bluetooth LE hardware support.
     */
    UNSUPPORTED
}
