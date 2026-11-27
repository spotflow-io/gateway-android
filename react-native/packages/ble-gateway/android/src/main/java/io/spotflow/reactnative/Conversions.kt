package io.spotflow.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import io.spotflow.ble.AttachmentEnd
import io.spotflow.ble.AttachmentState
import io.spotflow.ble.DeviceError
import io.spotflow.ble.GatewayOptions
import io.spotflow.ble.cloud.CloudEndpoint
import io.spotflow.ble.diagnostics.DeviceReport
import io.spotflow.ble.diagnostics.GatewayReport

// Converts between the gateway's types and the shapes in src/NativeSpotflowGateway.ts.

/** @throws IllegalArgumentException if an option is missing or out of range. */
internal fun ReadableMap.toGatewayConfig(): GatewayConfig {
    val source = if (hasKey("ingestKeySource")) optString("ingestKeySource") else "device"
    val ingestKey = when (source) {
        "device" -> {
            require(!hasKey("ingestKey")) { "ingestKey requires ingestKeySource: 'gateway'" }
            null
        }
        "gateway" -> optString("ingestKey")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("ingestKey is required for ingestKeySource: 'gateway'")
        else -> throw IllegalArgumentException("ingestKeySource must be 'device' or 'gateway'")
    }
    val options = GatewayOptions {
        optMap("endpoint")?.let {
            val host = it.optString("host") ?: throw IllegalArgumentException("endpoint.host is required")
            endpoint = CloudEndpoint(host, it.optLong("port")?.toInt() ?: CloudEndpoint.DEFAULT_PORT)
        }
        optLong("ramBufferBytes")?.let { ramBufferBytes = it }
        optLong("flashBufferBytes")?.let { flashBufferBytes = it }
        optBoolean("otaEnabled")?.let { otaEnabled = it }
        optLong("otaMaxArtifactBytes")?.let { otaMaxArtifactBytes = it }
    }
    return GatewayConfig(ingestKey, options)
}

internal fun LinkRequest.toMap(): WritableMap = Arguments.createMap().apply {
    putInt("linkId", linkId)
    putInt("requestId", requestId)
    putString("op", op.wireName)
    putString("service", ref.service.toString())
    putString("characteristic", ref.characteristic.toString())
    putArray("frames", Arguments.createArray().apply { frames.forEach { pushArray(it.toJsArray()) } })
}

internal fun attachmentStateEvent(attachmentId: Int, state: AttachmentState): WritableMap =
    state.toMap().apply { putInt("attachmentId", attachmentId) }

internal fun GatewayReport.toMap(): WritableMap = Arguments.createMap().apply {
    putArray("devices", Arguments.createArray().apply { devices.forEach { pushMap(it.toMap()) } })
    putMap(
        "bufferUsage",
        Arguments.createMap().apply {
            putDouble("ramBytes", bufferUsage.ramBytes.toDouble())
            putDouble("ramMaxBytes", bufferUsage.ramMaxBytes.toDouble())
            putDouble("flashBytes", bufferUsage.flashBytes.toDouble())
            putDouble("flashMaxBytes", bufferUsage.flashMaxBytes.toDouble())
            putBoolean("flashAvailable", bufferUsage.flashAvailable)
        },
    )
}

private fun DeviceReport.toMap(): WritableMap = Arguments.createMap().apply {
    putString("address", address)
    putString("deviceId", deviceId)
    putMap("attachmentState", attachmentState.toMap())
    putBoolean("cloudConnected", cloudConnected)
    putDouble("forwarded", forwarded.toDouble())
    putDouble("cloudMessagesReceived", cloudMessagesReceived.toDouble())
    putDouble("pendingMessages", pendingMessages.toDouble())
    putDouble("ramBytes", ramBytes.toDouble())
    putDouble("flashBytes", flashBytes.toDouble())
    putMap("cloudError", cloudError?.toMap())
    putBoolean("otaEnabled", otaEnabled)
    putMap(
        "activeFirmwareTransfer",
        activeFirmwareTransfer?.let {
            Arguments.createMap().apply {
                val size = it.sizeBytes
                if (size == null) putNull("sizeBytes") else putDouble("sizeBytes", size.toDouble())
                putDouble("lastReadEndOffsetBytes", it.lastReadEndOffsetBytes.toDouble())
            }
        },
    )
}

private fun AttachmentState.toMap(): WritableMap = Arguments.createMap().apply {
    when (val state = this@toMap) {
        AttachmentState.Starting -> putString("type", "starting")
        is AttachmentState.Ready -> {
            putString("type", "ready")
            putString("deviceId", state.deviceId)
        }
        is AttachmentState.Ended -> {
            putString("type", "ended")
            putMap("result", state.result.toMap())
        }
    }
}

private fun AttachmentEnd.toMap(): WritableMap = Arguments.createMap().apply {
    putString("reason", reason.name)
    putMap("error", error?.toMap())
}

private fun DeviceError.toMap(): WritableMap = Arguments.createMap().apply {
    putString("kind", kind.name)
    putString("message", message)
    gattStatus.let { if (it == null) putNull("gattStatus") else putInt("gattStatus", it) }
}

internal fun ByteArray.toJsArray(): WritableArray = Arguments.createArray().apply {
    forEach { pushInt(it.toInt() and 0xFF) }
}

internal fun ReadableArray.toBytes(): ByteArray = ByteArray(size()) { getInt(it).toByte() }

private fun ReadableMap.present(key: String): Boolean = hasKey(key) && getType(key) != ReadableType.Null

private fun ReadableMap.optString(key: String): String? = if (present(key)) getString(key) else null

private fun ReadableMap.optMap(key: String): ReadableMap? = if (present(key)) getMap(key) else null

private fun ReadableMap.optBoolean(key: String): Boolean? = if (present(key)) getBoolean(key) else null

private fun ReadableMap.optLong(key: String): Long? {
    if (!present(key)) return null
    val value = getDouble(key)
    require(value == Math.floor(value) && !value.isInfinite()) { "$key must be a whole number" }
    return value.toLong()
}
