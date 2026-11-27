package io.spotflow.reactnative

import io.spotflow.ble.BleLink
import io.spotflow.ble.BleLinkException
import io.spotflow.ble.GattRef
import io.spotflow.ble.NotificationListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A GATT operation the core asks the JS driver to perform. */
internal enum class LinkOp(val wireName: String) {
    READ("read"),
    WRITE("writeWithoutResponse"),
    ENABLE_NOTIFICATIONS("enableNotifications"),
    DISABLE_NOTIFICATIONS("disableNotifications"),
}

/** One request sent to JS. [frames] is empty except for [LinkOp.WRITE]. */
internal class LinkRequest(
    val linkId: Int,
    val requestId: Int,
    val op: LinkOp,
    val ref: GattRef,
    val frames: List<ByteArray>,
)

/**
 * The [BleLink]s whose GATT operations a JS driver performs. One hub serves one React context; when the
 * context goes away, [invalidate] drops every link.
 *
 * Calls for the same Bluetooth address — from any link, including the links of a replaced attachment —
 * go to JS one at a time, in the order the core issues them. A call stays at the head of the queue until
 * JS settles it or the link drops, also after the core has stopped waiting for it, so an abandoned
 * operation is never overtaken. Each link and request has its own ID, so a late result cannot settle
 * anything else.
 */
internal class JsLinkHub(
    /** Runs the queued calls; it must outlive the attachments. */
    private val scope: CoroutineScope,
    /** Hands a request to JS. Must not block. */
    private val send: (LinkRequest) -> Unit,
    /**
     * The queue of each address. Shared by every hub of the process, so two React contexts (the UI and
     * Headless JS) cannot interleave their operations on one peripheral.
     */
    private val queues: ConcurrentHashMap<String, Mutex> = processQueues,
) {
    private val links = ConcurrentHashMap<Int, JsLink>()
    private val nextLinkId = AtomicInteger()

    fun open(address: String, maxWritePayloadBytes: Int): JsLink {
        val queue = queues.getOrPut(address.uppercase()) { Mutex() }
        val link = JsLink(nextLinkId.incrementAndGet(), address, maxWritePayloadBytes, queue)
        links[link.id] = link
        return link
    }

    fun link(linkId: Int): JsLink? = links[linkId]

    fun resolve(linkId: Int, requestId: Int, value: ByteArray) {
        links[linkId]?.settle(requestId, Result.success(value))
    }

    fun reject(linkId: Int, requestId: Int, message: String, gattStatus: Int?) {
        links[linkId]?.settle(requestId, Result.failure(BleLinkException(message, gattStatus)))
    }

    fun notification(linkId: Int, seq: Int, value: ByteArray) {
        links[linkId]?.onNotification(seq, value)
    }

    fun notificationError(linkId: Int, message: String, gattStatus: Int?) {
        links[linkId]?.onNotificationError(BleLinkException(message, gattStatus))
    }

    fun setMaxWritePayloadBytes(linkId: Int, bytes: Int) {
        links[linkId]?.maxWritePayload = bytes
    }

    /** The host's link dropped: the core is told first, then every pending call fails. */
    fun disconnected(linkId: Int) {
        links.remove(linkId)?.disconnect()
    }

    /** The React context is gone, and with it every JS driver: all links drop. */
    fun invalidate() {
        links.keys.toList().forEach(::disconnected)
    }

    private companion object {
        val processQueues = ConcurrentHashMap<String, Mutex>()
    }

    internal inner class JsLink(
        val id: Int,
        override val address: String,
        maxWritePayloadBytes: Int,
        private val queue: Mutex,
    ) : BleLink {
        @Volatile var maxWritePayload: Int = maxWritePayloadBytes
        override val maxWritePayloadBytes: Int get() = maxWritePayload

        private val down = CompletableDeferred<Unit>()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
        private val nextRequestId = AtomicInteger()

        /** Receives TX Stream values between an enable and the matching disable. */
        @Volatile private var listener: NotificationListener? = null

        /** JS numbers the values of a link from 0; a gap or reordering ends the attachment. */
        private var expectedSeq = 0

        override suspend fun awaitDisconnect() = down.await()

        override suspend fun read(ref: GattRef): ByteArray = call(LinkOp.READ, ref)

        override suspend fun writeWithoutResponse(ref: GattRef, frames: List<ByteArray>) {
            call(LinkOp.WRITE, ref, frames)
        }

        override suspend fun enableNotifications(ref: GattRef, listener: NotificationListener) {
            call(LinkOp.ENABLE_NOTIFICATIONS, ref, listener = listener)
        }

        override suspend fun disableNotifications(ref: GattRef) {
            call(LinkOp.DISABLE_NOTIFICATIONS, ref)
        }

        private suspend fun call(
            op: LinkOp,
            ref: GattRef,
            frames: List<ByteArray> = emptyList(),
            listener: NotificationListener? = null,
        ): ByteArray {
            val result = CompletableDeferred<ByteArray>()
            // UNDISPATCHED: joins the address's queue right here, so calls keep the order they were issued in.
            // The job runs in the hub's scope: it keeps holding the queue if the caller stops waiting.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    queue.withLock {
                        if (down.isCompleted) return@withLock
                        val requestId = nextRequestId.incrementAndGet()
                        pending[requestId] = result
                        // Registered before the CCCD write is issued, so no early value is missed.
                        if (op == LinkOp.ENABLE_NOTIFICATIONS) this@JsLink.listener = listener
                        try {
                            send(LinkRequest(id, requestId, op, ref, frames))
                        } catch (t: Throwable) {
                            pending.remove(requestId)
                            result.completeExceptionally(BleLinkException("cannot reach the JS BLE driver", cause = t))
                        }
                        select {
                            result.onJoin {}
                            down.onJoin {}
                        }
                        pending.remove(requestId)
                        // A failed call counts as cancelled.
                        val succeeded = result.isCompleted && !result.isCancelled
                        if (op == LinkOp.DISABLE_NOTIFICATIONS || (op == LinkOp.ENABLE_NOTIFICATIONS && !succeeded)) {
                            this@JsLink.listener = null
                        }
                    }
                } finally {
                    result.completeExceptionally(BleLinkException("device disconnected"))
                }
            }
            return result.await()
        }

        fun settle(requestId: Int, outcome: Result<ByteArray>) {
            val call = pending[requestId] ?: return
            outcome.fold(call::complete, call::completeExceptionally)
        }

        fun onNotification(seq: Int, value: ByteArray) {
            val gap = synchronized(this) {
                val expected = expectedSeq
                expectedSeq = seq + 1
                seq != expected
            }
            val listener = listener ?: return
            if (gap) {
                listener.onError(BleLinkException("TX Stream notifications were lost or reordered on their way from JS"))
            } else {
                listener.onValue(value)
            }
        }

        fun onNotificationError(error: BleLinkException) {
            listener?.onError(error)
        }

        fun disconnect() {
            down.complete(Unit)
            pending.values.forEach { it.completeExceptionally(BleLinkException("device disconnected")) }
            listener = null
        }
    }
}
