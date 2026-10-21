package com.p2chat.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.p2chat.data.db.AppDatabase
import com.p2chat.pipeline.ReceivePipeline
import com.p2chat.ui.MainActivity
import com.p2chat.util.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service running decentralized BLE advertising, scanning, and GATT routing (NET-13, T2.10).
 */
class MeshService : Service() {

    companion object {
        private const val TAG = "MeshService"
        const val CHANNEL_ID = "p2chat_mesh_channel"
        const val NOTIFICATION_ID = 4201

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _bleState = MutableStateFlow(BleState.READY)
        val bleState: StateFlow<BleState> = _bleState.asStateFlow()

        // Globally accessible instances for ViewModels
        var sharedTransport: MeshTransport = DefaultMeshTransport()
            private set
        var sharedScanner: MeshScanner? = null
            private set
        var sharedConnectionPool: ConnectionPool = DefaultConnectionPool()
            private set
        var sharedMeshController: MeshController = DefaultMeshController()
            private set
        var sharedGattManager: GattMeshManager? = null
            private set
        var sharedReceivePipeline: ReceivePipeline? = null

        fun start(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var advertiser: MeshAdvertiser? = null
    private var scanner: AndroidMeshScanner? = null
    private var gattManager: GattMeshManager? = null

    inner class LocalBinder : Binder() {
        val service: MeshService get() = this@MeshService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        SecureLog.d(TAG, "Creating MeshService...")
        createNotificationChannel()
        startAsForeground()

        val context = applicationContext
        val state = BlePermissions.getBleState(context)
        _bleState.value = state

        if (state != BleState.READY) {
            SecureLog.w(TAG, "Cannot initialize mesh: BLE state is $state")
            return
        }

        initializeMeshComponents(context)
        _isServiceRunning.value = true
        _bleState.value = BleState.READY
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val context = applicationContext
        val state = BlePermissions.getBleState(context)
        _bleState.value = state

        if (state == BleState.READY && advertiser == null) {
            initializeMeshComponents(context)
        }

        _isServiceRunning.value = true
        return START_STICKY
    }

    private fun initializeMeshComponents(context: Context) {
        val pool = sharedConnectionPool
        val transport = sharedTransport

        gattManager = GattMeshManager(
            context = context,
            connectionPool = pool,
            meshTransport = transport,
            receivePipeline = sharedReceivePipeline,
            scope = serviceScope
        )
        sharedGattManager = gattManager

        // Start GATT Server
        gattManager?.startGattServer()

        // Start Advertiser
        advertiser = MeshAdvertiser(context)
        advertiser?.startAdvertising()

        // Start Scanner with auto-connect
        scanner = AndroidMeshScanner(
            context = context,
            onPeerDiscovered = { peer, device ->
                gattManager?.connectToPeer(device)
            }
        )
        sharedScanner = scanner
        scanner?.startScan()

        SecureLog.d(TAG, "Mesh components successfully started (Advertiser + Scanner + GATT)")
    }

    private fun startAsForeground() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2Chat Mesh Active")
            .setContentText("Offline decentralized BLE routing")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "P2Chat BLE Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background BLE mesh routing and peer discovery"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        SecureLog.d(TAG, "Destroying MeshService...")
        _isServiceRunning.value = false
        _bleState.value = BleState.BLUETOOTH_OFF

        advertiser?.stopAdvertising()
        advertiser = null

        scanner?.stopScan()
        scanner = null

        gattManager?.stopAll()
        gattManager = null

        serviceScope.cancel()
        super.onDestroy()
    }
}
