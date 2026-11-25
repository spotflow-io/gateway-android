package io.spotflow.ble.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import io.spotflow.ble.BleLink
import io.spotflow.ble.BleLinkException
import io.spotflow.ble.GattRef
import io.spotflow.ble.NotificationListener
import io.spotflow.ble.protocol.FrameCodec
import io.spotflow.ble.protocol.GattProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Android GATT mechanics of one host-owned `BluetoothGatt` — the [BleLink] adapter behind
 * `GattBridge` ([link]).
 *
 * The host connects the GATT, discovers its services and negotiates its MTU; this client follows the
 * connection and the MTU from the events it receives, and only reads, writes and subscribes. It serves one
 * GATT: once [bind] has named it, events of any other GATT are ignored.
 *
 * It owns the GATT's [callback] and its operation queue: Android allows one outstanding GATT operation
 * at a time, so every operation — the gateway's, and the host's inside [exclusive] — waits its turn, first
 * come first served. An operation holds the queue until its callback arrives or the link drops, also when
 * whoever issued it has stopped waiting, so the next one is never rejected as busy. The queue belongs to
 * the GATT, not to one link: it orders the operations of successive links on it.
 *
 * As a second line of defence against a host that forwards its own operations' callbacks, a callback
 * only completes the operation it answers: a result for another characteristic is ignored.
 */
internal class GattClient {
    private enum class Kind { READ, WRITE, DESCRIPTOR }

    /** The outstanding operation, completed by the callback that answers it. */
    private class Pending(val kind: Kind, val uuid: UUID? = null) {
        val result = CompletableDeferred<Any?>()
    }

    private val queue = Mutex()
    @Volatile private var pending: Pending? = null

    // Written by coroutines and read on binder threads (and the other way round).
    @Volatile var gatt: BluetoothGatt? = null
        private set

    /** Completed when the link drops; replaced when it comes back up. Up until told otherwise. */
    @Volatile private var linkDown = CompletableDeferred<Unit>()

    /** The connection's ATT MTU, as last negotiated; the minimum until an exchange is seen. */
    @Volatile var mtu: Int = FrameCodec.MIN_MTU

    /** Who listens to a characteristic's notifications: the link that subscribed, and its listener. */
    private class Subscription(val owner: BleLink, val listener: NotificationListener)

    private val subscriptions = ConcurrentHashMap<UUID, Subscription>()

    /** Names the GATT this client serves. */
    @Synchronized
    fun bind(gatt: BluetoothGatt) {
        val bound = this.gatt
        require(bound == null || bound === gatt) { "this bridge serves another BluetoothGatt: use one bridge per GATT" }
        this.gatt = gatt
    }

    /** Whether an event of [gatt] is this client's: all are, until [bind] names its GATT. */
    private fun ours(gatt: BluetoothGatt): Boolean = this.gatt.let { it == null || it === gatt }

