package io.spotflow.ble

import android.bluetooth.BluetoothAdapter
import android.content.Context
import io.spotflow.ble.cloud.BufferPool
import io.spotflow.ble.cloud.CredentialsProvider
import io.spotflow.ble.cloud.DeviceKeyQueueIds
import io.spotflow.ble.cloud.MessageStore
import io.spotflow.ble.cloud.MqttAuthException
import io.spotflow.ble.cloud.MqttConfig
import io.spotflow.ble.diagnostics.DeviceDiagnostics
import io.spotflow.ble.diagnostics.GatewayReport
import io.spotflow.ble.ota.ArtifactCache
import io.spotflow.ble.transport.BleConnection
import io.spotflow.ble.protocol.GattProfile
import io.spotflow.ble.transport.LinkConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

/**
 * The Spotflow BLE gateway: relays devices over BLE connections the host app owns to the Spotflow cloud.
 *
 * The host hands it each device as a [BleLink], with [attach]: an adapter over a connection the host
 * holds through whatever BLE library it uses, or — for a `BluetoothGatt` the host holds itself — a
 * [GattBridge]'s link.
 *
 * The host decides which devices to relay and owns their connections, including reconnecting: after a
 * link drops, it attaches the device again. For a gateway that discovers, connects and reconnects devices
 * by itself, see `io.spotflow.ble.managed.ManagedGateway`, which is built on this class.
 *
 * Buffered data is uploaded whenever the phone is online, including after a device disconnects. When the
 * gateway uses device-provisioned keys, data left on disk by an earlier run waits for the matching key
 * to be read again. Creating a gateway does no disk or network I/O on the calling thread; create one per
 * process, and call [shutdown] when done with it.
 */
