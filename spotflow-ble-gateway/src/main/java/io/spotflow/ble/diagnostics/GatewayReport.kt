package io.spotflow.ble.diagnostics

import io.spotflow.ble.AttachmentState
import io.spotflow.ble.DeviceError

/** A point-in-time diagnostic view, not an event log or a transactional view of all devices. */
@ConsistentCopyVisibility
public data class GatewayReport internal constructor(
    /** Devices tracked by Bluetooth address, in the order the gateway started tracking them. */
    val devices: List<DeviceReport>,
    val bufferUsage: BufferUsage,
)

/** Diagnostic data for one peripheral. MQTT may remain connected after the attachment ends. */
@ConsistentCopyVisibility
public data class DeviceReport internal constructor(
    val address: String,
    val deviceId: String?,
    /** State of the latest attachment for this address. */
    val attachmentState: AttachmentState,
    val cloudConnected: Boolean,
    /** Messages acknowledged by the cloud while this device has been tracked, across reconnects. */
    val forwarded: Long,
    /** Cloud messages queued for this device, counted before writing them to BLE. */
    val cloudMessagesReceived: Long,
    val pendingMessages: Long,
    val ramBytes: Long,
    val flashBytes: Long,
    /** Retryable MQTT connection issue, if any. Cleared when the connection succeeds. */
    val cloudError: DeviceError?,
    /** Whether the gateway and device both enable OTA. Known after reading device capabilities. */
    val otaEnabled: Boolean,
    /** Current firmware transfer, or null when none is tracked. Not the outcome of the update. */
    val activeFirmwareTransfer: FirmwareTransfer?,
)

/** Usage and configured limits of the shared store-and-forward buffer, including message overhead. */
@ConsistentCopyVisibility
public data class BufferUsage internal constructor(
    val ramBytes: Long,
    val ramMaxBytes: Long,
    val flashBytes: Long,
    /** Zero when flash buffering is disabled. */
    val flashMaxBytes: Long,
    /** False if the persistent buffer could not be opened. Independent of the configured flash budget. */
    val flashAvailable: Boolean,
)

/** An OTA artifact being prepared or served to a device. */
@ConsistentCopyVisibility
public data class FirmwareTransfer internal constructor(
    /** The artifact's size, or null while the gateway is preparing it. */
    val sizeBytes: Long?,
    /** End offset of the latest block prepared for a read, or zero before any read. Not a delivery acknowledgement. */
    val lastReadEndOffsetBytes: Long,
)
