package io.spotflow.ble.protocol

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import io.spotflow.ble.BleLink
import io.spotflow.ble.BleLinkException
import io.spotflow.ble.GattRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * UUIDs of the Spotflow Observability GATT service and its characteristics.
 *
 * See https://docs.spotflow.io/fundamentals/bluetooth-low-energy
 */
public object GattProfile {

    /** The Spotflow Observability Service advertised by a device running the Device SDK. */
    public val SERVICE: UUID = UUID.fromString("26530001-81E5-4861-82AE-2C92E6887922")

    /**
     * Read-only. Protocol version (major, minor) and, from protocol 1.1, a feature byte (bit 0: the device
     * takes OTA updates through the gateway). Never requires an encrypted link.
     */
    public val CAPABILITIES: UUID = UUID.fromString("26530002-81E5-4861-82AE-2C92E6887922")

    /** Read-only. The device ID; used verbatim as the MQTT username towards the Spotflow cloud. */
    public val DEVICE_ID: UUID = UUID.fromString("26530003-81E5-4861-82AE-2C92E6887922")

    /** Read-only. CBOR-encoded session metadata, published to `ingest-cbor` when a session opens. */
    public val SESSION_METADATA: UUID = UUID.fromString("26530004-81E5-4861-82AE-2C92E6887922")

    /** Notify. Device -> gateway stream (telemetry, reported configuration; OTA messages in 1.1). */
    public val TX_STREAM: UUID = UUID.fromString("26530005-81E5-4861-82AE-2C92E6887922")

    /** Write without response. Gateway -> device stream (desired configuration; OTA messages in 1.1). */
    public val RX_STREAM: UUID = UUID.fromString("26530006-81E5-4861-82AE-2C92E6887922")

    /** Read-only and optional. MQTT password for gateways configured to use a device's ingest key. */
    public val INGEST_KEY: UUID = UUID.fromString("26530007-81E5-4861-82AE-2C92E6887922")

    /** Client Characteristic Configuration Descriptor, used to enable notifications on TX_STREAM. */
    public val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** Whether this scan result lists [SERVICE]. A false result does not rule out the connected GATT service. */
    public fun advertisesService(result: ScanResult): Boolean =
        result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE)) == true

    /**
     * Reads the self-reported Spotflow ID from a connected, service-discovered [link], without starting a
     * relay or changing notifications. Call before attaching to select by device ID. The read uses
     * the link's operation queue. The ID is not proof of identity; [io.spotflow.ble.SpotflowGateway.attach]
     * reads it again during setup.
     *
     * @throws BleLinkException if the read fails, times out or returns an empty ID.
     */
    public suspend fun readDeviceId(link: BleLink): String = try {
        withTimeoutOrNull(READ_TIMEOUT_MS) { decodeDeviceId(link.read(DEVICE_ID_REF)) }
            ?: throw BleLinkException("Device ID read timed out")
    } catch (c: CancellationException) {
        throw c
    } catch (e: BleLinkException) {
        throw e
    } catch (e: Exception) {
        throw BleLinkException("could not read Device ID: ${e.message ?: e.javaClass.simpleName}", cause = e)
    }

    internal const val READ_TIMEOUT_MS = 10_000L

    internal fun decodeDeviceId(value: ByteArray): String = value.toString(Charsets.UTF_8).trim().also {
        if (it.isEmpty()) throw BleLinkException("device reported an empty device ID")
    }

    internal val CAPABILITIES_REF = GattRef(SERVICE, CAPABILITIES)
    internal val DEVICE_ID_REF = GattRef(SERVICE, DEVICE_ID)
    internal val SESSION_METADATA_REF = GattRef(SERVICE, SESSION_METADATA)
    internal val TX_STREAM_REF = GattRef(SERVICE, TX_STREAM)
    internal val RX_STREAM_REF = GattRef(SERVICE, RX_STREAM)
    internal val INGEST_KEY_REF = GattRef(SERVICE, INGEST_KEY)

    /** A readable name for [uuid], for logs and error messages. */
    public fun name(uuid: UUID): String = when (uuid) {
        SERVICE -> "Spotflow service"
        CAPABILITIES -> "Capabilities"
        DEVICE_ID -> "Device ID"
        SESSION_METADATA -> "Session Metadata"
        TX_STREAM -> "TX Stream"
        RX_STREAM -> "RX Stream"
        INGEST_KEY -> "Ingest Key"
        CCCD -> "CCCD"
        else -> uuid.toString()
    }
}