public class SpotflowGateway internal constructor(
    context: Context,
    options: GatewayOptions,
    private val platform: GatewayPlatform,
    dispatcher: CoroutineDispatcher,
    private val deviceKeyQueueId: ((deviceId: String, key: String) -> String)? = null,
) {
    /**
     * @param credentials e.g. [StaticIngestKey][io.spotflow.ble.cloud.StaticIngestKey], or a provider of
     *   per-device keys or rotation.
     * @param options where to upload, how much to buffer, OTA; see [GatewayOptions].
     */
    @JvmOverloads
    public constructor(
        context: Context,
        credentials: CredentialsProvider,
        options: GatewayOptions = GatewayOptions.DEFAULT,
    ) : this(
        context,
        options,
        AndroidGatewayPlatform(context.applicationContext, credentials, MqttConfig(options.endpoint)),
        Dispatchers.IO,
    )

    // Application context only: the gateway may outlive whatever component created it.
    private val appContext = context.applicationContext

    // The handler is a backstop: this runs inside someone else's app, and an exception that slipped
    // through must not take that app down (an uncaught one in a coroutine crashes the process).
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error ->
            Slog.e(TAG, "unexpected failure in the gateway", error)
        },
    )

    // Keep the transport alive during best-effort attachment cleanup when shutdown cancels the sessions.
    private val transportScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    private class Session(val attachment: Attachment) {
        lateinit var job: Job
        var requestedEnd: AttachmentEnd.Reason? = null
    }

    private val lifecycleLock = Any()
    private var shutDown = false
    // Includes retiring sessions so even a burst of replacements cannot overtake their cleanup.
    private val sessions = HashMap<String, LinkedHashSet<Session>>()

    /** The dashboard's tracked peripherals, protected by [lifecycleLock]. */
    private val tracked = LinkedHashMap<String, DeviceDiagnostics>()

    /** One RAM and one flash budget shared by every device (see [BufferPool]). */
    private val buffers: BufferPool = run {
        val store = MessageStore.acquire(appContext, options.flashBufferBytes)
        BufferPool(store, options.ramBufferBytes, options.flashBufferBytes) { MessageStore.release(store) }
    }

    /** OTA artifacts downloaded for the devices, shared by all of them; null with OTA off. */
    private val artifacts: ArtifactCache? = options.takeIf { it.otaEnabled }?.let {
        ArtifactCache({ File(appContext.cacheDir, "spotflow-ota") }, platform.artifactDownloader, it.otaMaxArtifactBytes, scope)
    }

    private val links = CloudLinkRegistry(scope) { deviceId, diagnostics, credential ->
        val queueId = credential?.queueId ?: MessageStore.suppliedKeyQueue(deviceId)
        CloudLink(deviceId, buffers.open(queueId), platform.createUplink(deviceId, credential), diagnostics, platform.online)
    }

    /** Current diagnostics, built on demand from memory. Does not perform BLE, network or disk I/O. */
    public fun diagnosticsSnapshot(): GatewayReport {
        val devices = synchronized(lifecycleLock) { tracked.values.toList() }
        return GatewayReport(Collections.unmodifiableList(devices.map { it.snapshot() }), buffers.usageSnapshot())
    }

    init {
        scope.coroutineContext.job.invokeOnCompletion { transportScope.cancel() }
        platform.start(onBluetoothOff = ::handleBluetoothOff)
        // Upload buffers left on disk by an earlier run (e.g. the app was killed during an outage) even if
        // those devices never come back. This runs once right away — which is also what opens the flash
        // store, here rather than on the thread that created the gateway — and again whenever a gateway
        // that was shut down hands its data over: a restarted gateway overlaps with the previous one,
        // which may still be writing its RAM to flash after this one has looked.
        scope.launch { buffers.handoffs.collect { recoverStoredBuffers() } }
    }

    private fun recoverStoredBuffers() {
        val stored = runCatching { buffers.storedDeviceIds() }
            .onFailure { Slog.w(TAG, "cannot read the stored buffers: ${it.message}") }
            .getOrDefault(emptyList())
        // Device-key queues are opened by an attachment with matching credentials, never recovered
        // under configured credentials. Still read the store in device mode to initialize usage counters.
        if (deviceKeyQueueId == null) {
            stored.mapNotNull(MessageStore::suppliedKeyDeviceId).forEach { id -> runCatching { links.recover(id) } }
        }
    }

    /**
     * Turning Bluetooth off drops every link, but often without a GATT disconnect callback, which would
     * leave sessions parked forever: end them here.
     */
    internal fun handleBluetoothOff() {
        synchronized(lifecycleLock) {
            sessions.values.flatten().forEach { end(it, AttachmentEnd.Reason.DISCONNECTED) }
        }
    }

    // ---- attaching -------------------------------------------------------------------------------

    /**
     * Relays the device behind [link], a BLE connection the host app owns. Returns at once: the session
     * runs in the background, reports through [Attachment.state], and ends when the link drops, a call to the link
     * fails or times out, or the host detaches it — [Attachment.awaitEnd] says which. The host attaches a
     * fresh link after it reconnects.
     *
     * Attaching a [BleLink.address] that is already relayed replaces that session; the new one
     * starts once the previous one has attempted to turn the stream off and released its cloud link. A link
     * of another peripheral that reports a device ID already relayed ends with
     * [DeviceError.Kind.DUPLICATE_DEVICE].
     *
     * To select devices by ID, use [GattProfile.readDeviceId] before attaching.
     *
     * @throws IllegalArgumentException if [BleLink.address] is not a Bluetooth address.
     * @throws IllegalStateException if this gateway has been shut down.
     */
    public fun attach(link: BleLink): Attachment {
        val address = link.address
        require(BluetoothAdapter.checkBluetoothAddress(address)) { "'$address' is not a Bluetooth address" }
        return startAttached(LinkConnection(address, transportScope, link))
    }

    internal fun hasAttachment(address: String): Boolean = synchronized(lifecycleLock) {
        !sessions[address].isNullOrEmpty()
    }

    /** Starts a selected device without rereading its ID or taking over an existing attachment. */
    internal fun attachManaged(link: BleLink, deviceId: String): Attachment? = synchronized(lifecycleLock) {
        if (shutDown || hasAttachment(link.address)) return null
        startAttached(LinkConnection(link.address, transportScope, link), deviceId)
    }

    /** Called under [lifecycleLock]. The first requested stop reason wins. */
    private fun end(session: Session, reason: AttachmentEnd.Reason) {
        if (session.requestedEnd == null) session.requestedEnd = reason
        session.job.cancel()
    }

    internal fun startAttached(
        connection: BleConnection,
        selectedDeviceId: String? = null,
    ): Attachment = synchronized(lifecycleLock) {
        check(!shutDown) { "gateway has been shut down" }
        val address = connection.deviceAddress
        val attachment = Attachment(address, ::detachAttachment)
        val session = Session(attachment)
        val previous = sessions[address]?.toList().orEmpty()
        val diagnostics = tracked.getOrPut(address) { DeviceDiagnostics(attachment) }
        diagnostics.update { attachmentState = attachment.state }
        var outcome: AttachmentEnd? = null
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            previous.forEach { it.job.join() }
            outcome = try {
                GatewaySession(connection, links, attachment, diagnostics, artifacts, selectedDeviceId, deviceKeyQueueId).run()
                AttachmentEnd(AttachmentEnd.Reason.DISCONNECTED)
            } catch (c: CancellationException) {
                throw c // normal teardown — not an error
            } catch (t: Throwable) {
                if (connection.linkLost) {
                    AttachmentEnd(AttachmentEnd.Reason.DISCONNECTED)
                } else {
                    Slog.w(TAG, "session for $address failed: ${t.message}")
                    AttachmentEnd(AttachmentEnd.Reason.FAILED, errorOf(t))
                }
            }
        }
        sessions.getOrPut(address) { LinkedHashSet() }.add(session)
        session.job.invokeOnCompletion {
            val result = synchronized(lifecycleLock) {
                val result = session.requestedEnd?.let(::AttachmentEnd) ?: outcome ?: AttachmentEnd(AttachmentEnd.Reason.DETACHED)
                sessions[address]?.let {
                    it.remove(session)
                    if (it.isEmpty()) sessions.remove(address)
                }
                result
            }
            attachment.finish(result)
        }
        previous.lastOrNull()?.let { end(it, AttachmentEnd.Reason.REPLACED) }
        session.job.start()
        attachment
    }

    /** Ends [attachment] — unless a newer one has replaced it, which keeps running. */
    private fun detachAttachment(attachment: Attachment) {
        synchronized(lifecycleLock) {
            val session = sessions[attachment.address]?.lastOrNull() ?: return
            if (session.attachment !== attachment || session.requestedEnd != null) return
            tracked.remove(attachment.address)
            end(session, AttachmentEnd.Reason.DETACHED)
        }
    }

    internal fun forgetInactive(address: String) {
        synchronized(lifecycleLock) {
            if (!hasAttachment(address)) tracked.remove(address)
        }
    }

    /**
     * Requests shutdown and returns immediately. Attachments end as [AttachmentEnd.Reason.DETACHED].
     * Resources are released and unsent data is handed off to the flash tier (if configured) in the
     * background. This does not wait for cloud delivery. This gateway cannot accept new attachments.
     */
    public fun shutdown() {
        synchronized(lifecycleLock) {
            if (shutDown) return
            shutDown = true
            sessions.values.flatten().forEach { end(it, AttachmentEnd.Reason.DETACHED) }
            scope.cancel()
        }
        platform.stop()
        artifacts?.clear()
        // The flash store closes once every link has flushed and closed its buffer.
        buffers.shutdown()
    }

    private fun errorOf(error: Throwable): DeviceError {
        val kind = when (error) {
            is MqttAuthException -> DeviceError.Kind.AUTH_REJECTED
            is DeviceIngestKeyException -> DeviceError.Kind.DEVICE_INGEST_KEY
            is DuplicateDeviceException -> DeviceError.Kind.DUPLICATE_DEVICE
            else -> DeviceError.Kind.BLUETOOTH
        }
        return DeviceError(kind, error.message ?: error.javaClass.simpleName)
    }

    public companion object {
        /**
         * Uses the optional Ingest Key GATT characteristic as the only credential source. A missing,
         * unreadable or invalid key fails the attachment. After a process restart, queued data waits
         * until a device attaches with the same ID and key at the same endpoint.
         */
        @JvmStatic
        @JvmOverloads
        public fun usingDeviceIngestKeys(context: Context, options: GatewayOptions = GatewayOptions.DEFAULT): SpotflowGateway =
            SpotflowGateway(
                context,
                options,
                AndroidGatewayPlatform(context.applicationContext, null, MqttConfig(options.endpoint)),
                Dispatchers.IO,
                DeviceKeyQueueIds(options.endpoint)::queueId,
            )

        private const val TAG = "SpotflowGateway"
    }
}
