package io.spotflow.ble.transport

import io.spotflow.ble.BleLink
import io.spotflow.ble.BleLinkException
import io.spotflow.ble.GattRef
import io.spotflow.ble.NotificationListener
import io.spotflow.ble.protocol.GattProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * A host's [BleLink] under the test's control: it records every call, answers reads from [values], and
 * holds calls at [gate] while one is set — as a host whose own operations keep its queue busy would.
 */
internal class FakeLink(
    override val address: String = "AA:BB:CC:DD:EE:FF",
    deviceId: String = "test-device",
) : BleLink {
    override var maxWritePayloadBytes: Int = 244

    /** What reading each characteristic returns. */
    val values = hashMapOf(
        GattProfile.CAPABILITIES to byteArrayOf(1, 1, 0),
        GattProfile.DEVICE_ID to deviceId.toByteArray(),
        GattProfile.SESSION_METADATA to "meta".toByteArray(),
    )

    /** Calls that acquired the GATT queue: "read <characteristic>", "write", "enable", "disable". */
    val calls = ArrayList<String>()

    /** Number of calls submitted to this adapter, including ones still waiting for its queue. */
    var submittedCalls = 0
        private set

    /** The values of every write, in order. */
    val writes = ArrayList<List<ByteArray>>()

    /** While set, every call waits for it before it does anything. */
    var gate: CompletableDeferred<Unit>? = null

    /** The next call fails with this. */
    var failNext: Throwable? = null

    /** Reading these characteristics fails with what they map to. */
    val failReads = HashMap<UUID, Throwable>()

    var listener: NotificationListener? = null
        private set

    /** Whether the device has TX Stream notifications on, as its CCCD says. */
    var notifying = false
        private set

    /** Calls cancelled while running: the gateway must leave a call it gave up on to settle. */
    var cancelledCalls = 0
        private set

    private val down = CompletableDeferred<Unit>()
    private val queue = Mutex()

    fun drop() {
        down.complete(Unit)
    }

    /** The device notifies [value] on the TX Stream, if notifications are on. */
    fun notify(value: ByteArray) {
        listener?.onValue(value)
    }

    override suspend fun awaitDisconnect() = down.await()

    private suspend fun <T> call(name: String, block: () -> T): T {
        submittedCalls++
        try {
            return queue.withLock {
                calls += name
                gate?.let { waiting -> select { down.onAwait {}; waiting.onAwait {} } }
                if (down.isCompleted) throw BleLinkException("device disconnected")
                failNext?.let { failNext = null; throw it }
                block()
            }
        } catch (c: CancellationException) {
            cancelledCalls++
            throw c
        }
    }

    override suspend fun read(ref: GattRef): ByteArray =
        call("read ${GattProfile.name(ref.characteristic)}") {
            failReads[ref.characteristic]?.let { throw it }
            values.getValue(ref.characteristic)
        }

    override suspend fun writeWithoutResponse(ref: GattRef, frames: List<ByteArray>) =
        call("write") { writes += frames }

    override suspend fun enableNotifications(ref: GattRef, listener: NotificationListener) {
        this.listener = listener
        call("enable") { notifying = true }
    }

    override suspend fun disableNotifications(ref: GattRef) = call("disable") { notifying = false }
}
