package io.spotflow.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.spotflow.ble.cloud.MessageStore
import io.spotflow.ble.cloud.CloudEndpoint
import io.spotflow.ble.cloud.DeviceIngestCredential
import io.spotflow.ble.cloud.DeviceKeyQueueIds
import io.spotflow.ble.cloud.Uplink
import io.spotflow.ble.diagnostics.DeviceReport
import io.spotflow.ble.protocol.DeviceCapabilities
import io.spotflow.ble.protocol.GattProfile
import io.spotflow.ble.protocol.Message
import io.spotflow.ble.protocol.MessageType
import io.spotflow.ble.transport.FakeLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.spec.SecretKeySpec

/**
 * The gateway's own logic — attached sessions, what it reports, the data it hands over — against a fake
 * [GatewayPlatform], in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpotflowGatewayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val address = "AA:BB:CC:DD:EE:FF"
    private val telemetry = Message(MessageType.TELEMETRY, byteArrayOf(1, 2, 3))

    /**
     * Runs [test] with a gateway on the test scheduler, and shuts the gateway down afterwards so the
     * process-wide flash store is closed before the next test gets a fresh data directory.
     */
    private fun gatewayTest(test: suspend TestScope.(gateway: SpotflowGateway, platform: FakePlatform) -> Unit) = runTest {
        val platform = FakePlatform()
        val gateway = gateway(platform)
        try {
            test(gateway, platform)
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    private val queueIds = DeviceKeyQueueIds(CloudEndpoint.PRODUCTION) { SecretKeySpec(ByteArray(32), "HmacSHA256") }

    private fun TestScope.gateway(platform: FakePlatform, useDeviceKeys: Boolean = false) =
        SpotflowGateway(context, GatewayOptions.DEFAULT, platform, StandardTestDispatcher(testScheduler),
            if (useDeviceKeys) queueIds::queueId else null)

    private fun SpotflowGateway.state(): DeviceReport? = diagnosticsSnapshot().devices.firstOrNull { it.address == address }

    private val DeviceReport.ended get() = (attachmentState as? AttachmentState.Ended)?.result

    @Test
    fun `attach reads the complete GATT key without assuming a particular MTU`() = runTest {
        val platform = FakePlatform()
        val gateway = gateway(platform, useDeviceKeys = true)
        val key = "A".repeat(511) + "!"
        val link = FakeLink(address).apply {
            maxWritePayloadBytes = 20
            values[GattProfile.INGEST_KEY] = key.toByteArray()
        }
        try {
            val attachment = gateway.attach(link)
            runCurrent()
            assertEquals(AttachmentState.Ready("test-device"), attachment.state.value)
            assertEquals(1, platform.deviceUplink("test-device", key)?.connectCount)
            assertEquals(1, link.calls.count { it == "read Ingest Key" })
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    @Test
    fun `device key is read before streaming and used after BLE disconnect without rereading`() = runTest {
        val platform = FakePlatform().apply { online.value = false }
        val gateway = gateway(platform, useDeviceKeys = true)
        try {
            val connection = FakeBleConnection(address).apply { deviceKeyValue = "key-A".toByteArray() }
            val attachment = gateway.startAttached(connection)
            runCurrent()
            assertEquals(AttachmentState.Ready("test-device"), attachment.state.value)
            assertEquals(listOf("prepare", "capabilities", "deviceId", "ingestKey", "metadata", "notifications"), connection.operations)
            val uplink = platform.deviceUplink("test-device", "key-A")!!
            assertEquals(0, uplink.connectAttempts)
            connection.emit(telemetry)
            runCurrent()
            connection.drop()
            runCurrent()
            uplink.failConnect = true
            platform.online.value = true
            runCurrent()
            assertEquals(1, uplink.connectAttempts)
            uplink.failConnect = false
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(uplink.publishedTo("ingest-cbor", telemetry.payload))
            assertEquals(1, connection.operations.count { it == "ingestKey" })
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    @Test
    fun `absent unreadable and malformed keys fail safely before streaming`() = runTest {
        val platform = FakePlatform()
        val gateway = gateway(platform, useDeviceKeys = true)
        try {
            val values = listOf(null, byteArrayOf(), "bad key".toByteArray(), byteArrayOf(0), byteArrayOf(-1), ByteArray(513) { 33 })
            val secret = "driver-error-includes-secret"
            val connections = values.map { bytes -> FakeBleConnection(address).apply { deviceKeyValue = bytes } } +
                FakeBleConnection(address).apply { keyReadFailure = BleLinkException(secret, 5) }
            connections.forEach { connection ->
                val attachment = gateway.startAttached(connection)
                runCurrent()
                val end = attachment.awaitEnd()
                assertEquals(AttachmentEnd.Reason.FAILED, end.reason)
                assertEquals(DeviceError.Kind.DEVICE_INGEST_KEY, end.error?.kind)
                assertFalse(end.error!!.message.contains(secret))
                assertFalse("notifications" in connection.operations)
                assertTrue(connection.closed)
            }
            assertTrue(gateway.state()!!.ended!!.error!!.message.contains("Pair"))
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    @Test
    fun `reattachment rereads the key and a rotated key cannot drain the old backlog`() = runTest {
        val platform = FakePlatform().apply { online.value = false }
        val gateway = gateway(platform, useDeviceKeys = true)
        fun connect(key: String) = FakeBleConnection(address).apply { deviceKeyValue = key.toByteArray() }
        try {
            val initial = connect("key-A")
            gateway.startAttached(initial)
            runCurrent()
            initial.emit(telemetry)
            runCurrent()
            initial.drop()
            runCurrent()

            val rotated = connect("key-B")
            val replacement = gateway.startAttached(rotated)
            runCurrent()
            platform.online.value = true
            runCurrent()
            assertEquals(1, rotated.operations.count { it == "ingestKey" })
            val wrongUplink = platform.deviceUplink("test-device", "key-B")!!
            assertFalse(wrongUplink.publishedTo("ingest-cbor", telemetry.payload))
            assertEquals(0, platform.deviceUplink("test-device", "key-A")!!.connectAttempts)
            replacement.detach()
            runCurrent()

            gateway.startAttached(connect("key-A"))
            runCurrent()
            assertTrue(platform.deviceUplink("test-device", "key-A")!!.publishedTo("ingest-cbor", telemetry.payload))
            assertFalse(wrongUplink.connected)
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    @Test
    fun `restart only drains queues for the selected mode and matching device key`() = runTest {
        val id = "test-device"
        val deviceBacklog = byteArrayOf(8)
        val suppliedBacklog = byteArrayOf(9)
        val store = MessageStore.acquire(context, 10_000)
        store.enqueueAll(queueIds.queueId(id, "key-A"), listOf(MessageStore.Entry(1, "ingest-cbor", deviceBacklog)))
        store.enqueueAll(MessageStore.suppliedKeyQueue(id), listOf(MessageStore.Entry(1, "ingest-cbor", suppliedBacklog)))
        MessageStore.release(store)

        val platform = FakePlatform()
        val deviceGateway = gateway(platform, useDeviceKeys = true)
        try {
            runCurrent()
            assertEquals(0, platform.uplink(id).connectAttempts)
            assertNull(platform.deviceUplink(id, "key-A"))
            deviceGateway.startAttached(FakeBleConnection(address).apply { deviceKeyValue = "key-B".toByteArray() })
            runCurrent()
            assertFalse(platform.deviceUplink(id, "key-B")!!.publishedTo("ingest-cbor", deviceBacklog))
        } finally {
            deviceGateway.shutdown()
            advanceUntilIdle()
        }

        val configured = gateway(platform)
        try {
            runCurrent()
            assertTrue(platform.uplink(id).publishedTo("ingest-cbor", suppliedBacklog))
            assertFalse(platform.uplink(id).publishedTo("ingest-cbor", deviceBacklog))
        } finally {
            configured.shutdown()
            advanceUntilIdle()
        }

        val reconnected = gateway(platform, useDeviceKeys = true)
        try {
            reconnected.startAttached(FakeBleConnection(address).apply { deviceKeyValue = "key-A".toByteArray() })
            runCurrent()
            val uplink = platform.deviceUplink(id, "key-A")!!
            assertTrue(uplink.publishedTo("ingest-cbor", deviceBacklog))
            assertFalse(uplink.publishedTo("ingest-cbor", suppliedBacklog))
        } finally {
            reconnected.shutdown()
            advanceUntilIdle()
        }
    }

    @Test
    fun `an attached connection is relayed until the host detaches it`() = gatewayTest { gateway, platform ->
        val connection = FakeBleConnection(address)
        val attachment = gateway.startAttached(connection)
        runCurrent()
        assertEquals(AttachmentState.Ready("test-device"), attachment.state.value)
        assertFalse("supplied-key mode never reads the ingest key", "ingestKey" in connection.operations)

        connection.emit(telemetry)
        runCurrent()
        assertTrue(platform.uplink("test-device").publishedTo("ingest-cbor", byteArrayOf(1, 2, 3)))

        attachment.detach()
        runCurrent()
        assertNull(gateway.state())
        assertTrue(connection.closed)
    }

    @Test
    fun `attaching again replaces the previous session for that device`() = gatewayTest { gateway, _ ->
        val first = FakeBleConnection(address)
        val firstAttachment = gateway.startAttached(first)
        runCurrent()

        val second = FakeBleConnection(address)
        gateway.startAttached(second)
        runCurrent()

        assertTrue(first.closed)
        assertEquals(AttachmentEnd.Reason.REPLACED, firstAttachment.awaitEnd().reason)
        assertEquals(AttachmentState.Ready("test-device"), gateway.state()?.attachmentState)
        assertEquals(AttachmentState.Ended(firstAttachment.awaitEnd()), firstAttachment.state.value)
    }

    @Test
    fun `a rejected key ends the session and says so`() = gatewayTest { gateway, platform ->
        platform.uplink("test-device").authFail = true
        val attachment = gateway.startAttached(FakeBleConnection(address))
        runCurrent()

        assertEquals(AttachmentEnd.Reason.FAILED, attachment.awaitEnd().reason)
        assertEquals(DeviceError.Kind.AUTH_REJECTED, gateway.state()?.ended?.error?.kind)
        assertEquals(AttachmentState.Ended(attachment.awaitEnd()), gateway.state()?.attachmentState)
        assertEquals(
            "background uploads of the backlog are paused for the same reason",
            DeviceError.Kind.AUTH_REJECTED,
            gateway.state()?.cloudError?.kind,
        )
        assertFalse(gateway.state()!!.cloudConnected)
    }

    @Test
    fun `turning Bluetooth off ends the sessions`() = gatewayTest { gateway, platform ->
        val connection = FakeBleConnection(address)
        val attachment = gateway.startAttached(connection)
        runCurrent()

        platform.turnBluetoothOff()
        runCurrent()

        assertEquals(AttachmentEnd.Reason.DISCONNECTED, attachment.awaitEnd().reason)
        assertEquals(AttachmentState.Ended(attachment.awaitEnd()), gateway.state()?.attachmentState)
        assertTrue(connection.closed)
    }

    @Test
    fun `stored data automatically retries rejected credentials with backoff`() = runTest {
        // Left on flash by an earlier run, for a device that is not coming back.
        val store = MessageStore.acquire(context, 10_000)
        store.enqueueAll(MessageStore.suppliedKeyQueue("gone-device"), listOf(MessageStore.Entry(1, "ingest-cbor", byteArrayOf(5, 5))))
        MessageStore.release(store)

        val platform = FakePlatform().apply { uplink("gone-device").authFail = true }
        val gateway = gateway(platform)
        try {
            advanceTimeBy(59_999); runCurrent()
            assertEquals("no retry during the first backoff", 1, platform.uplink("gone-device").connectAttempts)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, platform.uplink("gone-device").connectAttempts)

            platform.uplink("gone-device").authFail = false // the key was fixed
            advanceTimeBy(119_999); runCurrent()
            assertEquals("the backoff doubled", 2, platform.uplink("gone-device").connectAttempts)
            advanceTimeBy(1); runCurrent()

            assertTrue(platform.uplink("gone-device").publishedTo("ingest-cbor", byteArrayOf(5, 5)))
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    @Test
    fun `an auth failure ends the attachment but retries its backlog even with flash disabled`() = runTest {
        val platform = FakePlatform()
        val uplink = platform.uplink("test-device")
        val connectGate = CompletableDeferred<Unit>()
        uplink.connectGate = connectGate
        uplink.authFail = true
        val options = GatewayOptions { flashBufferBytes = 0 }
        val gateway = SpotflowGateway(context, options, platform, StandardTestDispatcher(testScheduler))
        try {
            val connection = FakeBleConnection(address)
            val attachment = gateway.startAttached(connection)
            runCurrent()
            connection.emit(telemetry)
            runCurrent()
            connectGate.complete(Unit)
            runCurrent()
            assertEquals(DeviceError.Kind.AUTH_REJECTED, attachment.awaitEnd().error?.kind)
            assertEquals(AttachmentState.Ended(attachment.awaitEnd()), gateway.state()?.attachmentState)
            assertEquals(1, uplink.connectAttempts)

            uplink.authFail = false
            advanceTimeBy(60_000); runCurrent()
            assertTrue(uplink.publishedTo("ingest-cbor", telemetry.payload))
            assertEquals(true, gateway.state()?.cloudConnected)
            assertEquals(AttachmentState.Ended(attachment.awaitEnd()), gateway.state()?.attachmentState)
        } finally {
            connectGate.complete(Unit)
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    @Test
    fun `the buffer limits and OTA come from the options`() = runTest {
        val options = GatewayOptions { ramBufferBytes = 2048; flashBufferBytes = 0; otaEnabled = false }
        val gateway = SpotflowGateway(context, options, FakePlatform(), StandardTestDispatcher(testScheduler))
        try {
            val otaDevice = FakeBleConnection(address).apply { capabilities = DeviceCapabilities(1, 1, 0x01) }
            gateway.startAttached(otaDevice)
            runCurrent()

            val report = gateway.diagnosticsSnapshot()
            assertEquals(2048L, report.bufferUsage.ramMaxBytes)
            assertEquals("RAM only", 0L, report.bufferUsage.flashMaxBytes)
            assertTrue("a zero budget does not mean the database failed", report.bufferUsage.flashAvailable)
            assertEquals(AttachmentState.Ready("test-device"), gateway.state()?.attachmentState)
            assertFalse("OTA is off, though the device takes it", gateway.state()!!.otaEnabled)
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    @Test
    fun `relay readiness is independent of MQTT and the next attempt clears the end`() = gatewayTest { gateway, platform ->
        platform.online.value = false
        val first = FakeBleConnection(address)
        val attachment = gateway.startAttached(first)
        assertEquals(AttachmentState.Starting, attachment.state.value)
        runCurrent()
        assertEquals(AttachmentState.Ready("test-device"), attachment.state.value)
        assertFalse(gateway.state()!!.cloudConnected)

        first.drop()
        runCurrent()
        assertEquals(AttachmentEnd.Reason.DISCONNECTED, gateway.state()?.ended?.reason)

        val gate = CompletableDeferred<Unit>()
        val second = FakeBleConnection(address).apply { prepareGate = gate }
        val replacement = gateway.startAttached(second)
        runCurrent()
        assertEquals(AttachmentState.Starting, replacement.state.value)
        assertEquals(AttachmentState.Starting, gateway.state()?.attachmentState)
        gate.complete(Unit)
        runCurrent()
        assertEquals(AttachmentState.Ready("test-device"), replacement.state.value)
        assertEquals(AttachmentState.Ended(attachment.awaitEnd()), attachment.state.value)
    }

    // ---- restart ---------------------------------------------------------------------------------

    @Test
    fun `data a shut-down gateway writes out after its successor started is still uploaded`() = runTest {
        val oldPlatform = FakePlatform().apply { uplink("test-device").failConnect = true } // the cloud is unreachable
        val old = gateway(oldPlatform)
        val connection = FakeBleConnection(address)
        old.startAttached(connection)
        runCurrent()
        connection.emit(Message(MessageType.TELEMETRY, byteArrayOf(9, 9)))
        runCurrent() // buffered in RAM only: nothing is on flash yet

        // Restart in place: the new gateway is created first and looks for stored buffers — finding none.
        val newPlatform = FakePlatform()
        val new = gateway(newPlatform)
        try {
            runCurrent()
            assertEquals(0, newPlatform.uplink("test-device").connectAttempts)

            old.shutdown() // only now does the old one write its RAM to flash
            runCurrent()

            assertTrue(
                "the device never reconnects, yet its data is delivered",
                newPlatform.uplink("test-device").publishedTo("ingest-cbor", byteArrayOf(9, 9)),
            )
        } finally {
            new.shutdown()
            advanceUntilIdle()
        }
    }
}

/** A [GatewayPlatform] under the test's control. */
internal class FakePlatform : GatewayPlatform {
    override val online = MutableStateFlow(true)

    private var onBluetoothOff: (() -> Unit)? = null

    override fun start(onBluetoothOff: () -> Unit) {
        this.onBluetoothOff = onBluetoothOff
    }

    override fun stop() {
        onBluetoothOff = null
    }

    fun turnBluetoothOff() {
        onBluetoothOff?.invoke()
    }

    private val uplinks = HashMap<String, FakeUplink>()

    /** The one fake uplink of [deviceId], shared by every cloud link the gateway creates for it. */
    fun uplink(deviceId: String): FakeUplink = uplinks.getOrPut(deviceId) { FakeUplink() }

    private val deviceUplinks = HashMap<Pair<String, String>, FakeUplink>()
    fun deviceUplink(deviceId: String, key: String): FakeUplink? = deviceUplinks[deviceId to key]

    override fun createUplink(deviceId: String, deviceCredential: DeviceIngestCredential?): Uplink =
        if (deviceCredential == null) uplink(deviceId)
        else deviceUplinks.getOrPut(deviceId to deviceCredential.key) { FakeUplink() }

}
