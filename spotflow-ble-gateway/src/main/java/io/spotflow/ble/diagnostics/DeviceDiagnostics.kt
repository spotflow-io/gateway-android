package io.spotflow.ble.diagnostics

import io.spotflow.ble.Attachment
import io.spotflow.ble.DeviceError

/** One in-memory row per tracked address. Its monitor protects fields only, never transport or disk work. */
internal class DeviceDiagnostics(attachment: Attachment) {
    private val address = attachment.address
    var attachmentState = attachment.state
    var deviceId: String? = null
    var cloudConnected = false
    var forwarded = 0L
    var cloudMessagesReceived = 0L
    var pendingMessages = 0L
    var ramBytes = 0L
    var flashBytes = 0L
    var cloudError: DeviceError? = null
    var otaEnabled = false
    var activeFirmwareTransfer: FirmwareTransfer? = null

    /** When this device's latest attachment ended, relative to the others' (larger is later). Gateway-owned. */
    var endedOrder = 0L

    inline fun update(block: DeviceDiagnostics.() -> Unit) = synchronized(this) { block() }

    fun snapshot(): DeviceReport = synchronized(this) {
        DeviceReport(
            address, deviceId, attachmentState.value, cloudConnected, forwarded, cloudMessagesReceived,
            pendingMessages, ramBytes, flashBytes, cloudError, otaEnabled, activeFirmwareTransfer,
        )
    }
}
