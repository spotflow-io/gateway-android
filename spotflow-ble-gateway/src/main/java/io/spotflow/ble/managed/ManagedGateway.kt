package io.spotflow.ble.managed

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.RequiresPermission
import io.spotflow.ble.Attachment
import io.spotflow.ble.AttachmentEnd
import io.spotflow.ble.BleLink
import io.spotflow.ble.DeviceError
import io.spotflow.ble.GattBridge
import io.spotflow.ble.GatewayOptions
import io.spotflow.ble.SpotflowGateway
import io.spotflow.ble.diagnostics.GatewayReport
import io.spotflow.ble.cloud.CredentialsProvider
import io.spotflow.ble.protocol.GattProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A standalone gateway: connects to devices advertising the Spotflow service to read their device IDs.
 * It relays only devices the [deviceFilter] accepts, and reconnects them with backoff.
 *
 * Follow [devices], [status], [bufferUsage] and [scanStatus] for relay and discovery state.
 *
 * The host may [attach] connections of its own. The scan leaves host attachments alone and may pick
 * up those devices again after the host stops relaying them.
 *
 * To keep relaying while the screen is off, run it from [SpotflowGatewayService]. Call [shutdown] when
 * done with it.
 */
public class ManagedGateway internal constructor(
    private val gateway: SpotflowGateway,
    private val deviceFilter: DeviceFilter,
    private val platform: ManagedPlatform,
    dispatcher: CoroutineDispatcher,
) {
    /**
     * @param deviceFilter decides which devices are relayed. It is required because the choice matters:
     *   the BLE protocol does not authenticate devices, so production integrations should accept only the
     *   devices they expect. Pass [DeviceFilter.AcceptAll] to relay everything in range.
     * @param options where to upload, how much to buffer, OTA; see [GatewayOptions].
     */
    @JvmOverloads
    public constructor(
        context: Context,
        credentials: CredentialsProvider,
        deviceFilter: DeviceFilter,
        options: GatewayOptions = GatewayOptions.DEFAULT,
    ) : this(
        SpotflowGateway(context, credentials, options),
        deviceFilter,
        AndroidManagedPlatform(context.applicationContext),
        Dispatchers.IO,
    )

    // The handler is a backstop: this runs inside someone else's app, and an exception that slipped
    // through must not take that app down (an uncaught one in a coroutine crashes the process).
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error ->
            ManagedLog.e("unexpected failure in the managed gateway", error)
        },
    )

    /** The scan's job and each managed device's, by address. */
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _scanStatus = MutableStateFlow(ScanStatus(ScanState.STOPPED))

    /** Current relay and buffer diagnostics, built on demand without I/O. */
    public fun diagnosticsSnapshot(): GatewayReport = gateway.diagnosticsSnapshot()

    /** Relays a host-owned link, bypassing the managed discovery filter. The host selects the device first. */
    public fun attach(link: BleLink): Attachment = gateway.attach(link)

    /** Whether discovery is running, or why not. */
    public val scanStatus: StateFlow<ScanStatus> = _scanStatus

    /** True if the device has Bluetooth and it is currently turned on. */
    public val isBluetoothEnabled: Boolean get() = platform.isBluetoothEnabled

    @Volatile private var wantScanning = false

    /** Bumped by [retry] to end the pauses of devices that were stopped. */
    private val retrySignal = MutableStateFlow(0)

    /** Devices the scan leaves alone until explicitly allowed again. */
    private val ignored = ConcurrentHashMap.newKeySet<String>()
    private val managedAttachments = HashMap<String, Attachment>() // guarded by ignored

    init {
        platform.start(onBluetoothOff = ::handleBluetoothOff, onBluetoothOn = ::handleBluetoothOn)
    }

    internal fun handleBluetoothOff() {
        if (wantScanning) setScan(ScanState.WAITING_FOR_BLUETOOTH)
        if (jobs.isEmpty()) return
        ManagedLog.w("Bluetooth off; stopping ${jobs.size} job(s)")
        // The scan first, so it stops adding devices; then each device as it is taken out of the map.
        jobs.remove(SCAN_JOB_KEY)?.cancel()
        for (key in jobs.keys.toList()) jobs.remove(key)?.cancel()
    }

    @SuppressLint("MissingPermission") // held when scanning was first started
    internal fun handleBluetoothOn() {
        if (wantScanning) {
            ManagedLog.i("Bluetooth on; resuming scanning")
            startScanning()
        }
    }

    // ---- discovery -------------------------------------------------------------------------------

    /**
     * Starts discovering and relaying devices. Scanning keeps itself going: a scan that fails is retried
     * with backoff, and it resumes when Bluetooth is turned back on. Follow it in [scanStatus].
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    public fun startScanning() {
        check(scope.isActive) { "gateway has been shut down" }
        wantScanning = true
        if (jobs.containsKey(SCAN_JOB_KEY)) return
        if (!platform.isBluetoothEnabled) {
            ManagedLog.w("cannot scan yet: Bluetooth is off or unavailable")
            setScan(ScanState.WAITING_FOR_BLUETOOTH)
            return
        }
        // LAZY: register the job before it runs, so its own cleanup can never race the registration.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                scanLoop()
            } finally {
                // Allow startScanning() to resume discovery later — but only remove *this* job, never a
                // newer scan started after a quick stop/start.
                jobs.remove(SCAN_JOB_KEY, coroutineContext.job)
            }
        }
        if (jobs.putIfAbsent(SCAN_JOB_KEY, job) == null) job.start() else job.cancel()
    }

    /**
     * Scans until cancelled, in segments.
     *
     * The first segment is short and `BALANCED`, to find the devices in range quickly; from then on the
     * scan is low-power — known devices reconnect through `autoConnect`, so the scan only has to notice
     * new devices and those given up on. Each segment ends with a restart, because Android throttles a
     * scan left running for 30 minutes (down to results it only hears about from other apps' scans, on
     * older versions). A scan that fails — which Android reports once and never again — is retried with
     * backoff instead of leaving discovery dead until Bluetooth is toggled.
     */
    private suspend fun scanLoop() {
        var lowPower = false
        var backoff = SCAN_RETRY_INITIAL_MS
        while (currentCoroutineContext().isActive) {
            if (!platform.isBluetoothEnabled) {
                setScan(ScanState.WAITING_FOR_BLUETOOTH) // the adapter watcher restarts scanning
                return
            }
            try {
                setScan(ScanState.SCANNING)
                val segment = if (lowPower) SCAN_RESTART_MS else SCAN_BOOST_MS
                val ended = withTimeoutOrNull(segment) {
                    platform.scan(lowPower).collect { address ->
                        backoff = SCAN_RETRY_INITIAL_MS
                        discovered(address)
                    }
                }
                check(ended == null) { "the scan ended unexpectedly" }
                lowPower = true
                backoff = SCAN_RETRY_INITIAL_MS
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // e.g. the scan could not be registered, a permission is missing, or Bluetooth went off.
                ManagedLog.w("scanning failed, retrying in ${backoff / 1000} s: ${t.message}")
                setScan(ScanState.RETRYING, t.message ?: t.javaClass.simpleName)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(SCAN_RETRY_MAX_MS)
            }
        }
    }

    private fun setScan(state: ScanState, error: String? = null) {
        _scanStatus.update { ScanStatus(state, error) }
    }

    /** Stops discovering new devices. Devices already being relayed stay connected. */
    public fun stopScanning() {
        wantScanning = false
        jobs.remove(SCAN_JOB_KEY)?.cancel()
        setScan(ScanState.STOPPED)
    }

    private fun discovered(address: String) {
        // Bluetooth may have just been turned off, with the teardown already past this point.
        if (!platform.isBluetoothEnabled || jobs.containsKey(address) || address in ignored) return
        // Relayed already, by an attachment of the host's own: not this layer's to take over.
        if (gateway.hasAttachment(address)) return
        launchManaged(address)
    }

    // ---- the reconnect loop ----------------------------------------------------------------------

    /**
     * How one connection to a device went: relayed until [end], or — with [end] null — never attached,
     * because bringing the link up failed with [failure]. [heardFrom]: whether Android said anything at all
     * about the connection.
     */
    private class Attempt(
        val end: AttachmentEnd? = null,
        val failure: Throwable? = null,
        val heardFrom: Boolean = true,
        val rejected: Boolean = false,
    )

    private fun launchManaged(address: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Direct connect while the device is likely in range — right after the scan found it, or right
            // after a working session dropped (usually a brief radio gap). Direct connects take about a
            // second; autoConnect scans at a low duty cycle and can take many seconds. Only after a direct
            // attempt fails does it fall back to autoConnect, which re-attaches whenever the device reappears.
            var direct = true
            var backoff = INITIAL_BACKOFF_MS
            var authBackoff = AUTH_RETRY_INITIAL_MS
            while (isActive) {
                if (address in ignored || gateway.hasAttachment(address)) {
                    // The host relays it now, through an attachment of its own: it is the host's.
                    jobs.remove(address, coroutineContext.job)
                    return@launch
                }
                val attempt = try {
                    relay(address, autoConnect = !direct)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Attempt(failure = t)
                } ?: break
                if (attempt.rejected) {
                    ManagedLog.w("ignoring $address: rejected by the device filter")
                    pause(REJECTED_RETRY_MS)
                    break
                }
                when (attempt.end?.reason) {
                    null -> if (!direct && !attempt.heardFrom) {
                        // Gone for a long time: free the GATT slot. The scanner reconnects it if it reappears.
                        ManagedLog.i("giving up on $address: not seen for ${AUTO_CONNECT_TIMEOUT_MS / 1000} s")
                        gateway.forgetInactive(address)
                        jobs.remove(address, coroutineContext.job)
                        return@launch
                    } else {
                        ManagedLog.w("cannot connect to $address: ${attempt.failure?.message}")
                    }
                    AttachmentEnd.Reason.DISCONNECTED -> {
                        direct = true
                        backoff = INITIAL_BACKOFF_MS
                        authBackoff = AUTH_RETRY_INITIAL_MS
                        delay(RECONNECT_DELAY_MS)
                        continue
                    }
                    AttachmentEnd.Reason.REPLACED -> {
                        // The host attached it itself: it is the host's while it relays it. The scan may
                        // pick it up again once nobody does (see discovered()).
                        jobs.remove(address, coroutineContext.job)
                        return@launch
                    }
                    AttachmentEnd.Reason.DETACHED -> {
                        jobs.remove(address, coroutineContext.job)
                        return@launch
                    }
                    AttachmentEnd.Reason.FAILED -> if (attempt.end.error?.kind == DeviceError.Kind.AUTH_REJECTED) {
                        // Hammering the broker with a rejected key is pointless, but the key can change (a
                        // rotating CredentialsProvider, a key fixed on the server side): ask again rarely.
                        ManagedLog.w("auth rejected for $address, retrying in ${authBackoff / 1000} s")
                        pause(authBackoff)
                        authBackoff = (authBackoff * 2).coerceAtMost(AUTH_RETRY_MAX_MS)
                        direct = true
                        continue
                    } else {
                        ManagedLog.w("session for $address failed: ${attempt.end.error?.message}")
                    }
                }
                // Failed, or could not even bring the link up: back off, then wait with autoConnect.
                direct = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            jobs.remove(address, coroutineContext.job)
        }
        if (jobs.putIfAbsent(address, job) == null) job.start() else job.cancel()
    }

    /**
     * Connects to [address] and brings the link up — the connection, service discovery, the MTU — as any
     * host does before attaching a link; then relays it through [gateway] until the attachment ends, and
     * closes the connection. Also when cancelled, which releases a pending autoConnect too.
     */
    private suspend fun relay(address: String, autoConnect: Boolean): Attempt? {
        val bridge = GattBridge()
        val callback = ManagedGattCallback(bridge.callback)
        // Null e.g. when Bluetooth was turned off in the meantime.
        val gatt = platform.connectGatt(address, autoConnect, callback)
            ?: return Attempt(end = null, failure = IllegalStateException("connectGatt() returned null"), heardFrom = true)
        var attachment: Attachment? = null
        try {
            try {
                callback.awaitConnected(if (autoConnect) AUTO_CONNECT_TIMEOUT_MS else DIRECT_CONNECT_TIMEOUT_MS)
                callback.discoverServices(gatt, SETUP_TIMEOUT_MS)
                callback.requestMtu(gatt, MAX_MTU, SETUP_TIMEOUT_MS)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                return Attempt(end = null, failure = t, heardFrom = callback.heardFrom)
            }
            if (gateway.hasAttachment(address)) return null
            val link = bridge.link(gatt)
            val deviceId = GattProfile.readDeviceId(link)
            if (!deviceFilter.accept(address, deviceId)) {
                gateway.forgetInactive(address)
                return Attempt(rejected = true)
            }
            val accepted = synchronized(ignored) {
                if (address in ignored || !scope.isActive || !platform.isBluetoothEnabled) null
                else gateway.attachManaged(link, deviceId)?.also { managedAttachments[address] = it }
            } ?: return null
            attachment = accepted
            return Attempt(end = accepted.awaitEnd())
        } finally {
            synchronized(ignored) { attachment?.let { managedAttachments.remove(address, it) } }
            withContext(NonCancellable) { callback.close(gatt) }
        }
    }

    /** Waits [ms], or less if [retry] is called meanwhile. */
    private suspend fun pause(ms: Long) {
        val seen = retrySignal.value
        withTimeoutOrNull(ms) { retrySignal.first { it != seen } }
    }

    /**
     * Gives paused efforts another go instead of waiting for their backoff:
     * devices whose ingest key the broker rejected ([DeviceError.Kind.AUTH_REJECTED]), devices the
     * [DeviceFilter] turned down. Background uploads retry on their own.
     * Call after changing credentials or the filter. Devices excluded by [ignoreDevice] remain excluded.
     */
    public fun retry() {
        retrySignal.update { it + 1 }
    }

    /**
     * Stops and disconnects a managed device, and excludes its address from discovery until [allowDevice].
     * Does not stop a host-owned attachment. Data already buffered can still be uploaded.
     */
    public fun ignoreDevice(address: String) {
        val job = synchronized(ignored) {
            ignored += address
            managedAttachments.remove(address)?.detach()
            jobs.remove(address)
        }
        job?.cancel()
        gateway.forgetInactive(address)
    }

    /** Allows an ignored address to be picked up by discovery again, subject to the filter. */
    public fun allowDevice(address: String) {
        synchronized(ignored) { ignored.remove(address) }
    }

    /** Requests shutdown and returns immediately. Only managed BLE connections are disconnected. */
    public fun shutdown() {
        wantScanning = false
        platform.stop()
        gateway.shutdown()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        setScan(ScanState.STOPPED)
    }

    private companion object {
        private const val SCAN_JOB_KEY = "__scan__"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val RECONNECT_DELAY_MS = 500L
        private const val MAX_BACKOFF_MS = 30_000L

        /** With autoConnect, how long a device may stay away before it is given up on. */
        private const val AUTO_CONNECT_TIMEOUT_MS = 10 * 60_000L

        /** A direct connect: Android gives up by itself (after about 30 s); this is only a backstop. */
        private const val DIRECT_CONNECT_TIMEOUT_MS = 60_000L

        /** How long the scan runs at the quicker `BALANCED` duty cycle after starting. */
        private const val SCAN_BOOST_MS = 2 * 60_000L

        /** A running scan is restarted this often, inside Android's 30-minute limit. */
        private const val SCAN_RESTART_MS = 25 * 60_000L

        // Android allows 5 scan starts per 30 s; this backoff stays well inside that.
        private const val SCAN_RETRY_INITIAL_MS = 5_000L
        private const val SCAN_RETRY_MAX_MS = 60_000L

        private const val AUTH_RETRY_INITIAL_MS = 60_000L
        private const val AUTH_RETRY_MAX_MS = 15 * 60_000L

        /** How long a device the filter rejected is left alone before it is asked about again. */
        private const val REJECTED_RETRY_MS = 10 * 60_000L

        /** How long service discovery and the MTU exchange may take: as long as the gateway gives a call. */
        private const val SETUP_TIMEOUT_MS = 10_000L

        /** The largest ATT MTU Android negotiates. */
        private const val MAX_MTU = 517

    }
}
