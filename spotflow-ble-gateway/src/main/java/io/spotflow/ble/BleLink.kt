package io.spotflow.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.UUID

/**
 * One connected, service-discovered BLE link to a Spotflow device, owned by the host app. Hand it to
 * [SpotflowGateway.attach] to relay the device over it.
 *
 * The host owns permissions, discovery, connection, MTU negotiation, reconnection and the GATT operation
 * queue. The gateway keeps the Spotflow BLE protocol (framing, reassembly, ACK/NACK, OTA and artifact
 * transfer) and the cloud side; through this interface it only issues raw GATT operations.
 *
 * ## Contract
 * - **Call order.** The gateway issues at most one call per link at a time (not counting
 *   [awaitDisconnect]) and waits for each to return or time out before issuing the next. The adapter
 *   serializes GATT operations per live connection, including host operations and replacement
 *   attachments. Operations run in issue order and settle. If the caller stops waiting or is cancelled,
 *   an operation already submitted to the stack must settle or the link must drop before the adapter
 *   releases the queue.
 * - **Failures** are reported as [BleLinkException], with the GATT status where there is one.
 * - **Ready when attached.** Attach a link once its connection is up, its services are discovered and
 *   any MTU exchange has finished: the gateway only uses the operations exposed here.
 * - **Disconnects.** When the link drops, [awaitDisconnect] returns and the adapter fails every call still
 *   pending. Attach a fresh link once the host has reconnected.
 *
 * For a `BluetoothGatt` the app holds itself, [GattBridge] provides the link.
 */
public interface BleLink {
    /**
     * The peripheral's Bluetooth address, as `BluetoothDevice.getAddress()` reports it. It is the key the
     * gateway uses to identify the peripheral.
     */
    public val address: String

    /**
     * The largest value one write carries: the ATT MTU minus 3, or 20 while the MTU is unknown. Read
     * before each message is framed, so an MTU exchange after attaching takes effect.
     */
    public val maxWritePayloadBytes: Int

    /** Returns once the link has dropped. Signal disconnect before failing any pending GATT operations. */
    public suspend fun awaitDisconnect()

    /** Reads the complete characteristic value, using a GATT long read if needed. Never log values. */
    public suspend fun read(ref: GattRef): ByteArray

    /**
     * Writes each of [frames] as one write without response, in order and without splitting any, and
     * returns once the last one has been handed to the stack — not merely queued behind the host's
     * operations. A value the stack rejects may be dropped without an error (the RX Stream is best
     * effort); it is not retried.
     */
    public suspend fun writeWithoutResponse(ref: GattRef, frames: List<ByteArray>)

    /**
     * Writes the characteristic's CCCD to enable notifications and returns once that write has completed.
     * Register [listener] before issuing the CCCD write; values received for this subscription are
     * delivered serially and in arrival order, even if they arrive before the write completes. A failed
     * CCCD write fails the call, with its GATT status.
     */
    public suspend fun enableNotifications(ref: GattRef, listener: NotificationListener)

    /**
     * Writes the characteristic's CCCD to disable notifications and returns once that write has completed.
     * Leaves the host's subscriptions on other characteristics alone.
     */
    public suspend fun disableNotifications(ref: GattRef)
}

/** Receives the notifications of one characteristic; see [BleLink.enableNotifications]. */
public interface NotificationListener {
    /** A notified value. Must not block. */
    public fun onValue(value: ByteArray)

    /** The subscription failed; the attachment ends. */
    public fun onError(error: BleLinkException)
}

/** A characteristic, by its service and its own UUID. */
public data class GattRef(val service: UUID, val characteristic: UUID)

/** A [BleLink] operation failed. [gattStatus] is the GATT status, where the stack reported one. */
public open class BleLinkException @JvmOverloads constructor(
    message: String,
    public val gattStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * A device relayed over a host-owned link; returned by [SpotflowGateway.attach].
 */
public class Attachment internal constructor(
    /** The peripheral's Bluetooth address. */
    public val address: String,
    private val detacher: (Attachment) -> Unit,
) {
    private val _state = MutableStateFlow<AttachmentState>(AttachmentState.Starting)

    /** Lifecycle of this attachment only. The final value is retained after cleanup. */
    public val state: StateFlow<AttachmentState> = _state.asStateFlow()

    /**
     * Waits for the relay session to end and returns why. The gateway waits up to 2 s for best-effort
     * TX notification cleanup; this does not guarantee notifications are off or the link is disconnected.
     * Cancelling the waiter does not detach the session.
     */
    public suspend fun awaitEnd(): AttachmentEnd = state.filterIsInstance<AttachmentState.Ended>().first().result

    /**
     * Stops relaying this attachment — not a newer one for the same address — and turns the device's TX
     * Stream notifications off if possible. Returns at once; use [awaitEnd] for the outcome. The host
     * owns the link, and data already buffered is still uploaded.
     */
    public fun detach() {
        detacher(this)
    }

    internal fun ready(deviceId: String) {
        _state.compareAndSet(AttachmentState.Starting, AttachmentState.Ready(deviceId))
    }

    /** Records how the attachment ended after cleanup. */
    internal fun finish(end: AttachmentEnd) {
        _state.value = AttachmentState.Ended(end)
    }
}

/** Lifecycle of one Spotflow relay, independent of the host's BLE connection and MQTT. */
public sealed interface AttachmentState {
    /** Waiting to set up the relay, or setting up Spotflow notifications. */
    public data object Starting : AttachmentState

    /** The gateway can receive and buffer messages. MQTT may still be disconnected. */
    @ConsistentCopyVisibility
    public data class Ready internal constructor(val deviceId: String) : AttachmentState

    /** This attachment is finished. Buffered uploads may still continue. */
    @ConsistentCopyVisibility
    public data class Ended internal constructor(val result: AttachmentEnd) : AttachmentState
}

/** How an [Attachment] ended. */
@ConsistentCopyVisibility
public data class AttachmentEnd internal constructor(val reason: Reason, val error: DeviceError? = null) {
    public enum class Reason {
        /** The host called [Attachment.detach] or shut the gateway down. */
        DETACHED,

        /** The same peripheral was attached again; the new attachment took over. */
        REPLACED,

        /** The link dropped ([BleLink.awaitDisconnect] returned), or Bluetooth was turned off. */
        DISCONNECTED,

        /**
         * A call failed or timed out, or the device or the cloud refused the session; [error] says which.
         */
        FAILED,
    }
}
