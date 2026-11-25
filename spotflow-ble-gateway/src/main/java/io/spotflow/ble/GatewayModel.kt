package io.spotflow.ble

/** Why a device is not (fully) relayed at the moment. */
@ConsistentCopyVisibility
public data class DeviceError internal constructor(val kind: Kind, val message: String) {
    public enum class Kind {
        /**
         * The broker rejected the ingest key for this device. The session ends; a `ManagedGateway` asks the
         * credentials provider again after a pause that grows to 15 minutes.
         */
        AUTH_REJECTED,

        /** The cloud could not be reached, or refused the connection for another reason. Retried. */
        CLOUD_UNREACHABLE,

        /** The Bluetooth session failed (connecting, discovering services, reading the device). */
        BLUETOOTH,

        /**
         * Another connected device reports the same device ID; only one of them is relayed. The session
         * ends, and the host decides whether to attach the device again (a `ManagedGateway` retries).
         */
        DUPLICATE_DEVICE,

        /** The gateway could not obtain a usable ingest key from the device. */
        DEVICE_INGEST_KEY,
    }
}

internal class DeviceIngestKeyException(message: String) : IllegalStateException(message)

/** Thrown when a second BLE session reports a device ID that already has a live session. */
internal class DuplicateDeviceException(deviceId: String) :
    IllegalStateException("device ID '$deviceId' is already connected via another Bluetooth address")