    /** The callback the host registers, or forwards its GATT's events to. */
    val callback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!ours(gatt)) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (status == BluetoothGatt.GATT_SUCCESS) {
                    // A new connection: its MTU starts from the minimum again.
                    mtu = FrameCodec.MIN_MTU
                    if (linkDown.isCompleted) linkDown = CompletableDeferred()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    subscriptions.clear()
                    linkDown.complete(Unit)
                    pending?.result?.completeExceptionally(BleLinkException("device disconnected, status=$status"))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (ours(gatt) && status == BluetoothGatt.GATT_SUCCESS) this@GattClient.mtu = mtu
        }

        // API 33+ delivers the value directly.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (ours(gatt)) completeRead(characteristic.uuid, value, status)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (ours(gatt) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                completeRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!ours(gatt)) return
            complete(Kind.WRITE, characteristic.uuid, status, Unit) { "characteristic write failed: $it" }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!ours(gatt) || descriptor.uuid != GattProfile.CCCD) return
            val characteristic = descriptor.characteristic?.uuid ?: return
            complete(Kind.DESCRIPTOR, characteristic, status, Unit) { "descriptor write failed: $it" }
        }

        // API 33+ notification signature.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ours(gatt)) onNotification(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (ours(gatt) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
            }
        }
    }

    private fun onNotification(uuid: UUID, value: ByteArray) {
        subscriptions[uuid]?.listener?.onValue(value.copyOf())
    }

    /** Completes the pending read — if this result is for the characteristic it asked for. */
    private fun completeRead(uuid: UUID, value: ByteArray, status: Int) {
        complete(Kind.READ, uuid, status, value.copyOf()) { "${GattProfile.name(uuid)} read failed: $it" }
    }

    /** Completes the outstanding operation if it is of [kind] (and on [uuid], where it has one). */
    private inline fun complete(kind: Kind, uuid: UUID?, status: Int, value: Any?, failure: (Int) -> String) {
        val op = pending?.takeIf { it.kind == kind && (it.uuid == null || it.uuid == uuid) } ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            op.result.complete(value)
        } else {
            op.result.completeExceptionally(BleLinkException(failure(status), status))
        }
    }

    // ---- the queue -------------------------------------------------------------------------------

    /**
     * Runs [block] holding the queue. If [block] is cancelled while an operation of it is outstanding, the
     * queue stays held until that operation is answered or the link drops.
     */
    private suspend fun <T> queued(block: suspend () -> T): T {
        queue.lock()
        try {
            return block()
        } finally {
            val outstanding = pending
            if (outstanding == null) {
                queue.unlock()
            } else {
                outstanding.result.invokeOnCompletion {
                    if (pending === outstanding) pending = null
                    queue.unlock()
                }
            }
        }
    }

    /** Issues one operation and awaits its callback. Call holding the queue. */
    private suspend fun operation(kind: Kind, uuid: UUID? = null, issue: (BluetoothGatt) -> Unit): Any? {
        val g = requireGatt()
        val op = Pending(kind, uuid)
        pending = op
        val result = try {
            issue(g)
            op.result.await()
        } catch (c: CancellationException) {
            // Still outstanding: queued() keeps the queue until it is answered.
            if (op.result.isCompleted) pending = null
            throw c
        } catch (t: Throwable) {
            pending = null
            throw t
        }
        pending = null
        return result
    }

    /**
     * Runs [block] holding the GATT operation queue, so operations the host issues inside it cannot
     * overlap with the gateway's own.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        queue.lock()
        try {
            return block()
        } finally {
            queue.unlock()
        }
    }

    // ---- the link --------------------------------------------------------------------------------

    /** A [BleLink] over this GATT for as long as its current connection is up. */
    fun link(): BleLink = GattLink(requireGatt().device.address, linkDown)

    private inner class GattLink(override val address: String, private val down: Deferred<Unit>) : BleLink {

        override val maxWritePayloadBytes: Int get() = mtu - FrameCodec.ATT_OVERHEAD

        override suspend fun awaitDisconnect() = down.await()

        private fun ensureUp() {
            if (down.isCompleted) throw BleLinkException("device disconnected")
        }

        @SuppressLint("MissingPermission")
        override suspend fun read(ref: GattRef): ByteArray = queued {
            ensureUp()
            operation(Kind.READ, ref.characteristic) { g ->
                check(g.readCharacteristic(characteristic(g, ref))) { "readCharacteristic(${GattProfile.name(ref.characteristic)}) rejected" }
            } as ByteArray
        }

        override suspend fun writeWithoutResponse(ref: GattRef, frames: List<ByteArray>) = queued {
            for (value in frames) {
                ensureUp()
                operation(Kind.WRITE, ref.characteristic) { g -> writeNoResponse(g, characteristic(g, ref), value) }
            }
        }

        override suspend fun enableNotifications(ref: GattRef, listener: NotificationListener) {
            val subscription = Subscription(this, listener)
            try {
                queued {
                    ensureUp()
                    // Install after taking the queue, before the device can start sending for this session.
                    subscriptions[ref.characteristic] = subscription
                    writeCccd(ref, enable = true)
                }
            } catch (t: Throwable) {
                subscriptions.remove(ref.characteristic, subscription)
                throw t
            }
        }

        override suspend fun disableNotifications(ref: GattRef) {
            try {
                queued {
                    ensureUp()
                    writeCccd(ref, enable = false)
                }
            } finally {
                // Only this link's: a newer session on the GATT may have subscribed meanwhile.
                subscriptions[ref.characteristic]?.takeIf { it.owner === this }?.let { subscriptions.remove(ref.characteristic, it) }
            }
        }

    }

    /** Turns notifications of [ref] on or off, locally and on the device (its CCCD). Call holding the queue. */
    @SuppressLint("MissingPermission")
    private suspend fun writeCccd(ref: GattRef, enable: Boolean) {
        operation(Kind.DESCRIPTOR, ref.characteristic) { g ->
            val characteristic = characteristic(g, ref)
            check(g.setCharacteristicNotification(characteristic, enable)) { "setCharacteristicNotification rejected" }
            val cccd = characteristic.getDescriptor(GattProfile.CCCD)
                ?: error("${GattProfile.name(ref.characteristic)} is missing its CCCD descriptor")
            val value = if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = g.writeDescriptor(cccd, value)
                check(status == BluetoothStatusCodes.SUCCESS) { "writeDescriptor rejected: $status" }
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    check(g.writeDescriptor(cccd)) { "writeDescriptor rejected" }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNoResponse(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            check(status == BluetoothStatusCodes.SUCCESS) { "writeCharacteristic rejected: $status" }
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = value
                check(g.writeCharacteristic(ch)) { "writeCharacteristic rejected" }
            }
        }
    }

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw IllegalStateException("no GATT attached")

    private fun characteristic(g: BluetoothGatt, ref: GattRef): BluetoothGattCharacteristic =
        g.getService(ref.service)?.getCharacteristic(ref.characteristic)
            ?: error("characteristic ${GattProfile.name(ref.characteristic)} not found")
}
