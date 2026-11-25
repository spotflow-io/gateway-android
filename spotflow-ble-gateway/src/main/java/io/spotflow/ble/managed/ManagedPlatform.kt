package io.spotflow.ble.managed

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * What a [ManagedGateway] needs from Android, behind one seam: the Bluetooth adapter, the scanner and
 * opening GATT connections. Its own logic — when to connect, when to retry — sits on top of it and is
 * tested against a fake.
 */
internal interface ManagedPlatform {
    val isBluetoothEnabled: Boolean

    /** Starts watching the Bluetooth adapter. */
    fun start(onBluetoothOff: () -> Unit, onBluetoothOn: () -> Unit)

    fun stop()

    /**
     * Addresses of devices advertising the Spotflow service, for as long as the flow is collected. Fails
     * if the scan cannot be started or stops working.
     */
    fun scan(lowPower: Boolean): Flow<String>

    /**
     * Opens a GATT connection to [address] that reports to [callback]; `autoConnect` lets Android connect
     * whenever the device appears. Null if it cannot (e.g. Bluetooth was just turned off).
     */
    fun connectGatt(address: String, autoConnect: Boolean, callback: BluetoothGattCallback): BluetoothGatt?
}

internal class AndroidManagedPlatform(private val appContext: Context) : ManagedPlatform {

    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private var bluetoothReceiver: BroadcastReceiver? = null

    /**
     * Watches the Bluetooth adapter, so discovery stops while Bluetooth is off and resumes when it is
     * back — even with the screen off, since a managed gateway runs in a foreground service.
     */
    @Synchronized
    override fun start(onBluetoothOff: () -> Unit, onBluetoothOn: () -> Unit) {
        if (bluetoothReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                    BluetoothAdapter.STATE_ON -> onBluetoothOn()
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bluetoothReceiver = receiver
    }

    @Synchronized
    override fun stop() {
        bluetoothReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        bluetoothReceiver = null
    }

    override fun scan(lowPower: Boolean): Flow<String> {
        val adapter = adapter ?: return flow { throw IllegalStateException("this device has no Bluetooth") }
        return SpotflowScanner(adapter).scan(lowPower).map { it.address }
    }

    // The host is responsible for holding BLUETOOTH_CONNECT (documented in the README/manifest).
    @SuppressLint("MissingPermission")
    override fun connectGatt(address: String, autoConnect: Boolean, callback: BluetoothGattCallback): BluetoothGatt? {
        val device = adapter?.takeIf { it.isEnabled }?.getRemoteDevice(address) ?: return null
        return device.connectGatt(appContext, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }
}
