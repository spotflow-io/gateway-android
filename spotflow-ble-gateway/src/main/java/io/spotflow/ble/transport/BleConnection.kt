package io.spotflow.ble.transport

import io.spotflow.ble.protocol.DeviceCapabilities
import io.spotflow.ble.protocol.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A transport to one Spotflow device that exposes the framed message streams, over a connection the
 * host app owns: a [LinkConnection] over a `BleLink`.
 *
 * Only one call to the device is ever waited for at a time.
 */
internal interface BleConnection {

    /** Bluetooth MAC address of the device. */
    val deviceAddress: String

    /** Observable connection state. */
    val state: StateFlow<ConnectionState>

    /** True if physical link loss was reported, not just if the relay stopped. */
    val linkLost: Boolean get() = false

    /** Why the connection failed, once [state] is [ConnectionState.FAILED]. */
    val failure: Throwable? get() = null

    /** Reassembled messages arriving on the TX Stream (device -> gateway). */
    val incoming: Flow<Message>

    /** Starts using the link. Messages only start flowing after [startStreaming]. */
    suspend fun prepare()

    /** Reads the Capabilities characteristic: protocol version and feature bits. */
    suspend fun readCapabilities(): DeviceCapabilities

    /** Enables TX Stream notifications; [state] then reaches [ConnectionState.STREAMING]. */
    suspend fun startStreaming()

    /** Reads the device ID (MQTT username) from the Device ID characteristic. */
    suspend fun readDeviceId(): String

    /** Reads the complete raw Ingest Key characteristic. Only used in device-key mode. */
    suspend fun readIngestKey(): ByteArray

    /** Reads the CBOR session-metadata characteristic. */
    suspend fun readSessionMetadata(): ByteArray

    /** Fragments and writes a DESIRED_CONFIGURATION payload to the RX Stream. */
    suspend fun sendDesiredConfiguration(payload: ByteArray)

    /**
     * Writes an OTA command (OTA_C2D) and suspends until the device acknowledges it, retransmitting on a
     * NACK or silence. Throws if the link goes down first.
     */
    suspend fun sendOtaCommand(payload: ByteArray)

    /** Fragments and writes an ARTIFACT_RESPONSE payload to the RX Stream. */
    suspend fun sendArtifactResponse(payload: ByteArray)

    /**
     * Releases the transport: turns the device's TX stream off and stops using the link, leaving the
     * host to own disconnection. Call from a non-cancellable context.
     */
    suspend fun close()
}

internal enum class ConnectionState { IDLE, PREPARING, STREAMING, FAILED }
