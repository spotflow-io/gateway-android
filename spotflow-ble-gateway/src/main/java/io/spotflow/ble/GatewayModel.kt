package io.spotflow.ble

/**
 * Why a device is not (fully) relayed at the moment: how an attachment failed
 * ([AttachmentEnd.error]), or what keeps its cloud connection from working
 * ([DeviceReport.cloudError][io.spotflow.ble.diagnostics.DeviceReport.cloudError]).
 */
public class DeviceError internal constructor(
    public val kind: Kind,
    /** A human-readable explanation, for logs and display. Not meant to be parsed: use [kind] and [gattStatus]. */
    public val message: String,
    /** The GATT status the Bluetooth stack reported for the failed operation, if there was one. */
    public val gattStatus: Int? = null,
) {
    public enum class Kind {
        /**
         * The broker rejected the ingest key for this device. Ends an attachment; as a `cloudError`, uploads
         * of buffered data are paused and retried with a backoff that grows to 15 minutes. A `ManagedGateway`
         * asks the credentials provider again after a pause that grows to 15 minutes.
         */
        AUTH_REJECTED,

        /**
         * The cloud could not be reached, or refused the connection for another reason. Only a `cloudError`:
         * retried, it never ends an attachment.
         */
        CLOUD_UNREACHABLE,

        /**
         * A Bluetooth operation on the link failed or timed out (reading the device, enabling its stream,
         * writing to it). [gattStatus] holds the stack's status where it reported one.
         */
        BLUETOOTH,

        /**
         * The device requires an encrypted link and this phone is not paired (bonded) with it, or pairing
         * failed: pair the device, then attach it again. [gattStatus] says which status the device answered.
         */
        PAIRING_REQUIRED,

        /**
         * Another connected device reports the same device ID; only one of them is relayed. The session
         * ends, and the host decides whether to attach the device again (a `ManagedGateway` retries).
         */
        DUPLICATE_DEVICE,

        /** The gateway could not obtain a usable ingest key from the device. */
        DEVICE_INGEST_KEY,
    }

    override fun equals(other: Any?): Boolean =
        other is DeviceError && other.kind == kind && other.message == message && other.gattStatus == gattStatus

    override fun hashCode(): Int = listOf(kind, message, gattStatus).hashCode()

    override fun toString(): String =
        "DeviceError(kind=$kind, message=$message" + (gattStatus?.let { ", gattStatus=$it" } ?: "") + ")"
}

/** The device's ingest key cannot be used; [gattStatus] is that of a failed read. */
internal class DeviceIngestKeyException(message: String, val gattStatus: Int? = null) : IllegalStateException(message)

/** Thrown when a second BLE session reports a device ID that already has a live session. */
internal class DuplicateDeviceException(deviceId: String) :
    IllegalStateException("device ID '$deviceId' is already connected via another Bluetooth address")
