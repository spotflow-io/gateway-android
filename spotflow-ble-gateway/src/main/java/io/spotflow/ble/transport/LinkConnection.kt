package io.spotflow.ble.transport

import android.bluetooth.BluetoothGatt
import io.spotflow.ble.BleLink
import io.spotflow.ble.BleLinkException
import io.spotflow.ble.GattRef
import io.spotflow.ble.NotificationListener
import io.spotflow.ble.Slog
import io.spotflow.ble.protocol.DeviceCapabilities
import io.spotflow.ble.protocol.FrameCodec
import io.spotflow.ble.protocol.GattProfile
import io.spotflow.ble.protocol.Message
import io.spotflow.ble.protocol.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The Spotflow BLE protocol over a [BleLink] — the core of every attachment, whether the link is the
 * host's own or a `GattBridge`'s. It frames outgoing messages, reassembles incoming ones, handles the
 * ACK/NACK of acknowledged messages, and times every call it makes. One connection serves one session
 * and is not reused.
 *
 * ## Calls
 * At most one link call is waited for at a time ([awaitDisconnect][BleLink.awaitDisconnect] aside). Each
 * is timed from when it is issued — so the time it spends behind the host's own operations counts — and
 * a call that times out ends the relay ([ConnectionState.FAILED]). Calls run in [scope], which outlives
 * the session, so a call abandoned on timeout keeps running; the link's queue runs it before the next
 * one, which may already have been issued (see the call order in [BleLink]).
 *
 * When the link drops, the pending call is no longer waited for and the state becomes
 * [ConnectionState.IDLE].
 */
