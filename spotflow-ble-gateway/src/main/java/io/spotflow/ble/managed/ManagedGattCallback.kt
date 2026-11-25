package io.spotflow.ble.managed

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import androidx.annotation.RequiresApi
import io.spotflow.ble.protocol.GattProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * The callback a [ManagedGateway] registers with `connectGatt`. It forwards every event to [target], a
 * `GattBridge`'s callback — exactly what the bridge asks of a host — and adds what is the managed layer's
 * own business as the GATT's owner: bringing the link up (the connection, service discovery, the MTU),
 * whether the connection was ever heard from, and the link parameters.
 *
 * One callback serves one connection attempt. Each step completes only after the bridge has seen the
 * event, so the bridge knows the MTU by the time the link is attached.
 */
internal class ManagedGattCallback(private val target: BluetoothGattCallback) : BluetoothGattCallback() {

    /** Whether Android reported anything about the connection: a link that came up, or one that failed. */
    @Volatile var heardFrom = false
        private set

    @Volatile private var priorityRequests = 0

    private val connected = CompletableDeferred<Unit>()
    private val disconnected = CompletableDeferred<IOException>()
    @Volatile private var discovery: CompletableDeferred<Unit>? = null
    @Volatile private var mtuExchange: CompletableDeferred<Unit>? = null

    /** Waits up to [timeoutMs] for the connection; fails if Android reports that it failed or dropped. */
    suspend fun awaitConnected(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { connected.await() }
            ?: throw IOException("not connected within ${timeoutMs / 1000} s")
        checkConnected()
    }

    /** Discovers services, and checks that the Spotflow service is there. */
    @SuppressLint("MissingPermission")
    suspend fun discoverServices(gatt: BluetoothGatt, timeoutMs: Long) {
        val done = CompletableDeferred<Unit>().also { discovery = it }
        checkConnected()
        check(gatt.discoverServices()) { "discoverServices() rejected" }
        withTimeoutOrNull(timeoutMs) { done.await() } ?: throw IOException("service discovery timed out")
        checkConnected()
        checkNotNull(gatt.getService(GattProfile.SERVICE)) { "Spotflow service not found on device" }
    }

    /**
     * Asks for [mtu] as the ATT MTU. A rejected request or failed exchange can use the current MTU.
     * If an accepted request never completes, don't attach: the GATT operation may still be in flight.
     */
    @SuppressLint("MissingPermission")
    suspend fun requestMtu(gatt: BluetoothGatt, mtu: Int, timeoutMs: Long) {
        val done = CompletableDeferred<Unit>().also { mtuExchange = it }
        checkConnected()
        if (!gatt.requestMtu(mtu)) {
            ManagedLog.w("MTU request rejected")
        } else {
            withTimeoutOrNull(timeoutMs) { done.await() } ?: throw IOException("MTU request timed out")
        }
        checkConnected()
    }

    private suspend fun checkConnected() {
        if (disconnected.isCompleted) throw disconnected.await()
    }

    /**
     * Ends this connection: disconnects and closes [gatt]. `close()` silences the callback, so the
     * attachment is told about the drop here — otherwise a session waiting on the GATT would wait forever.
     */
    @SuppressLint("MissingPermission")
    fun close(gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
        target.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        runCatching { gatt.close() }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        heardFrom = true
        if (newState == BluetoothProfile.STATE_CONNECTED) priorityRequests = 0
        target.onConnectionStateChange(gatt, status, newState)
        when {
            newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> connected.complete(Unit)
            newState == BluetoothProfile.STATE_CONNECTED || newState == BluetoothProfile.STATE_DISCONNECTED -> {
                val cause = IOException("connection failed or dropped, status=$status")
                disconnected.complete(cause)
                connected.completeExceptionally(cause)
                discovery?.completeExceptionally(cause)
                mtuExchange?.completeExceptionally(cause)
            }
        }
    }

    /**
     * Not in the public SDK (hidden since API 26), but the framework calls it on every link parameter
     * change, so a method with this exact signature receives it (no `override` keyword: the SDK stubs
     * don't declare it).
     *
     * Some peripherals request a very short supervision timeout (e.g. 420 ms). The link then drops
     * whenever the radio misses packets for that long, which happens constantly on a phone. Asking
     * Android for BALANCED priority renegotiates the link with Android's 5 s supervision timeout. Capped
     * per connection, so a peripheral that insists can't cause an endless renegotiation.
     */
    @Suppress("unused")
    fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        if (timeout >= MIN_SUPERVISION_TIMEOUT || priorityRequests >= MAX_PRIORITY_REQUESTS) return
        priorityRequests++
        ManagedLog.i("peripheral set a ${timeout * 10} ms supervision timeout; requesting a longer one")
        @SuppressLint("MissingPermission")
        val requested = runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
        if (requested.getOrNull() != true) ManagedLog.w("connection priority request rejected")
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        target.onServicesDiscovered(gatt, status)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            discovery?.complete(Unit)
        } else {
            discovery?.completeExceptionally(IOException("service discovery failed, status=$status"))
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        target.onMtuChanged(gatt, mtu, status)
        mtuExchange?.complete(Unit)
    }

    // Only called by Android 13+; older versions call the overload below.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) = target.onCharacteristicRead(gatt, characteristic, value, status)

    @Deprecated("Deprecated in API 33")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
        target.onCharacteristicRead(gatt, characteristic, status)

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
        target.onCharacteristicWrite(gatt, characteristic, status)

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
        target.onDescriptorWrite(gatt, descriptor, status)


    // Only called by Android 13+; older versions call the overload below.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
        target.onCharacteristicChanged(gatt, characteristic, value)

    @Deprecated("Deprecated in API 33")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
        target.onCharacteristicChanged(gatt, characteristic)

    private companion object {
        /**
         * Shortest supervision timeout tolerated from the peripheral, in 10 ms units (2 s). Shorter ones
         * drop the link on any brief radio gap (Wi-Fi coexistence, the phone in a pocket).
         */
        const val MIN_SUPERVISION_TIMEOUT = 200
        const val MAX_PRIORITY_REQUESTS = 3
    }
}
