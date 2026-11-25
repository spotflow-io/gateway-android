package io.spotflow.ble

import android.bluetooth.BluetoothGatt
import io.spotflow.ble.cloud.DeviceIngestCredential
import io.spotflow.ble.cloud.SpotflowTopics
import io.spotflow.ble.diagnostics.DeviceDiagnostics
import io.spotflow.ble.ota.ArtifactCache
import io.spotflow.ble.ota.ArtifactServer
import io.spotflow.ble.protocol.DeviceCapabilities
import io.spotflow.ble.protocol.MessageType
import io.spotflow.ble.transport.BleConnection
import io.spotflow.ble.transport.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Bridges a single [BleConnection] to the device's [CloudLink].
 *
 * Received BLE messages are enqueued to the link's store-and-forward buffer (independent of network
 * state), and the link's drainer publishes them to MQTT, owning the connect/reconnect lifecycle. When
 * the BLE link drops, the session ends but the cloud link lingers (see [CloudLinkRegistry]) to finish
 * uploading what is buffered.
 */
internal class GatewaySession(
    private val connection: BleConnection,
    private val links: CloudLinkRegistry,
    private val attachment: Attachment,
    private val diagnostics: DeviceDiagnostics,
    /** Where OTA artifacts are downloaded to; null when the gateway's OTA is off. */
    private val artifacts: ArtifactCache? = null,
    /** The ID already read and accepted by managed discovery, on this very connection. */
    private val selectedDeviceId: String? = null,
    /** Null when credentials are supplied by the gateway. */
    private val deviceKeyQueueId: ((deviceId: String, key: String) -> String)? = null,
) {
    /** Runs until the connection is torn down or the coroutine is cancelled. */
    suspend fun run() = coroutineScope {
        try {
            // Order per the Spotflow BLE protocol: read Capabilities, Device ID and Session Metadata
            // first; enable the TX stream last.
            connection.prepare()
            // OTA only for a device that advertises it in a protocol version this gateway knows (and only
            // if the gateway's OTA is on): anything else is relayed for telemetry and configuration only.
            val ota = try {
                val capabilities = connection.readCapabilities()
                if (!capabilities.isCompatible) {
                    Slog.w(TAG, "device speaks protocol $capabilities; this gateway speaks ${DeviceCapabilities.SUPPORTED_MAJOR}.x")
                } else if (capabilities.otaSupported && artifacts == null) {
                    Slog.i(TAG, "device supports OTA updates (protocol $capabilities); OTA is turned off in this gateway")
                }
                capabilities.isCompatible && capabilities.otaSupported && artifacts != null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Slog.w(TAG, "could not read capabilities: ${t.message}")
                false
            }
            diagnostics.update {
                otaEnabled = ota
                activeFirmwareTransfer = null
            }
            val deviceId = selectedDeviceId ?: connection.readDeviceId()
            if (deviceId.isBlank()) throw BleLinkException("device reported an empty device ID")
            diagnostics.update { this.deviceId = deviceId }

            val credential = deviceKeyQueueId?.let { bindDeviceKey(deviceId, it) }
            val link = links.claim(deviceId, diagnostics, credential)
            link.setOtaEnabled(ota)
            // Everything of this session that uses the link; see the hand-over below.
            val linkUsers = ArrayList<Job>()
            try {
                try {
                    link.enqueueSessionMetadata(SpotflowTopics.INGEST, connection.readSessionMetadata())
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // Optional characteristic; relaying works without it.
                    Slog.w(TAG, "session metadata unavailable for $deviceId: ${t.message}")
                }

                // Completed by the device's first reported configuration this session. The Device SDK
                // registers its desired-configuration handler just before sending that message; until
                // then it accepts RX writes but silently discards them.
                val deviceAcceptsConfiguration = CompletableDeferred<Unit>()

                // Completed once the TX stream is on: the device acknowledges OTA commands over it.
                val streaming = CompletableDeferred<Unit>()

                // Artifact requests are answered in a scope of their own: an OPEN can wait minutes for its
                // download, and the session must not wait for that when it ends.
                val artifactJob = Job(coroutineContext.job).also { linkUsers += it }
                val artifactScope = this + artifactJob
                val artifactServer = artifacts?.takeIf { ota }?.let { cache ->
                    ArtifactServer(cache, deviceId, connection::sendArtifactResponse) { transfer ->
                        diagnostics.update { activeFirmwareTransfer = transfer }
                    }
                }

                // BLE -> buffer (RAM first; never blocks on the network).
                linkUsers += launch {
                    connection.incoming.collect { message ->
                        val topic = when (message.type) {
                            MessageType.TELEMETRY -> SpotflowTopics.INGEST
                            MessageType.REPORTED_CONFIGURATION -> {
                                if (deviceAcceptsConfiguration.complete(Unit)) {
                                    Slog.d(TAG, "$deviceId reported its configuration; desired configuration can be delivered")
                                }
                                SpotflowTopics.REPORTED_CONFIGURATION
                            }
                            MessageType.OTA_D2C -> SpotflowTopics.OTA_RESULTS.takeIf { ota } ?: return@collect
                            MessageType.ARTIFACT_REQUEST -> {
                                artifactServer?.handle(artifactScope, message.payload)
                                return@collect
                            }
                            else -> return@collect
                        }
                        link.enqueue(topic, message.payload)
                    }
                }

                // Buffer -> MQTT, owning connect/reconnect so offline just keeps buffering.
                linkUsers += launch { link.drain(stopWhenEmpty = false) }

                // Cloud -> device, in order, acknowledged to the broker only once written — and only once
                // the device can take it (see above). A device that never reports configuration doesn't
                // support it, so pending desired configuration then stays queued rather than being lost.
                linkUsers += launch {
                    deviceAcceptsConfiguration.await()
                    link.deliverDesiredConfiguration { connection.sendDesiredConfiguration(it) }
                }

                // OTA commands, each acknowledged to the broker once the device has acknowledged it.
                if (ota) {
                    linkUsers += launch {
                        streaming.await()
                        link.deliverOtaCommands { connection.sendOtaCommand(it) }
                    }
                }

                // Reads are done: start the device's TX stream.
                connection.startStreaming()
                attachment.ready(deviceId)
                streaming.complete(Unit)

                val end = connection.state.first {
                    it == ConnectionState.IDLE || it == ConnectionState.FAILED
                }
                // A link that failed (e.g. a call that timed out) ends the session with why.
                if (end == ConnectionState.FAILED) throw connection.failure ?: IOException("the BLE link failed")
            } finally {
                // NonCancellable so the hand-off runs even when the session is cancelled (Stop /
                // Bluetooth off) — the link then lingers to upload (or at least persist) what's buffered.
                withContext(NonCancellable) {
                    // The link is handed over only once this session has really let go of it: releasing
                    // it starts another drain loop, and a link has a single consumer. Cancelling without
                    // waiting would leave this session's loop running next to that one for a moment.
                    linkUsers.forEach { it.cancel() }
                    linkUsers.joinAll()
                    links.release(link)
                }
            }
        } finally {
            diagnostics.update { activeFirmwareTransfer = null }
            withContext(NonCancellable) { connection.close() }
        }
    }

    private suspend fun bindDeviceKey(deviceId: String, queueId: (String, String) -> String): DeviceIngestCredential {
        val value = try {
            connection.readIngestKey()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            val message = when ((e as? BleLinkException)?.gattStatus) {
                BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
                0x0C, 0x89 -> "Pair with the device before reading its ingest key"
                else -> "Could not read the device ingest key; check that it is provisioned and the BLE link is paired"
            }
            // Don't forward transport messages or causes: a driver might include the raw value.
            throw DeviceIngestKeyException(message)
        }
        val key = if (value.size in 1..512 && value.all { it in 0x21..0x7e }) {
            value.toString(Charsets.US_ASCII)
        } else {
            throw DeviceIngestKeyException("The device returned a malformed ingest key")
        }
        return try {
            DeviceIngestCredential(key, queueId(deviceId, key))
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            throw DeviceIngestKeyException("Could not identify the device ingest key's local buffer")
        }
    }

    private companion object {
        const val TAG = "SpotflowGateway"
    }
}
