package io.spotflow.ble.diagnostics

import io.spotflow.ble.AttachmentState
import io.spotflow.ble.DeviceError

// Plain classes rather than data classes: fields can be added in any place later without breaking apps
// that destructure them. Equality is by value all the same.

/** A point-in-time diagnostic view, not an event log or a transactional view of all devices. */
public class GatewayReport internal constructor(
    /** Devices tracked by Bluetooth address, in the order the gateway started tracking them. */
    public val devices: List<DeviceReport>,
    public val bufferUsage: BufferUsage,
) {
    override fun equals(other: Any?): Boolean =
        other is GatewayReport && other.devices == devices && other.bufferUsage == bufferUsage

    override fun hashCode(): Int = 31 * devices.hashCode() + bufferUsage.hashCode()

    override fun toString(): String = "GatewayReport(devices=$devices, bufferUsage=$bufferUsage)"
}

/**
 * Diagnostic data for one peripheral. MQTT may remain connected after the attachment ends.
 *
 * A device is tracked from its first attachment. Detaching drops it from the report (its buffered data is
 * still uploaded); after a disconnect or failure it stays, with how its attachment ended, until it is
 * attached again — of such devices, the gateway keeps the 64 whose attachments ended last.
 */
public class DeviceReport internal constructor(
    public val address: String,
    public val deviceId: String?,
    /** State of the latest attachment for this address. */
    public val attachmentState: AttachmentState,
    public val cloudConnected: Boolean,
    /** Messages acknowledged by the cloud while this device has been tracked, across reconnects. */
    public val forwarded: Long,
    /** Cloud messages queued for this device, counted before writing them to BLE. */
    public val cloudMessagesReceived: Long,
    public val pendingMessages: Long,
    public val ramBytes: Long,
    public val flashBytes: Long,
    /**
     * Why the device's cloud connection is not working, while it isn't: [DeviceError.Kind.CLOUD_UNREACHABLE]
     * or [DeviceError.Kind.AUTH_REJECTED]. Retried in the background; cleared once a connection succeeds.
     */
    public val cloudError: DeviceError?,
    /** Whether the gateway and device both enable OTA. Known after reading device capabilities. */
    public val otaEnabled: Boolean,
    /** Current firmware transfer, or null when none is tracked. Not the outcome of the update. */
    public val activeFirmwareTransfer: FirmwareTransfer?,
) {
    private val fields: List<Any?>
        get() = listOf(
            address, deviceId, attachmentState, cloudConnected, forwarded, cloudMessagesReceived,
            pendingMessages, ramBytes, flashBytes, cloudError, otaEnabled, activeFirmwareTransfer,
        )

    override fun equals(other: Any?): Boolean = other is DeviceReport && other.fields == fields

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String =
        "DeviceReport(address=$address, deviceId=$deviceId, attachmentState=$attachmentState, " +
            "cloudConnected=$cloudConnected, forwarded=$forwarded, cloudMessagesReceived=$cloudMessagesReceived, " +
            "pendingMessages=$pendingMessages, ramBytes=$ramBytes, flashBytes=$flashBytes, cloudError=$cloudError, " +
            "otaEnabled=$otaEnabled, activeFirmwareTransfer=$activeFirmwareTransfer)"
}

/** Usage and configured limits of the shared store-and-forward buffer, including message overhead. */
public class BufferUsage internal constructor(
    public val ramBytes: Long,
    public val ramMaxBytes: Long,
    public val flashBytes: Long,
    /** Zero when flash buffering is disabled. */
    public val flashMaxBytes: Long,
    /** False if the persistent buffer could not be opened. Independent of the configured flash budget. */
    public val flashAvailable: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is BufferUsage && other.ramBytes == ramBytes && other.ramMaxBytes == ramMaxBytes &&
            other.flashBytes == flashBytes && other.flashMaxBytes == flashMaxBytes &&
            other.flashAvailable == flashAvailable

    override fun hashCode(): Int = listOf(ramBytes, ramMaxBytes, flashBytes, flashMaxBytes, flashAvailable).hashCode()

    override fun toString(): String =
        "BufferUsage(ramBytes=$ramBytes, ramMaxBytes=$ramMaxBytes, flashBytes=$flashBytes, " +
            "flashMaxBytes=$flashMaxBytes, flashAvailable=$flashAvailable)"
}

/** An OTA artifact being prepared or served to a device. */
public class FirmwareTransfer internal constructor(
    /** The artifact's size, or null while the gateway is preparing it. */
    public val sizeBytes: Long?,
    /** End offset of the latest block prepared for a read, or zero before any read. Not a delivery acknowledgement. */
    public val lastReadEndOffsetBytes: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is FirmwareTransfer && other.sizeBytes == sizeBytes && other.lastReadEndOffsetBytes == lastReadEndOffsetBytes

    override fun hashCode(): Int = 31 * (sizeBytes?.hashCode() ?: 0) + lastReadEndOffsetBytes.hashCode()

    override fun toString(): String = "FirmwareTransfer(sizeBytes=$sizeBytes, lastReadEndOffsetBytes=$lastReadEndOffsetBytes)"
}
