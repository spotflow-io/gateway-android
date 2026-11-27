package io.spotflow.ble.managed

import android.util.Log
import io.spotflow.ble.SpotflowLog

/**
 * Decides which devices a [ManagedGateway] relays. Called after the device ID has been read, before
 * anything is sent to the cloud under that ID.
 *
 * Managed mode connects to anything advertising the Spotflow service, and the BLE protocol does not
 * authenticate devices — so unless the filter is selective, any nearby peripheral can publish data (and
 * receive desired configuration) under any device ID in your workspace. Production integrations should
 * accept only the devices they expect (e.g. an allowlist, or IDs provisioned to the signed-in user).
 *
 * A rejected device is asked about again after a few minutes, and right away after
 * [ManagedGateway.retry] — call that when what the filter accepts has changed.
 */
public fun interface DeviceFilter {
    public suspend fun accept(address: String, deviceId: String): Boolean

    public companion object {
        /**
         * Relays every device in range. Fine for a demo or a lab; in production it lets any nearby
         * peripheral use your ingest key (see [DeviceFilter]).
         */
        @JvmField
        public val AcceptAll: DeviceFilter = DeviceFilter { _, _ -> true }
    }
}

/** What a [ManagedGateway]'s device discovery is doing; see [ScanStatus]. */
public enum class ScanState {
    /** Not scanning: [ManagedGateway.startScanning] was not called, or scanning was stopped. */
    STOPPED,

    /** Scanning for devices advertising the Spotflow service. */
    SCANNING,

    /** Bluetooth is off; scanning resumes by itself when it is turned on. */
    WAITING_FOR_BLUETOOTH,

    /** The last scan failed (see [ScanStatus.error]); it is being retried with backoff. */
    RETRYING,
}

/** A [ManagedGateway]'s device discovery; see [ManagedGateway.scanStatus]. */
public class ScanStatus internal constructor(
    public val state: ScanState,
    /** Why the scan failed, while [state] is [ScanState.RETRYING] — e.g. a missing permission. */
    public val error: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is ScanStatus && other.state == state && other.error == error
    override fun hashCode(): Int = 31 * state.hashCode() + (error?.hashCode() ?: 0)
    override fun toString(): String = "ScanStatus(state=$state" + (error?.let { ", error=$it" } ?: "") + ")"
}

/** The managed layer's logging, through the library's public [SpotflowLog] settings. */
internal object ManagedLog {
    private const val TAG = "SpotflowGateway"

    fun i(message: String) = emit(Log.INFO, message, null)
    fun w(message: String) = emit(Log.WARN, message, null)
    fun e(message: String, error: Throwable? = null) = emit(Log.ERROR, message, error)

    private fun emit(priority: Int, message: String, error: Throwable?) {
        if (priority < SpotflowLog.minPriority) return
        val logger = SpotflowLog.logger ?: return
        // A host-supplied logger must never be able to break relaying.
        runCatching { logger.log(priority, TAG, message, error) }
    }
}
