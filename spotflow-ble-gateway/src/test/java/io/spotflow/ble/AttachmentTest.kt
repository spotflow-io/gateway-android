package io.spotflow.ble

import android.bluetooth.BluetoothGatt
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.spotflow.ble.AttachmentEnd.Reason
import io.spotflow.ble.protocol.FrameCodec
import io.spotflow.ble.protocol.MessageType
import io.spotflow.ble.protocol.GattProfile
import io.spotflow.ble.transport.FakeLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Attaching host-owned [BleLink]s: the session over the link, and every way an attachment ends. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AttachmentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val address = "AA:BB:CC:DD:EE:FF"

    private fun linkTest(
        test: suspend TestScope.(gateway: SpotflowGateway, platform: FakePlatform) -> Unit,
    ) = runTest {
        val platform = FakePlatform()
        val gateway = SpotflowGateway(
            context, GatewayOptions.DEFAULT, platform, StandardTestDispatcher(testScheduler),
        )
        try {
            test(gateway, platform)
        } finally {
            gateway.shutdown()
            advanceUntilIdle()
        }
    }

    private fun SpotflowGateway.report(of: String = address) = diagnosticsSnapshot().devices.firstOrNull { it.address == of }

    @Test
    fun `an attached link is relayed, in protocol order`() = linkTest { gateway, platform ->
        val link = FakeLink()
        val attachment = gateway.attach(link)
        runCurrent()

        assertEquals(listOf("read Capabilities", "read Device ID", "read Session Metadata", "enable"), link.calls)
        assertEquals(AttachmentState.Ready("test-device"), attachment.state.value)
        assertEquals("test-device", gateway.report()?.deviceId)
        assertNull(attachment.endIfFinished())
        assertTrue(platform.uplink("test-device").publishedTo("ingest-cbor", "meta".toByteArray()))

        FrameCodec.fragment(MessageType.TELEMETRY, byteArrayOf(4, 5, 6), seq = 1, frameSize = 20).forEach(link::notify)
        runCurrent()
        assertTrue(platform.uplink("test-device").publishedTo("ingest-cbor", byteArrayOf(4, 5, 6)))
    }

    @Test
    fun `diagnostics are snapshots and traffic does not change the attachment lifecycle`() = linkTest { gateway, platform ->
        val link = FakeLink()
        val attachment = gateway.attach(link)
        val starting = gateway.diagnosticsSnapshot()
        assertEquals(AttachmentState.Starting, starting.devices.single().attachmentState)
        assertTrue(link.calls.isEmpty())
        assertFalse("taking a snapshot doesn't open the store", context.getDatabasePath("spotflow_buffer.db").exists())

        runCurrent()
        val ready = attachment.state.value
        val beforeTraffic = gateway.diagnosticsSnapshot()
        val gattCalls = link.calls.toList()
        val uplink = platform.uplink("test-device")
        val connectAttempts = uplink.connectAttempts

        FrameCodec.fragment(MessageType.TELEMETRY, byteArrayOf(4, 5, 6), seq = 1, frameSize = 20).forEach(link::notify)
        runCurrent()
        val afterTraffic = gateway.diagnosticsSnapshot()
        assertSame(ready, attachment.state.value)
        assertEquals(AttachmentState.Starting, starting.devices.single().attachmentState)
        assertEquals(beforeTraffic.devices.single().forwarded + 1, afterTraffic.devices.single().forwarded)
        assertEquals(gattCalls, link.calls)
        assertEquals(connectAttempts, uplink.connectAttempts)

        link.drop()
        runCurrent()
        val ended = attachment.state.value as AttachmentState.Ended
        assertSame(ended.result, attachment.awaitEnd())
        assertSame(ended, gateway.diagnosticsSnapshot().devices.single().attachmentState)
    }

    @Test
    fun `a link without a Bluetooth address is refused`() = linkTest { gateway, _ ->
        val error = runCatching { gateway.attach(FakeLink(address = "4f0c2a5e-peripheral")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `detaching turns the stream off, ends the attachment and leaves the link up`() = linkTest { gateway, _ ->
        val link = FakeLink()
        val attachment = gateway.attach(link)
        runCurrent()

        attachment.detach()
        assertNull("detach doesn't wait for cleanup", attachment.endIfFinished())
        runCurrent()

        assertEquals(AttachmentEnd(Reason.DETACHED), attachment.awaitEnd())
        assertEquals("disable", link.calls.last())
        assertFalse(link.notifying)
        assertNull(gateway.report())
    }

    @Test
    fun `end is published after the 2 s cleanup budget even if the stream is still on`() = linkTest { gateway, _ ->
        val link = FakeLink()
        val attachment = gateway.attach(link)
        runCurrent()
        val gate = CompletableDeferred<Unit>().also { link.gate = it } // the host's queue is busy

        attachment.detach()
        advanceTimeBy(1_999); runCurrent()
        assertNull(attachment.endIfFinished())

        advanceTimeBy(1); runCurrent()
        assertEquals(Reason.DETACHED, attachment.awaitEnd().reason)
        gate.complete(Unit); runCurrent()
        assertFalse("the disable still ran", link.notifying)
    }

    @Test
    fun `reattaching immediately after detach waits for the old session to clean up`() = linkTest { gateway, _ ->
        val oldLink = FakeLink()
        val old = gateway.attach(oldLink)
        runCurrent()
        val gate = CompletableDeferred<Unit>().also { oldLink.gate = it }

        old.detach()
        val freshLink = FakeLink()
        val fresh = gateway.attach(freshLink)
        runCurrent()
        assertTrue(freshLink.calls.isEmpty())

        gate.complete(Unit)
        runCurrent()
        assertEquals(Reason.DETACHED, old.awaitEnd().reason)
        assertNull(fresh.endIfFinished())
        assertEquals(AttachmentState.Ready("test-device"), fresh.state.value)
    }

    @Test
    fun `rapid replacements do not overtake the oldest session's cleanup`() = linkTest { gateway, _ ->
        val first = FakeLink()
        val old = gateway.attach(first)
        runCurrent()
        val gate = CompletableDeferred<Unit>().also { first.gate = it }

        val intermediate = gateway.attach(FakeLink())
        runCurrent()
        val newestLink = FakeLink()
        val newest = gateway.attach(newestLink)
        runCurrent()

        assertEquals(Reason.REPLACED, intermediate.awaitEnd().reason)
        assertNull(old.endIfFinished())
        assertTrue(newestLink.calls.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(Reason.REPLACED, old.awaitEnd().reason)
        assertNull(newest.endIfFinished())
        assertTrue(newestLink.notifying)
    }

    @Test
    fun `a dropped link ends the attachment as disconnected`() = linkTest { gateway, _ ->
        val link = FakeLink()
        val attachment = gateway.attach(link)
        runCurrent()

        link.drop(); runCurrent()

        assertEquals(AttachmentEnd(Reason.DISCONNECTED), attachment.awaitEnd())
        assertEquals(AttachmentState.Ended(attachment.awaitEnd()), gateway.report()?.attachmentState)
        assertFalse("nothing is written to a link that is gone", link.calls.contains("disable"))
    }

    @Test
    fun `the host can check the device ID without attaching or talking to the cloud`() = linkTest { gateway, platform ->
        val link = FakeLink()
        assertEquals("test-device", GattProfile.readDeviceId(link))

        assertEquals(listOf("read Device ID"), link.calls)
        assertNull(gateway.report())
        assertEquals(0, platform.uplink("test-device").connectAttempts)
    }

    @Test
    fun `disconnect during a GATT read ends as disconnected rather than failed`() = linkTest { gateway, _ ->
        val link = FakeLink().apply { gate = CompletableDeferred() }
        val attachment = gateway.attach(link)
        runCurrent()
        link.drop()
        runCurrent()

        assertEquals(Reason.DISCONNECTED, attachment.awaitEnd().reason)
        assertNull(attachment.awaitEnd().error)
    }

    @Test
    fun `a call that times out ends the attachment as failed, and says why`() = linkTest { gateway, _ ->
        val link = FakeLink().apply { gate = CompletableDeferred() }
        val attachment = gateway.attach(link)
        advanceTimeBy(10_001); runCurrent()

        val end = attachment.awaitEnd()
        assertEquals(Reason.FAILED, end.reason)
        assertEquals(DeviceError.Kind.BLUETOOTH, end.error?.kind)
        assertEquals(AttachmentState.Ended(end), gateway.report()?.attachmentState)
    }

    @Test
    fun `a device that requires pairing fails the attachment with a kind the host can act on`() = linkTest { gateway, _ ->
        val link = FakeLink().apply {
            failReads[GattProfile.DEVICE_ID] = BleLinkException("read failed", BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION)
        }
        val end = gateway.attach(link).also { runCurrent() }.awaitEnd()

        assertEquals(Reason.FAILED, end.reason)
        assertEquals(DeviceError.Kind.PAIRING_REQUIRED, end.error?.kind)
        assertEquals(BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION, end.error?.gattStatus)
    }

    @Test
    fun `another GATT failure is a Bluetooth error that carries its status`() = linkTest { gateway, _ ->
        val link = FakeLink().apply { failReads[GattProfile.DEVICE_ID] = BleLinkException("read failed", 0x85) }
        val end = gateway.attach(link).also { runCurrent() }.awaitEnd()

        assertEquals(DeviceError.Kind.BLUETOOTH, end.error?.kind)
        assertEquals(0x85, end.error?.gattStatus)
    }

    @Test
    fun `the report keeps only the most recently ended of the devices no longer attached`() = linkTest { gateway, _ ->
        val addresses = (0 until SpotflowGateway.MAX_INACTIVE_ROWS + 6).map { "AA:BB:CC:DD:%02X:%02X".format(it / 256, it % 256) }
        val live = gateway.attach(FakeLink(address = "11:22:33:44:55:66", deviceId = "live"))
        addresses.forEach { address ->
            val link = FakeLink(address = address, deviceId = "device-$address")
            gateway.attach(link)
            runCurrent()
            link.drop()
            runCurrent()
        }

        val reported = gateway.diagnosticsSnapshot().devices.map { it.address }
        assertEquals(SpotflowGateway.MAX_INACTIVE_ROWS + 1, reported.size)
        assertTrue("an attached device is always reported", "11:22:33:44:55:66" in reported)
        assertEquals(addresses.takeLast(SpotflowGateway.MAX_INACTIVE_ROWS), reported - "11:22:33:44:55:66")
        assertNull(live.endIfFinished())
    }

    @Test
    fun `attaching the same peripheral again replaces the session once the old one turned the stream off`() =
        linkTest { gateway, _ ->
            val first = FakeLink()
            val firstAttachment = gateway.attach(first)
            runCurrent()
            val gate = CompletableDeferred<Unit>().also { first.gate = it }

            val second = FakeLink()
            val secondAttachment = gateway.attach(second)
            runCurrent()

            assertEquals("the old session issued its disable", "disable", first.calls.last())
            assertTrue("and the new one waits for it", second.calls.isEmpty())
            assertNull("still winding down", firstAttachment.endIfFinished())

            advanceTimeBy(2_001); runCurrent()
            assertEquals(AttachmentEnd(Reason.REPLACED), firstAttachment.awaitEnd())
            gate.complete(Unit); runCurrent()

            assertEquals(AttachmentState.Ready("test-device"), secondAttachment.state.value)
            assertTrue(second.notifying)
            assertNull(secondAttachment.endIfFinished())
        }

    @Test
    fun `detaching a replaced attachment leaves the newer one running`() = linkTest { gateway, _ ->
        val first = gateway.attach(FakeLink())
        runCurrent()
        val second = FakeLink()
        val replacement = gateway.attach(second)
        runCurrent()

        first.detach()
        runCurrent()

        assertNull(replacement.endIfFinished())
        assertEquals(AttachmentState.Ready("test-device"), replacement.state.value)
        assertTrue(second.notifying)
    }

    @Test
    fun `another peripheral reporting a relayed device ID fails as a duplicate`() = linkTest { gateway, _ ->
        val original = gateway.attach(FakeLink())
        runCurrent()

        val other = "11:22:33:44:55:66"
        val impostor = gateway.attach(FakeLink(address = other))
        runCurrent()

        assertNull(original.endIfFinished())
        assertEquals(Reason.FAILED, impostor.awaitEnd().reason)
        assertEquals(DeviceError.Kind.DUPLICATE_DEVICE, impostor.awaitEnd().error?.kind)
        assertEquals(AttachmentState.Ended(impostor.awaitEnd()), gateway.report(other)?.attachmentState)
    }

    @Test
    fun `turning Bluetooth off ends attachments as disconnected`() = linkTest { gateway, platform ->
        val attachment = gateway.attach(FakeLink())
        runCurrent()

        platform.turnBluetoothOff()
        runCurrent()

        assertEquals(Reason.DISCONNECTED, attachment.awaitEnd().reason)
    }

    @Test
    fun `shutting the gateway down ends attachments as detached`() = linkTest { gateway, _ ->
        val link = FakeLink()
        val attachment = gateway.attach(link)
        runCurrent()

        gateway.shutdown()
        runCurrent()

        assertEquals(Reason.DETACHED, attachment.awaitEnd().reason)
        assertFalse(link.notifying)
        assertTrue(runCatching { gateway.attach(FakeLink()) }.exceptionOrNull() is IllegalStateException)
    }
}

/** Checks completion without advancing the test clock or leaving a waiter behind. */
internal suspend fun Attachment.endIfFinished(): AttachmentEnd? = coroutineScope {
    val waiter = async(start = CoroutineStart.UNDISPATCHED) { awaitEnd() }
    if (waiter.isCompleted) waiter.await() else null.also { waiter.cancel() }
}