internal class LinkConnection(
    override val deviceAddress: String,
    /** Where calls run; it outlives the session, so a call abandoned by it can still settle. */
    private val scope: CoroutineScope,
    /**
     * The host's link. It stays up after the session, so [close] turns the device's TX stream off: the
     * device would otherwise keep notifying with nobody listening.
     */
    private val link: BleLink,
) : BleConnection {

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    override val state: StateFlow<ConnectionState> = _state

    // Unbounded so a burst of large coredump chunks is never dropped while MQTT publishing catches up.
    // The buffer is drained by the single relay pump and is naturally bounded by the coredump size.
    private val incomingChannel = Channel<Message>(Channel.UNLIMITED)
    override val incoming: Flow<Message> = incomingChannel.receiveAsFlow()

    @Volatile private var failed: Throwable? = null
    override val failure: Throwable? get() = failed

    @Volatile private var prepared = false

    /** Completed once the link has dropped. */
    private val linkDown = CompletableDeferred<Unit>()
    override val linkLost: Boolean get() = linkDown.isCompleted
    private var disconnectWatch: Job? = null

    /** Whether TX Stream notifications were (asked to be) turned on, so [close] knows to turn them off. */
    @Volatile private var streamRequested = false
    @Volatile private var closed = false

    // Only what a device sends: anything else on the TX Stream is dropped on arrival.
    private val reassembler = FrameCodec.Reassembler { it.isFromDevice }

    /** One call at a time. */
    private val callLock = Mutex()

    /** A message sent with NEEDS_ACK, waiting for the device's ACK (true) or NACK (false). */
    private class PendingAck(val seq: Int) {
        val result = CompletableDeferred<Boolean>()
    }

    /** Keyed by message type: the protocol allows one unacknowledged message per type. */
    private val pendingAcks = ConcurrentHashMap<Int, PendingAck>()

    /** Sequence number of the next message, per type: (type, seq) identifies a message. */
    private val txSeq = IntArray(256)

    private fun nextSeq(type: MessageType): Int = synchronized(txSeq) { txSeq[type.value]++ and 0xFF }

    private val txListener = object : NotificationListener {
        override fun onValue(value: ByteArray) = onNotification(value)

        override fun onError(error: BleLinkException) {
            if (!closed) fail(error)
        }
    }

    override suspend fun prepare() {
        prepared = true
        _state.value = ConnectionState.PREPARING
        disconnectWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                link.awaitDisconnect()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(t)
                return@launch
            }
            onLinkDown()
        }
    }

    override suspend fun readCapabilities(): DeviceCapabilities =
        DeviceCapabilities.parse(read(GattProfile.CAPABILITIES_REF))

    override suspend fun readDeviceId(): String =
        GattProfile.decodeDeviceId(read(GattProfile.DEVICE_ID_REF))

    override suspend fun readIngestKey(): ByteArray = read(GattProfile.INGEST_KEY_REF)

    override suspend fun readSessionMetadata(): ByteArray = read(GattProfile.SESSION_METADATA_REF)

    private suspend fun read(ref: GattRef): ByteArray =
        call("read ${GattProfile.name(ref.characteristic)}", OP_TIMEOUT_MS) { read(ref) }

    /** Enables TX Stream notifications; messages start flowing on [incoming]. */
    override suspend fun startStreaming() {
        streamRequested = true
        call("enable notifications", OP_TIMEOUT_MS) { enableNotifications(GattProfile.TX_STREAM_REF, txListener) }
        // Only from PREPARING: if the link dropped just now, the state says so and must keep saying so,
        // or whoever waits for the session to end would wait forever.
        check(_state.compareAndSet(ConnectionState.PREPARING, ConnectionState.STREAMING)) {
            failed?.message ?: "device disconnected while its stream was being started"
        }
    }

    override suspend fun sendDesiredConfiguration(payload: ByteArray) =
        writeMessage(MessageType.DESIRED_CONFIGURATION, payload)

    override suspend fun sendOtaCommand(payload: ByteArray) = writeAcknowledged(MessageType.OTA_C2D, payload)

    override suspend fun sendArtifactResponse(payload: ByteArray) =
        writeMessage(MessageType.ARTIFACT_RESPONSE, payload)

    /** Fragments and writes one message of [type] to the RX Stream (best effort: no acknowledgement). */
    private suspend fun writeMessage(type: MessageType, payload: ByteArray) {
        writeFrames(FrameCodec.fragment(type, payload, nextSeq(type), frameSize()))
    }

    /**
     * Writes one message of [type] with NEEDS_ACK and suspends until the device acknowledges it. A NACK or
     * no answer within [ACK_TIMEOUT_MS] retransmits the complete message, with the same sequence number,
     * after a backoff — for as long as the link is up. Throws once it is not.
     */
    private suspend fun writeAcknowledged(type: MessageType, payload: ByteArray) {
        val seq = nextSeq(type)
        val frames = FrameCodec.fragment(type, payload, seq, frameSize(), needsAck = true)
        var backoff = ACK_RETRY_INITIAL_MS
        while (true) {
            val pending = PendingAck(seq)
            // Armed before the write, so a quick answer can't slip past.
            pendingAcks[type.value] = pending
            try {
                // The frames are on their way once this returns, so the wait for the answer starts now.
                writeFrames(frames)
                when (withTimeoutOrNull(ACK_TIMEOUT_MS) { pending.result.await() }) {
                    true -> return
                    false -> Slog.d(TAG, "device refused $type #$seq (NACK); retransmitting")
                    null -> Slog.d(TAG, "no acknowledgement of $type #$seq; retransmitting")
                }
            } finally {
                pendingAcks.remove(type.value, pending)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(ACK_RETRY_MAX_MS)
        }
    }

    private suspend fun writeFrames(frames: List<ByteArray>) =
        call("write", OP_TIMEOUT_MS + WRITE_TIMEOUT_PER_FRAME_MS * frames.size) {
            writeWithoutResponse(GattProfile.RX_STREAM_REF, frames)
        }

    /** The link's frame size, read for each message: the MTU may have changed since the last. */
    private fun frameSize(): Int = link.maxWritePayloadBytes

    /**
     * Issues one call on the link and waits for it — at most [timeoutMs], and only while the link is up.
     * On a timeout the connection fails; the call itself is left to settle.
     */
    private suspend fun <T> call(what: String, timeoutMs: Long, block: suspend BleLink.() -> T): T = callLock.withLock {
        check(prepared) { "not prepared" }
        if (linkDown.isCompleted || closed) throw IllegalStateException("device disconnected")
        failed?.let { throw it }
        // UNDISPATCHED: handed to the adapter right here, so calls reach it in the order they are issued.
        val result = scope.async(start = CoroutineStart.UNDISPATCHED) { link.block() }
        val settled = withTimeoutOrNull(timeoutMs) {
            select {
                linkDown.onJoin { false }
                result.onJoin { true }
            }
        }
        if (linkLost) throw IllegalStateException("device disconnected during $what")
        when (settled) {
            null -> throw IOException("$what timed out after ${timeoutMs / 1000} s").also { fail(it) }
            false -> throw IllegalStateException("device disconnected during $what")
            true -> try {
                result.await()
            } catch (e: BleLinkException) {
                val status = e.gattStatus ?: throw e
                throw BleLinkException("$what failed: ${describeGattStatus(status)}", status, e)
            }
        }
    }

    private fun onNotification(value: ByteArray) {
        if (closed) return
        val type = value.firstOrNull()?.toInt()?.and(0xFF)
        if (type == MessageType.ACK.value || type == MessageType.NACK.value) {
            // [type, acknowledged type, acknowledged seq]: completes the message waiting for it, if any.
            if (value.size >= 3) {
                val pending = pendingAcks[value[1].toInt() and 0xFF]
                if (pending != null && pending.seq == (value[2].toInt() and 0xFF)) {
                    pending.result.complete(type == MessageType.ACK.value)
                }
            }
            return
        }
        val message = reassembler.onFragment(value) ?: return
        if (message.payload.size >= LARGE_MESSAGE_BYTES) {
            // Large TELEMETRY messages are typically coredump chunks; log to aid diagnosis.
            Slog.d(TAG, "reassembled ${message.type} (${message.payload.size} bytes)")
        }
        incomingChannel.trySend(message)
    }

    private fun onLinkDown() {
        linkDown.complete(Unit)
        reassembler.reset()
        _state.value = ConnectionState.IDLE
        val cause = IllegalStateException("device disconnected")
        pendingAcks.values.forEach { it.result.completeExceptionally(cause) }
    }

    /** Ends the relay: the session sees [ConnectionState.FAILED] and [failure]. */
    private fun fail(cause: Throwable) {
        if (failed != null || linkLost) return
        failed = cause
        Slog.w(TAG, "link to $deviceAddress failed: ${cause.message}")
        _state.update { if (linkLost) ConnectionState.IDLE else ConnectionState.FAILED }
        pendingAcks.values.forEach { it.result.completeExceptionally(cause) }
    }

    /**
     * Ends the session's use of the link. First turns the device's TX stream off, waiting up to
     * [STOP_STREAMING_TIMEOUT_MS] for it; the write still runs, in order, if that wait ends first. Call
     * from a non-cancellable context.
     */
    override suspend fun close() {
        if (streamRequested && !linkDown.isCompleted) {
            // Issued at once, also when a call of this session is still pending: the adapter runs it after.
            val stopping = scope.async(start = CoroutineStart.UNDISPATCHED) {
                link.disableNotifications(GattProfile.TX_STREAM_REF)
            }
            val stopped = withTimeoutOrNull(STOP_STREAMING_TIMEOUT_MS) {
                select {
                    stopping.onJoin { !stopping.isCancelled && runCatching { stopping.await() }.isSuccess }
                    linkDown.onJoin { false }
                }
            }
            if (stopped != true) Slog.w(TAG, "could not turn the TX stream off before detaching")
        }
        closed = true
        disconnectWatch?.cancel()
        reassembler.reset()
        incomingChannel.close()
        _state.update { if (it == ConnectionState.FAILED) it else ConnectionState.IDLE }
    }

    companion object {
        private const val TAG = "SpotflowLink"
        private const val LARGE_MESSAGE_BYTES = 4096

        /** How long a read, or a change to notifications, may take. */
        const val OP_TIMEOUT_MS = GattProfile.READ_TIMEOUT_MS

        /**
         * What a write gets per frame on top of [OP_TIMEOUT_MS]: a 4096-byte artifact response at 20-byte
         * frames is about 240 frames, which take about 12 s at one frame per 50 ms connection interval.
         */
        const val WRITE_TIMEOUT_PER_FRAME_MS = 100L

        const val STOP_STREAMING_TIMEOUT_MS = 2_000L

        /** How long to wait for an ACK/NACK, and the backoff between retransmissions. */
        const val ACK_TIMEOUT_MS = 5_000L
        private const val ACK_RETRY_INITIAL_MS = 1_000L
        private const val ACK_RETRY_MAX_MS = 30_000L

        /** ATT `Insufficient Encryption Key Size`: the link's encryption is too weak (not in the public SDK). */
        private const val GATT_INSUFFICIENT_KEY_SIZE = 0x0C

        /** `GATT_AUTH_FAIL`: pairing failed or was declined (not in the public SDK). */
        private const val GATT_AUTH_FAIL = 0x89

        /**
         * Whether a failed operation's GATT status means the phone has to pair (bond) with the device. A
         * device can require an encrypted link for everything but its Capabilities (the Device SDK does by
         * default when it is built with OTA), and then answers an unpaired gateway with one of these.
         */
        fun needsPairing(status: Int): Boolean = when (status) {
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
            GATT_INSUFFICIENT_KEY_SIZE,
            GATT_AUTH_FAIL,
            -> true
            else -> false
        }

        /** A failed operation's GATT status, explained where the user has to act. */
        fun describeGattStatus(status: Int): String =
            if (needsPairing(status)) "$status, the device requires an encrypted link: pair (bond) it with this phone" else status.toString()
    }
}
